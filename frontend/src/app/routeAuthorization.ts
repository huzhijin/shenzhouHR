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
  '/access/accounts': ['ACCOUNT:READ'],
  '/access/roles': ['ROLE:READ'],
  '/access/audit': ['AUDIT:READ'],
};

export function authorizedMenu(session: CurrentCapabilities): MenuItem[] {
  return session.menu.filter((item) => {
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
