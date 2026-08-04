import type { AsyncResource } from '../../shared/hooks/useAsyncResource';

export const ATTENDANCE_DIRECTORY_PAGE_SIZE = 100;

type DirectoryStatus = AsyncResource<unknown>['status'];

export async function loadAllAttendanceDirectoryItems<T>(
  loadPage: (page: number, size: number) => Promise<{
    items: T[];
    total: number;
  }>,
): Promise<T[]> {
  const firstPage = await loadPage(0, ATTENDANCE_DIRECTORY_PAGE_SIZE);
  const items = [...firstPage.items];
  const pageCount = Math.ceil(
    firstPage.total / ATTENDANCE_DIRECTORY_PAGE_SIZE,
  );

  for (let page = 1; page < pageCount; page += 1) {
    const nextPage = await loadPage(page, ATTENDANCE_DIRECTORY_PAGE_SIZE);
    items.push(...nextPage.items);
  }

  return items;
}

export function missingDirectoryLabel(
  status: DirectoryStatus,
  resourceName: string,
): string {
  if (status === 'loading' || status === 'partial-loading') {
    return `${resourceName}名称加载中`;
  }
  if (status === 'ready' || status === 'empty') {
    return `历史${resourceName}（名称未找到）`;
  }
  return `${resourceName}名称暂不可用`;
}
