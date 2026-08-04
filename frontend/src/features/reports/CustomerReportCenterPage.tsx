import {
  IconDownload,
  IconInfoCircle,
  IconShieldCheck,
} from '@tabler/icons-react';
import { Button, Select, Tooltip } from 'antd';
import type { ReactNode } from 'react';
import { useMemo, useState } from 'react';

import {
  applyCustomerReportSpecificFilters,
  annualLevelOneOptions,
  annualLevelTwoOptions,
  attendanceLegend,
  customerReportTabs,
  defaultCustomerReportSpecificFilters,
  getCustomerReportDemo,
  normalizeAnnualLeaveFilters,
  reportFilterOptions,
  reportSpecificFilterOptions,
  type AnnualLeaveReportRow,
  type AttendanceExceptionReportRow,
  type AttendanceDetailRow,
  type AttendanceStatusKey,
  type CustomerReportDemo,
  type CustomerReportFilters,
  type CustomerReportKey,
  type CustomerReportSpecificFilters,
  type ExceptionReportRow,
} from './customerReportDemo';
import {
  authorizedDepartmentOptions,
  authorizedEmployeeOptions,
  customerReportDemoScopes,
  defaultCustomerReportDataScope,
  scopeTypeLabel,
  type CustomerReportDataScope,
} from './customerReportAccess';
import {
  buildCustomerReportCsv,
  downloadCustomerReportCsv,
} from './customerReportExport';
import './customerReports.css';

export interface CustomerReportExportRequest {
  reportKey: CustomerReportKey;
  reportTitle: string;
  month: string;
  department: string;
  employee: string;
  rowCount: number;
  generatedAt: string;
  reportFilters: Record<string, string>;
  dataScopeReference: string;
  dataScopeLabel: string;
}

export function CustomerReportCenterPage({
  capabilities,
  dataScopes = customerReportDemoScopes,
  onExport,
}: {
  capabilities?: readonly string[];
  dataScopes?: readonly CustomerReportDataScope[];
  onExport?: (request: CustomerReportExportRequest) => void;
}) {
  const availableDataScopes = dataScopes.length > 0
    ? dataScopes
    : [defaultCustomerReportDataScope];
  const [activeScopeReference, setActiveScopeReference] = useState(
    availableDataScopes[0]!.reference,
  );
  const activeDataScope = availableDataScopes.find(
    (scope) => scope.reference === activeScopeReference,
  ) ?? availableDataScopes[0]!;
  const [filters, setFilters] = useState<CustomerReportFilters>({
    month: '2026-06',
    department: '全部部门',
    employee: '全部员工',
  });
  const [activeReport, setActiveReport] = useState<CustomerReportKey>('attendance-detail');
  const [exportFeedback, setExportFeedback] = useState('');
  const [specificFeedback, setSpecificFeedback] = useState('');
  const [draftSpecificFilters, setDraftSpecificFilters] = useState<CustomerReportSpecificFilters>({
    ...defaultCustomerReportSpecificFilters,
  });
  const [appliedSpecificFilters, setAppliedSpecificFilters] = useState<CustomerReportSpecificFilters>({
    ...defaultCustomerReportSpecificFilters,
  });
  const sourceReport = useMemo(
    () => getCustomerReportDemo(filters, activeDataScope),
    [activeDataScope, filters],
  );
  const report = useMemo(() => applyCustomerReportSpecificFilters(
    sourceReport,
    activeReport,
    appliedSpecificFilters,
  ), [activeReport, appliedSpecificFilters, sourceReport]);
  const activeTab = customerReportTabs.find((tab) => tab.key === activeReport)!;
  const canExport = capabilities === undefined
    || capabilities.includes('ATTENDANCE_REPORT:EXPORT_CREATE');
  const capabilityMode = capabilities === undefined
    ? '独立演示'
    : canExport
      ? '授权导出'
      : '无导出权限';
  const departmentOptions = authorizedDepartmentOptions(activeDataScope);
  const employeeOptions = authorizedEmployeeOptions(
    activeDataScope,
    filters.department,
  );

  const changeFilter = <K extends keyof CustomerReportFilters>(
    key: K,
    value: CustomerReportFilters[K],
  ) => {
    setExportFeedback('');
    setSpecificFeedback('');
    if (key === 'department') {
      setDraftSpecificFilters((specificFilters) => (
        normalizeAnnualLeaveFilters(
          specificFilters,
          value,
          activeDataScope.allowedDepartments,
        )
      ));
      setAppliedSpecificFilters((specificFilters) => (
        normalizeAnnualLeaveFilters(
          specificFilters,
          value,
          activeDataScope.allowedDepartments,
        )
      ));
    }
    setFilters((current) => {
      if (key === 'department') {
        return { ...current, department: value, employee: '全部员工' };
      }
      return { ...current, [key]: value };
    });
  };

  const handleExport = () => {
    if (!canExport) {
      setSpecificFeedback('');
      setExportFeedback('当前角色只有报表查看权限，不能创建导出任务。');
      return;
    }
    const generatedAt = new Date().toISOString();
    const request: CustomerReportExportRequest = {
      reportKey: activeReport,
      reportTitle: activeTab.label,
      month: filters.month,
      department: filters.department,
      employee: filters.employee,
      rowCount: rowCountForReport(report, activeReport),
      generatedAt,
      reportFilters: specificCriteriaForReport(activeReport, appliedSpecificFilters),
      dataScopeReference: activeDataScope.reference,
      dataScopeLabel: activeDataScope.label,
    };
    downloadCustomerReportCsv(buildCustomerReportCsv({
      ...request,
      report,
    }));
    onExport?.(request);
    setSpecificFeedback('');
    setExportFeedback(
      `“${activeTab.label}”已按当前筛选条件导出。`,
    );
  };

  const changeSpecificFilter = <K extends keyof CustomerReportSpecificFilters>(
    key: K,
    value: CustomerReportSpecificFilters[K],
  ) => {
    setDraftSpecificFilters((current) => {
      const next = { ...current, [key]: value };
      return key === 'annualLevelOne'
        ? normalizeAnnualLeaveFilters(
          next,
          filters.department,
          activeDataScope.allowedDepartments,
        )
        : next;
    });
    setExportFeedback('');
  };

  const changeDataScope = (reference: string) => {
    const nextScope = availableDataScopes.find((scope) => scope.reference === reference);
    if (!nextScope) return;
    setActiveScopeReference(nextScope.reference);
    setFilters({
      month: filters.month,
      department: '全部部门',
      employee: '全部员工',
    });
    setDraftSpecificFilters({ ...defaultCustomerReportSpecificFilters });
    setAppliedSpecificFilters({ ...defaultCustomerReportSpecificFilters });
    setSpecificFeedback('');
    setExportFeedback('');
  };

  const applySpecificQuery = () => {
    const nextFilters = copySpecificFiltersForReport(
      appliedSpecificFilters,
      draftSpecificFilters,
      activeReport,
    );
    const nextReport = applyCustomerReportSpecificFilters(
      sourceReport,
      activeReport,
      nextFilters,
    );
    setAppliedSpecificFilters(nextFilters);
    setExportFeedback('');
    setSpecificFeedback(
      `已应用${activeTab.label}专属条件，当前返回 ${rowCountForReport(nextReport, activeReport)} 条记录。`,
    );
  };

  const resetSpecificQuery = () => {
    const nextDraftFilters = resetSpecificFiltersForReport(draftSpecificFilters, activeReport);
    const nextFilters = resetSpecificFiltersForReport(appliedSpecificFilters, activeReport);
    const nextReport = applyCustomerReportSpecificFilters(
      sourceReport,
      activeReport,
      nextFilters,
    );
    setDraftSpecificFilters(nextDraftFilters);
    setAppliedSpecificFilters(nextFilters);
    setExportFeedback('');
    setSpecificFeedback(
      `已重置${activeTab.label}专属条件，当前返回 ${rowCountForReport(nextReport, activeReport)} 条记录。`,
    );
  };

  return (
    <main className="customer-report">
      <header className="customer-report__hero">
        <div>
          <div className="customer-report__eyebrow">
            <span className="customer-report__demo-badge">客户演示数据</span>
            <span>数据截至 2026-07-28 13:45</span>
          </div>
          <h1>考勤报表中心</h1>
          <p>
            从考勤明细、异常、工时到年休假余额，一处查看并按当前筛选导出。
          </p>
        </div>
        <div className="customer-report__hero-action">
          <span className="customer-report__version">统计月份 · 2026 年 6 月</span>
          <Button
            type="primary"
            size="large"
            icon={<IconDownload aria-hidden="true" stroke={2} />}
            onClick={handleExport}
            data-capability-mode={capabilityMode}
            disabled={!canExport}
            title={canExport ? undefined : '当前账号没有导出权限'}
          >
            导出当前报表
          </Button>
        </div>
      </header>

      <section className="customer-report__notice" aria-label="演示数据说明">
        <IconInfoCircle aria-hidden="true" stroke={2} />
        <p>
          <strong>演示说明：</strong>
          当前页面使用脱敏示例数据，字段、颜色和统计方式按现有电子表格样表呈现。
        </p>
      </section>

      <section className="customer-report__scope-card" aria-label="数据权限">
        <div className="customer-report__scope-icon" aria-hidden="true">
          <IconShieldCheck stroke={2} />
        </div>
        <div className="customer-report__scope-copy">
          <span>数据权限已生效</span>
          <strong>{activeDataScope.actorLabel} · {activeDataScope.label}</strong>
          <p>
            系统已按{scopeTypeLabel(activeDataScope.type)}限制查询、明细和导出范围。
          </p>
        </div>
        <label className="customer-report__scope-selector">
          <span>权限角色</span>
          <Select
            aria-label="权限角色"
            value={activeDataScope.reference}
            options={availableDataScopes.map((scope) => ({
              value: scope.reference,
              label: scope.actorLabel,
            }))}
            onChange={changeDataScope}
            popupMatchSelectWidth={false}
          />
        </label>
        <span className="customer-report__scope-lock">数据范围 · 已锁定</span>
      </section>

      <section className="customer-report__filter-card" aria-label="报表筛选">
        <div className="customer-report__filter-heading">
          <div>
            <span>查询条件</span>
            <small>切换条件后，所有报表同步更新</small>
          </div>
          <button
            type="button"
            className="customer-report__reset"
            onClick={() => {
              setFilters({
                month: '2026-06',
                department: '全部部门',
                employee: '全部员工',
              });
              setSpecificFeedback('');
              setExportFeedback('');
            }}
          >
            重置筛选
          </button>
        </div>
        <div className="customer-report__filters">
          <label>
            <span>月份</span>
            <Select
              aria-label="月份"
              value={filters.month}
              options={reportFilterOptions.months.map((option) => ({ ...option }))}
              onChange={(value) => changeFilter('month', value)}
              popupMatchSelectWidth={false}
            />
          </label>
          <label>
            <span>部门</span>
            <Select
              aria-label="部门"
              value={filters.department}
              options={departmentOptions}
              onChange={(value) => changeFilter('department', value)}
              popupMatchSelectWidth={false}
            />
          </label>
          <label>
            <span>员工</span>
            <Select
              aria-label="员工"
              showSearch
              value={filters.employee}
              options={employeeOptions}
              onChange={(value) => changeFilter('employee', value)}
              popupMatchSelectWidth={false}
            />
          </label>
          <div className="customer-report__filter-result">
            <span>当前范围</span>
            <strong>{filters.department} · {filters.employee}</strong>
            <small>
              {report.metadata.monthLabel}，共 {report.metadata.rowCount} 名授权员工
            </small>
          </div>
        </div>
      </section>

      <section className="customer-report__metrics" aria-label="当前范围概览">
        <MetricCard
          label="范围员工"
          value={sourceReport.attendanceRows.length}
          unit="人"
          hint="筛选范围内在册人员"
        />
        <MetricCard
          label="请假总时长"
          value={sum(sourceReport.leaveRows.map((row) => row.hours)).toFixed(1)}
          unit="小时"
          hint={`${sourceReport.leaveRows.length} 条已审批记录`}
        />
        <MetricCard
          label="加班总时长"
          value={sum(sourceReport.overtimeRows.map((row) => (
            row.weekdayHours + row.weekendHours + row.statutoryHours
          ))).toFixed(1)}
          unit="小时"
          hint="工作日、周末与法定节假日"
        />
        <MetricCard
          label="待处理异常"
          value={sourceReport.attendanceExceptionRows.filter(
            (row) => row.state !== '已处理',
          ).length}
          unit="项"
          hint="可在考勤异常总览中分级处理"
          tone="warning"
        />
      </section>

      <section className="customer-report__workspace">
        <nav className="customer-report__tabs" role="tablist" aria-label="报表目录">
          {customerReportTabs.map((tab, index) => (
            <button
              id={`customer-report-tab-${tab.key}`}
              key={tab.key}
              type="button"
              role="tab"
              aria-label={tab.label}
              aria-selected={activeReport === tab.key}
              aria-controls={`customer-report-panel-${tab.key}`}
              tabIndex={activeReport === tab.key ? 0 : -1}
              className={activeReport === tab.key ? 'is-active' : undefined}
              onClick={() => {
                setActiveReport(tab.key);
                setExportFeedback('');
                setSpecificFeedback('');
              }}
            >
              <span>{String(index + 1).padStart(2, '0')}</span>
              {tab.shortLabel}
            </button>
          ))}
        </nav>

        <ReportSpecificFilterBar
          reportKey={activeReport}
          reportTitle={activeTab.label}
          month={sourceReport.metadata.month}
          department={filters.department}
          allowedDepartments={activeDataScope.allowedDepartments}
          filters={draftSpecificFilters}
          resultCount={rowCountForReport(report, activeReport)}
          onChange={changeSpecificFilter}
          onQuery={applySpecificQuery}
          onReset={resetSpecificQuery}
        />

        {specificFeedback ? (
          <div className="customer-report__query-feedback" role="status" aria-live="polite">
            {specificFeedback}
          </div>
        ) : null}

        {exportFeedback ? (
          <div className="customer-report__export-feedback" role="status" aria-live="polite">
            <span aria-hidden="true">✓</span>
            <div>
              <strong>导出任务已创建</strong>
              <p>{exportFeedback}</p>
            </div>
          </div>
        ) : null}

        <div
          id={`customer-report-panel-${activeReport}`}
          role="tabpanel"
          aria-label={activeTab.label}
          aria-labelledby={`customer-report-tab-${activeReport}`}
          className="customer-report__tabpanel"
        >
          <ActiveReport reportKey={activeReport} report={report} />
        </div>
      </section>
    </main>
  );
}

function MetricCard({
  label,
  value,
  unit,
  hint,
  tone = 'default',
}: {
  label: string;
  value: string | number;
  unit: string;
  hint: string;
  tone?: 'default' | 'warning';
}) {
  return (
    <article className={`customer-report__metric customer-report__metric--${tone}`}>
      <span>{label}</span>
      <strong>{value}<small>{unit}</small></strong>
      <p>{hint}</p>
    </article>
  );
}

function ReportSpecificFilterBar({
  reportKey,
  reportTitle,
  month,
  department,
  allowedDepartments,
  filters,
  resultCount,
  onChange,
  onQuery,
  onReset,
}: {
  reportKey: CustomerReportKey;
  reportTitle: string;
  month: string;
  department: string;
  allowedDepartments: readonly string[];
  filters: CustomerReportSpecificFilters;
  resultCount: number;
  onChange: <K extends keyof CustomerReportSpecificFilters>(
    key: K,
    value: CustomerReportSpecificFilters[K],
  ) => void;
  onQuery: () => void;
  onReset: () => void;
}) {
  return (
    <section
      className="customer-report__specific-filter"
      aria-label={`${reportTitle}专属筛选`}
    >
      <div className="customer-report__specific-heading">
        <div>
          <span>本报表专属条件</span>
          <small>{specificFilterDescription(reportKey)}</small>
        </div>
        <span className="customer-report__specific-count" data-testid="specific-result-count">
          {resultCount} 条
        </span>
      </div>
      <div className="customer-report__specific-controls">
        <SpecificFilterFields
          reportKey={reportKey}
          month={month}
          department={department}
          allowedDepartments={allowedDepartments}
          filters={filters}
          onChange={onChange}
        />
        <div className="customer-report__specific-actions">
          <Button
            type="primary"
            aria-label={`查询${reportTitle}`}
            onClick={onQuery}
          >
            查询
          </Button>
          <Button
            aria-label={`重置${reportTitle}筛选`}
            onClick={onReset}
          >
            重置
          </Button>
        </div>
      </div>
    </section>
  );
}

function SpecificFilterFields({
  reportKey,
  month,
  department,
  allowedDepartments,
  filters,
  onChange,
}: {
  reportKey: CustomerReportKey;
  month: string;
  department: string;
  allowedDepartments: readonly string[];
  filters: CustomerReportSpecificFilters;
  onChange: <K extends keyof CustomerReportSpecificFilters>(
    key: K,
    value: CustomerReportSpecificFilters[K],
  ) => void;
}) {
  switch (reportKey) {
    case 'attendance-detail':
      return (
        <SpecificSelect
          label="考勤状态"
          value={filters.attendanceStatus}
          options={[
            { value: '全部状态', label: '全部状态' },
            ...attendanceLegend.map((item) => ({ value: item.key, label: item.label })),
          ]}
          onChange={(value) => onChange(
            'attendanceStatus',
            value as CustomerReportSpecificFilters['attendanceStatus'],
          )}
        />
      );
    case 'leave':
      return (
        <SpecificSelect
          label="请假类型"
          value={filters.leaveType}
          options={plainOptions(reportSpecificFilterOptions.leaveTypes)}
          onChange={(value) => onChange('leaveType', value)}
        />
      );
    case 'overtime':
      return (
        <>
          <SpecificSelect
            label="加班类型"
            value={filters.overtimeType}
            options={plainOptions(reportSpecificFilterOptions.overtimeTypes)}
            onChange={(value) => onChange(
              'overtimeType',
              value as CustomerReportSpecificFilters['overtimeType'],
            )}
          />
          <SpecificSelect
            label="加班日期"
            value={filters.overtimeDay}
            options={[
              { value: '全部日期', label: '全部日期' },
              ...Array.from({ length: daysInMonth(month) }, (_, index) => ({
                value: String(index + 1),
                label: `${month.slice(5, 7)}月${String(index + 1).padStart(2, '0')}日`,
              })),
            ]}
            onChange={(value) => onChange('overtimeDay', value)}
          />
        </>
      );
    case 'work-hours':
      return (
        <SpecificSelect
          label="在职状态"
          value={filters.employmentStatus}
          options={plainOptions(reportSpecificFilterOptions.employmentStatuses)}
          onChange={(value) => onChange(
            'employmentStatus',
            value as CustomerReportSpecificFilters['employmentStatus'],
          )}
        />
      );
    case 'exceptions':
      return (
        <>
          <SpecificSelect
            label="异常类型"
            value={filters.exceptionType}
            options={plainOptions(reportSpecificFilterOptions.exceptionTypes)}
            onChange={(value) => onChange(
              'exceptionType',
              value as CustomerReportSpecificFilters['exceptionType'],
            )}
          />
          <SpecificSelect
            label="异常级别"
            value={filters.exceptionSeverity}
            options={plainOptions(reportSpecificFilterOptions.exceptionSeverities)}
            onChange={(value) => onChange(
              'exceptionSeverity',
              value as CustomerReportSpecificFilters['exceptionSeverity'],
            )}
          />
          <SpecificSelect
            label="处理状态"
            value={filters.exceptionState}
            options={plainOptions(reportSpecificFilterOptions.exceptionStates)}
            onChange={(value) => onChange(
              'exceptionState',
              value as CustomerReportSpecificFilters['exceptionState'],
            )}
          />
        </>
      );
    case 'late':
      return (
        <>
          <SpecificSelect
            label="迟到次数"
            value={filters.lateCount}
            options={plainOptions(reportSpecificFilterOptions.lateCounts)}
            onChange={(value) => onChange(
              'lateCount',
              value as CustomerReportSpecificFilters['lateCount'],
            )}
          />
          <SpecificSelect
            label="迟到时长级别"
            value={filters.lateLevel}
            options={plainOptions(reportSpecificFilterOptions.lateLevels)}
            onChange={(value) => onChange(
              'lateLevel',
              value as CustomerReportSpecificFilters['lateLevel'],
            )}
          />
        </>
      );
    case 'missed-punch':
      return (
        <SpecificSelect
          label="缺卡时段"
          value={filters.punchType}
          options={plainOptions(reportSpecificFilterOptions.punchTypes)}
          onChange={(value) => onChange(
            'punchType',
            value as CustomerReportSpecificFilters['punchType'],
          )}
        />
      );
    case 'attendance-rate':
      return (
        <SpecificSelect
          label="出勤类型"
          value={filters.attendanceType}
          options={plainOptions(reportSpecificFilterOptions.attendanceTypes)}
          onChange={(value) => onChange('attendanceType', value)}
        />
      );
    case 'annual-leave':
      return (
        <>
          <SpecificSelect
            label="年休假余额状态"
            value={filters.annualBalance}
            options={plainOptions(reportSpecificFilterOptions.annualBalances)}
            onChange={(value) => onChange(
              'annualBalance',
              value as CustomerReportSpecificFilters['annualBalance'],
            )}
          />
          <SpecificSelect
            label="一级部门"
            value={filters.annualLevelOne}
            options={plainOptions(annualLevelOneOptions(
              department,
              allowedDepartments,
            ))}
            onChange={(value) => onChange('annualLevelOne', value)}
          />
          <SpecificSelect
            label="二级部门"
            value={filters.annualLevelTwo}
            options={plainOptions(annualLevelTwoOptions(
              filters.annualLevelOne,
              department,
              allowedDepartments,
            ))}
            onChange={(value) => onChange('annualLevelTwo', value)}
          />
        </>
      );
  }
}

function SpecificSelect({
  label,
  value,
  options,
  onChange,
}: {
  label: string;
  value: string;
  options: ReadonlyArray<{ value: string; label: string }>;
  onChange: (value: string) => void;
}) {
  return (
    <label className="customer-report__specific-field">
      <span>{label}</span>
      <select
        aria-label={label}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      >
        {options.map((option) => (
          <option value={option.value} key={option.value}>{option.label}</option>
        ))}
      </select>
    </label>
  );
}

function ActiveReport({
  reportKey,
  report,
}: {
  reportKey: CustomerReportKey;
  report: CustomerReportDemo;
}) {
  switch (reportKey) {
    case 'attendance-detail':
      return <AttendanceDetailReport report={report} />;
    case 'leave':
      return <LeaveReport report={report} />;
    case 'overtime':
      return <OvertimeReport report={report} />;
    case 'work-hours':
      return <WorkHoursReport report={report} />;
    case 'exceptions':
      return <AttendanceExceptionReport report={report} />;
    case 'late':
      return <ExceptionReport report={report} type="late" />;
    case 'missed-punch':
      return <ExceptionReport report={report} type="missed-punch" />;
    case 'attendance-rate':
      return <AttendanceRateReport report={report} />;
    case 'annual-leave':
      return <AnnualLeaveReport report={report} />;
  }
}

function AttendanceDetailReport({ report }: { report: CustomerReportDemo }) {
  return (
    <ReportSheet
      title={`${report.metadata.monthLabel}考勤统计`}
      subtitle={`${report.metadata.company} · 上下班打卡与异常状态明细`}
      meta={`${report.attendanceRows.length} 人 · ${daysInMonth(report.metadata.month)} 个自然日`}
    >
      <div className="customer-report__legend" aria-label="考勤状态颜色图例">
        {attendanceLegend.map((item) => (
          <span key={item.key}>
            <i style={{ backgroundColor: item.color }} aria-hidden="true" />
            {item.label}
          </span>
        ))}
      </div>
      <div className="customer-report__table-scroll" data-testid="report-scroll-region">
        <table className="customer-report__table customer-report__table--matrix" data-testid="report-table">
          <thead>
            <tr>
              <th scope="col">工号</th>
              <th scope="col">姓名</th>
              <th scope="col">部门</th>
              {report.attendanceRows[0]?.days.map((day) => (
                <th scope="col" key={day.day}>
                  <span>{String(day.day).padStart(2, '0')}</span>
                  <small>/{day.weekday}</small>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {report.attendanceRows.length > 0 ? report.attendanceRows.map((row) => (
              <AttendanceMatrixRow row={row} key={row.employeeNo} />
            )) : <EmptyTableRow colSpan={daysInMonth(report.metadata.month) + 3} />}
          </tbody>
        </table>
      </div>
      <SheetFootnote>
        单元格第一行为上班卡，第二行为下班卡或审批状态；悬停异常色块可查看说明。
      </SheetFootnote>
    </ReportSheet>
  );
}

function AttendanceMatrixRow({ row }: { row: AttendanceDetailRow }) {
  return (
    <tr>
      <td>{row.employeeNo}</td>
      <td><strong>{row.employee}</strong><small>{row.position}</small></td>
      <td>{row.department}</td>
      {row.days.map((day) => {
        const color = day.status ? statusColor(day.status) : undefined;
        const content = (
          <div
            className={`customer-report__attendance-cell${day.status ? ` is-${day.status}` : ''}`}
            style={color ? { backgroundColor: color } : undefined}
          >
            <span>{day.primary}</span>
            <small>{day.secondary}</small>
          </div>
        );
        return (
          <td key={day.day} className={day.status ? 'has-status' : undefined}>
            {day.note ? (
              <Tooltip title={day.note} placement="top">
                {content}
              </Tooltip>
            ) : content}
          </td>
        );
      })}
    </tr>
  );
}

function LeaveReport({ report }: { report: CustomerReportDemo }) {
  return (
    <ReportSheet
      title={`${report.metadata.company}${report.metadata.monthLabel}请假统计`}
      subtitle="已通过审批的请假记录，统一折算为小时"
      meta={`合计 ${sum(report.leaveRows.map((row) => row.hours)).toFixed(1)} 小时`}
    >
      <ScrollTable>
        <table className="customer-report__table" data-testid="report-table">
          <thead>
            <tr>
              <th scope="col">序号</th>
              <th scope="col">部门</th>
              <th scope="col">姓名</th>
              <th scope="col">类型</th>
              <th scope="col">时间（小时数）</th>
              <th scope="col">请假期间</th>
              <th scope="col">备注</th>
              <th scope="col">审批状态</th>
            </tr>
          </thead>
          <tbody>
            {report.leaveRows.length > 0 ? report.leaveRows.map((row) => (
              <tr key={row.id}>
                <td>{row.id}</td>
                <td>{row.department}</td>
                <td><strong>{row.employee}</strong></td>
                <td><StatusPill label={row.type} /></td>
                <td className="customer-report__number">{row.hours.toFixed(1)}</td>
                <td>{row.period}</td>
                <td>{row.remark}</td>
                <td><StatePill label={row.approvalState} /></td>
              </tr>
            )) : <EmptyTableRow colSpan={8} />}
          </tbody>
        </table>
      </ScrollTable>
    </ReportSheet>
  );
}

function OvertimeReport({ report }: { report: CustomerReportDemo }) {
  const departmentRows = [...new Set(report.overtimeRows.map((row) => row.department))].map((department) => {
    const rows = report.overtimeRows.filter((row) => row.department === department);
    return {
      department,
      people: rows.length,
      weekdayHours: sum(rows.map((row) => row.weekdayHours)),
      weekendHours: sum(rows.map((row) => row.weekendHours)),
      statutoryHours: sum(rows.map((row) => row.statutoryHours)),
    };
  });
  const dayCount = daysInMonth(report.metadata.month);

  return (
    <ReportSheet
      title={`${report.metadata.monthLabel}加班统计`}
      subtitle="部门汇总 + 员工每日加班，支持横向复核"
      meta={`${report.overtimeRows.length} 人`}
    >
      <div className="customer-report__table-scroll" data-testid="report-scroll-region">
        <h3>部门加班汇总</h3>
        <table className="customer-report__table customer-report__table--compact">
          <thead>
            <tr>
              <th scope="col">部门</th>
              <th scope="col">加班人数</th>
              <th scope="col">平时加班</th>
              <th scope="col">周末加班</th>
              <th scope="col">法定加班</th>
              <th scope="col">总计（小时）</th>
            </tr>
          </thead>
          <tbody>
            {departmentRows.map((row) => (
              <tr key={row.department}>
                <td><strong>{row.department}</strong></td>
                <td>{row.people}</td>
                <td>{row.weekdayHours.toFixed(1)}</td>
                <td>{row.weekendHours.toFixed(1)}</td>
                <td>{row.statutoryHours.toFixed(1)}</td>
                <td><strong>{(row.weekdayHours + row.weekendHours + row.statutoryHours).toFixed(1)}</strong></td>
              </tr>
            ))}
          </tbody>
        </table>
        <h3>员工每日加班</h3>
        <table className="customer-report__table customer-report__table--daily" data-testid="report-table">
          <thead>
            <tr>
              <th scope="col">部门</th>
              <th scope="col">员工</th>
              <th scope="col">平时</th>
              <th scope="col">周末</th>
              {Array.from({ length: dayCount }, (_, index) => (
                <th scope="col" key={index + 1}>{index + 1}日</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {report.overtimeRows.length > 0 ? report.overtimeRows.map((row) => (
              <tr key={row.employee}>
                <td>{row.department}</td>
                <td><strong>{row.employee}</strong></td>
                <td>{row.weekdayHours || '—'}</td>
                <td>{row.weekendHours || '—'}</td>
                {row.dailyHours.slice(0, dayCount).map((hours, index) => (
                  <td key={index}>{hours || ''}</td>
                ))}
              </tr>
            )) : <EmptyTableRow colSpan={dayCount + 4} />}
          </tbody>
        </table>
      </div>
    </ReportSheet>
  );
}

function WorkHoursReport({ report }: { report: CustomerReportDemo }) {
  return (
    <ReportSheet
      title={`${report.metadata.monthLabel}个人月度工时`}
      subtitle="应出勤、加班、休假与个人实际出勤的统一口径"
      meta={`${report.workHoursRows.length} 人`}
    >
      <ScrollTable>
        <table className="customer-report__table" data-testid="report-table">
          <thead>
            <tr>
              <th scope="col">姓名</th>
              <th scope="col">部门</th>
              <th scope="col">应出勤工时</th>
              <th scope="col">加班时数</th>
              <th scope="col">事假+病假+其他假期</th>
              <th scope="col">年假</th>
              <th scope="col">加班换调休</th>
              <th scope="col">个人实际出勤工时</th>
              <th scope="col">备注</th>
            </tr>
          </thead>
          <tbody>
            {report.workHoursRows.length > 0 ? report.workHoursRows.map((row) => (
              <tr key={row.employee}>
                <td><strong>{row.employee}</strong></td>
                <td>{row.department}</td>
                <td>{row.plannedHours.toFixed(1)}</td>
                <td>{row.overtimeHours.toFixed(1)}</td>
                <td>{row.leaveHours.toFixed(1)}</td>
                <td>{row.annualLeaveHours.toFixed(1)}</td>
                <td>{row.exchangedHours.toFixed(1)}</td>
                <td className="customer-report__number"><strong>{row.actualHours.toFixed(1)}</strong></td>
                <td>{row.note}</td>
              </tr>
            )) : <EmptyTableRow colSpan={9} />}
          </tbody>
        </table>
      </ScrollTable>
    </ReportSheet>
  );
}

function AttendanceExceptionReport({ report }: { report: CustomerReportDemo }) {
  const rows = report.attendanceExceptionRows;
  const pendingCount = rows.filter((row) => row.state !== '已处理').length;
  const highCount = rows.filter((row) => row.severity === '高').length;
  const missingPunchCount = rows.filter((row) => (
    row.exceptionType === '上班缺卡' || row.exceptionType === '下班缺卡'
  )).length;
  return (
    <ReportSheet
      title={`${report.metadata.company}${report.metadata.monthLabel}考勤异常总览`}
      subtitle="迟到、早退、缺卡、旷工、排班和审批冲突统一复核；假因已按字段策略脱敏"
      meta={`${rows.length} 条 · ${pendingCount} 条待处理`}
    >
      <div className="customer-report__exception-summary" aria-label="异常汇总">
        <ExceptionSummaryItem label="高风险" value={highCount} tone="danger" />
        <ExceptionSummaryItem label="缺卡" value={missingPunchCount} tone="warning" />
        <ExceptionSummaryItem
          label="审批/打卡冲突"
          value={rows.filter((row) => row.exceptionType.includes('冲突')).length}
        />
        <ExceptionSummaryItem label="未闭环" value={pendingCount} tone="warning" />
      </div>
      <ScrollTable>
        <table
          className="customer-report__table customer-report__table--exceptions"
          data-testid="report-table"
        >
          <thead>
            <tr>
              <th scope="col">考勤日期</th>
              <th scope="col">级别</th>
              <th scope="col">异常类型</th>
              <th scope="col">工号</th>
              <th scope="col">姓名</th>
              <th scope="col">部门</th>
              <th scope="col">班次</th>
              <th scope="col">应出勤</th>
              <th scope="col">打卡摘要</th>
              <th scope="col">异常分钟</th>
              <th scope="col">证据摘要</th>
              <th scope="col">处理状态</th>
              <th scope="col">负责人</th>
              <th scope="col">处理时限</th>
            </tr>
          </thead>
          <tbody>
            {rows.length > 0 ? rows.map((row) => (
              <AttendanceExceptionRow row={row} key={row.id} />
            )) : <EmptyTableRow colSpan={14} />}
          </tbody>
        </table>
      </ScrollTable>
      <SheetFootnote>
        当前为常用异常首版；汇总、明细与导出均已绑定
        {report.metadata.dataScope.label}，越权筛选返回空结果。
      </SheetFootnote>
    </ReportSheet>
  );
}

function ExceptionSummaryItem({
  label,
  value,
  tone = 'default',
}: {
  label: string;
  value: number;
  tone?: 'default' | 'warning' | 'danger';
}) {
  return (
    <span className={`customer-report__exception-summary-item is-${tone}`}>
      {label}
      <strong>{value}</strong>
    </span>
  );
}

function AttendanceExceptionRow({ row }: { row: AttendanceExceptionReportRow }) {
  return (
    <tr>
      <td>{row.businessDate}</td>
      <td>
        <span
          className="customer-report__severity"
          data-severity={row.severity}
        >
          {row.severity}
        </span>
      </td>
      <td><strong>{row.exceptionType}</strong></td>
      <td>{row.employeeNo}</td>
      <td><strong>{row.employee}</strong></td>
      <td>{row.department}</td>
      <td>{row.shiftLabel}</td>
      <td>{row.scheduledWindow}</td>
      <td>{row.punchSummary}</td>
      <td className="customer-report__number">
        {row.exceptionMinutes === undefined ? '—' : row.exceptionMinutes}
      </td>
      <td>{row.evidenceSummary}</td>
      <td><StatePill label={row.state} /></td>
      <td>{row.owner}</td>
      <td>{row.dueAt}</td>
    </tr>
  );
}

function ExceptionReport({
  report,
  type,
}: {
  report: CustomerReportDemo;
  type: 'late' | 'missed-punch';
}) {
  const isLate = type === 'late';
  const rows = isLate ? report.lateRows : report.missedPunchRows;
  return (
    <ReportSheet
      title={`${report.metadata.company}${report.metadata.monthLabel}${isLate ? '迟到' : '忘打卡'}统计`}
      subtitle={isLate ? '按员工汇总迟到次数与发生时间' : '按员工汇总缺卡班次与补签状态'}
      meta={`${sum(rows.map((row) => row.count))} 次`}
    >
      <ExceptionTable rows={rows} />
    </ReportSheet>
  );
}

function ExceptionTable({ rows }: { rows: ExceptionReportRow[] }) {
  return (
    <ScrollTable>
      <table className="customer-report__table" data-testid="report-table">
        <thead>
          <tr>
            <th scope="col">序号</th>
            <th scope="col">部门</th>
            <th scope="col">姓名</th>
            <th scope="col">次数</th>
            <th scope="col">发生明细</th>
            <th scope="col">复核人</th>
            <th scope="col">处理状态</th>
          </tr>
        </thead>
        <tbody>
          {rows.length > 0 ? rows.map((row) => (
            <tr key={row.id}>
              <td>{row.id}</td>
              <td>{row.department}</td>
              <td><strong>{row.employee}</strong></td>
              <td className="customer-report__number">{row.count}</td>
              <td>{row.details}</td>
              <td>{row.reviewer}</td>
              <td><StatePill label={row.state} /></td>
            </tr>
          )) : <EmptyTableRow colSpan={7} />}
        </tbody>
      </table>
    </ScrollTable>
  );
}

function AttendanceRateReport({ report }: { report: CustomerReportDemo }) {
  return (
    <ReportSheet
      title={`${report.metadata.company}${report.metadata.monthLabel}出勤率统计`}
      subtitle="出勤率按个人应出勤工时扣除缺勤工时计算"
      meta={`${report.attendanceRateRows.length} 人`}
    >
      <ScrollTable>
        <table className="customer-report__table" data-testid="report-table">
          <thead>
            <tr>
              <th scope="col">序号</th>
              <th scope="col">部门</th>
              <th scope="col">姓名</th>
              <th scope="col">主要缺勤类型</th>
              <th scope="col">缺勤时间（小时）</th>
              <th scope="col">出勤率</th>
              <th scope="col">口径说明</th>
            </tr>
          </thead>
          <tbody>
            {report.attendanceRateRows.length > 0 ? report.attendanceRateRows.map((row) => (
              <tr key={row.id}>
                <td>{row.id}</td>
                <td>{row.department}</td>
                <td><strong>{row.employee}</strong></td>
                <td><StatusPill label={row.type} /></td>
                <td>{row.hours.toFixed(1)}</td>
                <td>
                  <div className="customer-report__rate">
                    <strong>{row.rate}</strong>
                    <i style={{ width: row.rate }} aria-hidden="true" />
                  </div>
                </td>
                <td>{row.note}</td>
              </tr>
            )) : <EmptyTableRow colSpan={7} />}
          </tbody>
        </table>
      </ScrollTable>
    </ReportSheet>
  );
}

function AnnualLeaveReport({ report }: { report: CustomerReportDemo }) {
  const year = report.metadata.month.slice(0, 4);
  return (
    <ReportSheet
      title={`${year}年员工年休假统计汇总`}
      subtitle={`计算日 ${year}/12/31 · 工龄、可休额度、月度使用与余额`}
      meta={`${report.annualLeaveRows.length} 人`}
      annual
    >
      <div className="customer-report__annual-summary">
        <span>可休总额 <strong>{sum(report.annualLeaveRows.map((row) => row.availableDays)).toFixed(1)} 天</strong></span>
        <span>已使用 <strong>{sum(report.annualLeaveRows.flatMap((row) => row.monthlyUsedDays)).toFixed(1)} 天</strong></span>
        <span>剩余 <strong>{sum(report.annualLeaveRows.map((row) => row.remainingDays)).toFixed(1)} 天</strong></span>
      </div>
      <ScrollTable>
        <table
          className="customer-report__table customer-report__table--annual"
          data-testid="report-table"
        >
          <thead>
            <tr>
              <th scope="col">序号</th>
              <th scope="col">一级部门</th>
              <th scope="col">二级部门</th>
              <th scope="col">姓名</th>
              <th scope="col">入职日期</th>
              <th scope="col">公司工龄</th>
              <th scope="col">公司外已证明工龄</th>
              <th scope="col">累计工龄</th>
              <th scope="col">法定年休假天数</th>
              <th scope="col">新员工折算天数</th>
              <th scope="col">可休天数</th>
              <th scope="col">可休小时数</th>
              {Array.from({ length: 12 }, (_, index) => (
                <th scope="col" key={index + 1}>{index + 1}月</th>
              ))}
              <th scope="col">实际剩余天数</th>
              <th scope="col">备注</th>
            </tr>
          </thead>
          <tbody>
            {report.annualLeaveRows.length > 0 ? report.annualLeaveRows.map((row) => (
              <AnnualLeaveRow row={row} key={row.id} />
            )) : <EmptyTableRow colSpan={26} />}
          </tbody>
        </table>
      </ScrollTable>
    </ReportSheet>
  );
}

function AnnualLeaveRow({ row }: { row: AnnualLeaveReportRow }) {
  return (
    <tr>
      <td>{row.id}</td>
      <td>{row.departmentLevelOne}</td>
      <td>{row.departmentLevelTwo}</td>
      <td><strong>{row.employee}</strong></td>
      <td>{row.joinedOn}</td>
      <td>{row.companySeniority.toFixed(1)}</td>
      <td>{row.priorSeniority.toFixed(1)}</td>
      <td>{row.totalSeniority.toFixed(1)}</td>
      <td>{row.statutoryDays.toFixed(1)}</td>
      <td>{row.newHireDays ? row.newHireDays.toFixed(1) : '—'}</td>
      <td>{row.availableDays.toFixed(1)}</td>
      <td>{row.availableHours.toFixed(1)}</td>
      {row.monthlyUsedDays.map((days, index) => (
        <td key={index}>{days ? days.toFixed(1) : ''}</td>
      ))}
      <td className="customer-report__number"><strong>{row.remainingDays.toFixed(1)}</strong></td>
      <td>{row.note || '—'}</td>
    </tr>
  );
}

function ReportSheet({
  title,
  subtitle,
  meta,
  annual = false,
  children,
}: {
  title: string;
  subtitle: string;
  meta: string;
  annual?: boolean;
  children: ReactNode;
}) {
  return (
    <article className={`customer-report__sheet${annual ? ' customer-report__sheet--annual' : ''}`}>
      <header className="customer-report__sheet-header">
        <div>
          <span className="customer-report__sheet-kicker">{annual ? '年休假报表' : '考勤统计报表'}</span>
          <h2>{title}</h2>
          <p>{subtitle}</p>
        </div>
        <div className="customer-report__sheet-meta">
          <strong>{meta}</strong>
          <span>示例数据 · 已脱敏</span>
        </div>
      </header>
      {children}
    </article>
  );
}

function ScrollTable({ children }: { children: ReactNode }) {
  return (
    <div className="customer-report__table-scroll" data-testid="report-scroll-region">
      {children}
    </div>
  );
}

function StatusPill({ label }: { label: string }) {
  return <span className={`customer-report__status customer-report__status--${statusClass(label)}`}>{label}</span>;
}

function StatePill({ label }: { label: string }) {
  const waiting = label.includes('待');
  return (
    <span className={`customer-report__state${waiting ? ' is-waiting' : ''}`}>
      <i aria-hidden="true" />
      {label}
    </span>
  );
}

function SheetFootnote({ children }: { children: ReactNode }) {
  return (
    <p className="customer-report__footnote">
      <IconInfoCircle aria-hidden="true" stroke={2} />
      {children}
    </p>
  );
}

function EmptyTableRow({ colSpan }: { colSpan: number }) {
  return (
    <tr>
      <td colSpan={colSpan} className="customer-report__empty">
        当前筛选条件下暂无记录，请调整部门或员工。
      </td>
    </tr>
  );
}

function specificFilterDescription(reportKey: CustomerReportKey): string {
  const descriptions: Record<CustomerReportKey, string> = {
    'attendance-detail': '按异常或审批状态定位员工明细',
    leave: '按请假类型核对审批与小时数',
    overtime: '按加班性质与具体发生日期交叉查询',
    'work-hours': '区分在职、本月入职和本月离职',
    exceptions: '按异常类型、风险级别和处理状态定位待办',
    late: '按发生次数和最长迟到分钟分级',
    'missed-punch': '区分上班缺卡与下班缺卡',
    'attendance-rate': '按主要缺勤类型查看出勤率',
    'annual-leave': '按余额风险和组织层级核对额度',
  };
  return descriptions[reportKey];
}

function copySpecificFiltersForReport(
  current: CustomerReportSpecificFilters,
  draft: CustomerReportSpecificFilters,
  reportKey: CustomerReportKey,
): CustomerReportSpecificFilters {
  const next = { ...current };
  switch (reportKey) {
    case 'attendance-detail':
      next.attendanceStatus = draft.attendanceStatus;
      break;
    case 'leave':
      next.leaveType = draft.leaveType;
      break;
    case 'overtime':
      next.overtimeType = draft.overtimeType;
      next.overtimeDay = draft.overtimeDay;
      break;
    case 'work-hours':
      next.employmentStatus = draft.employmentStatus;
      break;
    case 'exceptions':
      next.exceptionType = draft.exceptionType;
      next.exceptionSeverity = draft.exceptionSeverity;
      next.exceptionState = draft.exceptionState;
      break;
    case 'late':
      next.lateCount = draft.lateCount;
      next.lateLevel = draft.lateLevel;
      break;
    case 'missed-punch':
      next.punchType = draft.punchType;
      break;
    case 'attendance-rate':
      next.attendanceType = draft.attendanceType;
      break;
    case 'annual-leave':
      next.annualBalance = draft.annualBalance;
      next.annualLevelOne = draft.annualLevelOne;
      next.annualLevelTwo = draft.annualLevelTwo;
      break;
  }
  return next;
}

function resetSpecificFiltersForReport(
  current: CustomerReportSpecificFilters,
  reportKey: CustomerReportKey,
): CustomerReportSpecificFilters {
  const next = { ...current };
  switch (reportKey) {
    case 'attendance-detail':
      next.attendanceStatus = defaultCustomerReportSpecificFilters.attendanceStatus;
      break;
    case 'leave':
      next.leaveType = defaultCustomerReportSpecificFilters.leaveType;
      break;
    case 'overtime':
      next.overtimeType = defaultCustomerReportSpecificFilters.overtimeType;
      next.overtimeDay = defaultCustomerReportSpecificFilters.overtimeDay;
      break;
    case 'work-hours':
      next.employmentStatus = defaultCustomerReportSpecificFilters.employmentStatus;
      break;
    case 'exceptions':
      next.exceptionType = defaultCustomerReportSpecificFilters.exceptionType;
      next.exceptionSeverity = defaultCustomerReportSpecificFilters.exceptionSeverity;
      next.exceptionState = defaultCustomerReportSpecificFilters.exceptionState;
      break;
    case 'late':
      next.lateCount = defaultCustomerReportSpecificFilters.lateCount;
      next.lateLevel = defaultCustomerReportSpecificFilters.lateLevel;
      break;
    case 'missed-punch':
      next.punchType = defaultCustomerReportSpecificFilters.punchType;
      break;
    case 'attendance-rate':
      next.attendanceType = defaultCustomerReportSpecificFilters.attendanceType;
      break;
    case 'annual-leave':
      next.annualBalance = defaultCustomerReportSpecificFilters.annualBalance;
      next.annualLevelOne = defaultCustomerReportSpecificFilters.annualLevelOne;
      next.annualLevelTwo = defaultCustomerReportSpecificFilters.annualLevelTwo;
      break;
  }
  return next;
}

function specificCriteriaForReport(
  reportKey: CustomerReportKey,
  filters: CustomerReportSpecificFilters,
): Record<string, string> {
  switch (reportKey) {
    case 'attendance-detail':
      return { 考勤状态: displayAttendanceStatus(filters.attendanceStatus) };
    case 'leave':
      return { 请假类型: filters.leaveType };
    case 'overtime':
      return { 加班类型: filters.overtimeType, 加班日期: filters.overtimeDay };
    case 'work-hours':
      return { 在职状态: filters.employmentStatus };
    case 'exceptions':
      return {
        异常类型: filters.exceptionType,
        异常级别: filters.exceptionSeverity,
        处理状态: filters.exceptionState,
      };
    case 'late':
      return { 迟到次数: filters.lateCount, 迟到时长级别: filters.lateLevel };
    case 'missed-punch':
      return { 缺卡时段: filters.punchType };
    case 'attendance-rate':
      return { 出勤类型: filters.attendanceType };
    case 'annual-leave':
      return {
        年休假余额状态: filters.annualBalance,
        一级部门: filters.annualLevelOne,
        二级部门: filters.annualLevelTwo,
      };
  }
}

function displayAttendanceStatus(
  status: CustomerReportSpecificFilters['attendanceStatus'],
): string {
  if (status === '全部状态') return status;
  return attendanceLegend.find((item) => item.key === status)?.label ?? status;
}

function plainOptions(
  values: readonly string[],
): ReadonlyArray<{ value: string; label: string }> {
  return values.map((value) => ({ value, label: value }));
}

function rowCountForReport(report: CustomerReportDemo, key: CustomerReportKey): number {
  const counts: Record<CustomerReportKey, number> = {
    'attendance-detail': report.attendanceRows.length,
    leave: report.leaveRows.length,
    overtime: report.overtimeRows.length,
    'work-hours': report.workHoursRows.length,
    exceptions: report.attendanceExceptionRows.length,
    late: report.lateRows.length,
    'missed-punch': report.missedPunchRows.length,
    'attendance-rate': report.attendanceRateRows.length,
    'annual-leave': report.annualLeaveRows.length,
  };
  return counts[key];
}

function daysInMonth(month: string): number {
  const [year, monthNumber] = month.split('-').map(Number);
  return new Date(year!, monthNumber!, 0).getDate();
}

function sum(values: number[]): number {
  return values.reduce((total, value) => total + value, 0);
}

function statusColor(status: AttendanceStatusKey): string {
  return attendanceLegend.find((item) => item.key === status)?.color ?? '#ffffff';
}

function statusClass(label: string): string {
  if (label.includes('病')) return 'sick';
  if (label.includes('年')) return 'annual';
  if (label.includes('事')) return 'personal';
  if (label.includes('调')) return 'time-off';
  return 'neutral';
}

export default CustomerReportCenterPage;
