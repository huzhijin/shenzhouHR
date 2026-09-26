package com.szsemicon.hr.people.application;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.people.application.PeopleRepository.CompanyRef;
import com.szsemicon.hr.people.application.roster.RosterNames;
import com.szsemicon.hr.people.application.roster.RosterPrecheck;
import com.szsemicon.hr.people.application.roster.RosterPrecheck.Company;
import com.szsemicon.hr.people.application.roster.RosterPrecheck.Employee;
import com.szsemicon.hr.people.application.roster.RosterPrecheck.Org;
import com.szsemicon.hr.people.application.roster.RosterPrecheck.Result;
import com.szsemicon.hr.people.application.roster.RosterRow;
import com.szsemicon.hr.people.application.roster.RosterWorkbook;
import com.szsemicon.hr.people.domain.PeopleModels.EmployeeVersion;
import com.szsemicon.hr.people.domain.PeopleModels.EmploymentPeriod;
import com.szsemicon.hr.people.domain.PeopleModels.OrganizationVersion;
import com.szsemicon.hr.people.infrastructure.persistence.RosterImportMapper;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import com.szsemicon.hr.shared.validation.IdempotencyKeyPolicy;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class RosterImportService {

    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principals;
    private final PeopleRepository people;
    private final RosterImportMapper batches;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final NewHireAttendanceRecovery newHireRecovery;

    public RosterImportService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principals,
            PeopleRepository people,
            RosterImportMapper batches,
            AuditService audit,
            ObjectMapper objectMapper,
            Clock clock) {
        this(capabilities, principals, people, batches, audit, objectMapper, clock, null);
    }

    @Autowired
    public RosterImportService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principals,
            PeopleRepository people,
            RosterImportMapper batches,
            AuditService audit,
            ObjectMapper objectMapper,
            Clock clock,
            @Autowired(required = false) NewHireAttendanceRecovery newHireRecovery) {
        this.capabilities = capabilities;
        this.principals = principals;
        this.people = people;
        this.batches = batches;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.newHireRecovery = newHireRecovery;
    }

    public byte[] template() {
        capabilities.require(CapabilityCodes.PEOPLE_IMPORT_TEMPLATE_DOWNLOAD);
        return RosterWorkbook.template();
    }

    @Transactional
    public BatchDetail upload(String originalFileName, byte[] content, String reason) {
        capabilities.require(CapabilityCodes.PEOPLE_IMPORT_UPLOAD);
        if (reason == null || reason.strip().length() < 2) {
            throw invalid("原因至少 2 个字符");
        }
        if (content == null || content.length == 0) {
            throw invalid("请上传花名册 .xlsx");
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new ApiProblemException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "PEOPLE_IMPORT_FILE_TOO_LARGE", "上传文件超过 20MB 限制");
        }
        if (originalFileName == null || !originalFileName.toLowerCase().endsWith(".xlsx")) {
            throw invalid("仅支持 .xlsx");
        }
        List<RosterRow> rows;
        try {
            rows = RosterWorkbook.parse(content);
        } catch (PeopleWorkbookException exception) {
            throw invalid(exception.getMessage());
        }
        Result precheck = RosterPrecheck.run(rows, snapshot());
        Instant now = clock.instant();
        String actor = principals.currentPrincipalId();
        String batchId = UUID.randomUUID().toString();
        String json = writeJson(precheck);
        batches.insert(new RosterImportMapper.Row(
                batchId,
                "PRECHECKED",
                reason.strip(),
                originalFileName,
                sha256(content),
                content,
                json,
                0,
                actor,
                now,
                null));
        audit.record(
                actor, "ROSTER_IMPORT_PRECHECKED", "ROSTER_IMPORT", batchId,
                "SUCCESS", reason.strip(), null, sha256(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return toDetail(batches.find(batchId), precheck);
    }

    @Transactional(readOnly = true)
    public BatchDetail get(String batchId) {
        capabilities.require(CapabilityCodes.PEOPLE_IMPORT_READ);
        RosterImportMapper.Row row = batches.find(batchId);
        if (row == null) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        return toDetail(row, readJson(row.precheckJson()));
    }

    @Transactional
    public BatchDetail publish(String batchId, String reason, String idempotencyKey) {
        capabilities.require(CapabilityCodes.PEOPLE_IMPORT_PUBLISH);
        if (!IdempotencyKeyPolicy.isValid(idempotencyKey)) {
            throw invalid("Idempotency-Key 必须为 16 至 128 位字母、数字或 ._:-");
        }
        if (reason == null || reason.strip().length() < 2) {
            throw invalid("原因至少 2 个字符");
        }
        RosterImportMapper.Row batch = batches.find(batchId);
        if (batch == null) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        if ("PUBLISHED".equals(batch.status())) {
            return toDetail(batch, readJson(batch.precheckJson()));
        }
        Result precheck = readJson(batch.precheckJson());
        if (!precheck.canPublish()) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "ROSTER_IMPORT_BLOCKED",
                    "存在冲突或错误，处理后再发布");
        }
        List<RosterRow> rows;
        try {
            rows = RosterWorkbook.parse(batch.fileContent());
        } catch (PeopleWorkbookException exception) {
            throw invalid(exception.getMessage());
        }
        Instant now = clock.instant();
        String actor = principals.currentPrincipalId();
        Map<String, String> pathIds = indexExistingPaths();
        LocalDate earliestHire = null;
        for (RosterPrecheck.Diff diff : precheck.diffs()) {
            if (!"ADDED".equals(diff.category()) && !"UPDATED".equals(diff.category())) {
                continue;
            }
            RosterRow row = rowOf(rows, diff.rowNumber());
            String companyCode = RosterNames.mapCompanyCode(row.companyName()).orElseThrow();
            CompanyRef company = companyByCode(companyCode);
            people.lockCompany(company.companyId());
            String leafId = ensurePath(company, row, pathIds, batch.batchId(), actor, now, reason.strip());
            LocalDate hired = publishEmployee(
                    company, row, leafId, batch.batchId(), actor, now, reason.strip());
            if (hired != null && (earliestHire == null || hired.isBefore(earliestHire))) {
                earliestHire = hired;
            }
        }
        NewHireRecoverySignals.afterCommit(newHireRecovery, earliestHire);
        people.rebuildOrganizationClosure(UUID.randomUUID().toString());
        if (batches.markPublished(batchId, batch.rowVersion(), now) != 1) {
            throw new ApiProblemException(HttpStatus.CONFLICT, "STALE_VERSION", "导入任务状态已变化");
        }
        audit.record(
                actor, "ROSTER_IMPORT_PUBLISHED", "ROSTER_IMPORT", batchId,
                "SUCCESS", reason.strip(), null, batch.fileSha256());
        return get(batchId);
    }

    private LocalDate publishEmployee(
            CompanyRef company,
            RosterRow row,
            String organizationId,
            String batchId,
            String actor,
            Instant now,
            String reason) {
        EmployeeVersion existing = people.findEmployeeByNumber(
                company.companyId(), row.employeeNumber().strip()).orElse(null);
        boolean created = existing == null;
        String employeeId;
        if (created) {
            employeeId = UUID.randomUUID().toString();
            people.createEmployeeIdentity(
                    employeeId,
                    company.companyId(),
                    row.employeeNumber().strip(),
                    row.displayName().strip(),
                    "ACTIVE",
                    row.hireDate(),
                    now);
            EmployeeVersion version = new EmployeeVersion(
                    UUID.randomUUID().toString(),
                    employeeId,
                    company.companyId(),
                    row.employeeNumber().strip(),
                    row.displayName().strip(),
                    "ACTIVE",
                    null,
                    row.hireDate(),
                    null,
                    "LOCAL",
                    batchId,
                    0,
                    reason,
                    actor,
                    now);
            people.saveEmployeeVersion(version);
            audit.record(
                    actor, "EMPLOYEE_VERSION_CREATED", "EMPLOYEE", employeeId,
                    "SUCCESS", reason, null, employeeId);
        } else {
            employeeId = existing.employeeId();
        }
        List<EmploymentPeriod> open = people.listEmploymentPeriods(employeeId, row.hireDate(), 20, 0)
                .stream()
                .filter(period -> period.endExclusive() == null)
                .toList();
        if (!open.isEmpty() && organizationId.equals(open.get(0).organizationId())) {
            return created ? row.hireDate() : null;
        }
        if (!open.isEmpty()) {
            return created ? row.hireDate() : null;
        }
        EmploymentPeriod period = new EmploymentPeriod(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                employeeId,
                organizationId,
                null,
                row.hireDate(),
                null,
                null,
                "ACTIVE",
                batchId,
                0,
                reason,
                actor,
                now);
        people.saveEmploymentPeriodVersion(period, true);
        audit.record(
                actor, "EMPLOYMENT_PERIOD_CREATED", "EMPLOYEE", employeeId,
                "SUCCESS", reason, null, period.employmentPeriodId());
        return created ? row.hireDate() : null;
    }

    private String ensurePath(
            CompanyRef company,
            RosterRow row,
            Map<String, String> pathIds,
            String batchId,
            String actor,
            Instant now,
            String reason) {
        List<String> tokens = row.pathTokens(company.code());
        String parentId = companyRoot(company.companyId(), pathIds);
        List<String> accumulated = new ArrayList<>();
        for (int index = 0; index < tokens.size(); index++) {
            accumulated.add(tokens.get(index));
            String key = company.companyId() + "|" + String.join("/", accumulated);
            String existing = pathIds.get(key);
            if (existing != null) {
                parentId = existing;
                continue;
            }
            boolean last = index == tokens.size() - 1;
            String type = last && !RosterNames.normalizeToken(row.groupName()).isEmpty()
                    ? "TEAM"
                    : "DEPARTMENT";
            String organizationId = UUID.randomUUID().toString();
            String code = generatedCode(company.companyId());
            people.lockCompany(company.companyId());
            people.createOrganizationIdentity(organizationId, company.companyId(), "ACTIVE", now);
            OrganizationVersion version = new OrganizationVersion(
                    UUID.randomUUID().toString(),
                    organizationId,
                    company.companyId(),
                    parentId,
                    code,
                    tokens.get(index),
                    type,
                    "ACTIVE",
                    row.hireDate() == null ? LocalDate.now(clock) : row.hireDate(),
                    null,
                    "LOCAL",
                    batchId,
                    0,
                    reason,
                    actor,
                    now,
                    0);
            people.saveOrganizationVersion(version, UUID.randomUUID().toString());
            audit.record(
                    actor, "ORGANIZATION_VERSION_CREATED", "ORGANIZATION", organizationId,
                    "SUCCESS", reason, null, organizationId);
            pathIds.put(key, organizationId);
            parentId = organizationId;
        }
        return parentId;
    }

    private Map<String, String> indexExistingPaths() {
        Map<String, String> pathIds = new HashMap<>();
        List<OrganizationVersion> orgs = people.listCurrentOrganizations();
        Map<String, List<OrganizationVersion>> byParent = new HashMap<>();
        for (OrganizationVersion org : orgs) {
            byParent.computeIfAbsent(
                    org.companyId() + "|" + (org.parentOrganizationId() == null ? "" : org.parentOrganizationId()),
                    key -> new ArrayList<>()).add(org);
            if (org.parentOrganizationId() == null) {
                pathIds.put(org.companyId() + "|", org.organizationId());
            }
        }
        for (CompanyRef company : people.listActiveCompanies()) {
            String root = pathIds.get(company.companyId() + "|");
            if (root == null) {
                continue;
            }
            walk(company, root, List.of(), byParent, pathIds);
        }
        return pathIds;
    }

    private void walk(
            CompanyRef company,
            String parentId,
            List<String> prefix,
            Map<String, List<OrganizationVersion>> byParent,
            Map<String, String> pathIds) {
        List<OrganizationVersion> children = byParent.getOrDefault(company.companyId() + "|" + parentId, List.of());
        for (OrganizationVersion child : children) {
            List<String> next = new ArrayList<>(prefix);
            next.add(RosterNames.alias(company.code(), child.name()));
            pathIds.put(company.companyId() + "|" + String.join("/", next), child.organizationId());
            walk(company, child.organizationId(), next, byParent, pathIds);
        }
    }

    private String companyRoot(String companyId, Map<String, String> pathIds) {
        return pathIds.get(companyId + "|");
    }

    private String generatedCode(String companyId) {
        for (int attempt = 0; attempt < 8; attempt++) {
            String code = "D-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            if (!people.organizationCodeExists(companyId, code, null)) {
                return code;
            }
        }
        throw invalid("无法生成唯一组织编码");
    }

    private CompanyRef companyByCode(String code) {
        return people.listActiveCompanies().stream()
                .filter(company -> code.equals(company.code()))
                .findFirst()
                .orElseThrow(() -> invalid("系统中没有公司 " + code));
    }

    private RosterPrecheck.Snapshot snapshot() {
        List<Company> companies = people.listActiveCompanies().stream()
                .map(company -> new Company(company.companyId(), company.code(), company.name()))
                .toList();
        List<Org> orgs = people.listCurrentOrganizations().stream()
                .map(org -> new Org(
                        org.organizationId(),
                        org.companyId(),
                        org.parentOrganizationId(),
                        org.name(),
                        org.code(),
                        org.organizationType(),
                        org.status()))
                .toList();
        Map<String, String> openOrg = new HashMap<>();
        for (EmploymentPeriod period : people.listOpenEmployments()) {
            openOrg.putIfAbsent(period.employeeId(), period.organizationId());
        }
        List<Employee> employees = people.listCurrentEmployees().stream()
                .map(employee -> new Employee(
                        employee.employeeId(),
                        employee.companyId(),
                        employee.employeeNumber(),
                        employee.displayName(),
                        openOrg.get(employee.employeeId())))
                .toList();
        return RosterPrecheck.snapshot(companies, orgs, employees);
    }

    private RosterRow rowOf(List<RosterRow> rows, int rowNumber) {
        return rows.stream()
                .filter(row -> row.rowNumber() == rowNumber)
                .findFirst()
                .orElseThrow(() -> invalid("预检行不存在"));
    }

    private BatchDetail toDetail(RosterImportMapper.Row row, Result precheck) {
        return new BatchDetail(
                row.batchId(),
                row.status(),
                row.reason(),
                row.originalFileName(),
                row.fileSha256(),
                precheck,
                row.rowVersion(),
                row.createdAt(),
                row.publishedAt());
    }

    private String writeJson(Result precheck) {
        try {
            return objectMapper.writeValueAsString(precheck);
        } catch (Exception exception) {
            throw new IllegalStateException("roster precheck must be JSON serializable", exception);
        }
    }

    private Result readJson(String json) {
        try {
            return objectMapper.readValue(json, Result.class);
        } catch (Exception exception) {
            throw new IllegalStateException("roster precheck JSON is unreadable", exception);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static ApiProblemException invalid(String message) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    public record BatchDetail(
            String batchId,
            String status,
            String reason,
            String originalFileName,
            String fileSha256,
            Result precheck,
            long rowVersion,
            Instant createdAt,
            Instant publishedAt) {
    }
}
