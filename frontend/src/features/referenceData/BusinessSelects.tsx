import { useEffect, useId, useState } from 'react';

import {
  listReferenceAttendanceGroups,
  listReferenceAttendanceSources,
  listReferenceCalendars,
  listReferenceCompanies,
  listReferenceLocations,
  listReferenceOrganizations,
  listReferenceShiftTemplates,
  listReferenceShiftVersions,
  searchReferenceEmployees,
} from './referenceDataApi';
import {
  effectivePeriodLabel,
  employeeReferenceLabel,
  flattenOrganizationOptions,
  lifecycleStatusLabel,
  namedCodeLabel,
  type ReferenceOption,
} from './referenceDataLabels';
import {
  type BusinessSelectProps,
  ReferenceSelect,
} from './ReferenceSelect';
import { pickPreferredCompany } from '../../shared/preferredCompany';
import {
  useDebouncedValue,
  useReferenceOptions,
} from './useReferenceOptions';

export function CompanySelect(props: BusinessSelectProps) {
  const statusMessageId = useId();
  const state = useReferenceOptions(
    async () => (await listReferenceCompanies()).map((company) => ({
      value: company.companyId,
      label: company.companyName,
      searchText: `${company.companyName} ${company.companyCode ?? ''}`,
    })),
    [],
  );
  const onlyCompany = state.status === 'ready' && state.options.length === 1
    ? state.options[0]
    : undefined;
  const preferredCompany = state.status === 'ready'
    ? pickPreferredCompany(state.options, (company) => company.label)
    : undefined;
  const noCompany = state.status === 'ready' && state.options.length === 0;
  const statusMessage = noCompany
    ? '当前账号没有可用公司，请联系系统管理员检查公司权限。'
    : onlyCompany
      ? '当前账号仅授权 1 家公司，已自动选择。'
      : undefined;
  const describedBy = [
    props['aria-describedby'],
    statusMessage ? statusMessageId : undefined,
  ].filter(Boolean).join(' ') || undefined;
  useEffect(() => {
    if (!props.value && preferredCompany) {
      props.onChange?.(preferredCompany.value);
    }
  }, [preferredCompany, props.onChange, props.value]);
  return (
    <div className="company-select-field">
      <ReferenceSelect
        {...props}
        aria-describedby={describedBy}
        state={state}
        disabled={props.disabled || Boolean(onlyCompany) || noCompany}
        allowClear={onlyCompany ? false : props.allowClear}
        placeholder={noCompany ? '无可用公司' : props.placeholder ?? '请选择公司'}
      />
      {statusMessage ? (
        <span id={statusMessageId} className="form-help" role="status">
          {statusMessage}
        </span>
      ) : null}
    </div>
  );
}

export function EmployeeSelect(props: BusinessSelectProps) {
  const [query, setQuery] = useState('');
  const debouncedQuery = useDebouncedValue(query);
  const state = useReferenceOptions(
    async () => (await searchReferenceEmployees(debouncedQuery)).map((employee) => {
      const label = employeeReferenceLabel(employee);
      return {
        value: employee.employeeId,
        label,
        searchText: `${employee.displayName} ${employee.employeeNumber} ${
          employee.organizationName ?? ''
        } ${employee.organizationCode ?? ''}`,
      };
    }),
    [debouncedQuery],
  );
  return (
    <ReferenceSelect
      {...props}
      state={state}
      remoteSearch
      onSearch={setQuery}
      placeholder={props.placeholder ?? '搜索姓名、工号或部门'}
    />
  );
}

export function OrganizationSelect(props: BusinessSelectProps) {
  const state = useReferenceOptions(
    async () => flattenOrganizationOptions(await listReferenceOrganizations()),
    [],
  );
  return (
    <ReferenceSelect
      {...props}
      state={state}
      placeholder={props.placeholder ?? '请选择部门或组织'}
    />
  );
}

export interface ScopedReferenceSelectProps extends BusinessSelectProps {
  companyId?: string;
}

export function LocationSelect({
  companyId,
  ...props
}: ScopedReferenceSelectProps) {
  const state = useReferenceOptions(
    async () => (await listReferenceLocations(companyId))
      .map((location) => namedOption(
        location.companyLocationId,
        location.name,
        location.code,
        lifecycleStatusLabel(location.status),
      )),
    [companyId],
  );
  return <ReferenceSelect {...props} state={state} placeholder={props.placeholder ?? '请选择地点'} />;
}

export interface AttendanceGroupSelectProps extends ScopedReferenceSelectProps {
  asOf?: string;
  locationId?: string;
}

export function AttendanceGroupSelect({
  companyId,
  asOf,
  locationId,
  ...props
}: AttendanceGroupSelectProps) {
  const state = useReferenceOptions(
    async () => (await listReferenceAttendanceGroups(asOf, companyId))
      .filter((group) => (
        !locationId || group.locationId === locationId
      ))
      .map((group) => {
        const name = namedCodeLabel(group.name, group.code);
        const label = `${name} · 修订 ${group.revisionNumber}`;
        return {
          value: group.groupId,
          label,
          searchText: `${group.name} ${group.code} 修订 ${group.revisionNumber}`,
        };
      }),
    [asOf, companyId, locationId],
  );
  return (
    <ReferenceSelect
      {...props}
      state={state}
      placeholder={props.placeholder ?? '请选择考勤组'}
    />
  );
}

export interface ShiftTemplateSelectProps extends ScopedReferenceSelectProps {
  locationId?: string;
}

export function ShiftTemplateSelect({
  companyId,
  locationId,
  ...props
}: ShiftTemplateSelectProps) {
  const state = useReferenceOptions(
    async () => (await listReferenceShiftTemplates(companyId))
      .filter((shift) => (
        !locationId || shift.locationId === locationId
      ))
      .map((shift) => namedOption(
        shift.shiftId,
        shift.name,
        shift.code,
        lifecycleStatusLabel(shift.status),
      )),
    [companyId, locationId],
  );
  return (
    <ReferenceSelect
      {...props}
      state={state}
      placeholder={props.placeholder ?? '请选择班次'}
    />
  );
}

export interface ShiftVersionSelectProps extends BusinessSelectProps {
  shiftId?: string;
  shiftLabel?: string;
}

export function ShiftVersionSelect({
  shiftId,
  shiftLabel,
  ...props
}: ShiftVersionSelectProps) {
  const state = useReferenceOptions(
    async () => {
      if (shiftId) {
        const [versions, shifts] = await Promise.all([
          listReferenceShiftVersions(shiftId),
          shiftLabel ? Promise.resolve([]) : listReferenceShiftTemplates(),
        ]);
        const resolvedShiftLabel = shiftLabel?.trim()
          || shifts.find((shift) => shift.shiftId === shiftId)?.name;
        return versions.map((version) => shiftVersionOption(
          version,
          resolvedShiftLabel,
        ));
      }

      const activeShifts = (await listReferenceShiftTemplates())
        .filter((shift) => shift.status === 'ACTIVE');
      const versionLists = await Promise.all(activeShifts.map(async (shift) => ({
        shift,
        versions: await listReferenceShiftVersions(shift.shiftId),
      })));
      return versionLists.flatMap(({ shift, versions }) => versions.map(
        (version) => shiftVersionOption(version, shift.name),
      ));
    },
    [shiftId, shiftLabel],
  );
  return (
    <ReferenceSelect
      {...props}
      state={state}
      emptyText="暂无可选班次版本"
      placeholder={props.placeholder ?? '请选择班次版本'}
    />
  );
}

export interface CalendarSelectProps extends ScopedReferenceSelectProps {
  locationId?: string;
  year?: number;
}

export function CalendarSelect({
  companyId,
  locationId,
  year,
  ...props
}: CalendarSelectProps) {
  const state = useReferenceOptions(
    async () => (await listReferenceCalendars(year, companyId))
      .filter((calendar) => (
        !locationId || calendar.locationId === locationId
      ))
      .map((calendar) => {
        const name = namedCodeLabel(calendar.name, calendar.code);
        const label = `${name} · ${calendar.calendarYear} · V${calendar.versionNumber}`;
        return {
          value: calendar.calendarId,
          label,
          searchText: `${calendar.name} ${calendar.code} ${calendar.calendarYear} V${calendar.versionNumber}`,
        };
      }),
    [companyId, locationId, year],
  );
  return (
    <ReferenceSelect
      {...props}
      state={state}
      placeholder={props.placeholder ?? '请选择工作日历'}
    />
  );
}

export interface AttendanceSourceSelectProps extends ScopedReferenceSelectProps {
  sourceTypes?: readonly string[];
}

export function AttendanceSourceSelect({
  companyId,
  sourceTypes,
  ...props
}: AttendanceSourceSelectProps) {
  const sourceTypeKey = sourceTypes?.join('\u0000') ?? '';
  const state = useReferenceOptions(
    async () => (await listReferenceAttendanceSources())
      .filter((source) => (
        (!companyId || source.companyId === companyId)
        && (!sourceTypes?.length || sourceTypes.includes(source.sourceType))
      ))
      .map((source) => namedOption(
        source.sourceId,
        source.displayName,
        source.code,
        sourceTypeLabel(source.sourceType),
      )),
    [companyId, sourceTypeKey],
  );
  return (
    <ReferenceSelect
      {...props}
      state={state}
      placeholder={props.placeholder ?? '请选择考勤来源'}
    />
  );
}

function namedOption(
  value: string,
  name: string,
  code: string,
  suffix?: string,
): ReferenceOption {
  const nameAndCode = namedCodeLabel(name, code);
  return {
    value,
    label: suffix ? `${nameAndCode} · ${suffix}` : nameAndCode,
    searchText: `${name} ${code} ${suffix ?? ''}`,
  };
}

function sourceTypeLabel(sourceType: string): string {
  return ({
    DELI_CLOUD: '得力云考勤',
    OA_ATTENDANCE: 'OA 考勤单据',
    DEVICE_EXCEL: '设备电子表格',
    STANDARD_XLSX: '标准电子表格',
  } as Readonly<Record<string, string>>)[sourceType] ?? '其他考勤来源';
}

function shiftVersionOption(
  version: Awaited<ReturnType<typeof listReferenceShiftVersions>>[number],
  shiftName?: string,
): ReferenceOption {
  const versionLabel = [
    shiftName?.trim(),
    `V${version.versionNumber}`,
    effectivePeriodLabel(version.effectiveFrom, version.effectiveTo),
  ].filter(Boolean).join(' · ');
  return {
    value: version.shiftVersionId,
    label: versionLabel,
    searchText: `${versionLabel} ${lifecycleStatusLabel(version.status)}`,
  };
}
