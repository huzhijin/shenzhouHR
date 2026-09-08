import { Button, Checkbox, DatePicker, Drawer, Dropdown, Input, InputNumber, Modal, Pagination, Select, TimePicker, TreeSelect, message } from 'antd';
import type { DataNode } from 'antd/es/tree';
import zhCN from 'antd/locale/zh_CN';
import { useEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import dayjs, { type Dayjs } from 'dayjs';

import { createIdempotencyKey, requestFile, requestJson, saveDownloadedFile } from '../../shared/api/apiClient';
import {
  adjustLeaveBalance,
  getLeaveAccount,
  type AnnualLeaveAccount,
  type LeaveAccountKind,
} from '../employee/employeeApi';
import { isDemoMode } from '../../shared/config/runtimeMode';
import { pickPreferredCompany } from '../../shared/preferredCompany';
import { defaultCustomerReportDataScope } from './customerReportAccess';
import type { CustomerReportDataScope } from './customerReportAccess';
import { loadCustomerReportScopes } from './customerReportApi';
import { departmentPathNodes, visibleDepartmentPath } from './departmentPath';
import {
  attendanceLegend,
  getCustomerReportDemo,
  REPORT_BADGE_COLORS,
  type AttendanceStatusKey,
} from './customerReportDemo';
import {
  financeOvertimeDateLabel,
  financeOvertimeDates,
  financeOvertimeHoursByDate,
  financeOvertimeLockedTreatment,
  financeOvertimeWeekdayNumber,
  formatFinanceHours,
  isoWeekdayBucket,
  isFinanceOvertimeCalendarSheet,
  overtimeFeeColumns,
  isCompensatoryOvertimeFilter,
  isPaidOvertimeFilter,
  isVoluntaryOvertimeFilter,
  overtimeCellFill,
  overtimeTreatmentHover,
  readOvertimeDayHours,
} from './financeOvertimeLayout';
import { defaultQueryPeriod, monthDateRange } from './queryPeriod';
import { getCurrentOrganizationTree, type OrganizationNode } from '../organization/organizationApi';
import './customerReports.css';

const ALL_DEPARTMENTS = '全部部门';

const sheets = [
  { key: 'exceptions', label: '异常总览' },
  { key: 'leave', label: '请假统计' },
  { key: 'leave-summary', label: '请假汇总' },
  { key: 'overtime', label: '加班统计' },
  { key: 'overtime-daily', label: '加班日报' },
  { key: 'finance-overtime', label: '每日加班查询' },
  { key: 'overtime-fee-daily', label: '每日加班费查询' },
  { key: 'overtime-voluntary-daily', label: '每日义务加班查询' },
  { key: 'overtime-comp-daily', label: '每日调休查询' },
  { key: 'absence-stat', label: '旷工统计表' },
  { key: 'leave-stat', label: '请假统计表' },
  { key: 'daily-journal', label: '考勤日报' },
  { key: 'makeup', label: '补签' },
  { key: 'work-hours', label: '月度工时统计表' },
  { key: 'late', label: '迟到统计' },
  { key: 'missed-punch', label: '忘打卡' },
  { key: 'missed-punch-stat', label: '忘打卡统计表' },
  { key: 'attendance-rate', label: '出勤率' },
  { key: 'annual-leave', label: '年休假' },
  { key: 'annual-leave-stat', label: '年假统计表' },
  { key: 'time-off', label: '调休额度' },
  { key: 'time-off-stat', label: '调休统计表' },
  { key: 'time-off-daily', label: '调休日报' },
  { key: 'matrix', label: '考勤明细' },
] as const;

type SheetKey = (typeof sheets)[number]['key'];

const yearSheets = new Set<SheetKey>(['annual-leave-stat', 'time-off-stat']);
const wideMetricSheets = new Set<SheetKey>(['absence-stat', 'leave-stat']);
export const DAY_UPDATED_TOAST = '已更新该日考勤';
export const EXCEPTION_TYPE_FILTER_OPTIONS = [
  { value: 'LATE', label: '迟到' },
  { value: 'EARLY_DEPARTURE', label: '早退' },
  { value: 'MISSING_ON_DUTY', label: '上班缺卡' },
  { value: 'MISSING_OFF_DUTY', label: '下班缺卡' },
  { value: 'MISSING_PUNCH', label: '缺卡待补签' },
  { value: 'ABSENCE', label: '旷工' },
  { value: 'FAKE_OVERTIME', label: '加班异常' },
  { value: 'NO_SHIFT_OR_CALENDAR', label: '无班次或日历' },
  { value: 'LEAVE_PUNCH_CONFLICT', label: '请假与打卡冲突' },
] as const;

export const MATRIX_CROSS_MONTH_TITLE = '暂不支持跨月';
export const MATRIX_CROSS_MONTH_CONTENT = '考勤明细目前只支持同一个月内的日期筛选，跨月展示列为后续功能。';
const PAGE_SIZE_OPTIONS = [50, 100, 200];

interface QueryPageResponse {
  sheet: string;
  companyId: string;
  fromDate: string;
  toDate: string;
  projectionVersion?: string;
  dataAsOf?: string;
  hint?: string;
  rowCount: number;
  page: number;
  size: number;
  rows: Array<Record<string, unknown>>;
  omittedMonths?: Array<{ period: string; reason: string }>;
  allowedActions?: string[];
  queryFingerprint?: string;
  scopeReference?: string;
  exportFieldAllowlist?: string[];
}

interface DirectoryResponse {
  companies?: Array<{ companyId: string; companyName: string }>;
  employees: Array<{
    employeeId: string;
    employeeNumber: string;
    employeeName: string;
    organizationId: string;
    organizationName: string;
  }>;
}

export function QueryReportsPage({
  capabilities,
}: {
  capabilities?: readonly string[];
}) {
  const params = useParams<{ sheet: string }>();
  const [searchParams] = useSearchParams();
  const sheet = (sheets.some((item) => item.key === params.sheet)
    ? params.sheet
    : 'exceptions') as SheetKey;
  const title = sheets.find((item) => item.key === sheet)?.label ?? '查询报表';
  const [scopes, setScopes] = useState<readonly CustomerReportDataScope[]>([]);
  const [companyId, setCompanyId] = useState<string>();
  const [organizationId, setOrganizationId] = useState<string>();
  const [organizationTree, setOrganizationTree] = useState<OrganizationNode[]>([]);
  const [employeeNumber, setEmployeeNumber] = useState('');
  const [period, setPeriod] = useState(() => defaultQueryPeriod());
  const [range, setRange] = useState<[Dayjs, Dayjs] | null>(() => monthDateRange(defaultQueryPeriod()));
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(50);
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<QueryPageResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [directory, setDirectory] = useState<DirectoryResponse['employees']>([]);
  const [filters, setFilters] = useState<Record<string, string>>({});
  const [detailIndex, setDetailIndex] = useState<number | null>(null);
  const [matrixView, setMatrixView] = useState<'calendar' | 'list'>('calendar');
  const [exporting, setExporting] = useState(false);
  const [exportFeedback, setExportFeedback] = useState<string | null>(null);
  const sheetRef = useRef(sheet);
  sheetRef.current = sheet;
  const canExport = (capabilities ?? []).includes('ATTENDANCE_REPORT:EXPORT_CREATE');
  const canDownload = (capabilities ?? []).includes('ATTENDANCE_REPORT:EXPORT_DOWNLOAD');
  const canAdjust = (capabilities ?? []).includes('ATTENDANCE_ADJUST:MANAGE');
  const canOaRecalc = (capabilities ?? []).includes('ANNUAL_LEAVE:ADJUST');
  const [oaRecalculating, setOaRecalculating] = useState(false);
  const [adjustRow, setAdjustRow] = useState<Record<string, unknown> | null>(null);
  const [onDutyTime, setOnDutyTime] = useState<Dayjs | null>(null);
  const [offDutyTime, setOffDutyTime] = useState<Dayjs | null>(null);
  const [adjustReason, setAdjustReason] = useState('人事核对后补卡');
  const [adjusting, setAdjusting] = useState(false);
  const [overtimeHours, setOvertimeHours] = useState<number | null>(null);
  const [clearedExceptionTypes, setClearedExceptionTypes] = useState<string[]>([]);
  const [dayTypes, setDayTypes] = useState<string[]>([]);
  const [leaveAccount, setLeaveAccount] = useState<AnnualLeaveAccount | null>(null);
  const [leaveAccountError, setLeaveAccountError] = useState<string | null>(null);
  const [leaveAdjustHours, setLeaveAdjustHours] = useState<number | null>(null);
  const [leaveAdjustReason, setLeaveAdjustReason] = useState('');
  const [leaveAdjusting, setLeaveAdjusting] = useState(false);
  const canAdjustLeave = (capabilities ?? []).includes('ANNUAL_LEAVE:ADJUST');

  const currentMonth = period.format('YYYY-MM');

  useEffect(() => {
    const company = searchParams.get('companyId');
    const employee = searchParams.get('employeeNumber');
    const fromDate = searchParams.get('fromDate');
    const toDate = searchParams.get('toDate');
    const periodValue = searchParams.get('period');
    if (company) {
      setCompanyId(company);
    }
    if (employee) {
      setEmployeeNumber(employee);
    }
    if (periodValue && dayjs(periodValue, 'YYYY-MM', true).isValid()) {
      setPeriod(dayjs(periodValue, 'YYYY-MM'));
    }
    if (fromDate && toDate && dayjs(fromDate).isValid() && dayjs(toDate).isValid()) {
      setRange([dayjs(fromDate), dayjs(toDate)]);
    }
  }, [searchParams]);

  useEffect(() => {
    if (isDemoMode()) {
      const demo = getCustomerReportDemo(
        { month: currentMonth, department: '全部部门', employee: '全部员工' },
        defaultCustomerReportDataScope,
      );
      const nextScopes: CustomerReportDataScope[] = [{
        reference: 'demo-company',
        type: 'COMPANY',
        label: demo.metadata.company,
        actorLabel: '授权范围',
        allowedDepartments: [],
        allowedEmployees: [],
      }];
      setScopes(nextScopes);
      setCompanyId((current) => current ?? 'demo-company');
      const employees = demo.attendanceRows.map((row, index) => ({
        employeeId: row.employeeId ?? `demo-employee-${index}`,
        employeeNumber: row.employeeNo,
        employeeName: row.employee,
        organizationId: row.organizationId ?? `demo-org-${row.department}`,
        organizationName: row.department,
      }));
      setDirectory(employees);
      return;
    }
    const applyScopes = (nextScopes: readonly CustomerReportDataScope[]) => {
      setScopes(nextScopes);
      setCompanyId((current) => current
        ?? pickPreferredCompany(nextScopes, (item) => item.label)?.reference
        ?? nextScopes[0]?.reference);
    };
    void loadCustomerReportScopes(currentMonth).then(applyScopes).catch(() => {
      void requestJson<DirectoryResponse>(
        `/api/v1/attendance-report-queries/directory?period=${currentMonth}`,
      ).then((body) => {
        applyScopes((body.companies ?? []).map((company) => ({
          reference: company.companyId,
          type: 'COMPANY' as const,
          label: company.companyName,
          actorLabel: '授权范围',
          allowedDepartments: [],
          allowedEmployees: [],
        })));
      }).catch(() => {
        /* keep last successful company list */
      });
    });
  }, [currentMonth]);

  useEffect(() => {
    if (isDemoMode() || !companyId) return;
    void requestJson<DirectoryResponse>(
      `/api/v1/attendance-report-queries/directory?period=${currentMonth}&companyId=${encodeURIComponent(companyId)}`,
    ).then((body) => {
      setDirectory(body.employees ?? []);
    }).catch(() => {
      setDirectory([]);
    });
  }, [companyId, currentMonth]);

  useEffect(() => {
    if (isDemoMode()) {
      setOrganizationTree([]);
      return;
    }
    let cancelled = false;
    void getCurrentOrganizationTree(false)
      .then((nodes) => {
        if (!cancelled) setOrganizationTree(nodes);
      })
      .catch(() => {
        if (!cancelled) setOrganizationTree([]);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const query = async (nextPage = 0, nextSize = pageSize, options?: { keepDetail?: boolean }) => {
    if (!companyId) return;
    const requestedSheet = sheet;
    setLoading(true);
    setError(null);
    setResult((current) => (current?.sheet === requestedSheet ? current : null));
    if (!options?.keepDetail) {
      setDetailIndex(null);
    }
    const parameters = new URLSearchParams();
    parameters.set('companyId', companyId);
    parameters.set('page', String(nextPage));
    parameters.set('size', String(nextSize));
    if (organizationId) parameters.set('organizationId', organizationId);
    if (employeeNumber.trim()) parameters.set('employeeNumber', employeeNumber.trim());
    const queryRange = sheetQueryRange(sheet, range, period);
    parameters.set('period', currentMonth);
    parameters.set('fromDate', queryRange[0].format('YYYY-MM-DD'));
    parameters.set('toDate', queryRange[1].format('YYYY-MM-DD'));
    const allowedFilters = sheetFilterKeys(sheet);
    Object.entries(filters).forEach(([key, value]) => {
      if (value && allowedFilters.has(key)) parameters.set(key, value);
    });
    const lockedTreatment = financeOvertimeLockedTreatment(sheet);
    if (lockedTreatment) {
      parameters.set('overtimeTreatment', lockedTreatment);
    }
    try {
      const body = isDemoMode()
        ? demoQueryPage(sheet, currentMonth, nextPage, nextSize, {
            organizationId,
            employeeNumber,
            filters,
            range,
          })
        : await requestJson<QueryPageResponse>(
            `/api/v1/attendance-report-queries/${sheet}?${parameters.toString()}`,
          );
      if (requestedSheet !== sheetRef.current) {
        return;
      }
      setResult(body);
      setPage(nextPage);
    } catch (cause: unknown) {
      if (requestedSheet !== sheetRef.current) {
        return;
      }
      setError(cause instanceof Error ? cause.message : '查询失败');
      setResult(null);
    } finally {
      if (requestedSheet === sheetRef.current) {
        setLoading(false);
      }
    }
  };

  const exportCurrentSheet = async (exportScope: 'ALL' | 'PAGE' = 'ALL') => {
    if (!companyId || exporting) return;
    if (!canDownload) {
      setExportFeedback('当前账号没有导出下载权限。');
      return;
    }
    if (!result) {
      setExportFeedback('请先查询出核算结果再导出。');
      return;
    }
    setExporting(true);
    setExportFeedback(null);
    const exportPage = exportScope === 'PAGE' ? page : 0;
    const exportSize = exportScope === 'PAGE' ? pageSize : 50_000;
    try {
      if (isDemoMode()) {
        const { downloadQuerySheetWorkbook } = await import('./queryReportWorkbook');
        const exportResult = demoQueryPage(sheet, currentMonth, exportPage, exportSize, {
          organizationId,
          employeeNumber,
          filters,
          range,
        });
        if (isFinanceOvertimeCalendarSheet(sheet)) {
          const dates = financeOvertimeDates(exportResult.fromDate, exportResult.toDate);
          const treatment = financeOvertimeLockedTreatment(sheet) ?? filters.overtimeTreatment;
          const feeColumns = overtimeFeeColumns(treatment);
          const identityHeaders = [
            '部门', '工号', '加班人', '平时加班', '周末加班', '节假日加班',
            ...(feeColumns.paid ? ['加班费'] : []),
            ...(feeColumns.compensatory ? ['转调休'] : []),
            ...(feeColumns.voluntary ? ['义务加班'] : []),
          ];
          const headers = [
            ...identityHeaders,
            ...dates.map(financeOvertimeDateLabel),
          ];
          const body = (exportResult.rows ?? []).map((row, index) => {
            const byDate = financeOvertimeHoursByDate(row.days);
            const excelRow = index + 2;
            const weekdayCols: string[] = [];
            const weekendCols: string[] = [];
            dates.forEach((date, dateIndex) => {
              const col = excelColumnLetter(identityHeaders.length + dateIndex);
              if (isoWeekdayBucket(date) === 'weekend') {
                weekendCols.push(`${col}${excelRow}`);
              } else {
                weekdayCols.push(`${col}${excelRow}`);
              }
            });
            return [
              visibleDepartmentPath(String(row.department ?? '')) || '',
              String(row.employeeNumber ?? ''),
              String(row.employeeName ?? ''),
              weekdayCols.length ? `=${weekdayCols.length === 1 ? weekdayCols[0] : `SUM(${weekdayCols.join(',')})`}` : '0',
              weekendCols.length ? `=${weekendCols.length === 1 ? weekendCols[0] : `SUM(${weekendCols.join(',')})`}` : '0',
              '0',
              ...(feeColumns.paid ? [formatFinanceHours(Number(row.paidOvertimeHours ?? 0))] : []),
              ...(feeColumns.compensatory ? [formatFinanceHours(Number(row.compensatoryOvertimeHours ?? 0))] : []),
              ...(feeColumns.voluntary ? [formatFinanceHours(Number(row.voluntaryOvertimeHours ?? 0))] : []),
              ...dates.map((date) => formatFinanceHours(byDate.get(date) ?? 0, true)),
            ];
          });
          await downloadQuerySheetWorkbook({
            title,
            month: exportResult.fromDate.slice(0, 7),
            headers,
            rows: body,
          });
        } else if (wideMetricSheets.has(sheet)) {
          const dates = financeOvertimeDates(exportResult.fromDate, exportResult.toDate);
          const totalKey = sheet === 'absence-stat' ? 'absenceHours' : 'leaveHours';
          const headers = [
            '部门', '工号', '姓名', sheet === 'absence-stat' ? '合计旷工' : '合计请假',
            ...dates.map(financeOvertimeDateLabel),
          ];
          const body = (exportResult.rows ?? []).map((row) => {
            const byDate = metricHoursByDate(row.days);
            return [
              visibleDepartmentPath(String(row.department ?? '')) || '',
              String(row.employeeNumber ?? ''),
              String(row.employeeName ?? ''),
              formatFinanceHours(Number(row[totalKey] ?? 0)),
              ...dates.map((date) => formatFinanceHours(byDate.get(date) ?? 0, true)),
            ];
          });
          await downloadQuerySheetWorkbook({
            title,
            month: exportResult.fromDate.slice(0, 7),
            headers,
            rows: body,
          });
        } else if (sheet === 'work-hours') {
          const exportColumns = visibleExportColumns(sheet, filters.overtimeTreatment);
          await downloadQuerySheetWorkbook({
            title,
            month: exportResult.fromDate.slice(0, 7),
            headers: exportColumns.map((column) => column.title),
            rows: (exportResult.rows ?? []).map((row, index) => {
              const excelRow = index + 2;
              return exportColumns.map((column) => (
                column.key === 'actualHours'
                  ? `=D${excelRow}+E${excelRow}+F${excelRow}-G${excelRow}-H${excelRow}+I${excelRow}-J${excelRow}`
                  : formatCell(sheet, column.key, row[column.key])
              ));
            }),
          });
        } else {
          const exportColumns = visibleExportColumns(sheet, filters.overtimeTreatment);
          await downloadQuerySheetWorkbook({
            title,
            month: exportResult.fromDate.slice(0, 7),
            headers: exportColumns.map((column) => column.title),
            rows: (exportResult.rows ?? []).map((row) => exportColumns.map((column) => (
              column.key === 'exceptionDayCount'
                ? String(countExceptionDays(row))
                : formatCell(sheet, column.key, row[column.key])
            ))),
          });
        }
      } else {
        const parameters = new URLSearchParams();
        parameters.set('companyId', companyId);
        if (organizationId) parameters.set('organizationId', organizationId);
        if (employeeNumber.trim()) parameters.set('employeeNumber', employeeNumber.trim());
        const exportRange = sheetQueryRange(sheet, range, period);
        parameters.set('period', currentMonth);
        parameters.set('fromDate', exportRange[0].format('YYYY-MM-DD'));
        parameters.set('toDate', exportRange[1].format('YYYY-MM-DD'));
        parameters.set('exportScope', exportScope);
        if (exportScope === 'PAGE') {
          parameters.set('page', String(exportPage));
          parameters.set('size', String(exportSize));
        }
        const allowedFilters = sheetFilterKeys(sheet);
        Object.entries(filters).forEach(([key, value]) => {
          if (value && allowedFilters.has(key)) parameters.set(key, value);
        });
        const lockedTreatment = financeOvertimeLockedTreatment(sheet);
        if (lockedTreatment) {
          parameters.set('overtimeTreatment', lockedTreatment);
        }
        saveDownloadedFile(await requestFile(
          `/api/v1/attendance-report-queries/${sheet}/export?${parameters.toString()}`,
        ));
      }
      setExportFeedback(null);
    } catch (cause: unknown) {
      setExportFeedback(cause instanceof Error ? cause.message : '导出失败，请刷新后重试。');
    } finally {
      setExporting(false);
    }
  };

  const recalculateFromOa = async () => {
    if (!companyId || oaRecalculating) return;
    setOaRecalculating(true);
    try {
      if (!isDemoMode()) {
        await requestJson('/api/v1/leave-accounts/oa-recalculate', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            companyId,
            year: Number(currentMonth.slice(0, 4)),
          }),
        });
      }
      message.success('已按 OA 重算年假和调休，报表将刷新');
      await query(page);
    } catch (cause: unknown) {
      message.error(cause instanceof Error ? cause.message : '按 OA 重算失败');
    } finally {
      setOaRecalculating(false);
    }
  };

  const openMatrixDayAdjust = (day: Record<string, unknown>, date: string) => {
    if (!detailRow) return;
    const first = formatPunch(day.firstPunchAt, date);
    const last = formatPunch(day.lastPunchAt, date);
    setAdjustRow({
      employeeId: detailRow.employeeId,
      employeeName: detailRow.employeeName,
      businessDate: date,
      onDuty: first === '—' ? '' : first,
      offDuty: last === '—' ? '' : last,
    });
    setOnDutyTime(parseClock(first === '—' ? '' : first, date));
    setOffDutyTime(parseClock(last === '—' ? '' : last, date));
    const overtimeMinutes = Number(day.paidOvertimeMinutes ?? 0)
      + Number(day.compensatoryOvertimeMinutes ?? 0);
    setOvertimeHours(overtimeMinutes > 0 ? Math.round(overtimeMinutes / 30) / 2 : null);
    setClearedExceptionTypes([]);
    setDayTypes([]);
    setAdjustReason('人事核对后补卡');
  };

  const submitPunchAdjustment = async () => {
    if (!adjustRow) return;
    if (!onDutyTime && !offDutyTime && overtimeHours == null
      && clearedExceptionTypes.length === 0 && dayTypes.length === 0) {
      message.error('请填写上班或下班时刻、加班小时、当天类型或取消的异常');
      return;
    }
    if (!adjustReason.trim()) {
      message.error('请填写原因');
      return;
    }
    setAdjusting(true);
    try {
      if (!isDemoMode()) {
        await requestJson('/api/v1/attendance/hr-adjustments/punches', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            companyId,
            employeeId: adjustRow.employeeId,
            businessDate: String(adjustRow.businessDate).slice(0, 10),
            onDutyAt: clockInstant(String(adjustRow.businessDate), onDutyTime),
            offDutyAt: clockInstant(String(adjustRow.businessDate), offDutyTime),
            reason: adjustReason.trim(),
            overtimeHours,
            clearedExceptionTypes,
            dayTypes,
          }),
        });
      }
      setAdjustRow(null);
      await query(page, pageSize, { keepDetail: true });
      message.success(DAY_UPDATED_TOAST);
    } catch (cause: unknown) {
      message.error(cause instanceof Error ? cause.message : '保存失败');
    } finally {
      setAdjusting(false);
    }
  };

  const submitLeaveAdjustment = async () => {
    if (!detailRow) return;
    const employeeId = String(detailRow.employeeId ?? '');
    if (!employeeId) {
      message.error('缺少员工标识，无法调整额度');
      return;
    }
    if (leaveAdjustHours == null || !Number.isFinite(leaveAdjustHours) || leaveAdjustHours === 0) {
      message.error('请填写非零的调整小时');
      return;
    }
    if (leaveAdjustReason.trim().length < 2) {
      message.error('请填写原因');
      return;
    }
    const kind: LeaveAccountKind = sheet === 'time-off-stat' ? 'TIME_OFF' : 'ANNUAL_LEAVE';
    setLeaveAdjusting(true);
    try {
      const next = await adjustLeaveBalance(
        employeeId,
        leaveAdjustHours,
        period.year(),
        leaveAdjustReason.trim(),
        createIdempotencyKey(`query-leave-adjust-${kind}-${employeeId}`),
        kind,
      );
      setLeaveAccount(next);
      setLeaveAdjustHours(null);
      setLeaveAdjustReason('');
      message.success('额度已调整，流水已记录。列表可休随下次核算更新。');
    } catch (cause: unknown) {
      message.error(cause instanceof Error ? cause.message : '调整失败');
    } finally {
      setLeaveAdjusting(false);
    }
  };

  useEffect(() => {
    setFilters({});
    setRange(null);
    setDetailIndex(null);
    setMatrixView('calendar');
    setExportFeedback(null);
    setLeaveAccount(null);
    setLeaveAccountError(null);
    setLeaveAdjustHours(null);
    setLeaveAdjustReason('');
  }, [sheet]);

  useEffect(() => {
    if (!yearSheets.has(sheet) || detailIndex == null) {
      setLeaveAccount(null);
      setLeaveAccountError(null);
      return;
    }
    const row = result?.rows?.[detailIndex];
    const employeeId = row ? String(row.employeeId ?? '') : '';
    if (!employeeId) {
      setLeaveAccount(null);
      setLeaveAccountError('缺少员工标识，无法读取活账户');
      return;
    }
    const kind: LeaveAccountKind = sheet === 'time-off-stat' ? 'TIME_OFF' : 'ANNUAL_LEAVE';
    let cancelled = false;
    setLeaveAccount(null);
    setLeaveAccountError(null);
    void getLeaveAccount(employeeId, period.year(), kind)
      .then((account) => {
        if (!cancelled) setLeaveAccount(account);
      })
      .catch((cause: unknown) => {
        if (!cancelled) {
          setLeaveAccountError(cause instanceof Error ? cause.message : '活账户读取失败');
        }
      });
    return () => {
      cancelled = true;
    };
  }, [sheet, detailIndex, period, result]);

  useEffect(() => {
    void query(0);
  }, [sheet, companyId]);

  const columns = useMemo(
    () => sheetColumns(
      sheet,
      financeOvertimeLockedTreatment(sheet) ?? filters.overtimeTreatment,
    ),
    [sheet, filters.overtimeTreatment],
  );
  const rows = result?.sheet === sheet ? (result.rows ?? []) : [];
  const exceptionSummary = sheet === 'exceptions' ? summarizeExceptions(rows) : null;
  const canOpenDetail = sheet !== 'annual-leave'
    && sheet !== 'time-off'
    && !isFinanceOvertimeCalendarSheet(sheet)
    && !wideMetricSheets.has(sheet);
  const detailRow = detailIndex == null ? null : rows[detailIndex] ?? null;

  const departments = Array.from(new Map(directory.map((row) => [row.organizationId, row.organizationName])).entries());

  const departmentTreeData = useMemo<DataNode[]>(() => {
    const tree = Array.isArray(organizationTree) ? organizationTree : [];
    const selectedScope = scopes.find((scope) => scope.reference === companyId);
    const company = findQueryCompanyNode(
      tree,
      companyId,
      selectedScope?.label,
    );
    const toNodes = (nodes: OrganizationNode[]): DataNode[] => nodes.map((node) => ({
      key: node.organizationId,
      value: node.organizationId,
      title: node.name,
      children: node.children.length > 0 ? toNodes(node.children) : undefined,
    }));
    return [
      {
        key: ALL_DEPARTMENTS,
        value: ALL_DEPARTMENTS,
        title: '全部部门',
        children: company ? toNodes(company.children) : toNodes(tree),
      },
    ];
  }, [companyId, organizationTree, scopes]);

  const employeeOptions = useMemo(() => {
    const selectedIds = collectQueryDescendantOrganizationIds(
      organizationTree,
      organizationId,
    );
    return directory
      .filter((row) => (
        !organizationId
        || selectedIds.size === 0
        || selectedIds.has(row.organizationId)
      ))
      .map((row) => ({
        value: row.employeeNumber,
        label: `${row.employeeNumber} ${row.employeeName}`,
      }));
  }, [directory, organizationId, organizationTree]);

  const sheetFilterTitle = sheetFilterHeading(sheet);

  return (
    <main className="customer-report query-report">
      <header className="customer-report__hero">
        <div>
          <h1>{title}</h1>
          <p>
            {result?.hint
              ?? (result?.dataAsOf
                ? `核算于 ${formatDateTime(result.dataAsOf)}`
                : '查询已钉住的核算结果，不现场重算。')}
            {yearSheets.has(sheet)
              ? ' 可休以详情为准，列表随下次核算更新。8 月账期前无流水的月份显示 /。'
              : null}
            {sheet === 'missed-punch-stat'
              ? ' 列出本月漏刷人员、次数和日期备注；点姓名或「查看日历」在抽屉中看哪一天。'
              : null}
          </p>
        </div>
      </header>
      <section className="customer-report__filter-card" aria-label="查询条件">
        <div className="customer-report__filter-heading">
          <div>
            <span>查询条件</span>
            <small>起止日期必填。月初默认上月；数据较多时会继续加载，请稍候。</small>
          </div>
          <button
            type="button"
            className="customer-report__reset"
            onClick={() => {
              setOrganizationId(undefined);
              setEmployeeNumber('');
              const nextPeriod = defaultQueryPeriod();
              if (yearSheets.has(sheet)) {
                setPeriod(nextPeriod.startOf('year'));
                setRange([nextPeriod.startOf('year'), nextPeriod.endOf('year')]);
              } else {
                setPeriod(nextPeriod);
                setRange(monthDateRange(nextPeriod));
              }
              setFilters({});
            }}
          >
            重置筛选
          </button>
        </div>
        <div className="query-report__groups">
          <div className="query-report__group">
            <h2>范围</h2>
            <div className="query-report__group-fields">
              <FilterField label="公司">
                <Select
                  value={companyId}
                  options={scopes.map((scope) => ({ value: scope.reference, label: scope.label }))}
                  onChange={(value) => setCompanyId(value)}
                  placeholder="请选择公司"
                  notFoundContent={scopes.length === 0 ? '正在加载公司…' : '暂无数据'}
                  popupMatchSelectWidth={false}
                />
              </FilterField>
              <FilterField label="部门（含子部门）">
                {!isDemoMode() && organizationTree.length > 0 ? (
                <TreeSelect
                  allowClear
                  showSearch
                  treeDefaultExpandAll={false}
                  treeNodeFilterProp="title"
                  placeholder="全部部门"
                  value={organizationId ?? ALL_DEPARTMENTS}
                  treeData={departmentTreeData}
                  onChange={(value) => {
                    const selected = value == null || value === '' || value === ALL_DEPARTMENTS
                      ? undefined
                      : String(value);
                    setOrganizationId(selected);
                    setEmployeeNumber('');
                  }}
                  notFoundContent="未找到匹配部门"
                  popupMatchSelectWidth={false}
                />
                ) : (
                <Select
                  allowClear
                  placeholder="全部部门"
                  value={organizationId}
                  options={departments.map(([value, label]) => ({
                    value,
                    label: visibleDepartmentPath(label),
                  }))}
                  onChange={(value) => {
                    setOrganizationId(value);
                    setEmployeeNumber('');
                  }}
                  showSearch
                  optionFilterProp="label"
                  popupMatchSelectWidth={false}
                />
                )}
              </FilterField>
              <FilterField label="员工">
                <Select
                  allowClear
                  showSearch
                  placeholder="全部员工"
                  value={employeeNumber || undefined}
                  options={employeeOptions}
                  onChange={(value) => setEmployeeNumber(value ?? '')}
                  optionFilterProp="label"
                  popupMatchSelectWidth={false}
                />
              </FilterField>
            </div>
          </div>
          <div className="query-report__group">
            <h2>期间</h2>
            <div className="query-report__group-fields query-report__group-fields--period">
              {yearSheets.has(sheet) ? (
                <FilterField label="自然年">
                  <DatePicker
                    locale={zhCN.DatePicker}
                    picker="year"
                    value={period}
                    allowClear={false}
                    onChange={(value) => {
                      if (!value) return;
                      setPeriod(value.startOf('year'));
                      setRange([value.startOf('year'), value.endOf('year')]);
                    }}
                    style={{ width: '100%' }}
                  />
                </FilterField>
              ) : (
                <FilterField label="起止日期">
                  <DatePicker.RangePicker
                    locale={zhCN.DatePicker}
                    format="YYYY年M月D日"
                    allowClear={false}
                    value={range ?? monthDateRange(period)}
                    presets={[
                      { label: '本月', value: monthDateRange(dayjs()) },
                      { label: '上月', value: monthDateRange(dayjs().subtract(1, 'month')) },
                      { label: '近三个月', value: [
                        dayjs().subtract(2, 'month').startOf('month'),
                        dayjs().endOf('month'),
                      ] },
                    ]}
                    onChange={(value) => {
                      const next = value as [Dayjs, Dayjs] | null;
                      if (!next?.[0] || !next[1]) return;
                      if (sheet === 'matrix' && !rangeStaysInMonth(next[0], next[1], next[0].format('YYYY-MM'))) {
                        Modal.warning({
                          title: MATRIX_CROSS_MONTH_TITLE,
                          content: MATRIX_CROSS_MONTH_CONTENT,
                        });
                        return;
                      }
                      setRange([next[0].startOf('day'), next[1].endOf('day')]);
                      setPeriod(next[0].startOf('month'));
                    }}
                    style={{ width: '100%' }}
                  />
                </FilterField>
              )}
            </div>
          </div>
          <div className="query-report__group">
            <h2>{sheetFilterTitle}</h2>
            <div className="query-report__group-fields">
              <SheetFilters sheet={sheet} filters={filters} onChange={setFilters} />
            </div>
          </div>
        </div>
        <div className="query-report__actions">
          <Button type="primary" loading={loading} disabled={loading} onClick={() => void query(0)}>
            查询
          </Button>
          <Button disabled={loading} onClick={() => void query(page)}>
            刷新
          </Button>
          {canExport ? (
            <Dropdown
              disabled={loading || exporting || !companyId}
              menu={{
                items: [
                  { key: 'ALL', label: '导出全部' },
                  { key: 'PAGE', label: '导出当前页' },
                ],
                onClick: ({ key }) => {
                  void exportCurrentSheet(key === 'PAGE' ? 'PAGE' : 'ALL');
                },
              }}
            >
              <Button disabled={loading || exporting || !companyId} loading={exporting}>
                导出
              </Button>
            </Dropdown>
          ) : null}
          {canOaRecalc && (sheet === 'annual-leave' || sheet === 'time-off') ? (
            <Button
              disabled={loading || oaRecalculating || !companyId}
              loading={oaRecalculating}
              onClick={() => {
                void recalculateFromOa();
              }}
            >
              按 OA 重算
            </Button>
          ) : null}
        </div>
      </section>
      {error ? <p className="customer-report__load-error">{error}</p> : null}
      {exportFeedback ? <p className="customer-report__load-error">{exportFeedback}</p> : null}
      {result?.omittedMonths?.length ? (
        <p className="query-report__hint">
          未计入月份：
          {result.omittedMonths.map((item) => `${item.period}（${item.reason === 'NO_DATA' ? '暂无数据' : '未核算'}）`).join('、')}
        </p>
      ) : null}
      {exceptionSummary ? (
        <div className="customer-report__exception-summary" aria-label="异常汇总">
          <span className="customer-report__exception-summary-item is-danger">
            高风险<strong>{exceptionSummary.high}</strong>
          </span>
          <span className="customer-report__exception-summary-item is-warning">
            缺卡<strong>{exceptionSummary.missing}</strong>
          </span>
          <span className="customer-report__exception-summary-item is-warning">
            未闭环<strong>{exceptionSummary.open}</strong>
          </span>
          <span className="customer-report__exception-summary-item">
            本页<strong>{rows.length}</strong>
          </span>
        </div>
      ) : null}
      <section className="query-report__table" aria-label={title}>
        <div className="customer-report__table-scroll">
          {isFinanceOvertimeCalendarSheet(sheet) ? (
            <FinanceOvertimeTable
              fromDate={result?.fromDate}
              toDate={result?.toDate}
              rows={rows}
              loading={loading}
              hint={result?.hint}
              overtimeTreatment={financeOvertimeLockedTreatment(sheet) ?? filters.overtimeTreatment}
            />
          ) : wideMetricSheets.has(sheet) ? (
            <MetricStatTable
              fromDate={result?.fromDate}
              toDate={result?.toDate}
              rows={rows}
              loading={loading}
              hint={result?.hint}
              totalKey={sheet === 'absence-stat' ? 'absenceHours' : 'leaveHours'}
              totalLabel={sheet === 'absence-stat' ? '合计旷工' : '合计请假'}
              colorize={sheet === 'leave-stat'}
            />
          ) : yearSheets.has(sheet) ? (
            <LeaveStatTable
              sheet={sheet}
              rows={rows}
              loading={loading}
              hint={result?.hint}
              onOpen={(index) => setDetailIndex(index)}
            />
          ) : (
            <table
              className={`customer-report__table${sheet === 'exceptions' ? ' customer-report__table--query-exceptions' : ''}`}
            >
              <thead>
                <tr>
                  {columns.map((column) => (
                    <th scope="col" key={column.key}>{column.title}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.length === 0 ? (
                  <tr>
                    <td colSpan={columns.length}>
                      {loading ? '正在查询…' : (result?.hint ?? '当前筛选条件下没有记录')}
                    </td>
                  </tr>
                ) : rows.map((row, index) => (
                  <tr
                    key={index}
                    className={canOpenDetail ? 'query-report__row-link' : undefined}
                    onClick={canOpenDetail ? () => setDetailIndex(index) : undefined}
                  >
                    {columns.map((column) => (
                      <td
                        key={column.key}
                        className={column.key === 'department' ? 'customer-report__dept' : undefined}
                        title={column.key === 'department'
                          ? visibleDepartmentPath(String(row.department ?? ''))
                          : undefined}
                      >
                        {column.key === 'adjust'
                          ? (canAdjust ? (
                            <button
                              type="button"
                              className="query-report__detail-btn"
                              onClick={(event) => {
                                event.stopPropagation();
                                setAdjustRow(row);
                                setOnDutyTime(parseClock(row.onDuty, row.businessDate));
                                setOffDutyTime(parseClock(row.offDuty, row.businessDate));
                                setAdjustReason('人事核对后补卡');
                                setOvertimeHours(null);
                                setClearedExceptionTypes([]);
                                setDayTypes([]);
                              }}
                            >
                              改打卡
                            </button>
                          ) : '—')
                          : renderCell(sheet, column.key, row, () => setDetailIndex(index))}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
        <Pagination
          current={page + 1}
          pageSize={pageSize}
          total={result?.rowCount ?? 0}
          showSizeChanger
          pageSizeOptions={PAGE_SIZE_OPTIONS.map(String)}
          showTotal={(total) => (
            isFinanceOvertimeCalendarSheet(sheet)
              || sheet === 'missed-punch-stat'
              || yearSheets.has(sheet)
              || wideMetricSheets.has(sheet)
              ? `共 ${total} 人`
              : `共 ${total} 条`
          )}
          onChange={(next, nextSize) => {
            if (nextSize !== pageSize) {
              setPageSize(nextSize);
              void query(0, nextSize);
              return;
            }
            void query(next - 1, pageSize);
          }}
          style={{ marginTop: 12, textAlign: 'right' }}
        />
      </section>
      <Drawer
        title={detailDrawerTitle(sheet, title, detailRow)}
        open={detailRow != null}
        onClose={() => setDetailIndex(null)}
        width={sheet === 'matrix' || sheet === 'missed-punch-stat' ? 920 : yearSheets.has(sheet) ? 560 : 440}
        destroyOnClose
        extra={detailRow && canOpenDetail ? (
          <div className="query-report__drawer-nav">
            {sheet === 'matrix' || sheet === 'missed-punch-stat' ? (
              <>
                <Button size="small" onClick={() => setMatrixView('calendar')}>日历</Button>
                <Button size="small" onClick={() => setMatrixView('list')}>列表</Button>
              </>
            ) : null}
            <Button
              size="small"
              disabled={detailIndex == null || detailIndex <= 0}
              onClick={() => setDetailIndex((index) => Math.max(0, (index ?? 0) - 1))}
            >
              上一条
            </Button>
            <Button
              size="small"
              disabled={detailIndex == null || detailIndex >= rows.length - 1}
              onClick={() => setDetailIndex((index) => Math.min(rows.length - 1, (index ?? 0) + 1))}
            >
              下一条
            </Button>
          </div>
        ) : null}
      >
        {detailRow && sheet === 'matrix' ? (
          <MatrixMonthView
            month={(range?.[0] ?? period).format('YYYY-MM')}
            row={detailRow}
            view={matrixView}
            onAdjust={canAdjust ? openMatrixDayAdjust : undefined}
          />
        ) : detailRow && sheet === 'missed-punch-stat' ? (
          <MissedPunchMonthView
            month={(range?.[0] ?? period).format('YYYY-MM')}
            row={detailRow}
            view={matrixView}
          />
        ) : detailRow && yearSheets.has(sheet) ? (
          <LeaveStatDrawer
            sheet={sheet}
            row={detailRow}
            year={period.year()}
            account={leaveAccount}
            error={leaveAccountError}
            canAdjust={canAdjustLeave}
            adjusting={leaveAdjusting}
            adjustmentHours={leaveAdjustHours}
            reason={leaveAdjustReason}
            onHoursChange={setLeaveAdjustHours}
            onReasonChange={setLeaveAdjustReason}
            onSave={() => void submitLeaveAdjustment()}
          />
        ) : detailRow ? (
          <dl className="query-report__drawer">
            {detailEntries(sheet, detailRow).map((entry) => (
              <div key={entry.key}>
                <dt>{entry.title}</dt>
                <dd>{entry.value}</dd>
              </div>
            ))}
          </dl>
        ) : null}
      </Drawer>
      <Modal
        title="人事调整打卡、加班和异常"
        open={adjustRow != null}
        onCancel={() => setAdjustRow(null)}
        onOk={() => void submitPunchAdjustment()}
        confirmLoading={adjusting}
        destroyOnClose
        okText="保存并重算"
      >
        <p>{String(adjustRow?.employeeName ?? '')} {String(adjustRow?.businessDate ?? '')}</p>
        <div className="query-report__group-fields">
          <FilterField label="上班">
            <TimePicker
              value={onDutyTime}
              format="HH:mm"
              onChange={(value) => setOnDutyTime(value)}
              allowClear
            />
          </FilterField>
          <FilterField label="下班">
            <TimePicker
              value={offDutyTime}
              format="HH:mm"
              onChange={(value) => setOffDutyTime(value)}
              allowClear
            />
          </FilterField>
          <FilterField label="加班小时">
            <InputNumber
              min={0}
              max={24}
              step={0.5}
              value={overtimeHours}
              onChange={(value) => setOvertimeHours(value)}
              placeholder="覆盖当天加班小时"
              style={{ width: '100%' }}
            />
          </FilterField>
          <FilterField label="当天类型">
            <Checkbox.Group
              value={dayTypes}
              onChange={(value) => setDayTypes(value as string[])}
              options={[
                { label: '加班', value: 'OVERTIME' },
                { label: '出差', value: 'TRIP' },
                { label: '外出', value: 'OUTING' },
                { label: '调休', value: 'TIME_OFF' },
                { label: '年假', value: 'ANNUAL_LEAVE' },
                { label: '事假', value: 'PERSONAL_LEAVE' },
                { label: '病假', value: 'SICK_LEAVE' },
                { label: '婚假', value: 'MARRIAGE_LEAVE' },
                { label: '丧假', value: 'BEREAVEMENT_LEAVE' },
                { label: '产假', value: 'MATERNITY_LEAVE' },
                { label: '陪产假', value: 'PATERNITY_LEAVE' },
                { label: '哺乳假', value: 'BREASTFEEDING_LEAVE' },
                { label: '工伤假', value: 'WORK_INJURY_LEAVE' },
                { label: '旷工', value: 'ABSENCE' },
                { label: '迟到', value: 'LATE' },
                { label: '早退', value: 'EARLY_DEPARTURE' },
                { label: '漏打卡', value: 'MISSING_PUNCH' },
              ]}
            />
          </FilterField>
          <FilterField label="取消异常">
            <Checkbox.Group
              value={clearedExceptionTypes}
              onChange={(value) => setClearedExceptionTypes(value as string[])}
              options={[
                { label: '迟到', value: 'LATE' },
                { label: '早退', value: 'EARLY_DEPARTURE' },
                { label: '缺卡', value: 'MISSING_PUNCH' },
                { label: '旷工', value: 'ABSENCE' },
                { label: '未报加班', value: 'OVERTIME_DOCUMENT_MISSING_OR_LATE' },
                { label: '加班结束晚于打卡', value: 'OVERTIME_FORM_BEYOND_LAST_PUNCH' },
                { label: '长时在岗待审', value: 'LONG_PUNCH_SPAN_REVIEW' },
              ]}
            />
          </FilterField>
          <FilterField label="原因">
            <Input
              value={adjustReason}
              onChange={(event) => setAdjustReason(event.target.value)}
              maxLength={500}
            />
          </FilterField>
        </div>
      </Modal>
    </main>
  );
}

function parseClock(value: unknown, businessDate: unknown): Dayjs | null {
  const clock = String(value ?? '');
  if (!/^\d{1,2}:\d{2}$/.test(clock)) {
    return null;
  }
  const date = String(businessDate ?? '').slice(0, 10);
  const parsed = dayjs(`${date} ${clock}`, 'YYYY-MM-DD HH:mm');
  return parsed.isValid() ? parsed : null;
}

function clockInstant(businessDate: string, value: Dayjs | null): string | null {
  if (!value) {
    return null;
  }
  const date = businessDate.slice(0, 10);
  const parsed = dayjs(`${date} ${value.format('HH:mm')}`, 'YYYY-MM-DD HH:mm');
  return parsed.isValid() ? parsed.toISOString() : null;
}

function matchesOvertimeTreatmentFilter(
  treatment: string | undefined,
  paidHours: number,
  compensatoryHours: number,
  totalHours: number,
  days: Array<{ treatment?: string; voluntaryHours?: number }>,
  buckets?: { weekday: number; weekend: number; holiday: number },
  voluntaryHours = 0,
): boolean {
  if (!treatment) return true;
  if (isPaidOvertimeFilter(treatment)) {
    return paidHours > 0;
  }
  if (isCompensatoryOvertimeFilter(treatment)) {
    return compensatoryHours > 0;
  }
  if (isVoluntaryOvertimeFilter(treatment)) {
    return voluntaryHours > 0
      || days.some((day) => day.treatment === 'VOLUNTARY' || (day.voluntaryHours ?? 0) > 0)
      || (totalHours > 0 && paidHours === 0 && compensatoryHours === 0);
  }
  if (treatment === '平时加班') return (buckets?.weekday ?? 0) > 0;
  if (treatment === '周末加班') return (buckets?.weekend ?? 0) > 0;
  if (treatment === '节假日加班') return (buckets?.holiday ?? 0) > 0;
  return true;
}

function projectedFinanceHours(
  paidHours: number,
  compensatoryHours: number,
  voluntaryHours: number,
  fallbackHours: number,
  treatment?: string,
): number {
  if (isPaidOvertimeFilter(treatment)) return paidHours;
  if (isCompensatoryOvertimeFilter(treatment)) return compensatoryHours;
  if (isVoluntaryOvertimeFilter(treatment)) return voluntaryHours;
  return fallbackHours;
}

function demoQueryPage(
  sheet: SheetKey,
  month: string,
  page: number,
  size: number,
  options: {
    organizationId?: string;
    employeeNumber: string;
    filters: Record<string, string>;
    range: [Dayjs, Dayjs] | null;
  },
): QueryPageResponse {
  const demo = getCustomerReportDemo(
    { month, department: '全部部门', employee: '全部员工' },
    defaultCustomerReportDataScope,
  );
  const employeeFilter = options.employeeNumber.trim();
  const departmentFilter = options.organizationId;
  const personNo = (row: object) => {
    if ('employeeNo' in row && typeof (row as { employeeNo?: unknown }).employeeNo === 'string') {
      return (row as { employeeNo: string }).employeeNo;
    }
    return '';
  };
  const matchesPerson = (row: {
    employeeNo?: string;
    department?: string;
    employee?: string;
  }) => {
    if (employeeFilter && personNo(row) !== employeeFilter && row.employee !== employeeFilter) {
      return false;
    }
    if (departmentFilter && row.department) {
      return `demo-org-${row.department}` === departmentFilter
        || row.department.includes(departmentFilter);
    }
    return true;
  };
  let rows: Array<Record<string, unknown>>;
  switch (sheet) {
    case 'exceptions':
      rows = demo.attendanceExceptionRows
        .filter((row) => matchesPerson(row))
        .filter((row) => !options.filters.exceptionType || row.exceptionType.includes(options.filters.exceptionType))
        .filter((row) => !options.filters.severity || row.severity === options.filters.severity)
        .filter((row) => !options.filters.state || row.state === options.filters.state)
        .map((row) => ({
          employeeNumber: row.employeeNo,
          employeeName: row.employee,
          department: row.department,
          businessDate: row.businessDate,
          exceptionType: row.exceptionType,
          severity: row.severity,
          state: row.state,
          minutes: row.exceptionMinutes,
          details: row.details,
        }));
      break;
    case 'leave':
      rows = demo.leaveRows
        .filter((row) => matchesPerson(row))
        .filter((row) => !options.filters.leaveType || row.type.includes(options.filters.leaveType))
        .map((row) => ({
          employeeNumber: personNo(row),
          employeeName: row.employee,
          department: row.department,
          leaveType: row.type,
          hours: row.hours,
          startAt: row.period,
          endAt: row.period,
          approvalState: row.approvalState,
        }));
      break;
    case 'overtime':
      rows = demo.overtimeRows
        .filter((row) => matchesPerson(row))
        .filter((row) => matchesOvertimeTreatmentFilter(
          options.filters.overtimeTreatment,
          Number(row.paidHours ?? 0),
          Number(row.compensatoryHours ?? 0),
          Number(row.totalHours ?? row.hours ?? 0),
          [{
            treatment: row.overtimeType === '义务加班' ? 'VOLUNTARY' : undefined,
            voluntaryHours: row.voluntaryHours ?? 0,
          }],
          undefined,
          Number(row.voluntaryHours ?? 0),
        ))
        .map((row) => ({
          employeeNumber: personNo(row),
          employeeName: row.employee,
          department: row.department,
          leaveType: (row.compensatoryHours ?? 0) > 0
            ? 'COMPENSATORY'
            : ((row.paidHours ?? 0) > 0 ? 'PAID' : 'VOLUNTARY'),
          hours: row.totalHours,
          startAt: null,
          endAt: null,
          approvalState: 'APPROVED',
        }));
      break;
    case 'work-hours':
      rows = demo.workHoursRows
        .filter((row) => matchesPerson(row))
        .map((row) => ({
          employeeNumber: personNo(row),
          employeeName: row.employee,
          department: row.department,
          scheduledHours: row.plannedHours,
          paidOvertimeHours: row.overtimeHours,
          voluntaryOvertimeHours: row.voluntaryOvertimeHours ?? 0,
          leaveHours: row.leaveHours,
          annualLeaveHours: row.annualLeaveHours ?? 0,
          compensatoryOvertimeHours: row.exchangedHours ?? 0,
          timeOffHours: row.usedTimeOffHours ?? 0,
          actualHours: row.actualHours,
          note: row.note,
        }));
      break;
    case 'late':
      rows = demo.lateRows
        .filter((row) => matchesPerson(row))
        .map((row) => ({
          employeeNumber: personNo(row),
          employeeName: row.employee,
          department: row.department,
          lateEvents: row.count,
          details: row.details,
        }));
      break;
    case 'missed-punch':
      rows = demo.missedPunchRows
        .filter((row) => matchesPerson(row))
        .map((row) => ({
          employeeNumber: personNo(row),
          employeeName: row.employee,
          department: row.department,
          count: row.count,
          details: row.details,
        }));
      break;
    case 'missed-punch-stat':
      rows = demo.attendanceRows
        .filter((row) => matchesPerson(row))
        .map((row, index) => {
          const days = row.days.slice(0, 8).map((day) => {
            const date = `${month}-${String(day.day).padStart(2, '0')}`;
            if (day.status === 'missed') {
              return {
                date,
                morning: { text: '漏刷', tone: 'MISSING_PUNCH' },
                afternoon: { text: day.secondary || '18:00', tone: null },
              };
            }
            if (index === 0 && day.day === 3) {
              return {
                date,
                morning: { text: '补签08:18', tone: 'PUNCH_CORRECTION' },
                afternoon: { text: day.secondary || '18:00', tone: null },
              };
            }
            return {
              date,
              morning: { text: day.primary || '08:30', tone: null },
              afternoon: { text: day.secondary || '18:00', tone: null },
            };
          });
          const summary = missedPunchSummary(days);
          return {
            employeeId: row.employeeId ?? `demo-employee-${index}`,
            sequence: index + 1,
            employeeNumber: row.employeeNo,
            employeeName: row.employee,
            department: row.department,
            missedCount: summary.missedCount,
            remark: summary.remark,
            days,
          };
        })
        .filter((row) => row.days.some((day) => (
          day.morning.tone === 'MISSING_PUNCH'
          || day.afternoon.tone === 'MISSING_PUNCH'
          || day.morning.tone === 'PUNCH_CORRECTION'
          || day.afternoon.tone === 'PUNCH_CORRECTION'
        )));
      break;
    case 'attendance-rate':
      rows = demo.attendanceRateRows
        .filter((row) => matchesPerson(row))
        .map((row) => ({
          employeeNumber: personNo(row),
          employeeName: row.employee,
          department: row.department,
          scheduledDays: row.scheduledDays,
          actualDays: row.actualDays,
          attendanceRate: row.rate,
        }));
      break;
    case 'annual-leave-stat':
    case 'time-off-stat': {
      const timeOff = sheet === 'time-off-stat';
      rows = demo.annualLeaveRows
        .filter((row) => matchesPerson(row))
        .map((row, index) => {
          const remainingDays = timeOff
            ? Number((row.remainingDays * 0.4).toFixed(2))
            : row.remainingDays;
          const remainingHours = remainingDays * 8;
          const used = Array.from({ length: 12 }, (_, monthIndex) => (
            monthIndex < 7 ? '/' : (monthIndex === 7 ? 0 : Number(((row.monthlyUsedDays?.[monthIndex] ?? 0)).toFixed(1)))
          ));
          return {
            employeeId: `demo-leave-${index}`,
            sequence: index + 1,
            levelOneDepartment: row.department,
            levelTwoDepartment: '',
            employeeNumber: personNo(row),
            employeeName: row.employee,
            department: row.department,
            hireDate: row.joinedOn ?? '2020-03-01',
            companyTenureYears: row.companySeniority ?? 3,
            priorTenureYears: row.priorSeniority ?? 0,
            cumulativeTenureYears: row.totalSeniority ?? 3,
            entitledDays: row.statutoryDays ?? 5,
            newHireCalendarDays: row.newHireDays ?? 0,
            openingHours: timeOff ? 16 : 40,
            overtimeCreditHours: timeOff ? remainingHours : 0,
            remainingDays,
            remainingHours,
            ...Object.fromEntries(used.map((value, monthIndex) => [`usedMonth${monthIndex + 1}`, value])),
          };
        });
      break;
    }
    case 'annual-leave':
    case 'time-off': {
      const timeOff = sheet === 'time-off';
      rows = demo.annualLeaveRows
        .filter((row) => matchesPerson(row))
        .map((row) => {
          const remainingDays = timeOff
            ? Number((row.remainingDays * 0.4).toFixed(2))
            : row.remainingDays;
          const remainingHours = remainingDays * 8;
          const usedHours = timeOff
            ? Number((row.availableHours * 0.25).toFixed(1))
            : Number(((row.availableDays - row.remainingDays) * 8).toFixed(1));
          return {
            employeeNumber: personNo(row),
            employeeName: row.employee,
            department: row.department,
            accountType: timeOff ? '调休' : '年假',
            openingHours: timeOff ? 0 : row.availableHours,
            grantedHours: timeOff ? 0 : row.availableHours,
            overtimeCreditHours: timeOff ? remainingHours + usedHours : 0,
            usedHours,
            remainingHours,
            remainingDays,
          };
        });
      break;
    }
    case 'leave-summary':
      rows = demo.leaveRows
        .filter((row) => matchesPerson(row))
        .reduce<Array<Record<string, unknown>>>((collected, row) => {
          const existing = collected.find((item) => (
            item.employeeName === row.employee && item.leaveType === row.type
          ));
          if (existing) {
            existing.hours = Number(existing.hours) + row.hours;
            existing.documentCount = Number(existing.documentCount) + 1;
            return collected;
          }
          collected.push({
            employeeNumber: personNo(row),
            employeeName: row.employee,
            department: row.department,
            leaveType: row.type,
            hours: row.hours,
            documentCount: 1,
          });
          return collected;
        }, []);
      break;
    case 'overtime-daily':
      rows = demo.overtimeDailyRows
        .filter((row) => matchesPerson(row))
        .filter((row) => matchesOvertimeTreatmentFilter(
          options.filters.overtimeTreatment,
          Number(row.paidOvertimeHours ?? 0),
          Number(row.compensatoryOvertimeHours ?? 0),
          Number(row.weekdayOvertimeHours)
            + Number(row.weekendOvertimeHours)
            + Number(row.holidayOvertimeHours),
          [],
          undefined,
          Number(row.voluntaryOvertimeHours ?? 0),
        ))
        .map((row) => {
          const paid = Number(row.paidOvertimeHours ?? 0);
          const compensatory = Number(row.compensatoryOvertimeHours ?? 0);
          const voluntary = Number(row.voluntaryOvertimeHours ?? 0);
          const weekday = Number(row.weekdayOvertimeHours);
          const weekend = Number(row.weekendOvertimeHours);
          const holiday = Number(row.holidayOvertimeHours);
          const bucket = projectedFinanceHours(
            paid,
            compensatory,
            voluntary,
            weekday + weekend + holiday,
            options.filters.overtimeTreatment,
          );
          const source = weekday > 0 ? 'weekday' : weekend > 0 ? 'weekend' : 'holiday';
          return {
            employeeNumber: row.employeeNo,
            employeeName: row.employee,
            department: row.department,
            businessDate: row.businessDate,
            weekdayOvertimeHours: source === 'weekday' ? bucket : 0,
            weekendOvertimeHours: source === 'weekend' ? bucket : 0,
            holidayOvertimeHours: source === 'holiday' ? bucket : 0,
            paidOvertimeHours: paid,
            compensatoryOvertimeHours: compensatory,
            voluntaryOvertimeHours: voluntary,
          };
        });
      break;
    case 'time-off-daily':
      rows = demo.leaveRows
        .filter((row) => row.type === '调休' && matchesPerson(row))
        .flatMap((row) => {
          const hours = row.hours;
          return [
            {
              employeeNumber: personNo(row),
              employeeName: row.employee,
              department: row.department,
              businessDate: `${month}-05`,
              hours: Math.round(hours * 5) / 10,
              leaveType: '调休',
            },
            {
              employeeNumber: personNo(row),
              employeeName: row.employee,
              department: row.department,
              businessDate: `${month}-06`,
              hours: hours - Math.round(hours * 5) / 10,
              leaveType: '调休',
            },
          ];
        });
      break;
    case 'finance-overtime':
    case 'overtime-fee-daily':
    case 'overtime-voluntary-daily':
    case 'overtime-comp-daily': {
      const occurrenceDay = options.filters.occurrenceDay;
      const overtimeTreatment = financeOvertimeLockedTreatment(sheet)
        ?? options.filters.overtimeTreatment;
      rows = demo.financeOvertimeRows
        .filter((row) => matchesPerson(row))
        .filter((row) => matchesOvertimeTreatmentFilter(
          overtimeTreatment,
          Number(row.paidOvertimeHours ?? 0),
          Number(row.compensatoryOvertimeHours ?? 0),
          Number(row.weekdayOvertimeHours)
            + Number(row.weekendOvertimeHours)
            + Number(row.holidayOvertimeHours),
          row.days,
          {
            weekday: Number(row.weekdayOvertimeHours),
            weekend: Number(row.weekendOvertimeHours),
            holiday: Number(row.holidayOvertimeHours),
          },
          Number(row.voluntaryOvertimeHours ?? 0),
        ))
        .filter((row) => {
          const calendar = options.filters.attendanceType;
          if (!calendar) return true;
          if (calendar === '平时加班') return Number(row.weekdayOvertimeHours) > 0;
          if (calendar === '周末加班') return Number(row.weekendOvertimeHours) > 0;
          if (calendar === '节假日加班') return Number(row.holidayOvertimeHours) > 0;
          return true;
        })
        .map((row) => {
          const days = (occurrenceDay
            ? row.days.filter((day) => Number(day.date.slice(8, 10)) === Number(occurrenceDay))
            : row.days
          ).map((day) => {
            const paidHours = Number(day.paidHours ?? 0);
            const compensatoryHours = Number(day.compensatoryHours ?? 0);
            const voluntaryHours = Number(day.voluntaryHours ?? 0);
            return {
              ...day,
              hours: projectedFinanceHours(
                paidHours,
                compensatoryHours,
                voluntaryHours,
                Number(day.hours ?? 0),
                overtimeTreatment,
              ),
            };
          });
          const weekday = days.reduce((sum, day) => (
            isoWeekdayBucket(day.date) === 'weekday' ? sum + Number(day.hours ?? 0) : sum
          ), 0);
          const weekend = days.reduce((sum, day) => (
            isoWeekdayBucket(day.date) === 'weekend' ? sum + Number(day.hours ?? 0) : sum
          ), 0);
          return {
            employeeNumber: row.employeeNo,
            employeeName: row.employee,
            department: row.department,
            weekdayOvertimeHours: overtimeTreatment ? weekday : row.weekdayOvertimeHours,
            weekendOvertimeHours: overtimeTreatment ? weekend : row.weekendOvertimeHours,
            holidayOvertimeHours: row.holidayOvertimeHours,
            paidOvertimeHours: row.paidOvertimeHours ?? 0,
            compensatoryOvertimeHours: row.compensatoryOvertimeHours ?? 0,
            voluntaryOvertimeHours: row.voluntaryOvertimeHours ?? 0,
            days,
          };
        })
        .filter((row) => !occurrenceDay || row.days.length > 0);
      break;
    }
    case 'absence-stat':
      rows = demo.attendanceRows
        .filter((row) => matchesPerson(row))
        .map((row) => {
          const days = row.days.slice(0, 8).flatMap((day) => {
            if (day.status !== 'missed') {
              return [];
            }
            return [{
              date: `${month}-${String(day.day).padStart(2, '0')}`,
              dayType: 'WEEKDAY',
              hours: 8,
            }];
          });
          return {
            employeeNumber: row.employeeNo,
            employeeName: row.employee,
            department: row.department,
            absenceHours: days.reduce((sum, day) => sum + day.hours, 0),
            days,
          };
        })
        .filter((row) => row.absenceHours > 0);
      break;
    case 'leave-stat':
      rows = demo.leaveRows
        .filter((row) => matchesPerson(row))
        .reduce<Array<Record<string, unknown>>>((collected, row) => {
          const existing = collected.find((item) => item.employeeName === row.employee);
          const date = `${month}-05`;
          const day = { date, dayType: row.type, hours: row.hours };
          if (existing) {
            existing.leaveHours = Number(existing.leaveHours) + row.hours;
            (existing.days as Array<Record<string, unknown>>).push(day);
            return collected;
          }
          collected.push({
            employeeNumber: personNo(row),
            employeeName: row.employee,
            department: row.department,
            leaveHours: row.hours,
            days: [day],
          });
          return collected;
        }, []);
      break;
    case 'daily-journal':
      rows = demo.attendanceRows
        .filter((row) => matchesPerson(row))
        .flatMap((row) => row.days.slice(0, 3).map((day, index) => ({
          sequence: index + 1,
          employeeNumber: row.employeeNo,
          employeeName: row.employee,
          department: row.department,
          businessDate: `${month}-${String(day.day).padStart(2, '0')}`,
          shiftLabel: row.position ?? '',
          onDuty: day.primary,
          offDuty: day.secondary,
          lateHours: day.status === 'late' ? 0.5 : '',
          earlyHours: day.status === 'early' ? 0.5 : '',
          absenceHours: '',
          leaveType: '',
          overtimeHours: day.status === 'overtime' ? 2.5 : '',
          remark: day.status === 'missed' ? '漏刷' : '',
        })));
      break;
    default:
      rows = demo.attendanceRows
        .filter((row) => matchesPerson(row))
        .map((row) => ({
          employeeNumber: row.employeeNo,
          employeeName: row.employee,
          department: row.department,
          days: row.days.slice(0, 7).map((day) => ({
            date: `${month}-${String(day.day).padStart(2, '0')}`,
            status: day.status,
            primary: day.primary,
          })),
        }));
  }
  const from = page * size;
  const pageRows = rows.slice(from, from + size);
  const noDataMonth = month < '2026-07' || month > '2026-08';
  return {
    sheet,
    companyId: 'demo-company',
    fromDate: `${month}-01`,
    toDate: dayjs(`${month}-01`).endOf('month').format('YYYY-MM-DD'),
    projectionVersion: noDataMonth ? undefined : 'DEMO-PIN',
    dataAsOf: noDataMonth ? undefined : demo.metadata.generatedAt,
    hint: noDataMonth
      ? '该期间暂无打卡或核算数据'
      : (pageRows.length === 0 ? '当前筛选条件下没有记录' : undefined),
    rowCount: noDataMonth ? 0 : rows.length,
    page,
    size,
    rows: noDataMonth ? [] : pageRows,
    omittedMonths: noDataMonth ? [{ period: month, reason: 'NO_DATA' }] : [],
    allowedActions: ['REPORT_QUERY', 'REPORT_EXPORT_CREATE'],
  };
}

export function visibleExportColumns(
  sheet: SheetKey,
  overtimeTreatment?: string,
): Array<{ key: string; title: string }> {
  return sheetColumns(sheet, overtimeTreatment).filter(
    (column) => column.key !== 'action' && column.key !== 'adjust',
  );
}

export function sheetColumns(
  sheet: SheetKey,
  overtimeTreatment?: string,
): Array<{ key: string; title: string }> {
  const identity = [
    { key: 'employeeNumber', title: '工号' },
    { key: 'employeeName', title: '姓名' },
    { key: 'department', title: '部门' },
  ];
  const action = { key: 'action', title: '详情' };
  switch (sheet) {
    case 'exceptions':
      return [
        { key: 'businessDate', title: '考勤日期' },
        { key: 'exceptionType', title: '异常类型' },
        { key: 'employeeNumber', title: '工号' },
        { key: 'employeeName', title: '姓名' },
        { key: 'department', title: '部门' },
        { key: 'details', title: '详情' },
        { key: 'state', title: '处理状态' },
      ];
    case 'leave':
      return [
        ...identity,
        { key: 'leaveType', title: '假别' },
        { key: 'startAt', title: '开始时间' },
        { key: 'endAt', title: '结束时间' },
        { key: 'hours', title: '小时' },
        { key: 'approvalState', title: '审批状态' },
        action,
      ];
    case 'overtime':
      return [
        ...identity,
        { key: 'leaveType', title: '加班方式' },
        { key: 'startAt', title: '开始时间' },
        { key: 'endAt', title: '结束时间' },
        { key: 'hours', title: '小时' },
        { key: 'approvalState', title: '审批状态' },
        { key: 'sourceOrigin', title: '来源' },
        action,
      ];
    case 'makeup':
      return [
        ...identity,
        { key: 'documentType', title: '单据类型' },
        { key: 'startAt', title: '补签时间' },
        { key: 'approvalState', title: '审批状态' },
        action,
      ];
    case 'work-hours':
      return [
        ...identity,
        { key: 'scheduledHours', title: '应出勤工时' },
        { key: 'paidOvertimeHours', title: '加班时数' },
        { key: 'voluntaryOvertimeHours', title: '义务加班' },
        { key: 'leaveHours', title: '事假+病假+其他假期' },
        { key: 'annualLeaveHours', title: '年假' },
        { key: 'compensatoryOvertimeHours', title: '加班换调休' },
        { key: 'timeOffHours', title: '实际调休' },
        { key: 'actualHours', title: '个人实际出勤工时' },
        { key: 'note', title: '备注' },
        action,
      ];
    case 'late':
      return [
        ...identity,
        { key: 'lateEvents', title: '迟到次数' },
        { key: 'lateMinutes', title: '迟到分钟' },
        { key: 'penalizedLateMinutes', title: '计罚迟到分钟' },
        action,
      ];
    case 'missed-punch':
      return [
        ...identity,
        { key: 'businessDate', title: '考勤日期' },
        { key: 'exceptionType', title: '缺卡类型' },
        { key: 'details', title: '说明' },
        action,
      ];
    case 'missed-punch-stat':
      return [
        { key: 'sequence', title: '序号' },
        { key: 'department', title: '部门' },
        { key: 'employeeName', title: '姓名' },
        { key: 'missedCount', title: '次数' },
        { key: 'remark', title: '备注' },
        { key: 'action', title: '详情' },
      ];
    case 'annual-leave-stat':
      return [
        { key: 'sequence', title: '序号' },
        { key: 'levelOneDepartment', title: '一级部门' },
        { key: 'levelTwoDepartment', title: '二级部门' },
        { key: 'employeeName', title: '姓名' },
        { key: 'hireDate', title: '入职日期' },
        { key: 'companyTenureYears', title: '公司工龄' },
        { key: 'priorTenureYears', title: '公司外已证明工龄' },
        { key: 'cumulativeTenureYears', title: '累计工龄（年）' },
        { key: 'entitledDays', title: '按累计工龄当年应休天数' },
        { key: 'newHireCalendarDays', title: '新员工计算年休假日历天数' },
        { key: 'openingHours', title: '期初小时' },
        { key: 'remainingDays', title: '可休天数' },
        { key: 'remainingHours', title: '可休小时数' },
        ...Array.from({ length: 12 }, (_, index) => ({
          key: `usedMonth${index + 1}`,
          title: `${index + 1}月已休`,
        })),
      ];
    case 'time-off-stat':
      return [
        { key: 'sequence', title: '序号' },
        { key: 'levelOneDepartment', title: '一级部门' },
        { key: 'levelTwoDepartment', title: '二级部门' },
        { key: 'employeeName', title: '姓名' },
        { key: 'openingHours', title: '期初小时' },
        { key: 'overtimeCreditHours', title: '加班转入小时' },
        { key: 'remainingDays', title: '可休天数' },
        { key: 'remainingHours', title: '可休小时数' },
        ...Array.from({ length: 12 }, (_, index) => ({
          key: `usedMonth${index + 1}`,
          title: `${index + 1}月已休`,
        })),
      ];
    case 'attendance-rate':
      return [
        ...identity,
        { key: 'scheduledDays', title: '应出勤天数' },
        { key: 'actualDays', title: '实际出勤天数' },
        { key: 'attendanceRate', title: '出勤率' },
        action,
      ];
    case 'annual-leave':
    case 'time-off':
      return [
        ...identity,
        { key: 'accountType', title: '账户类型' },
        { key: 'openingHours', title: '期初小时' },
        { key: 'grantedHours', title: '发放小时' },
        { key: 'overtimeCreditHours', title: '加班转入小时' },
        { key: 'usedHours', title: '已用小时' },
        { key: 'remainingHours', title: '剩余小时' },
        { key: 'remainingDays', title: '剩余天数' },
      ];
    case 'matrix':
      return [
        ...identity,
        { key: 'exceptionDayCount', title: '异常天数' },
        { key: 'action', title: '本月明细' },
      ];
    case 'leave-summary':
      return [
        ...identity,
        { key: 'leaveType', title: '假别' },
        { key: 'hours', title: '合计小时' },
        { key: 'documentCount', title: '单据数' },
      ];
    case 'overtime-daily': {
      const columns = overtimeFeeColumns(overtimeTreatment);
      return [
        ...identity,
        { key: 'businessDate', title: '日期' },
        { key: 'weekdayOvertimeHours', title: '工作日加班' },
        { key: 'weekendOvertimeHours', title: '周末加班' },
        { key: 'holidayOvertimeHours', title: '节假日加班' },
        ...(columns.paid ? [{ key: 'paidOvertimeHours', title: '加班费' }] : []),
        ...(columns.compensatory ? [{ key: 'compensatoryOvertimeHours', title: '转调休' }] : []),
        ...(columns.voluntary ? [{ key: 'voluntaryOvertimeHours', title: '义务加班' }] : []),
      ];
    }
    case 'time-off-daily':
      return [
        ...identity,
        { key: 'businessDate', title: '日期' },
        { key: 'hours', title: '调休小时' },
        { key: 'leaveType', title: '假别' },
      ];
    case 'finance-overtime':
    case 'overtime-fee-daily':
    case 'overtime-voluntary-daily':
    case 'overtime-comp-daily': {
      const columns = overtimeFeeColumns(
        financeOvertimeLockedTreatment(sheet) ?? overtimeTreatment,
      );
      return [
        { key: 'department', title: '部门' },
        { key: 'employeeNumber', title: '工号' },
        { key: 'employeeName', title: '加班人' },
        { key: 'weekdayOvertimeHours', title: '平时加班' },
        { key: 'weekendOvertimeHours', title: '周末加班' },
        { key: 'holidayOvertimeHours', title: '节假日加班' },
        ...(columns.paid ? [{ key: 'paidOvertimeHours', title: '加班费' }] : []),
        ...(columns.compensatory ? [{ key: 'compensatoryOvertimeHours', title: '转调休' }] : []),
        ...(columns.voluntary ? [{ key: 'voluntaryOvertimeHours', title: '义务加班' }] : []),
      ];
    }
    case 'absence-stat':
      return [
        { key: 'department', title: '部门' },
        { key: 'employeeNumber', title: '工号' },
        { key: 'employeeName', title: '姓名' },
        { key: 'absenceHours', title: '合计旷工' },
      ];
    case 'leave-stat':
      return [
        { key: 'department', title: '部门' },
        { key: 'employeeNumber', title: '工号' },
        { key: 'employeeName', title: '姓名' },
        { key: 'leaveHours', title: '合计请假' },
      ];
    case 'daily-journal':
      return [
        { key: 'sequence', title: '序号' },
        { key: 'department', title: '部门' },
        { key: 'employeeNumber', title: '工号' },
        { key: 'employeeName', title: '姓名' },
        { key: 'businessDate', title: '日期' },
        { key: 'shiftLabel', title: '班次' },
        { key: 'onDuty', title: '上班' },
        { key: 'offDuty', title: '下班' },
        { key: 'lateHours', title: '迟到' },
        { key: 'earlyHours', title: '早退' },
        { key: 'absenceHours', title: '旷工' },
        { key: 'leaveType', title: '请假' },
        { key: 'overtimeHours', title: '加班' },
        { key: 'remark', title: '备注' },
        { key: 'adjust', title: '改打卡' },
      ];
    default:
      return [...identity, action];
  }
}

function renderCell(
  sheet: SheetKey,
  key: string,
  row: Record<string, unknown>,
  onOpen: () => void,
): ReactNode {
  if (key === 'action') {
    return (
      <button
        type="button"
        className="query-report__detail-btn"
        onClick={(event) => {
          event.stopPropagation();
          onOpen();
        }}
      >
        {sheet === 'missed-punch-stat' ? '查看日历' : sheet === 'matrix' ? '查看本月' : '查看详情'}
      </button>
    );
  }
  if (key === 'exceptionDayCount') {
    return String(countExceptionDays(row));
  }
  if (key === 'details' || key === 'note' || key === 'remark') {
    const text = formatCell(sheet, key, row[key]);
    return text === '—' ? '—' : text;
  }
  if (key === 'severity') {
    const label = formatCell(sheet, key, row[key]);
    return (
      <span className="customer-report__severity" data-severity={label}>{label}</span>
    );
  }
  if (key === 'exceptionType') {
    return <strong>{formatCell(sheet, key, row[key])}</strong>;
  }
  if (key === 'department') {
    return departmentPathNodes(typeof row.department === 'string' ? row.department : undefined);
  }
  if (key === 'employeeName') {
    return <strong>{formatCell(sheet, key, row[key])}</strong>;
  }
  if (key === 'state' || key === 'approvalState') {
    const label = formatCell(sheet, key, row[key]);
    const waiting = label.includes('待') || label.includes('未');
    return (
      <span className={`customer-report__state${waiting ? ' is-waiting' : ''}`}>
        <i aria-hidden="true" />
        {label}
      </span>
    );
  }
  return formatCell(sheet, key, row[key]);
}

function summarizeExceptions(rows: Array<Record<string, unknown>>) {
  return {
    high: rows.filter((row) => severityLabel(String(row.severity ?? '')) === '高').length,
    missing: rows.filter((row) => exceptionTypeLabel(String(row.exceptionType ?? '')).includes('缺卡')).length,
    open: rows.filter((row) => {
      const state = statusLabel(String(row.state ?? ''));
      return state.includes('待') || state === '未关闭';
    }).length,
  };
}

function formatCell(sheet: SheetKey, key: string, value: unknown): string {
  if (value === '/') return '/';
  if (value == null || value === '') {
    if (key === 'details' || key === 'note' || key === 'remark') return '—';
    if (key === 'newHireCalendarDays' || key.startsWith('usedMonth')) return '0';
    return '—';
  }
  if (key === 'department') return visibleDepartmentPath(String(value));
  if (key === 'exceptionType') return exceptionTypeLabel(String(value));
  if (key === 'severity') return severityLabel(String(value));
  if (key === 'leaveType') return leaveTypeLabel(String(value), sheet);
  if (key === 'documentType') return documentTypeLabel(String(value));
  if (key === 'sourceOrigin') return String(value) === 'PAPER' ? '纸质' : 'OA';
  if (key === 'accountType') return accountTypeLabel(String(value));
  if (key === 'approvalState' || key === 'state' || key === 'sourceStatus') {
    return statusLabel(String(value));
  }
  if (key === 'details' || key === 'note') {
    const text = String(value);
    const code = text.match(/原因码=([A-Z0-9_]+)/)?.[1];
    if (code) return exceptionTypeLabel(code);
    return text;
  }
  if (key === 'startAt' || key === 'endAt' || key === 'businessDate' || key === 'hireDate') {
    return formatDateTime(value);
  }
  if (typeof value === 'number') {
    return isHourKey(key) ? formatHourValue(value) : String(value);
  }
  return looksEnglishCode(String(value)) ? translateCode(String(value), sheet) : String(value);
}

function isHourKey(key: string): boolean {
  return key === 'hours' || /Hours$/i.test(key);
}

function formatHourValue(value: number): string {
  if (!Number.isFinite(value)) return String(value);
  const halfSteps = Math.round(value * 2) / 2;
  return Number.isInteger(halfSteps) ? String(halfSteps) : halfSteps.toFixed(1);
}

function looksEnglishCode(value: string): boolean {
  return /^[A-Z][A-Z0-9_]+$/.test(value);
}

function translateCode(value: string, sheet: SheetKey): string {
  return exceptionTypeLabel(value)
    !== value ? exceptionTypeLabel(value)
    : leaveTypeLabel(value, sheet) !== value ? leaveTypeLabel(value, sheet)
      : documentTypeLabel(value) !== value ? documentTypeLabel(value)
        : statusLabel(value) !== value ? statusLabel(value)
          : accountTypeLabel(value);
}

function exceptionTypeLabel(value: string): string {
  return ({
    LATE: '迟到',
    LATE_CONVERTED_TO_ABSENCE: '迟到转旷工',
    EARLY_DEPARTURE: '早退',
    MISSING_ON_DUTY: '上班缺卡',
    MISSING_OFF_DUTY: '下班缺卡',
    MISSING_PUNCH_PENDING: '缺卡待补签',
    MISSING_PUNCH_OVERDUE: '缺卡超期',
    MISSING_PUNCH: '缺卡待补签',
    ABSENCE: '旷工',
    EVIDENCE_CONFLICT: '证据冲突',
    LEAVE_PUNCH_CONFLICT: '请假与打卡冲突',
    OUTING_OR_TRIP_INCOMPLETE: '外出出差不完整',
    OA_APPROVAL_STATUS_UNKNOWN: '审批状态未知',
    OA_PERSON_REFERENCE_INVALID: '人员引用无效',
    EMPLOYEE_UNMATCHED: '员工未匹配',
    DUPLICATE_SOURCE_RECORD: '来源记录重复',
    SOURCE_SCHEMA_CHANGED: '来源结构变更',
    SOURCE_SYNC_STALE: '来源同步过期',
    NO_ATTENDANCE_GROUP: '无考勤组',
    NO_SHIFT_OR_CALENDAR: '无班次或日历',
    AMBIGUOUS_PUNCH_MATCH: '打卡配对歧义',
    CROSS_MIDNIGHT_REVIEW_REQUIRED: '跨午夜待复核',
    OVERTIME_DOCUMENT_MISSING_OR_LATE: '未报加班',
    OVERTIME_FORM_BEYOND_LAST_PUNCH: '加班结束晚于打卡',
    LONG_PUNCH_SPAN_REVIEW: '长时在岗待审',
    FAKE_OVERTIME: '加班异常',
    EARLY_RETURN_CANDIDATE: '提前返回待确认',
    OUTING_OVERTIME_UNDECLARED: '外出超时未报加班',
    POST_CLOSE_SOURCE_CHANGE: '月结后来源变更',
    INPUT_INTEGRITY_ERROR: '输入完整性错误',
    NEGATIVE_LEAVE_BALANCE: '假期余额为负',
    NEGATIVE_ANNUAL_LEAVE_BALANCE: '年假余额为负',
    NEGATIVE_TIME_OFF_BALANCE: '调休余额为负',
  } as Record<string, string>)[value] ?? value;
}

function severityLabel(value: string): string {
  return ({ ERROR: '高', WARNING: '中', INFO: '低' } as Record<string, string>)[value] ?? value;
}

function accountTypeLabel(value: string): string {
  return ({
    ANNUAL_LEAVE: '年假',
    TIME_OFF: '调休',
    COMP_TIME: '调休',
  } as Record<string, string>)[value] ?? value;
}

function documentTypeLabel(value: string): string {
  return ({
    LEAVE: '请假',
    LEAVE_REVOCATION: '销假',
    OVERTIME: '加班',
    TRAVEL: '出差',
    TRIP: '出差',
    OUTING: '外出',
    CORRECTION: '补签',
    PUNCH_CORRECTION: '补签',
    TIME_OFF: '调休',
    EXEMPT_PUNCH: '免打卡',
  } as Record<string, string>)[value] ?? value;
}

function leaveTypeLabel(value: string, sheet: SheetKey): string {
  const mapped = ({
    ANNUAL: '年假',
    SICK: '病假',
    MARRIAGE: '婚假',
    MATERNITY: '产假',
    PATERNITY: '陪产假',
    BEREAVEMENT: '丧假',
    WORK_INJURY: '工伤假',
    PRENATAL_NURSING: '产检假',
    PERSONAL: '事假',
    COMPENSATORY: '转调休',
    TIME_OFF: '调休',
    PAID: '加班费',
    COMPENSATED_OVERTIME: '加班费',
    TIME_OFF_IN_LIEU: '转调休',
    VOLUNTARY: '义务加班',
    OBLIGATORY_OVERTIME: '义务加班',
  } as Record<string, string>)[value];
  if (mapped) return mapped;
  if (sheet === 'overtime') {
    if (value.includes('调休') || value.includes('COMPENSATORY')) return '转调休';
    if (value.includes('计薪') || value.includes('PAID')) return '计薪加班（加班费）';
    if (value.includes('义务') || value.includes('VOLUNTARY')) return '义务加班';
  }
  return value;
}

function statusLabel(value: string): string {
  return ({
    APPROVED: '已批准',
    PENDING: '审批中',
    DRAFT: '草稿',
    REJECTED: '已驳回',
    MODIFIED: '已变更',
    SUPPLEMENTED: '已补单',
    REVOKED: '已撤销',
    OPEN: '待处理',
    PENDING_EVIDENCE: '待补充凭证',
    PENDING_REVIEW: '待复核',
    RESOLVED: '已处理',
    CLOSED: '已关闭',
    UNKNOWN: '未识别',
  } as Record<string, string>)[value] ?? value;
}

function formatDateTime(value: unknown): string {
  const parsed = dayjs(String(value));
  if (!parsed.isValid()) return String(value);
  if (parsed.hour() === 0 && parsed.minute() === 0 && !String(value).includes('T')) {
    return parsed.format('YYYY年M月D日');
  }
  return parsed.format('YYYY年M月D日 HH:mm');
}

export function detailEntries(
  sheet: SheetKey,
  row: Record<string, unknown>,
): Array<{ key: string; title: string; value: string }> {
  const skip = new Set(['days', 'action', 'exceptionDayCount']);
  return sheetColumns(sheet)
    .filter((column) => column.key !== 'action' && !skip.has(column.key))
    .map((column) => {
      const value = formatCell(sheet, column.key, row[column.key]);
      return {
        key: column.key,
        title: column.title,
        value: value === '—' && (column.key === 'details' || column.key === 'note')
          ? '—'
          : value,
      };
    });
}

function detailDrawerTitle(
  sheet: SheetKey,
  title: string,
  row: Record<string, unknown> | null,
): string {
  if (!row) return `${title}详情`;
  if (sheet === 'matrix' || sheet === 'missed-punch-stat') {
    const name = String(row.employeeName ?? row.employeeNumber ?? '员工');
    return `${name} · ${sheet === 'missed-punch-stat' ? '忘打卡月历' : '本月考勤明细'}`;
  }
  if (sheet === 'annual-leave-stat' || sheet === 'time-off-stat') {
    const name = String(row.employeeName ?? row.employeeNumber ?? '员工');
    return `${name} · ${sheet === 'time-off-stat' ? '调休账户' : '年假账户'}`;
  }
  if (sheet === 'exceptions') return '异常详情';
  return `${title}详情`;
}

function countExceptionDays(row: Record<string, unknown>): number {
  const days = row.days;
  if (!Array.isArray(days)) return 0;
  return days.filter((day) => {
    const item = day as Record<string, unknown>;
    return Number(item.lateMinutes ?? 0) > 0
      || Number(item.earlyMinutes ?? 0) > 0
      || Number(item.missingPunches ?? 0) > 0;
  }).length;
}

export function rangeStaysInMonth(from: Dayjs, to: Dayjs, month: string): boolean {
  return from.format('YYYY-MM') === month && to.format('YYYY-MM') === month;
}

export function sheetQueryRange(
  sheet: SheetKey,
  range: [Dayjs, Dayjs] | null,
  period: Dayjs,
): [Dayjs, Dayjs] {
  if (yearSheets.has(sheet)) {
    return [period.startOf('year'), period.endOf('year')];
  }
  if (range?.[0] && range[1]) {
    return range;
  }
  return monthDateRange(period);
}

function MatrixMonthView({
  month,
  row,
  view,
  onAdjust,
}: {
  month: string;
  row: Record<string, unknown>;
  view: 'calendar' | 'list';
  onAdjust?: (day: Record<string, unknown>, date: string) => void;
}) {
  const start = dayjs(`${month}-01`);
  const daysInMonth = start.daysInMonth();
  const rawDays = Array.isArray(row.days) ? row.days as Array<Record<string, unknown>> : [];
  const byDate = new Map(rawDays.map((day) => [String(day.date ?? ''), day]));
  const cells = Array.from({ length: daysInMonth }, (_, index) => {
    const date = start.date(index + 1);
    return { date, day: byDate.get(date.format('YYYY-MM-DD')) };
  });
  return (
    <div>
      <p className="query-report__month-meta">
        {String(row.employeeNumber ?? '—')}
        {' · '}
        {String(row.employeeName ?? '—')}
        {' · '}
        {visibleDepartmentPath(typeof row.department === 'string' ? row.department : undefined) || '—'}
      </p>
      <div className="customer-report__legend" aria-label="考勤状态颜色图例">
        {attendanceLegend.slice(0, 8).map((item) => (
          <span key={item.key}>
            <i style={{ backgroundColor: item.color }} aria-hidden="true" />
            {item.label}
          </span>
        ))}
      </div>
      {view === 'calendar' ? (
        <div className="query-report__calendar">
          <div className="query-report__calendar-weekdays">
            {['日', '一', '二', '三', '四', '五', '六'].map((label) => (
              <span key={label}>{label}</span>
            ))}
          </div>
          <div className="query-report__calendar-grid">
            {Array.from({ length: start.day() }, (_, index) => (
              <div key={`pad-${index}`} className="query-report__calendar-cell is-empty" />
            ))}
            {cells.map(({ date, day }) => {
              const tone = matrixDayTone(day);
              const iso = date.format('YYYY-MM-DD');
              return (
                <div
                  key={iso}
                  className={`query-report__calendar-cell${onAdjust ? ' is-editable' : ''}`}
                  style={tone.color ? { backgroundColor: tone.color } : undefined}
                  onClick={onAdjust ? () => onAdjust(day ?? {}, iso) : undefined}
                  onKeyDown={onAdjust ? (event) => {
                    if (event.key === 'Enter' || event.key === ' ') {
                      event.preventDefault();
                      onAdjust(day ?? {}, iso);
                    }
                  } : undefined}
                  role={onAdjust ? 'button' : undefined}
                  tabIndex={onAdjust ? 0 : undefined}
                >
                  <strong>{date.date()}</strong>
                  <span>{matrixSlotText(day, 'morning', iso)}</span>
                  <small>{matrixSlotText(day, 'afternoon', iso)}</small>
                  <em>{tone.label}</em>
                </div>
              );
            })}
          </div>
        </div>
      ) : (
        <div className="customer-report__table-scroll">
          <table className="customer-report__table">
            <thead>
              <tr>
                <th scope="col">日期</th>
                <th scope="col">星期</th>
                <th scope="col">日类型</th>
                <th scope="col">状态</th>
                <th scope="col">上班打卡</th>
                <th scope="col">下班打卡</th>
              </tr>
            </thead>
            <tbody>
              {cells.map(({ date, day }) => {
                const iso = date.format('YYYY-MM-DD');
                return (
                  <tr
                    key={iso}
                    className={onAdjust ? 'query-report__row-link' : undefined}
                    onClick={onAdjust ? () => onAdjust(day ?? {}, iso) : undefined}
                  >
                    <td>{date.format('M月D日')}</td>
                    <td>{weekdayLabel(date.day())}</td>
                    <td>{dayTypeLabel(String(day?.dayType ?? ''))}</td>
                    <td><strong>{matrixDayStatus(day)}</strong></td>
                    <td>{formatPunch(day?.firstPunchAt, iso)}</td>
                    <td>{formatPunch(day?.lastPunchAt, iso)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function matrixDayTone(day: Record<string, unknown> | undefined): { label: string; color?: string } {
  const status = matrixDayStatusKey(day);
  const label = matrixDayStatus(day);
  if (!status) return { label };
  const legend = attendanceLegend.find((item) => item.key === status);
  const badge = status.replace(/-/g, '_').toUpperCase();
  return {
    label,
    color: legend?.color ?? REPORT_BADGE_COLORS[badge] ?? REPORT_BADGE_COLORS[status.toUpperCase()],
  };
}

export function matrixDayStatusKey(day: Record<string, unknown> | undefined): AttendanceStatusKey | undefined {
  if (!day) return undefined;
  const tones = matrixDayTones(day);
  if (tones.some((tone) => tone.includes('OVERTIME') || tone === 'RECOGNIZED_OVERTIME')) {
    return 'overtime';
  }
  if (tones.some((tone) => tone.includes('OUTING') || tone === 'OUT')) return 'out';
  if (tones.some((tone) => tone.includes('TRIP') || tone.includes('TRAVEL'))) return 'trip';
  if (tones.some((tone) => tone.includes('ANNUAL'))) return 'annual-leave';
  if (tones.some((tone) => tone.includes('SICK'))) return 'sick-leave';
  if (tones.some((tone) => tone.includes('PERSONAL'))) return 'personal-leave';
  if (tones.some((tone) => tone.includes('MARRIAGE'))) return 'marriage-leave';
  if (tones.some((tone) => tone.includes('MATERNITY'))) return 'maternity-leave';
  if (tones.some((tone) => tone.includes('PATERNITY'))) return 'paternity-leave';
  if (tones.some((tone) => tone.includes('BEREAVEMENT'))) return 'bereavement-leave';
  if (tones.some((tone) => tone.includes('TIME_OFF') || tone.includes('COMPENSATORY'))) {
    return 'time-off';
  }
  if (tones.some((tone) => tone.includes('PUNCH_CORRECTION'))) return 'corrected';
  const leave = String(day.leaveType ?? '').toUpperCase();
  if (leave.includes('ANNUAL')) return 'annual-leave';
  if (leave.includes('SICK')) return 'sick-leave';
  if (leave.includes('PERSONAL')) return 'personal-leave';
  if (leave.includes('MARRIAGE')) return 'marriage-leave';
  if (leave.includes('MATERNITY')) return 'maternity-leave';
  if (leave.includes('PATERNITY')) return 'paternity-leave';
  if (leave.includes('BEREAVEMENT')) return 'bereavement-leave';
  if (leave === 'TIME_OFF' || leave.includes('COMPENSATORY')) return 'time-off';
  if (leave.includes('OUTING') || leave === 'OUT') return 'out';
  if (leave.includes('TRIP') || leave.includes('TRAVEL')) return 'trip';
  if (Number(day.lateMinutes ?? 0) > 0) return 'late';
  if (Number(day.earlyMinutes ?? 0) > 0) return 'early';
  if (Number(day.paidOvertimeMinutes ?? 0) > 0 || Number(day.compensatoryOvertimeMinutes ?? 0) > 0) {
    return 'overtime';
  }
  if (Number(day.missingPunches ?? 0) > 0
      || tones.some((tone) => tone.includes('MISSING_PUNCH'))) {
    return 'missed';
  }
  const type = dayTypeLabel(String(day.dayType ?? ''));
  if (type === '休息日' || type === '节假日') return 'rest-day';
  return undefined;
}

function matrixDayTones(day: Record<string, unknown>): string[] {
  const badges = Array.isArray(day.badges) ? day.badges : [];
  const morning = slotView(day.morning).tone;
  const afternoon = slotView(day.afternoon).tone;
  return [...badges, morning, afternoon]
    .filter((value): value is string => typeof value === 'string' && value.length > 0)
    .map((value) => value.toUpperCase());
}

function weekdayLabel(day: number): string {
  return ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'][day] ?? '';
}

function dayTypeLabel(value: string): string {
  if (!value) return '—';
  return ({
    WORKDAY: '工作日',
    WEEKDAY: '工作日',
    WORKING_DAY: '工作日',
    SCHEDULED: '工作日',
    REST: '休息日',
    REST_DAY: '休息日',
    SATURDAY: '休息日',
    SUNDAY: '休息日',
    PUBLIC_HOLIDAY: '节假日',
    HOLIDAY: '节假日',
  } as Record<string, string>)[value] ?? translateCode(value, 'matrix');
}

export function matrixDayStatus(day: Record<string, unknown> | undefined): string {
  if (!day) return '无记录';
  const morning = slotView(day.morning);
  const afternoon = slotView(day.afternoon);
  if (morning.text === '外出' || afternoon.text === '外出' || matrixDayTones(day).some((tone) => tone.includes('OUTING'))) {
    return '外出';
  }
  if (morning.text === '出差' || afternoon.text === '出差' || matrixDayTones(day).some((tone) => tone.includes('TRIP'))) {
    return '出差';
  }
  if (typeof day.leaveType === 'string' && day.leaveType) {
    return leaveTypeLabel(day.leaveType, 'leave');
  }
  if (Number(day.lateMinutes ?? 0) > 0) return `迟到 ${day.lateMinutes} 分钟`;
  if (Number(day.earlyMinutes ?? 0) > 0) return `早退 ${day.earlyMinutes} 分钟`;
  if (Number(day.missingPunches ?? 0) > 0
      || matrixDayTones(day).some((tone) => tone.includes('MISSING_PUNCH'))) {
    return '缺卡';
  }
  const type = dayTypeLabel(String(day.dayType ?? ''));
  if (type === '休息日' || type === '节假日') return type;
  return '正常';
}

function formatPunch(value: unknown, businessDate?: unknown): string {
  if (value == null || value === '') return '—';
  if (typeof value === 'string' && value.includes('次日')) {
    return value;
  }
  const timestamp = Date.parse(String(value));
  if (!Number.isFinite(timestamp)) return '—';
  const clock = new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone: 'Asia/Shanghai',
  }).format(timestamp);
  const shanghaiDate = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(timestamp);
  const day = String(businessDate ?? '').slice(0, 10);
  if (/^\d{4}-\d{2}-\d{2}$/.test(day) && shanghaiDate > day) {
    return `次日 ${clock}`;
  }
  return clock;
}

function FinanceOvertimeTable({
  fromDate,
  toDate,
  rows,
  loading,
  hint,
  overtimeTreatment,
}: {
  fromDate?: string;
  toDate?: string;
  rows: Array<Record<string, unknown>>;
  loading: boolean;
  hint?: string;
  overtimeTreatment?: string;
}) {
  const dates = fromDate && toDate ? financeOvertimeDates(fromDate, toDate) : [];
  const weekdayTotal = rows.reduce((sum, row) => sum + Number(row.weekdayOvertimeHours ?? 0), 0);
  const weekendTotal = rows.reduce((sum, row) => sum + Number(row.weekendOvertimeHours ?? 0), 0);
  const holidayTotal = rows.reduce((sum, row) => sum + Number(row.holidayOvertimeHours ?? 0), 0);
  const dayTotals = dates.map((date) => rows.reduce((sum, row) => (
    sum + (financeOvertimeHoursByDate(row.days).get(date) ?? 0)
  ), 0));
  const paidTotal = rows.reduce((sum, row) => sum + Number(row.paidOvertimeHours ?? 0), 0);
  const compensatoryTotal = rows.reduce((sum, row) => sum + Number(row.compensatoryOvertimeHours ?? 0), 0);
  const voluntaryTotal = rows.reduce((sum, row) => sum + Number(row.voluntaryOvertimeHours ?? 0), 0);
  const feeColumns = overtimeFeeColumns(overtimeTreatment);
  const colSpan = 6
    + (feeColumns.paid ? 1 : 0)
    + (feeColumns.compensatory ? 1 : 0)
    + (feeColumns.voluntary ? 1 : 0)
    + dates.length;
  return (
    <table className="customer-report__table customer-report__table--finance-overtime">
      <thead>
        <tr>
          <th scope="col" rowSpan={2}>部门</th>
          <th scope="col" rowSpan={2}>工号</th>
          <th scope="col" rowSpan={2}>加班人</th>
          <th scope="col" rowSpan={2}>平时加班</th>
          <th scope="col" rowSpan={2}>周末加班</th>
          <th scope="col" rowSpan={2}>节假日加班</th>
          {feeColumns.paid ? <th scope="col" rowSpan={2}>加班费</th> : null}
          {feeColumns.compensatory ? <th scope="col" rowSpan={2}>转调休</th> : null}
          {feeColumns.voluntary ? <th scope="col" rowSpan={2}>义务加班</th> : null}
          {dates.map((date) => (
            <th scope="col" key={date}>{financeOvertimeDateLabel(date)}</th>
          ))}
        </tr>
        <tr>
          {dates.map((date) => (
            <th scope="col" key={`${date}-wd`}>{financeOvertimeWeekdayNumber(date)}</th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.length === 0 ? (
          <tr>
            <td colSpan={Math.max(colSpan, 8)}>
              {loading ? '正在查询…' : (hint ?? '当前筛选条件下没有记录')}
            </td>
          </tr>
        ) : rows.map((row, index) => {
          const byDate = financeOvertimeHoursByDate(row.days);
          return (
            <tr key={String(row.employeeNumber ?? index)}>
              <td className="customer-report__dept" title={visibleDepartmentPath(String(row.department ?? ''))}>
                {departmentPathNodes(typeof row.department === 'string' ? row.department : undefined)}
              </td>
              <td>{String(row.employeeNumber ?? '')}</td>
              <td><strong>{String(row.employeeName ?? '')}</strong></td>
              <td className="customer-report__number">{formatFinanceHours(Number(row.weekdayOvertimeHours ?? 0))}</td>
              <td className="customer-report__number">{formatFinanceHours(Number(row.weekendOvertimeHours ?? 0))}</td>
              <td className="customer-report__number">{formatFinanceHours(Number(row.holidayOvertimeHours ?? 0))}</td>
              {feeColumns.paid ? (
                <td className="customer-report__number">{formatFinanceHours(Number(row.paidOvertimeHours ?? 0))}</td>
              ) : null}
              {feeColumns.compensatory ? (
                <td className="customer-report__number">{formatFinanceHours(Number(row.compensatoryOvertimeHours ?? 0))}</td>
              ) : null}
              {feeColumns.voluntary ? (
                <td className="customer-report__number">{formatFinanceHours(Number(row.voluntaryOvertimeHours ?? 0))}</td>
              ) : null}
              {dates.map((date) => {
                const hours = byDate.get(date) ?? 0;
                const day = financeOvertimeDay(row.days, date);
                const hoursByType = readOvertimeDayHours(day);
                return (
                  <td
                    key={date}
                    className="customer-report__number"
                    style={hours > 0 ? overtimeCellFill(hoursByType, overtimeTreatment) : undefined}
                    title={overtimeTreatmentHover(hoursByType) || undefined}
                  >
                    {formatFinanceHours(hours, true)}
                  </td>
                );
              })}
            </tr>
          );
        })}
      </tbody>
      {rows.length > 0 ? (
        <tfoot>
          <tr>
            <td>总计</td>
            <td />
            <td />
            <td className="customer-report__number">{formatFinanceHours(weekdayTotal)}</td>
            <td className="customer-report__number">{formatFinanceHours(weekendTotal)}</td>
            <td className="customer-report__number">{formatFinanceHours(holidayTotal)}</td>
            {feeColumns.paid ? (
              <td className="customer-report__number">{formatFinanceHours(paidTotal)}</td>
            ) : null}
            {feeColumns.compensatory ? (
              <td className="customer-report__number">{formatFinanceHours(compensatoryTotal)}</td>
            ) : null}
            {feeColumns.voluntary ? (
              <td className="customer-report__number">{formatFinanceHours(voluntaryTotal)}</td>
            ) : null}
            {dates.map((date, index) => (
              <td key={date} className="customer-report__number">
                {formatFinanceHours(dayTotals[index] ?? 0, true)}
              </td>
            ))}
          </tr>
        </tfoot>
      ) : null}
    </table>
  );
}

function MetricStatTable({
  fromDate,
  toDate,
  rows,
  loading,
  hint,
  totalKey,
  totalLabel,
  colorize,
}: {
  fromDate?: string;
  toDate?: string;
  rows: Array<Record<string, unknown>>;
  loading: boolean;
  hint?: string;
  totalKey: 'absenceHours' | 'leaveHours';
  totalLabel: string;
  colorize: boolean;
}) {
  const dates = fromDate && toDate ? financeOvertimeDates(fromDate, toDate) : [];
  const totalHours = rows.reduce((sum, row) => sum + Number(row[totalKey] ?? 0), 0);
  const dayTotals = dates.map((date) => rows.reduce((sum, row) => (
    sum + (metricHoursByDate(row.days).get(date) ?? 0)
  ), 0));
  const colSpan = 4 + dates.length;
  return (
    <table className="customer-report__table customer-report__table--finance-overtime">
      <thead>
        <tr>
          <th scope="col" rowSpan={2}>部门</th>
          <th scope="col" rowSpan={2}>工号</th>
          <th scope="col" rowSpan={2}>姓名</th>
          <th scope="col" rowSpan={2}>{totalLabel}</th>
          {dates.map((date) => (
            <th scope="col" key={`metric-date-${date}`}>{financeOvertimeDateLabel(date)}</th>
          ))}
        </tr>
        <tr>
          {dates.map((date) => (
            <th scope="col" key={`metric-wd-${date}`}>{financeOvertimeWeekdayNumber(date)}</th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.length === 0 ? (
          <tr>
            <td colSpan={Math.max(colSpan, 5)}>
              {loading ? '正在查询…' : (hint ?? '当前筛选条件下没有记录')}
            </td>
          </tr>
        ) : rows.map((row, index) => {
          const byDate = metricHoursByDate(row.days);
          return (
            <tr key={String(row.employeeNumber ?? index)}>
              <td className="customer-report__dept" title={visibleDepartmentPath(String(row.department ?? ''))}>
                {departmentPathNodes(typeof row.department === 'string' ? row.department : undefined)}
              </td>
              <td>{String(row.employeeNumber ?? '')}</td>
              <td><strong>{String(row.employeeName ?? '')}</strong></td>
              <td className="customer-report__number">{formatFinanceHours(Number(row[totalKey] ?? 0))}</td>
              {dates.map((date) => {
                const hours = byDate.get(date) ?? 0;
                const day = financeOvertimeDay(row.days, date);
                return (
                  <td
                    key={date}
                    className="customer-report__number"
                    style={colorize ? leaveCellFill(day?.dayType) : undefined}
                  >
                    {formatFinanceHours(hours, true)}
                  </td>
                );
              })}
            </tr>
          );
        })}
      </tbody>
      {rows.length > 0 ? (
        <tfoot>
          <tr>
            <td>总计</td>
            <td />
            <td />
            <td className="customer-report__number">{formatFinanceHours(totalHours)}</td>
            {dates.map((date, index) => (
              <td key={date} className="customer-report__number">
                {formatFinanceHours(dayTotals[index] ?? 0, true)}
              </td>
            ))}
          </tr>
        </tfoot>
      ) : null}
    </table>
  );
}

function metricHoursByDate(days: unknown): Map<string, number> {
  const byDate = new Map<string, number>();
  if (!Array.isArray(days)) {
    return byDate;
  }
  for (const item of days) {
    if (item == null || typeof item !== 'object') {
      continue;
    }
    const record = item as Record<string, unknown>;
    const date = String(record.date ?? '').slice(0, 10);
    if (!date) {
      continue;
    }
    byDate.set(date, (byDate.get(date) ?? 0) + Number(record.hours ?? 0));
  }
  return byDate;
}

function leaveCellFill(dayType: unknown): CSSProperties | undefined {
  const hex = leaveToneHex(String(dayType ?? ''));
  return hex ? { background: hex, color: '#24344D' } : undefined;
}

function leaveToneHex(raw: string): string | undefined {
  const key = raw.toUpperCase();
  if (key.includes('ANNUAL')) return REPORT_BADGE_COLORS.ANNUAL_LEAVE;
  if (key.includes('SICK')) return REPORT_BADGE_COLORS.SICK_LEAVE;
  if (key.includes('PERSONAL') || key.includes('事假')) return REPORT_BADGE_COLORS.PERSONAL_LEAVE;
  if (key.includes('MARRIAGE') || key.includes('婚')) return REPORT_BADGE_COLORS.MARRIAGE_LEAVE;
  if (key.includes('MATERNITY') || key.includes('产假')) return REPORT_BADGE_COLORS.MATERNITY_LEAVE;
  if (key.includes('PATERNITY') || key.includes('陪产')) return REPORT_BADGE_COLORS.PATERNITY_LEAVE;
  if (key.includes('BEREAVEMENT') || key.includes('丧')) return REPORT_BADGE_COLORS.BEREAVEMENT_LEAVE;
  if (key.includes('TIME_OFF') || key.includes('调休') || key.includes('COMPENSATORY')) {
    return REPORT_BADGE_COLORS.TIME_OFF;
  }
  if (key.includes('WORK_INJURY') || key.includes('工伤')) return REPORT_BADGE_COLORS.WORK_INJURY_LEAVE;
  if (key.includes('BREASTFEEDING') || key.includes('哺乳')) return REPORT_BADGE_COLORS.BREASTFEEDING_LEAVE;
  return undefined;
}

function excelColumnLetter(index: number): string {
  let remaining = index + 1;
  let letters = '';
  while (remaining > 0) {
    const modulo = (remaining - 1) % 26;
    letters = String.fromCharCode(65 + modulo) + letters;
    remaining = Math.floor((remaining - 1) / 26);
  }
  return letters;
}

function financeOvertimeDay(days: unknown, date: string): Record<string, unknown> | undefined {
  if (!Array.isArray(days)) return undefined;
  const match = days.find((item) => {
    if (item == null || typeof item !== 'object') return false;
    return String((item as Record<string, unknown>).date ?? '').slice(0, 10) === date;
  }) as Record<string, unknown> | undefined;
  return match;
}

function findQueryCompanyNode(
  nodes: readonly OrganizationNode[],
  companyId: string | undefined,
  companyName: string | undefined,
): OrganizationNode | undefined {
  for (const node of nodes) {
    if (
      (companyId && node.organizationId === companyId)
      || (companyName && node.name === companyName)
    ) {
      return node;
    }
    const nested = findQueryCompanyNode(node.children ?? [], companyId, companyName);
    if (nested) {
      return nested;
    }
  }
  return undefined;
}

function collectQueryDescendantOrganizationIds(
  nodes: readonly OrganizationNode[],
  selectedId: string | undefined,
): Set<string> {
  const ids = new Set<string>();
  if (selectedId === undefined || selectedId === '') return ids;
  const walk = (current: readonly OrganizationNode[], capturing: boolean) => {
    current.forEach((node) => {
      const include = capturing || node.organizationId === selectedId;
      if (include) ids.add(node.organizationId);
      if (node.children.length > 0) walk(node.children, include);
    });
  };
  walk(nodes, false);
  if (ids.size === 0) ids.add(selectedId);
  return ids;
}

function FilterField({
  label,
  children,
}: {
  label: string;
  children: ReactNode;
}) {
  return (
    <label className="query-report__field">
      <span>{label}</span>
      {children}
    </label>
  );
}

function sheetFilterKeys(sheet: SheetKey): Set<string> {
  switch (sheet) {
    case 'exceptions':
      return new Set(['exceptionType', 'severity', 'state']);
    case 'leave':
      return new Set(['leaveType', 'approvalState']);
    case 'leave-summary':
      return new Set(['leaveType']);
    case 'overtime':
      return new Set(['overtimeTreatment', 'occurrenceDay']);
    case 'overtime-daily':
      return new Set(['overtimeTreatment']);
    case 'finance-overtime':
      return new Set(['overtimeTreatment', 'attendanceType', 'occurrenceDay', 'rateBelow']);
    case 'overtime-fee-daily':
    case 'overtime-voluntary-daily':
    case 'overtime-comp-daily':
      return new Set(['attendanceType', 'occurrenceDay', 'rateBelow']);
    case 'late':
      return new Set(['lateCountBand', 'lateMinuteBand']);
    case 'missed-punch':
      return new Set(['punchSide']);
    case 'missed-punch-stat':
      return new Set(['exceptionType', 'punchSide']);
    case 'annual-leave-stat':
    case 'time-off-stat':
      return new Set(['annualBalanceBand', 'annualLevelOne', 'annualLevelTwo']);
    case 'work-hours':
      return new Set(['employmentStatus']);
    case 'attendance-rate':
      return new Set(['attendanceType', 'rateBelow']);
    case 'annual-leave':
    case 'time-off':
      return new Set(['annualBalanceBand', 'annualLevelOne', 'annualLevelTwo']);
    case 'matrix':
      return new Set(['attendanceStatus']);
    default:
      return new Set();
  }
}

function sheetFilterHeading(sheet: SheetKey): string {
  switch (sheet) {
    case 'exceptions':
      return '异常细筛';
    case 'leave':
      return '请假细筛';
    case 'overtime':
      return '加班细筛';
    case 'overtime-daily':
      return '加班日报细筛';
    case 'finance-overtime':
      return '每日加班细筛';
    case 'overtime-fee-daily':
      return '每日加班费细筛';
    case 'overtime-voluntary-daily':
      return '每日义务加班细筛';
    case 'overtime-comp-daily':
      return '每日调休细筛';
    case 'time-off-daily':
      return '调休日报细筛';
    case 'makeup':
      return '补签细筛';
    case 'work-hours':
      return '工时细筛';
    case 'late':
      return '迟到细筛';
    case 'missed-punch':
      return '缺卡细筛';
    case 'missed-punch-stat':
      return '忘打卡统计细筛';
    case 'annual-leave-stat':
      return '年假统计细筛';
    case 'time-off-stat':
      return '调休统计细筛';
    case 'attendance-rate':
      return '出勤率细筛';
    case 'annual-leave':
      return '年假细筛';
    case 'time-off':
      return '调休细筛';
    case 'matrix':
      return '明细细筛';
    default:
      return '本页细筛';
  }
}

function SheetFilters({
  sheet,
  filters,
  onChange,
}: {
  sheet: SheetKey;
  filters: Record<string, string>;
  onChange: (filters: Record<string, string>) => void;
}) {
  const set = (key: string, value: string) => onChange({ ...filters, [key]: value });
  if (sheet === 'exceptions') {
    return (
      <>
        <FilterField label="异常类型">
          <Select
            allowClear
            placeholder="全部类型"
            value={filters.exceptionType || undefined}
            options={[...EXCEPTION_TYPE_FILTER_OPTIONS]}
            onChange={(value) => set('exceptionType', value ?? '')}
            popupMatchSelectWidth={false}
          />
        </FilterField>
        <FilterField label="级别">
          <Select
            allowClear
            placeholder="全部级别"
            value={filters.severity || undefined}
            options={[
              { value: 'INFO', label: '提示' },
              { value: 'WARNING', label: '警告' },
              { value: 'ERROR', label: '错误' },
            ]}
            onChange={(value) => set('severity', value ?? '')}
            popupMatchSelectWidth={false}
          />
        </FilterField>
        <FilterField label="状态">
          <Select
            allowClear
            placeholder="全部状态"
            value={filters.state || undefined}
            options={[
              { value: 'OPEN', label: '待处理' },
              { value: 'PENDING_EVIDENCE', label: '待补充凭证' },
              { value: 'PENDING_REVIEW', label: '待复核' },
              { value: 'RESOLVED', label: '已处理' },
            ]}
            onChange={(value) => set('state', value ?? '')}
            popupMatchSelectWidth={false}
          />
        </FilterField>
      </>
    );
  }
  if (sheet === 'leave') {
    return (
      <>
        <FilterField label="假别">
          <Select
            allowClear
            placeholder="全部假别"
            value={filters.leaveType || undefined}
            options={[
              { value: 'PERSONAL', label: '事假' },
              { value: 'SICK', label: '病假' },
              { value: 'ANNUAL', label: '年假' },
              { value: 'COMPENSATORY', label: '调休' },
              { value: 'MARRIAGE', label: '婚假' },
              { value: 'BEREAVEMENT', label: '丧假' },
              { value: 'PATERNITY', label: '陪产假' },
              { value: 'MATERNITY', label: '产假' },
            ]}
            onChange={(value) => set('leaveType', value ?? '')}
            popupMatchSelectWidth={false}
          />
        </FilterField>
        <FilterField label="审批状态">
          <Select
            allowClear
            placeholder="全部状态"
            value={filters.approvalState || undefined}
            options={[
              { value: 'APPROVED', label: '已批准' },
              { value: 'PENDING', label: '审批中' },
              { value: 'REJECTED', label: '已驳回' },
            ]}
            onChange={(value) => set('approvalState', value ?? '')}
            popupMatchSelectWidth={false}
          />
        </FilterField>
      </>
    );
  }
  if (sheet === 'overtime') {
    return (
      <>
        <FilterField label="加班性质">
          <Select
            allowClear
            placeholder="全部性质"
            value={filters.overtimeTreatment || undefined}
            options={['计薪加班', '转调休加班', '义务加班'].map((value) => ({ value, label: value }))}
            onChange={(value) => set('overtimeTreatment', value ?? '')}
            popupMatchSelectWidth={false}
          />
        </FilterField>
        <FilterField label="本月哪一天">
          <Select
            allowClear
            placeholder="全部日期"
            value={filters.occurrenceDay || undefined}
            options={Array.from({ length: 31 }, (_, index) => ({
              value: String(index + 1),
              label: `${index + 1}日`,
            }))}
            onChange={(value) => set('occurrenceDay', value ?? '')}
            popupMatchSelectWidth={false}
          />
        </FilterField>
      </>
    );
  }
  if (sheet === 'overtime-daily') {
    return (
      <FilterField label="加班类别">
        <Select
          allowClear
          placeholder="全部类别"
          value={filters.overtimeTreatment || undefined}
          options={[
            { value: '加班费', label: '加班费' },
            { value: '转调休', label: '转调休' },
            { value: '义务加班', label: '义务加班' },
          ]}
          onChange={(value) => set('overtimeTreatment', value ?? '')}
          popupMatchSelectWidth={false}
        />
      </FilterField>
    );
  }
  if (isFinanceOvertimeCalendarSheet(sheet)) {
    return (
      <>
        <FilterField label="日历类型">
          <Select
            allowClear
            placeholder="全部类型"
            value={filters.attendanceType || undefined}
            options={[
              { value: '平时加班', label: '平时加班' },
              { value: '周末加班', label: '周末加班' },
              { value: '节假日加班', label: '节假日加班' },
            ]}
            onChange={(value) => set('attendanceType', value ?? '')}
            popupMatchSelectWidth={false}
          />
        </FilterField>
        {sheet === 'finance-overtime' ? (
        <FilterField label="加班类别">
          <Select
            allowClear
            placeholder="全部类别"
            value={filters.overtimeTreatment || undefined}
            options={[
              { value: '加班费', label: '加班费' },
              { value: '转调休', label: '转调休' },
              { value: '义务加班', label: '义务加班' },
            ]}
            onChange={(value) => set('overtimeTreatment', value ?? '')}
            popupMatchSelectWidth={false}
          />
        </FilterField>
        ) : null}
        <FilterField label="本月哪一天">
          <Select
            allowClear
            placeholder="全部日期"
            value={filters.occurrenceDay || undefined}
            options={Array.from({ length: 31 }, (_, index) => ({
              value: String(index + 1),
              label: `${index + 1}日`,
            }))}
            onChange={(value) => set('occurrenceDay', value ?? '')}
            popupMatchSelectWidth={false}
          />
        </FilterField>
        <FilterField label="最低小时">
          <Select
            allowClear
            placeholder="不限"
            value={filters.rateBelow || undefined}
            options={[
              { value: '1', label: '满 1 小时' },
              { value: '2', label: '满 2 小时' },
              { value: '4', label: '满 4 小时' },
              { value: '8', label: '满 8 小时' },
            ]}
            onChange={(value) => set('rateBelow', value ?? '')}
            popupMatchSelectWidth={false}
          />
        </FilterField>
      </>
    );
  }
  if (sheet === 'late') {
    return (
      <>
        <FilterField label="次数档">
          <Select
            allowClear
            placeholder="全部次数"
            value={filters.lateCountBand || undefined}
            options={['2次及以上', '3次及以上'].map((value) => ({ value, label: value }))}
            onChange={(value) => set('lateCountBand', value ?? '')}
            popupMatchSelectWidth={false}
          />
        </FilterField>
        <FilterField label="分钟档">
          <Select
            allowClear
            placeholder="全部分钟"
            value={filters.lateMinuteBand || undefined}
            options={['10分钟以内', '11-30分钟', '30分钟以上'].map((value) => ({ value, label: value }))}
            onChange={(value) => set('lateMinuteBand', value ?? '')}
            popupMatchSelectWidth={false}
          />
        </FilterField>
      </>
    );
  }
  if (sheet === 'missed-punch') {
    return (
      <FilterField label="缺卡时段">
        <Select
          allowClear
          placeholder="全部时段"
          value={filters.punchSide || undefined}
          options={['上班缺卡', '下班缺卡'].map((value) => ({ value, label: value }))}
          onChange={(value) => set('punchSide', value ?? '')}
          popupMatchSelectWidth={false}
        />
      </FilterField>
    );
  }
  if (sheet === 'missed-punch-stat') {
    return (
      <>
        <FilterField label="类型">
          <Select
            allowClear
            placeholder="漏刷或补签"
            value={filters.exceptionType || undefined}
            options={[
              { value: 'MISSING_PUNCH', label: '漏刷' },
              { value: 'PUNCH_CORRECTION', label: '已补签' },
            ]}
            onChange={(value) => set('exceptionType', value ?? '')}
            popupMatchSelectWidth={false}
          />
        </FilterField>
        <FilterField label="时段">
          <Select
            allowClear
            placeholder="全部时段"
            value={filters.punchSide || undefined}
            options={[
              { value: 'MORNING', label: '上班漏' },
              { value: 'AFTERNOON', label: '下班漏' },
            ]}
            onChange={(value) => set('punchSide', value ?? '')}
            popupMatchSelectWidth={false}
          />
        </FilterField>
      </>
    );
  }
  if (sheet === 'work-hours') {
    return (
      <FilterField label="在职状态">
        <Select
          allowClear
          placeholder="全部状态"
          value={filters.employmentStatus || undefined}
          options={['在职', '本月入职', '本月离职'].map((value) => ({ value, label: value }))}
          onChange={(value) => set('employmentStatus', value ?? '')}
          popupMatchSelectWidth={false}
        />
      </FilterField>
    );
  }
  if (sheet === 'attendance-rate') {
    return (
      <>
        <FilterField label="出勤类型">
          <Select
            allowClear
            placeholder="全部类型"
            value={filters.attendanceType || undefined}
            options={[
              { value: 'ANNUAL', label: '年假' },
              { value: 'SICK', label: '病假' },
              { value: 'PERSONAL', label: '事假' },
              { value: 'COMPENSATORY', label: '调休' },
            ]}
            onChange={(value) => set('attendanceType', value ?? '')}
            popupMatchSelectWidth={false}
          />
        </FilterField>
        <FilterField label="低于比例（%）">
          <Input
            placeholder="例如 95"
            value={filters.rateBelow ?? ''}
            onChange={(event) => set('rateBelow', event.target.value)}
          />
        </FilterField>
      </>
    );
  }
  if (sheet === 'annual-leave' || sheet === 'time-off' || sheet === 'annual-leave-stat' || sheet === 'time-off-stat') {
    return (
      <>
        <FilterField label="余额">
          <Select
            allowClear
            placeholder="全部余额"
            value={filters.annualBalanceBand || undefined}
            options={['有余额', '余额不足2天', '已用完'].map((value) => ({ value, label: value }))}
            onChange={(value) => set('annualBalanceBand', value ?? '')}
            popupMatchSelectWidth={false}
          />
        </FilterField>
        <FilterField label="一级部门">
          <Input
            placeholder="可选"
            value={filters.annualLevelOne ?? ''}
            onChange={(event) => set('annualLevelOne', event.target.value)}
          />
        </FilterField>
        <FilterField label="二级部门">
          <Input
            placeholder="可选"
            value={filters.annualLevelTwo ?? ''}
            onChange={(event) => set('annualLevelTwo', event.target.value)}
          />
        </FilterField>
      </>
    );
  }
  if (sheet === 'matrix') {
    return (
      <>
        <FilterField label="考勤状态">
          <Select
            allowClear
            placeholder="全部状态"
            value={filters.attendanceStatus || undefined}
            options={[
              { value: 'late', label: '迟到' },
              { value: 'early', label: '早退' },
              { value: 'missed', label: '缺卡' },
              { value: 'annual-leave', label: '年假' },
              { value: 'rest-day', label: '休息日' },
            ]}
            onChange={(value) => set('attendanceStatus', value ?? '')}
            popupMatchSelectWidth={false}
          />
        </FilterField>
      </>
    );
  }
  return <p className="query-report__empty-filters">本页没有额外细筛，按范围和期间查询即可。</p>;
}

function missedPunchSummary(
  days: Array<{ date: string; morning: { tone: string | null }; afternoon: { tone: string | null } }>,
): { missedCount: number; remark: string } {
  const misses: string[] = [];
  const makeups: string[] = [];
  days.forEach((day) => {
    const date = dayjs(day.date);
    if (!date.isValid()) return;
    const label = (side: string) => `${date.month() + 1}月${date.date()}日（${side}）`;
    if (day.morning.tone === 'MISSING_PUNCH') misses.push(label('上班'));
    if (day.afternoon.tone === 'MISSING_PUNCH') misses.push(label('下班'));
    if (day.morning.tone === 'PUNCH_CORRECTION') makeups.push(label('上班补签'));
    if (day.afternoon.tone === 'PUNCH_CORRECTION') makeups.push(label('下班补签'));
  });
  const parts = misses.length > 0 ? misses : makeups;
  return { missedCount: parts.length, remark: parts.join(' ') };
}

function slotView(raw: unknown): { text: string; tone?: string } {
  if (raw == null || typeof raw !== 'object') return { text: '' };
  const slot = raw as Record<string, unknown>;
  return {
    text: String(slot.text ?? ''),
    tone: slot.tone == null ? undefined : String(slot.tone),
  };
}

function matrixSlotText(
  day: Record<string, unknown> | undefined,
  side: 'morning' | 'afternoon',
  iso: string,
): string {
  const slot = slotView(day?.[side]);
  if (slot.text && slot.text !== '漏刷') {
    return slot.text;
  }
  return formatPunch(side === 'morning' ? day?.firstPunchAt : day?.lastPunchAt, iso);
}

function SlotChip({ slot }: { slot: { text: string; tone?: string } }) {
  const miss = slot.tone === 'MISSING_PUNCH';
  const makeup = slot.tone === 'PUNCH_CORRECTION';
  const fill = slotToneFill(slot.tone);
  return (
    <span
      className={`query-report__slot${miss ? ' is-miss' : ''}${makeup ? ' is-makeup' : ''}`}
      style={makeup || miss ? undefined : fill}
    >
      {slot.text || ' '}
    </span>
  );
}

function slotToneFill(tone?: string): CSSProperties | undefined {
  if (!tone) return undefined;
  const color = REPORT_BADGE_COLORS[tone];
  if (!color) return undefined;
  const dark = new Set([
    'SICK_LEAVE',
    'ANNUAL_LEAVE',
    'BEREAVEMENT_LEAVE',
    'MISSING_PUNCH',
  ]);
  return {
    backgroundColor: color,
    color: dark.has(tone) ? '#fff' : '#24344D',
  };
}

function LeaveStatTable({
  sheet,
  rows,
  loading,
  hint,
  onOpen,
}: {
  sheet: SheetKey;
  rows: Array<Record<string, unknown>>;
  loading: boolean;
  hint?: string;
  onOpen: (index: number) => void;
}) {
  const columns = sheetColumns(sheet);
  return (
    <table className="customer-report__table customer-report__table--leave-stat">
      <thead>
        <tr>
          {columns.map((column) => (
            <th scope="col" key={column.key}>{column.title}</th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.length === 0 ? (
          <tr>
            <td colSpan={columns.length}>{loading ? '正在查询…' : (hint ?? '当前筛选条件下没有记录')}</td>
          </tr>
        ) : rows.map((row, index) => (
          <tr
            key={index}
            className="query-report__row-link"
            onClick={() => onOpen(index)}
          >
            {columns.map((column) => (
              <td key={column.key}>
                {column.key === 'employeeName'
                  ? <strong>{formatCell(sheet, column.key, row[column.key])}</strong>
                  : formatCell(sheet, column.key, row[column.key])}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function LeaveStatDrawer({
  sheet,
  row,
  year,
  account,
  error,
  canAdjust,
  adjusting,
  adjustmentHours,
  reason,
  onHoursChange,
  onReasonChange,
  onSave,
}: {
  sheet: SheetKey;
  row: Record<string, unknown>;
  year: number;
  account: AnnualLeaveAccount | null;
  error: string | null;
  canAdjust: boolean;
  adjusting: boolean;
  adjustmentHours: number | null;
  reason: string;
  onHoursChange: (value: number | null) => void;
  onReasonChange: (value: string) => void;
  onSave: () => void;
}) {
  const kindLabel = sheet === 'time-off-stat' ? '调休' : '年假';
  return (
    <div className="query-report__leave-drawer">
      <p className="query-report__month-meta">
        {String(row.employeeNumber ?? '—')}
        {' · '}
        {String(row.employeeName ?? '—')}
        {' · '}
        {year}
        年
      </p>
      <p className="query-report__hint">可休以详情为准，列表随下次核算更新。</p>
      {error ? <p className="customer-report__load-error">{error}</p> : null}
      <dl className="query-report__drawer">
        <div>
          <dt>活账户可休小时</dt>
          <dd>{account ? formatHourValue(account.balanceHours) : (error ? '—' : '读取中…')}</dd>
        </div>
        <div>
          <dt>活账户可休天数</dt>
          <dd>{account ? formatHourValue(account.equivalentDays) : '—'}</dd>
        </div>
      </dl>
      {canAdjust ? (
        <div className="query-report__group-fields">
          <FilterField label="调整小时（正增负减）">
            <InputNumber
              value={adjustmentHours}
              onChange={(value) => onHoursChange(typeof value === 'number' ? value : null)}
              step={0.5}
              style={{ width: '100%' }}
            />
          </FilterField>
          <FilterField label="原因">
            <Input
              value={reason}
              onChange={(event) => onReasonChange(event.target.value)}
              maxLength={500}
              placeholder={`必填，例如补发${kindLabel}额度`}
            />
          </FilterField>
          <Button type="primary" loading={adjusting} disabled={adjusting} onClick={onSave}>
            保存调整
          </Button>
        </div>
      ) : (
        <p className="query-report__hint">当前账号没有调整额度权限，仅可查看。</p>
      )}
      <h3>流水</h3>
      <table className="customer-report__table">
        <thead>
          <tr>
            <th scope="col">时间</th>
            <th scope="col">类型</th>
            <th scope="col">小时</th>
            <th scope="col">来源</th>
          </tr>
        </thead>
        <tbody>
          {(account?.entries ?? []).length === 0 ? (
            <tr><td colSpan={4}>{account ? '暂无流水' : '—'}</td></tr>
          ) : account?.entries.map((entry) => (
            <tr key={entry.entryId}>
              <td>{formatDateTime(entry.occurredAt)}</td>
              <td>{entry.entryTypeLabel}</td>
              <td>{formatHourValue(entry.amountHours)}</td>
              <td>{entry.sourceType}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function MissedPunchMonthView({
  month,
  row,
  view,
}: {
  month: string;
  row: Record<string, unknown>;
  view: 'calendar' | 'list';
}) {
  const start = dayjs(`${month}-01`);
  const daysInMonth = start.daysInMonth();
  const rawDays = Array.isArray(row.days) ? row.days as Array<Record<string, unknown>> : [];
  const byDate = new Map(rawDays.map((day) => [String(day.date ?? '').slice(0, 10), day]));
  const cells = Array.from({ length: daysInMonth }, (_, index) => {
    const date = start.date(index + 1);
    return { date, day: byDate.get(date.format('YYYY-MM-DD')) };
  });
  return (
    <div>
      <p className="query-report__month-meta">
        {String(row.employeeNumber ?? '—')}
        {' · '}
        {String(row.employeeName ?? '—')}
        {' · '}
        {visibleDepartmentPath(typeof row.department === 'string' ? row.department : undefined) || '—'}
      </p>
      <div className="customer-report__legend" aria-label="忘打卡图例">
        <span><i style={{ backgroundColor: '#be6cbb' }} aria-hidden="true" />漏刷</span>
        <span><i style={{ backgroundColor: '#fff', border: '1px solid #c41c1c' }} aria-hidden="true" />补签（红字）</span>
      </div>
      {view === 'calendar' ? (
        <div className="query-report__calendar">
          <div className="query-report__calendar-weekdays">
            {['日', '一', '二', '三', '四', '五', '六'].map((label) => (
              <span key={label}>{label}</span>
            ))}
          </div>
          <div className="query-report__calendar-grid">
            {Array.from({ length: start.day() }, (_, index) => (
              <div key={`pad-${index}`} className="query-report__calendar-cell is-empty" />
            ))}
            {cells.map(({ date, day }) => {
              const iso = date.format('YYYY-MM-DD');
              return (
                <div key={iso} className="query-report__calendar-cell">
                  <strong>{date.date()}</strong>
                  <SlotChip slot={slotView(day?.morning)} />
                  <SlotChip slot={slotView(day?.afternoon)} />
                </div>
              );
            })}
          </div>
        </div>
      ) : (
        <div className="customer-report__table-scroll">
          <table className="customer-report__table">
            <thead>
              <tr>
                <th scope="col">日期</th>
                <th scope="col">上班</th>
                <th scope="col">下班</th>
              </tr>
            </thead>
            <tbody>
              {cells.map(({ date, day }) => {
                const iso = date.format('YYYY-MM-DD');
                return (
                  <tr key={iso}>
                    <td>{date.format('M月D日')}</td>
                    <td><SlotChip slot={slotView(day?.morning)} /></td>
                    <td><SlotChip slot={slotView(day?.afternoon)} /></td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

export default QueryReportsPage;
