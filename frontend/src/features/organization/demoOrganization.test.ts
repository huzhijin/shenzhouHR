import { describe, expect, it } from 'vitest';

import { getDemoOrganizationTree } from './demoOrganization';
import type { OrganizationNode } from './organizationApi';

describe('demo organization', () => {
  it('provides a representative hierarchy with precise external ids', () => {
    const roots = getDemoOrganizationTree();
    const nodes = flatten(roots);

    expect(roots).toHaveLength(1);
    expect(nodes).toHaveLength(14);
    expect(nodes.some((node) => node.organizationType === 'TEAM')).toBe(true);
    expect(nodes.every((node) => (node.sourceOrganizationId?.length ?? 0) > 16)).toBe(true);
  });
});

function flatten(nodes: OrganizationNode[]): OrganizationNode[] {
  return nodes.flatMap((node) => [node, ...flatten(node.children)]);
}
