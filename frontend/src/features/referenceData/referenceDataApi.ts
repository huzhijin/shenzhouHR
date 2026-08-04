import { requestJson } from '../../shared/api/apiClient';
import { isDemoMode } from '../../shared/config/runtimeMode';
import {
  listAttendanceGroups,
  listCalendars,
  listLocations,
  listShifts,
  listShiftVersions,
} from '../attendanceSetup/attendanceSetupApi';
import { loadAllAttendanceDirectoryItems } from '../attendanceSetup/attendanceDirectory';
import type {
  AttendanceGroupView,
  LocationView,
  ShiftTemplateView,
  ShiftVersionView,
  WorkCalendarView,
} from '../attendanceSetup/attendanceSetupTypes';
import { listAttendanceSources } from '../attendanceSources/attendanceSourceApi';
import type { AttendanceSourceView } from '../attendanceSources/attendanceSourceTypes';
import {
  getEmployees,
  type EmployeeSummary,
} from '../employee/employeeApi';
import {
  getCurrentOrganizationTree,
  type OrganizationNode,
} from '../organization/organizationApi';

export interface CompanyReference {
  companyId: string;
  companyName: string;
  companyCode?: string | null;
}

interface CompanyDirectoryResponse {
  items: CompanyDirectoryItem[];
}

interface CompanyDirectoryItem {
  companyId: string;
  name?: string;
  code?: string | null;
  companyName?: string;
  companyCode?: string | null;
}

const demoCompanies: readonly CompanyReference[] = [{
  companyId: '9700000000000000001',
  companyName: '江苏神州半导体科技股份有限公司',
  companyCode: 'SZSC',
}];

export async function listReferenceCompanies(): Promise<CompanyReference[]> {
  if (isDemoMode()) {
    return demoCompanies.map((company) => ({ ...company }));
  }
  const response = await requestJson<CompanyDirectoryResponse | CompanyDirectoryItem[]>(
    '/api/v1/reference-data/companies',
  );
  const items = Array.isArray(response) ? response : response.items;
  return items.map((item) => ({
    companyId: item.companyId,
    companyName: item.name ?? item.companyName ?? '',
    companyCode: item.code ?? item.companyCode,
  }));
}

export async function searchReferenceEmployees(
  query: string,
): Promise<EmployeeSummary[]> {
  const page = await getEmployees(0, 50, {
    query: query.trim() || undefined,
    sort: 'employeeNumber',
  });
  return page.items;
}

export function listReferenceOrganizations(): Promise<OrganizationNode[]> {
  return getCurrentOrganizationTree(false);
}

export async function listReferenceLocations(): Promise<LocationView[]> {
  return loadAllAttendanceDirectoryItems((page, size) => listLocations(page, size));
}

export async function listReferenceAttendanceGroups(
  asOf?: string,
): Promise<AttendanceGroupView[]> {
  return loadAllAttendanceDirectoryItems(
    (page, size) => listAttendanceGroups(asOf, page, size),
  );
}

export async function listReferenceShiftTemplates(): Promise<ShiftTemplateView[]> {
  return loadAllAttendanceDirectoryItems((page, size) => listShifts(page, size));
}

export async function listReferenceShiftVersions(
  shiftId: string,
): Promise<ShiftVersionView[]> {
  return loadAllAttendanceDirectoryItems(
    (page, size) => listShiftVersions(shiftId, page, size),
  );
}

export async function listReferenceCalendars(
  year?: number,
): Promise<WorkCalendarView[]> {
  return loadAllAttendanceDirectoryItems(
    (page, size) => listCalendars(year, page, size),
  );
}

export async function listReferenceAttendanceSources(): Promise<AttendanceSourceView[]> {
  return loadAllAttendanceDirectoryItems(
    async (page, size) => {
      const response = await listAttendanceSources(page, size);
      return { items: response.items, total: response.totalElements };
    },
  );
}
