import type { OrganizationNode } from './organizationApi';

export interface OrganizationTreeDataNode {
  key: string;
  title: string;
  unit: OrganizationNode;
  children: OrganizationTreeDataNode[];
}

export function toOrganizationTreeData(nodes: OrganizationNode[]): OrganizationTreeDataNode[] {
  return nodes.map((node) => ({
    key: node.organizationId,
    title: node.name,
    unit: node,
    children: toOrganizationTreeData(node.children),
  }));
}

export function topLevelOrganizationKeys(nodes: OrganizationNode[]): string[] {
  return nodes.map((node) => node.organizationId);
}

export function allOrganizationKeys(nodes: OrganizationNode[]): string[] {
  return nodes.flatMap((node) => [
    node.organizationId,
    ...allOrganizationKeys(node.children),
  ]);
}
