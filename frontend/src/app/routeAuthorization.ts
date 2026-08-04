import type {
  CurrentCapabilities,
  MenuItem,
} from '../features/session/sessionApi';

const capabilitiesByPath: Readonly<Record<string, readonly string[]>> = {
  '/organization': ['MASTER_DATA:READ', 'ORGANIZATION:READ'],
  '/employees': ['MASTER_DATA:READ'],
  '/people/import': ['PEOPLE_IMPORT:READ', 'PEOPLE_IMPORT:TEMPLATE_DOWNLOAD'],
  '/people/organization': ['MASTER_DATA:READ', 'ORGANIZATION:READ'],
  '/people/employees': ['MASTER_DATA:READ'],
  '/people/employees/:employeeId': ['EMPLOYEE:READ'],
  '/rules': ['POLICY:READ'],
  '/rules/templates': ['POLICY:READ'],
  '/rules/attendance-groups': ['ATTENDANCE_SETUP:READ'],
  '/rules/shifts': ['ATTENDANCE_SETUP:READ'],
  '/rules/calendars': ['ATTENDANCE_SETUP:READ'],
  '/rules/attendance-policy': ['ATTENDANCE_SETUP:READ'],
  '/sources/online': ['ATTENDANCE_SOURCE:READ'],
  '/sources/oa': ['ATTENDANCE_SOURCE:READ'],
  '/sources/jobs': ['ATTENDANCE_SOURCE:READ'],
  '/sources/attendance-excel': ['ATTENDANCE_PUNCH_IMPORT:READ'],
  '/access/accounts': ['ACCOUNT:READ'],
  '/access/roles': ['ROLE:READ'],
  '/access/audit': ['AUDIT:READ'],
  '/attendance/screen': ['ATTENDANCE_DASHBOARD:READ'],
  '/attendance/reports': ['ATTENDANCE_REPORT:READ'],
  '/me/today': ['ATTENDANCE_SELF:READ'],
  '/me/records': ['ATTENDANCE_SELF:READ'],
  '/me/leave': ['LEAVE_SELF:READ'],
  '/me/feedback': ['ATTENDANCE_FEEDBACK:READ'],
};

const anyCapabilitiesByPath: Readonly<Record<string, readonly string[]>> = {
  // The route selects an organization or strictly self-scoped view in App.tsx.
  '/workbench': ['ATTENDANCE_DASHBOARD:READ', 'ATTENDANCE_SELF:READ'],
};

export function authorizedMenu(session: CurrentCapabilities): MenuItem[] {
  return session.menu.filter((item) => {
    const anyRequiredCapabilities = anyCapabilitiesByPath[item.path];
    if (anyRequiredCapabilities !== undefined) {
      return anyRequiredCapabilities.some((capability) => (
        session.capabilities.includes(capability)
      ));
    }
    const requiredCapabilities = capabilitiesByPath[item.path];
    return requiredCapabilities !== undefined
      && requiredCapabilities.every((capability) => session.capabilities.includes(capability));
  });
}

export function firstAuthorizedPath(session: CurrentCapabilities): string | undefined {
  return authorizedMenu(session)[0]?.path;
}

export function selectedMenuKey(
  menu: readonly MenuItem[],
  pathname: string,
): string | undefined {
  return menu
    .filter((item) => isPathWithin(pathname, item.path))
    .reduce<MenuItem | undefined>((longest, item) => (
      longest === undefined || item.path.length > longest.path.length ? item : longest
    ), undefined)
    ?.key;
}

function isPathWithin(pathname: string, menuPath: string): boolean {
  return pathname === menuPath || pathname.startsWith(`${menuPath}/`);
}
