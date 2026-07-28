package com.szsemicon.hr.organization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.organization.domain.OrganizationUnit;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CurrentOrganizationQueryServiceTest {

    private static final Instant EFFECTIVE_FROM = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void buildsStableTreeAndTreatsInvisibleParentAsScopeRoot() {
        OrganizationUnit child = unit("child", "parent", "20", "制造部");
        OrganizationUnit root = unit("root", null, "10", "江苏神州半导体科技股份有限公司");
        OrganizationUnit scopedRoot = unit("scoped", "outside-scope", "30", "技术中心");

        List<OrganizationTreeNode> tree = CurrentOrganizationQueryService.buildTree(
                List.of(scopedRoot, child, root));

        assertThat(tree).extracting(OrganizationTreeNode::organizationId)
                .containsExactly("root", "child", "scoped");
        assertThat(tree).allMatch(node -> node.children().isEmpty());
    }

    @Test
    void nestsVisibleChildrenUnderTheirParent() {
        OrganizationUnit parent = unit("parent", null, "10", "总部");
        OrganizationUnit child = unit("child", "parent", "20", "人力资源部");

        List<OrganizationTreeNode> tree = CurrentOrganizationQueryService.buildTree(List.of(child, parent));

        assertThat(tree).hasSize(1);
        assertThat(tree.getFirst().children()).extracting(OrganizationTreeNode::organizationId)
                .containsExactly("child");
    }

    @Test
    void rejectsCyclesInsteadOfReturningCorruptHierarchy() {
        OrganizationUnit first = unit("first", "second", "10", "第一组织");
        OrganizationUnit second = unit("second", "first", "20", "第二组织");

        assertThatThrownBy(() -> CurrentOrganizationQueryService.buildTree(List.of(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle");
    }

    private OrganizationUnit unit(String id, String parentId, String code, String name) {
        return new OrganizationUnit(
                id,
                parentId,
                code,
                name,
                "DEPARTMENT",
                "ACTIVE",
                "9223372036854775807",
                EFFECTIVE_FROM,
                null);
    }
}
