package com.szsemicon.hr.organization.application;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.organization.domain.OrganizationUnit;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CurrentOrganizationQueryService {

    private static final Comparator<OrganizationUnit> UNIT_ORDER = Comparator
            .comparing(OrganizationUnit::code)
            .thenComparing(OrganizationUnit::name)
            .thenComparing(OrganizationUnit::organizationId);

    private final CurrentCapabilityService capabilityService;
    private final CurrentPrincipalProvider principalProvider;
    private final OrganizationReadRepository repository;
    private final Clock clock;

    public CurrentOrganizationQueryService(
            CurrentCapabilityService capabilityService,
            CurrentPrincipalProvider principalProvider,
            OrganizationReadRepository repository,
            Clock clock) {
        this.capabilityService = capabilityService;
        this.principalProvider = principalProvider;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<OrganizationTreeNode> query(LocalDate asOf, boolean includeInactive) {
        capabilityService.require(CapabilityCodes.MASTER_DATA_READ);
        Instant permissionAt = clock.instant();
        Instant effectiveAt = asOf == null
                ? permissionAt
                : asOf.atStartOfDay(ZoneOffset.UTC).toInstant();
        List<OrganizationUnit> units = repository.findCurrentVisibleTo(
                principalProvider.currentPrincipalId(),
                CapabilityCodes.MASTER_DATA_READ,
                permissionAt,
                effectiveAt,
                includeInactive);
        return buildTree(units);
    }

    static List<OrganizationTreeNode> buildTree(List<OrganizationUnit> units) {
        Map<String, MutableNode> byId = new LinkedHashMap<>();
        units.stream().sorted(UNIT_ORDER).forEach(unit -> {
            if (byId.put(unit.organizationId(), new MutableNode(unit)) != null) {
                throw new IllegalStateException("duplicate organization identity in current projection");
            }
        });
        detectCycles(byId);

        List<MutableNode> roots = new ArrayList<>();
        for (MutableNode node : byId.values()) {
            MutableNode parent = byId.get(node.unit.parentOrganizationId());
            if (parent == null) {
                roots.add(node);
            } else {
                parent.children.add(node);
            }
        }
        return roots.stream().map(MutableNode::freeze).toList();
    }

    private static void detectCycles(Map<String, MutableNode> byId) {
        Map<String, VisitState> states = new HashMap<>();
        for (String organizationId : byId.keySet()) {
            visit(organizationId, byId, states);
        }
    }

    private static void visit(
            String organizationId,
            Map<String, MutableNode> byId,
            Map<String, VisitState> states) {
        VisitState state = states.get(organizationId);
        if (state == VisitState.VISITING) {
            throw new IllegalStateException("cycle detected in current organization projection");
        }
        if (state == VisitState.VISITED) {
            return;
        }
        states.put(organizationId, VisitState.VISITING);
        String parentId = byId.get(organizationId).unit.parentOrganizationId();
        if (parentId != null && byId.containsKey(parentId)) {
            visit(parentId, byId, states);
        }
        states.put(organizationId, VisitState.VISITED);
    }

    private enum VisitState {
        VISITING,
        VISITED
    }

    private static final class MutableNode {
        private final OrganizationUnit unit;
        private final List<MutableNode> children = new ArrayList<>();

        private MutableNode(OrganizationUnit unit) {
            this.unit = unit;
        }

        private OrganizationTreeNode freeze() {
            return new OrganizationTreeNode(
                    unit.organizationId(),
                    unit.organizationVersionId(),
                    unit.code(),
                    unit.name(),
                    unit.organizationType(),
                    unit.status(),
                    unit.sourceOrganizationId(),
                    unit.effectiveFrom(),
                    unit.effectiveTo(),
                    unit.sourceAuthority(),
                    unit.rowVersion(),
                    children.stream().map(MutableNode::freeze).toList());
        }
    }
}
