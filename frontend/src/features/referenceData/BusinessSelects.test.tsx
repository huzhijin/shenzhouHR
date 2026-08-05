import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { Form } from 'antd';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationDialog } from '../attendanceSetup/AttendanceGroupDialogs';
import {
  AttendanceGroupSelect,
  AttendanceSourceSelect,
  CalendarSelect,
  CompanySelect,
  EmployeeSelect,
  LocationSelect,
  OrganizationSelect,
  ShiftTemplateSelect,
  ShiftVersionSelect,
} from './BusinessSelects';
import { ReferenceSelect } from './ReferenceSelect';
import type * as referenceApi from './referenceDataApi';

const api = vi.hoisted(() => ({
  listReferenceAttendanceGroups: vi.fn(),
  listReferenceAttendanceSources: vi.fn(),
  listReferenceCalendars: vi.fn(),
  listReferenceCompanies: vi.fn(),
  listReferenceLocations: vi.fn(),
  listReferenceOrganizations: vi.fn(),
  listReferenceShiftTemplates: vi.fn(),
  listReferenceShiftVersions: vi.fn(),
  searchReferenceEmployees: vi.fn(),
}));

vi.mock('./referenceDataApi', () => api);

describe('business reference selects', () => {
  beforeEach(() => {
    api.listReferenceCompanies.mockResolvedValue([company]);
    api.searchReferenceEmployees.mockResolvedValue([employee]);
    api.listReferenceOrganizations.mockResolvedValue([organization]);
    api.listReferenceLocations.mockResolvedValue([location]);
    api.listReferenceAttendanceGroups.mockResolvedValue([group]);
    api.listReferenceShiftTemplates.mockResolvedValue([shift]);
    api.listReferenceShiftVersions.mockResolvedValue([shiftVersion]);
    api.listReferenceCalendars.mockResolvedValue([calendar]);
    api.listReferenceAttendanceSources.mockResolvedValue([source]);
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('renders business labels for every selected ID without exposing raw IDs', async () => {
    render(
      <>
        <CompanySelect aria-label="公司" value={company.companyId} />
        <EmployeeSelect aria-label="人员" value={employee.employeeId} />
        <OrganizationSelect
          aria-label="部门"
          value="organization-1"
        />
        <LocationSelect aria-label="地点" value={location.locationId} />
        <AttendanceGroupSelect aria-label="考勤组" value={group.groupId} />
        <ShiftTemplateSelect aria-label="班次" value={shift.shiftId} />
        <ShiftVersionSelect
          aria-label="班次版本"
          shiftId={shift.shiftId}
          shiftLabel={shift.name}
          value={shiftVersion.shiftVersionId}
        />
        <CalendarSelect aria-label="日历" value={calendar.calendarId} />
        <AttendanceSourceSelect aria-label="考勤来源" value={source.sourceId} />
      </>,
    );

    expect(await screen.findByText(company.companyName)).toBeInTheDocument();
    expect(await screen.findByText('张三（SZ0008）· 制造一部')).toBeInTheDocument();
    expect(await screen.findByText('神州半导体 / 制造一部'))
      .toBeInTheDocument();
    expect(screen.queryByText(/MFG-01/)).not.toBeInTheDocument();
    expect(await screen.findByText('苏州一号厂（LOC-01） · 启用')).toBeInTheDocument();
    expect(await screen.findByText('标准考勤组（GROUP-01） · 修订 2'))
      .toBeInTheDocument();
    expect(await screen.findByText('标准白班（SHIFT-01） · 启用')).toBeInTheDocument();
    expect(await screen.findByText('标准白班 · V3 · 2026-01-01 至 长期'))
      .toBeInTheDocument();
    expect(await screen.findByText('2026 工作日历（CAL-2026） · 2026 · V1'))
      .toBeInTheDocument();
    expect(await screen.findByText('得力考勤（DELI-01） · 得力云考勤'))
      .toBeInTheDocument();

    for (const id of [
      company.companyId,
      employee.employeeId,
      'organization-1',
      location.locationId,
      group.groupId,
      shift.shiftId,
      shiftVersion.shiftVersionId,
      calendar.calendarId,
      source.sourceId,
    ]) {
      expect(screen.queryByText(id)).not.toBeInTheDocument();
    }
  });

  it('loads all active shift templates when no shiftId is supplied', async () => {
    const inactiveShift = {
      ...shift,
      shiftId: 'shift-inactive',
      name: '停用夜班',
      status: 'INACTIVE' as const,
    };
    api.listReferenceShiftTemplates.mockResolvedValue([shift, inactiveShift]);

    render(
      <ShiftVersionSelect
        aria-label="所有班次版本"
        value={shiftVersion.shiftVersionId}
      />,
    );

    expect(await screen.findByText('标准白班 · V3 · 2026-01-01 至 长期'))
      .toBeInTheDocument();
    expect(screen.getByLabelText('所有班次版本')).not.toBeDisabled();
    expect(api.listReferenceShiftVersions).toHaveBeenCalledWith(shift.shiftId);
    expect(api.listReferenceShiftVersions).not.toHaveBeenCalledWith(
      inactiveShift.shiftId,
    );
  });

  it('debounces remote employee search and displays human labels', async () => {
    render(<EmployeeSelect aria-label="搜索人员" />);
    await waitFor(() => {
      expect(api.searchReferenceEmployees).toHaveBeenCalledWith('');
    });

    fireEvent.change(screen.getByLabelText('搜索人员'), {
      target: { value: 'SZ0008' },
    });

    await waitFor(() => {
      expect(api.searchReferenceEmployees).toHaveBeenLastCalledWith('SZ0008');
    }, { timeout: 1_000 });
  });

  it('accepts value/onChange injection from antd Form and preserves the ID value', async () => {
    const onFinish = vi.fn();
    render(
      <Form
        initialValues={{ companyId: company.companyId }}
        onFinish={onFinish}
      >
        <Form.Item name="companyId">
          <CompanySelect
            aria-label="表单公司"
            allowClear
            placeholder="选择所属公司"
          />
        </Form.Item>
        <button type="submit">保存</button>
      </Form>,
    );

    expect(await screen.findByText(company.companyName)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '保存' }));
    await waitFor(() => {
      expect(onFinish).toHaveBeenCalledWith({ companyId: company.companyId });
    });
  });

  it('shows an empty state and keeps disabled/placeholder props usable', async () => {
    api.listReferenceCompanies.mockResolvedValue([]);
    render(
      <CompanySelect
        aria-label="空公司"
        allowClear
        placeholder="选择所属公司"
      />,
    );

    const input = screen.getByLabelText('空公司');
    await waitFor(() => {
      expect(api.listReferenceCompanies).toHaveBeenCalled();
    });
    expect(input).toBeDisabled();
    expect(screen.getByText('无可用公司')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent(
      '当前账号没有可用公司，请联系系统管理员检查公司权限。',
    );
    expect(input).toHaveAttribute(
      'aria-describedby',
      screen.getByRole('status').id,
    );
  });

  it('auto-selects one authorized company and explains why the field is disabled', async () => {
    const onFinish = vi.fn();
    render(
      <Form onFinish={onFinish}>
        <Form.Item name="companyId">
          <CompanySelect aria-label="唯一公司" />
        </Form.Item>
        <button type="submit">保存唯一公司</button>
      </Form>,
    );

    const input = screen.getByLabelText('唯一公司');
    await waitFor(() => expect(input).toBeDisabled());
    expect(await screen.findByText(company.companyName)).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent(
      '当前账号仅授权 1 家公司，已自动选择。',
    );

    fireEvent.click(screen.getByRole('button', { name: '保存唯一公司' }));
    await waitFor(() => {
      expect(onFinish).toHaveBeenCalledWith({ companyId: company.companyId });
    });
  });

  it('keeps multiple authorized companies searchable and selectable without locking one', async () => {
    const secondCompany = {
      companyId: 'company-2',
      companyCode: 'SZSH',
      companyName: '上海昇州半导体科技有限公司',
    };
    const onChange = vi.fn();
    api.listReferenceCompanies.mockResolvedValue([company, secondCompany]);

    render(<CompanySelect aria-label="多公司" onChange={onChange} />);

    const input = screen.getByLabelText('多公司');
    await waitFor(() => expect(api.listReferenceCompanies).toHaveBeenCalled());
    expect(input).not.toBeDisabled();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();

    fireEvent.mouseDown(input);
    expect(await screen.findByText(company.companyName)).toBeInTheDocument();
    fireEvent.change(input, { target: { value: 'SZSH' } });
    const option = await screen.findByText(secondCompany.companyName);
    fireEvent.click(option);
    expect(onChange).toHaveBeenCalledWith(
      secondCompany.companyId,
      expect.objectContaining({ value: secondCompany.companyId }),
    );
  });

  it('does not expose a location-creation dialog without an existing location', () => {
    const { container } = render(
      <LocationDialog
        open
        processing={false}
        onSubmit={vi.fn()}
        onCancel={vi.fn()}
      />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('shows loading feedback and passes disabled through to antd Select', async () => {
    render(
      <>
        <ReferenceSelect
          aria-label="加载中的选择器"
          state={{ status: 'loading', options: [] }}
        />
        <CompanySelect aria-label="禁用公司" disabled />
      </>,
    );

    fireEvent.mouseDown(screen.getByLabelText('加载中的选择器'));
    expect(await screen.findByRole('status')).toHaveTextContent('正在加载…');
    expect(screen.getByLabelText('禁用公司')).toBeDisabled();
  });
});

const company: Awaited<ReturnType<
  typeof referenceApi.listReferenceCompanies
>>[number] = {
  companyId: 'company-1',
  companyCode: 'SZSC',
  companyName: '江苏神州半导体科技股份有限公司',
};

const employee: Awaited<ReturnType<
  typeof referenceApi.searchReferenceEmployees
>>[number] = {
  employeeId: 'employee-1',
  employeeVersionId: 'employee-version-1',
  employeeNumber: 'SZ0008',
  displayName: '张三',
  employmentStatus: 'ACTIVE',
  organizationId: 'organization-1',
  organizationName: '制造一部',
  organizationCode: 'MFG-01',
  seeyonOaCode: null,
  bindingStatus: null,
  assignmentEffectiveFrom: '2026-01-01',
  assignmentEffectiveTo: null,
  sourceAuthority: 'LOCAL',
  rowVersion: 1,
};

const organization: Awaited<ReturnType<
  typeof referenceApi.listReferenceOrganizations
>>[number] = {
  organizationId: 'company-organization',
  code: 'SZSC',
  name: '神州半导体',
  organizationType: 'COMPANY',
  status: 'ACTIVE',
  sourceOrganizationId: null,
  effectiveFrom: '2026-01-01',
  effectiveTo: null,
  children: [{
    organizationId: 'organization-1',
    code: 'MFG-01',
    name: '制造一部',
    organizationType: 'DEPARTMENT',
    status: 'ACTIVE',
    sourceOrganizationId: null,
    effectiveFrom: '2026-01-01',
    effectiveTo: null,
    children: [],
  }],
};

const location: Awaited<ReturnType<
  typeof referenceApi.listReferenceLocations
>>[number] = {
  locationId: 'location-1',
  sharedLocationId: 'shared-location-1',
  companyLocationId: 'location-1',
  companyId: company.companyId,
  code: 'LOC-01',
  locationRevisionId: 'location-revision-1',
  revisionNumber: 1,
  name: '苏州一号厂',
  timeZone: 'Asia/Shanghai',
  status: 'ACTIVE',
  effectiveFrom: '2026-01-01',
  effectiveTo: null,
  snapshotDigest: 'digest',
  rowVersion: 1,
  sharedManagementAllowed: true,
  changeReason: '初始化',
  updatedAt: '2026-01-01T00:00:00Z',
};

const group: Awaited<ReturnType<
  typeof referenceApi.listReferenceAttendanceGroups
>>[number] = {
  groupId: 'group-1',
  companyId: company.companyId,
  code: 'GROUP-01',
  groupRevisionId: 'group-revision-2',
  revisionNumber: 2,
  name: '标准考勤组',
  locationId: location.locationId,
  locationRevisionId: location.locationRevisionId,
  calendarId: 'calendar-1',
  shiftTemplateId: 'shift-1',
  status: 'ACTIVE',
  effectiveFrom: '2026-01-01',
  effectiveTo: null,
  snapshotDigest: 'digest',
  rowVersion: 1,
  changeReason: '初始化',
  updatedAt: '2026-01-01T00:00:00Z',
};

const shift: Awaited<ReturnType<
  typeof referenceApi.listReferenceShiftTemplates
>>[number] = {
  shiftId: 'shift-1',
  companyId: company.companyId,
  locationId: location.locationId,
  code: 'SHIFT-01',
  name: '标准白班',
  status: 'ACTIVE',
  rowVersion: 1,
  changeReason: '初始化',
  updatedAt: '2026-01-01T00:00:00Z',
};

const shiftVersion: Awaited<ReturnType<
  typeof referenceApi.listReferenceShiftVersions
>>[number] = {
  shiftVersionId: 'shift-version-3',
  shiftId: shift.shiftId,
  versionNumber: 3,
  status: 'PUBLISHED',
  effectiveFrom: '2026-01-01',
  effectiveTo: null,
  timeZone: 'Asia/Shanghai',
  segments: [],
  snapshotDigest: 'digest',
  rowVersion: 1,
  changeReason: '初始化',
  publishedAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
};

const calendar: Awaited<ReturnType<
  typeof referenceApi.listReferenceCalendars
>>[number] = {
  calendarId: 'calendar-1',
  companyId: company.companyId,
  locationId: location.locationId,
  code: 'CAL-2026',
  calendarVersionId: 'calendar-version-1',
  versionNumber: 1,
  name: '2026 工作日历',
  calendarYear: 2026,
  timeZone: 'Asia/Shanghai',
  status: 'PUBLISHED',
  effectiveFrom: '2026-01-01',
  effectiveTo: '2026-12-31',
  snapshotDigest: 'digest',
  rowVersion: 1,
  changeReason: '初始化',
  updatedAt: '2026-01-01T00:00:00Z',
};

const source: Awaited<ReturnType<
  typeof referenceApi.listReferenceAttendanceSources
>>[number] = {
  sourceId: 'source-1',
  companyId: company.companyId,
  sourceType: 'DELI_CLOUD',
  code: 'DELI-01',
  displayName: '得力考勤',
  state: 'ACTIVE',
  timeZone: 'Asia/Shanghai',
  configurationRevision: 1,
  committedWatermark: null,
  lastSuccessfulSyncAt: null,
  rowVersion: 1,
};
