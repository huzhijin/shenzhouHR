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

export function filterOrganizationNodes(
  nodes: OrganizationNode[],
  query: string,
): OrganizationNode[] {
  const normalizedQuery = query.trim().toLocaleLowerCase('zh-CN');
  if (!normalizedQuery) return nodes;
  return filterOrganizationBranch(nodes, normalizedQuery);
}

function filterOrganizationBranch(
  nodes: OrganizationNode[],
  normalizedQuery: string,
  parentNames: string[] = [],
): OrganizationNode[] {
  return nodes.flatMap((node) => {
    const path = [...parentNames, node.name];
    const children = filterOrganizationBranch(
      node.children,
      normalizedQuery,
      path,
    );
    const searchText = `${path.join(' ')} ${node.code}`
      .toLocaleLowerCase('zh-CN');
    if (!searchText.includes(normalizedQuery) && children.length === 0) return [];
    return [{ ...node, children }];
  });
}
