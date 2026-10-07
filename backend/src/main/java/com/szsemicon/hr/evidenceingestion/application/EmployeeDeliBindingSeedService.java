package com.szsemicon.hr.evidenceingestion.application;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.evidenceingestion.infrastructure.persistence.EmployeeDeliBindingMapper;
import com.szsemicon.hr.evidenceingestion.infrastructure.persistence.EmployeeRosterRow;
import com.szsemicon.hr.evidenceingestion.port.DeliPunchSourcePort.EmployeeDirectoryPerson;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmployeeDeliBindingSeedService {

    /**
     * Bindings must cover the August replay window. Seeding "now" would
     * leave 8/1–8/13 punches outside {@code effective_from}.
     */
    static final Instant BINDING_EFFECTIVE_FROM =
            Instant.parse("2026-01-01T00:00:00+08:00");

    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principals;
    private final EmployeeDeliBindingMapper mapper;
    private final Clock clock;

    public EmployeeDeliBindingSeedService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principals,
            EmployeeDeliBindingMapper mapper,
            Clock clock) {
        this.capabilities = capabilities;
        this.principals = principals;
        this.mapper = mapper;
        this.clock = clock;
    }

    public record SeedResult(
            List<String> seeded,
            List<String> skipped,
            List<String> missingEmployees) {
    }

    public record BindDecision(
            String employeeNumber, String reason) {
    }

    @Transactional
    public SeedResult seed(
            String sourceId, Map<String, String> employeeDirectory) {
        List<EmployeeDirectoryPerson> people = new ArrayList<>();
        if (employeeDirectory != null) {
            employeeDirectory.forEach((userId, empno) -> people.add(
                    new EmployeeDirectoryPerson(userId, empno, null)));
        }
        return seedPeople(sourceId, people);
    }

    @Transactional
    public SeedResult seedPeople(
            String sourceId, List<EmployeeDirectoryPerson> directoryPeople) {
        capabilities.require(CapabilityCodes.ATTENDANCE_SOURCE_CONFIGURE);
        String actorId = principals.currentPrincipalId();
        String resolvedSource = sourceId == null || sourceId.isBlank()
                ? mapper.findActiveDeliSourceId()
                : sourceId;
        if (resolvedSource == null || resolvedSource.isBlank()) {
            throw new ApiProblemException(
                    HttpStatus.NOT_FOUND,
                    "DELI_ACTIVE_SOURCE_NOT_FOUND",
                    "没有可用的得力数据源",
                    false);
        }
        Instant at = clock.instant();
        List<String> seeded = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<EmployeeRosterRow> roster = mapper.listCurrentRoster();
        if (roster == null) {
            roster = List.of();
        }
        Map<String, List<EmployeeRosterRow>> byNumber = indexBy(
                roster, EmployeeRosterRow::employeeNumber);
        Map<String, List<EmployeeRosterRow>> byName = indexBy(
                roster, EmployeeRosterRow::displayName);
        Set<String> boundSnowflakes = new HashSet<>();
        Map<String, EmployeeDirectoryPerson> peopleById = new LinkedHashMap<>();
        if (directoryPeople != null) {
            for (EmployeeDirectoryPerson person : directoryPeople) {
                if (person != null
                        && person.userId() != null
                        && !person.userId().isBlank()) {
                    peopleById.putIfAbsent(person.userId(), person);
                }
            }
        }

        for (var catalog : DeliConflictIdentityCatalog.knownBindings()) {
            if (!peopleById.isEmpty()
                    && !directoryAccepts(directoryEmpnoMap(peopleById), catalog)) {
                skipped.add(catalog.targetEmployeeNumber()
                        + " " + catalog.displayName()
                        + " catalog-directory-mismatch");
                continue;
            }
            writeBinding(
                    resolvedSource,
                    actorId,
                    at,
                    catalog.deliUserId(),
                    catalog.targetEmployeeNumber(),
                    catalog.displayName(),
                    "JULY_CONFLICT_MAP:" + catalog.deliUserId(),
                    "得力设备工号错挂，按七月映射确认雪花人员绑定",
                    seeded,
                    skipped,
                    missing);
            boundSnowflakes.add(catalog.deliUserId());
        }

        for (EmployeeDirectoryPerson person : peopleById.values()) {
            if (boundSnowflakes.contains(person.userId())) {
                continue;
            }
            if (!isSnowflakePersonId(person.userId())) {
                skipped.add(person.userId()
                        + " " + coalesce(person.displayName())
                        + " short-id-not-bound");
                continue;
            }
            BindDecision decision = decide(person, byNumber, byName);
            if (decision == null) {
                skipped.add(coalesce(person.employeeNum())
                        + " " + coalesce(person.displayName())
                        + " " + person.userId()
                        + " unmatched-or-ambiguous");
                continue;
            }
            writeBinding(
                    resolvedSource,
                    actorId,
                    at,
                    person.userId(),
                    decision.employeeNumber(),
                    coalesce(person.displayName()),
                    "DELI_DIRECTORY:" + person.userId(),
                    "得力人员目录雪花 id 按花名册确认绑定 " + decision.reason(),
                    seeded,
                    skipped,
                    missing);
            boundSnowflakes.add(person.userId());
        }
        return new SeedResult(
                List.copyOf(seeded),
                List.copyOf(skipped),
                List.copyOf(missing));
    }

    static BindDecision decide(
            EmployeeDirectoryPerson person,
            Map<String, List<EmployeeRosterRow>> byNumber,
            Map<String, List<EmployeeRosterRow>> byName) {
        EmployeeRosterRow named = unique(byName.get(trim(person.displayName())));
        EmployeeRosterRow numbered = unique(byNumber.get(trim(person.employeeNum())));
        if (named != null && numbered != null
                && !named.employeeId().equals(numbered.employeeId())) {
            return new BindDecision(named.employeeNumber(), "NAME_OVER_DEVICE_EMPNO");
        }
        if (numbered != null) {
            return new BindDecision(numbered.employeeNumber(), "EMPLOYEE_NUMBER");
        }
        if (named != null) {
            return new BindDecision(named.employeeNumber(), "DISPLAY_NAME");
        }
        return null;
    }

    static boolean directoryAccepts(
            Map<String, String> directory,
            DeliConflictIdentityCatalog.Entry entry) {
        if (directory == null || directory.isEmpty()) {
            return true;
        }
        String mapped = directory.get(entry.deliUserId());
        if (mapped == null || mapped.isBlank()) {
            return false;
        }
        return Objects.equals(mapped, entry.targetEmployeeNumber())
                || Objects.equals(mapped, entry.deviceEmployeeNumber());
    }

    static boolean isSnowflakePersonId(String personRef) {
        if (personRef == null || personRef.length() < 16) {
            return false;
        }
        for (int index = 0; index < personRef.length(); index++) {
            if (!Character.isDigit(personRef.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private void writeBinding(
            String sourceId,
            String actorId,
            Instant at,
            String deliUserId,
            String targetEmployeeNumber,
            String displayName,
            String confirmationRef,
            String reason,
            List<String> seeded,
            List<String> skipped,
            List<String> missing) {
        String employeeId = mapper.findEmployeeIdByNumber(targetEmployeeNumber);
        if (employeeId == null) {
            missing.add(targetEmployeeNumber + " " + coalesce(displayName));
            return;
        }
        String alreadyBound = mapper.findEmployeeIdByDeliPersonId(
                sourceId, deliUserId);
        if (alreadyBound != null && !alreadyBound.equals(employeeId)) {
            skipped.add(targetEmployeeNumber
                    + " " + coalesce(displayName)
                    + " already-bound-to=" + alreadyBound);
            return;
        }
        String bindingId = mapper.findCurrentBindingId(employeeId);
        if (bindingId == null) {
            bindingId = UUID.randomUUID().toString();
            mapper.insertBinding(
                    bindingId,
                    employeeId,
                    sourceId,
                    deliUserId,
                    targetEmployeeNumber,
                    confirmationRef,
                    actorId,
                    at,
                    BINDING_EFFECTIVE_FROM,
                    reason);
        } else {
            mapper.updateCurrentBinding(
                    bindingId,
                    sourceId,
                    deliUserId,
                    confirmationRef,
                    actorId,
                    at,
                    BINDING_EFFECTIVE_FROM,
                    reason);
        }
        mapper.insertRevision(
                UUID.randomUUID().toString(),
                bindingId,
                sourceId,
                employeeId,
                deliUserId,
                confirmationRef,
                actorId,
                at,
                reason);
        seeded.add(targetEmployeeNumber + " " + coalesce(displayName));
    }

    private static Map<String, String> directoryEmpnoMap(
            Map<String, EmployeeDirectoryPerson> peopleById) {
        Map<String, String> map = new LinkedHashMap<>();
        for (var person : peopleById.values()) {
            if (person.employeeNum() != null && !person.employeeNum().isBlank()) {
                map.put(person.userId(), person.employeeNum());
            }
        }
        return map;
    }

    private static Map<String, List<EmployeeRosterRow>> indexBy(
            List<EmployeeRosterRow> roster,
            java.util.function.Function<EmployeeRosterRow, String> key) {
        Map<String, List<EmployeeRosterRow>> index = new LinkedHashMap<>();
        for (EmployeeRosterRow row : roster) {
            String value = trim(key.apply(row));
            if (value == null) {
                continue;
            }
            index.computeIfAbsent(value, ignored -> new ArrayList<>()).add(row);
        }
        return index;
    }

    private static EmployeeRosterRow unique(List<EmployeeRosterRow> rows) {
        if (rows == null || rows.size() != 1) {
            return null;
        }
        return rows.getFirst();
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String coalesce(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }
}
