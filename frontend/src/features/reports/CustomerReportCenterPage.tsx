import {
  IconDownload,
  IconInfoCircle,
  IconRefresh,
  IconShieldCheck,
} from '@tabler/icons-react';
import { Button, DatePicker, Modal, Select, Tooltip, TreeSelect } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import dayjs from 'dayjs';
import type { DataNode } from 'antd/es/tree';
import type { CSSProperties, ReactNode } from 'react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import {
  applyCustomerReportSpecificFilters,
  annualLevelOneOptions,
  annualLevelTwoOptions,
  attendanceLegend,
  customerReportTabs,
  defaultCustomerReportSpecificFilters,
  formatMonth,
  getCustomerReportDemo,
  overviewCards,
  normalizeAnnualLeaveFilters,
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
import { downloadCustomerReportWorkbook } from './customerReportWorkbook';
import { departmentPathNodes, visibleDepartmentPath } from './departmentPath';
import { formatFinanceHours, overtimeCellFill, overtimeFeeColumns, overtimeTreatmentHover } from './financeOvertimeLayout';
import { isProvisionalRealtimeSource, realtimeSourceCutoff } from './reportSourceFreshness';
import {
  ALL_DEPARTMENTS,
  ALL_EMPLOYEES,
  loadCustomerReport,
  loadCustomerReportDirectory,
  loadCustomerReportScopes,
  recalculateCustomerReport,
  type CustomerReportDirectoryEntry,
} from './customerReportApi';
import { isDemoMode } from '../../shared/config/runtimeMode';
import { getCurrentOrganizationTree, type OrganizationNode } from '../organization/organizationApi';
import { pickPreferredCompany } from '../../shared/preferredCompany';
import { defaultQueryPeriod } from './queryPeriod';
import './customerReports.css';

export interface CustomerReportExportRequest {
  reportKey: CustomerReportKey;
  reportTitle: string;
  month: string;
  department: string;
  employee: string;
  organizationId?: string;
  employeeId?: string;
  rowCount: number;
  generatedAt: string;
  reportFilters: Record<string, string>;
  dataScopeReference: string;
  dataScopeLabel: string;
}

interface CustomerReportEmployeeDirectoryEntry {
  employeeId?: string;
  employeeNo?: string;
  employee: string;
  organizationId?: string;
  department: string;
}

interface CustomerReportFilterOption {
  value: string;
  label: string;
  searchText: string;
}

interface CustomerReportEmployeeOption extends CustomerReportFilterOption {
  employeeId?: string;
  employeeNo?: string;
  department?: string;
}

interface CustomerReportDepartmentOption extends CustomerReportFilterOption {
  organizationId?: string;
}

export function CustomerReportCenterPage({
  capabilities,
  dataScopes,
  onExport,
}: {
  capabilities?: readonly string[];
  dataScopes?: readonly CustomerReportDataScope[];
  onExport?: (request: CustomerReportExportRequest) => void;
}) {
  const initialScopes = dataScopes ?? customerReportDemoScopes;
  const fallbackScopes = initialScopes.length > 0 ? initialScopes : [defaultCustomerReportDataScope];
  const [activeScopeReference, setActiveScopeReference] = useState(
    fallbackScopes[0]!.reference,
  );
  const [availableScopes, setAvailableScopes] = useState<readonly CustomerReportDataScope[]>(
    () => (isDemoMode() ? fallbackScopes : []),
  );
  // Track scope loading errors so we can grey-out the selector instead of
  // silently showing hardcoded presets as if they came from the server.
  const [scopeLoadError, setScopeLoadError] = useState(false);
  const [loadedScopeMonth, setLoadedScopeMonth] = useState<string | null>(null);
  // In real mode scopes come exclusively from the server; demo mode uses presets.
  // Guard: while the server scope is still loading (availableScopes=[]) fall back
  // to the demo presets only to keep the component renderable — no data is shown
  // until real scopes arrive.
  const resolvedScopes = isDemoMode() || availableScopes.length === 0
    ? fallbackScopes
    : availableScopes;
  const activeDataScope = resolvedScopes.find(
    (scope) => scope.reference === activeScopeReference,
  ) ?? resolvedScopes[0]!;
  const deepLink = useMemo(() => readCustomerReportDeepLink(), []);
  const [filters, setFilters] = useState<CustomerReportFilters>(() => {
    const currentMonth = defaultQueryPeriod().format('YYYY-MM');
    const month = deepLink.period ?? currentMonth;
    const bounds = monthBounds(month);
    return {
      month,
      department: '全部部门',
      employee: '全部员工',
      organizationId: undefined,
      employeeId: undefined,
      fromDate: bounds.fromDate,
      toDate: bounds.toDate,
    };
  });
  const [activeReport, setActiveReport] = useState<CustomerReportKey>(
    () => deepLink.reportKey ?? 'attendance-detail',
  );
  const expectedProjectionVersion = deepLink.expectedProjectionVersion;
  const [exportFeedback, setExportFeedback] = useState('');
  const [specificFeedback, setSpecificFeedback] = useState('');
  const [draftSpecificFilters, setDraftSpecificFilters] = useState<CustomerReportSpecificFilters>({
    ...defaultCustomerReportSpecificFilters,
  });
  const [appliedSpecificFilters, setAppliedSpecificFilters] = useState<CustomerReportSpecificFilters>({
    ...defaultCustomerReportSpecificFilters,
  });

  // Demo/fallback report — always computed synchronously
  const demoReport = useMemo(
    () => getCustomerReportDemo(filters, activeDataScope),
    [activeDataScope, filters],
  );

  // Live API state (only used when !isDemoMode())
  const [liveReport, setLiveReport] = useState<CustomerReportDemo | null>(null);
  const [reportLoading, setReportLoading] = useState(false);
  const [reportError, setReportError] = useState<string | null>(null);
  const [directoryError, setDirectoryError] = useState<string | null>(null);
  // Directory cache: unique departments and employees extracted from the
  // attendance-detail matrix.  Kept in separate state so it survives tab
  // switches — other tabs replace liveReport but we want the filter options
  // to remain stable throughout the session for the active company-month.
  const activeDirectoryKey = `${activeDataScope.reference}:${filters.month}`;
  const [liveDirectories, setLiveDirectories] = useState<ReadonlyMap<
    string,
    readonly CustomerReportDirectoryEntry[]
  >>(() => new Map());
  const directoryRequests = useRef(new Map<
    string,
    Promise<readonly CustomerReportDirectoryEntry[]>
  >());
  const activeLiveDirectory = liveDirectories.get(activeDirectoryKey) ?? null;
  const [organizationTree, setOrganizationTree] = useState<OrganizationNode[]>([]);

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

  /**
   * Real mode must never borrow demo rows. Until the realtime snapshot actually
   * arrives, the sheet shows zero rows with real metadata, so the loading and
   * error panels are the only thing the operator can act on. Fabricated numbers
   * under an error banner would read as measured data.
   */
  const emptyLiveReport = useMemo<CustomerReportDemo>(() => ({
    metadata: {
      isDemo: false,
      company: activeDataScope.label,
      generatedAt: '',
      month: filters.month,
      monthLabel: formatMonth(filters.month),
      rowCount: 0,
      dataScope: activeDataScope,
    },
    attendanceRows: [],
    dailyJournalRows: [],
    overtimeDailyRows: [],
    financeOvertimeRows: [],
    leaveRows: [],
    overtimeRows: [],
    workHoursRows: [],
    attendanceExceptionRows: [],
    lateRows: [],
    missedPunchRows: [],
    attendanceRateRows: [],
    annualLeaveRows: [],
  }), [activeDataScope, filters.month]);

  // Active report: live data in real mode, demo data only in demo mode.
  const sourceReport = isDemoMode()
    ? demoReport
    : (liveReport ?? emptyLiveReport);

  /**
   * `generatedAt` is the realtime calculation's `dataAsOf` in real mode. Before the
   * first successful load there is no such instant, so the header says the data
   * is not loaded rather than printing a plausible-looking timestamp.
   */
  const dataAsOfLabel = sourceReport.metadata.generatedAt === ''
    ? '数据尚未加载'
    : `${isDemoMode() ? '示例生成于' : '核算于'} ${formatDataAsOf(sourceReport.metadata.generatedAt)}`;
  const sourceVersionLabel = sourceReport.metadata.generatedAt === ''
    ? '来源信息尚未加载'
    : sourceReport.metadata.sourceVersions !== undefined
        && sourceReport.metadata.sourceVersions.length > 0
      ? formatSourceFreshness(sourceReport.metadata.sourceVersions)
      : '来源截止信息未提供';

  // Bumped by refresh/retry to reload authorization options before recalculating.
  const [reloadToken, setReloadToken] = useState(0);
  const [provisionalPoll, setProvisionalPoll] = useState(0);
  const provisionalPolls = useRef(0);
  const refreshReport = useCallback(() => {
    setLoadedScopeMonth(null);
    setLiveReport(null);
    setReportError(null);
    setDirectoryError(null);
    setProvisionalPoll(0);
    setLiveDirectories((current) => {
      if (!current.has(activeDirectoryKey)) return current;
      const next = new Map(current);
      next.delete(activeDirectoryKey);
      return next;
    });
    setReloadToken((token) => token + 1);
  }, [activeDirectoryKey]);

  // Load available scopes for the selected period (real mode only)
  useEffect(() => {
    if (isDemoMode()) return;
    let cancelled = false;
    setScopeLoadError(false);
    setLoadedScopeMonth(null);
    setLiveReport(null);
    setDirectoryError(null);
    setProvisionalPoll(0);
    provisionalPolls.current = 0;
    loadCustomerReportScopes(filters.month)
      .then((scopes) => {
        if (cancelled) return;
        setAvailableScopes(scopes);
        setLoadedScopeMonth(filters.month);
        if (scopes.length > 0) {
          // Functional update keeps the check off a stale closure value.
          setActiveScopeReference((current) => {
            if (deepLink.companyId
              && scopes.some((scope) => scope.reference === deepLink.companyId)) {
              return deepLink.companyId;
            }
            if (scopes.some((scope) => scope.reference === current)) {
              return current;
            }
            return pickPreferredCompany(scopes, (scope) => scope.label)?.reference
              ?? scopes[0]!.reference;
          });
        }
      })
      .catch(() => {
        if (!cancelled) {
          setScopeLoadError(true);
          setLoadedScopeMonth(null);
        }
      });
    return () => { cancelled = true; };
  }, [filters.month, reloadToken]);

  // The searchable directory is a separate, unfiltered projection. Cache every
  // visited company-month so tab changes and month round-trips do not collapse
  // the options to the active report's current subset.
  useEffect(() => {
    if (
      isDemoMode()
      || loadedScopeMonth !== filters.month
      || availableScopes.length === 0
      || liveDirectories.has(activeDirectoryKey)
    ) return;
    let cancelled = false;
    setDirectoryError(null);
    let request = directoryRequests.current.get(activeDirectoryKey);
    if (request === undefined) {
      request = loadCustomerReportDirectory(filters.month, activeDataScope.reference);
      directoryRequests.current.set(activeDirectoryKey, request);
      void request.finally(() => {
        if (directoryRequests.current.get(activeDirectoryKey) === request) {
          directoryRequests.current.delete(activeDirectoryKey);
        }
      }).catch(() => undefined);
    }
    void request
      .then((directory) => {
        if (cancelled) return;
        setLiveDirectories((current) => {
          if (current.has(activeDirectoryKey)) return current;
          const next = new Map(current);
          next.set(activeDirectoryKey, directory);
          return next;
        });
        setDirectoryError(null);
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setDirectoryError(
            error instanceof Error ? error.message : '加载授权部门与员工失败，请重试。',
          );
        }
      });
    return () => { cancelled = true; };
  }, [
    activeDataScope.reference,
    activeDirectoryKey,
    availableScopes.length,
    filters.month,
    liveDirectories,
    loadedScopeMonth,
    organizationTree,
  ]);

  // Load the active report sheet (real mode only)
  useEffect(() => {
    if (
      isDemoMode()
      || loadedScopeMonth !== filters.month
      || availableScopes.length === 0
    ) return;
    let cancelled = false;
    let retryTimer: number | undefined;
    const polling = provisionalPoll > 0 && liveReport !== null;
    if (!polling) {
      setReportLoading(true);
      provisionalPolls.current = 0;
    }
    setReportError(null);
    loadCustomerReport(
      activeReport,
      filters,
      activeDataScope,
      polling ? undefined : expectedProjectionVersion,
      (partial) => {
        if (cancelled) return;
        setLiveReport(partial);
        setReportLoading(false);
      },
    )
      .then((data) => {
        if (cancelled) return;
        setLiveReport(data);
        setReportLoading(false);
        if (
          isProvisionalRealtimeSource(data.metadata.sourceVersions)
          && provisionalPolls.current < 30
        ) {
          provisionalPolls.current += 1;
          retryTimer = window.setTimeout(() => {
            setProvisionalPoll((token) => token + 1);
          }, 2500);
        }
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setReportLoading(false);
          setReportError(
            error instanceof Error ? error.message : '加载报表失败，请重试。',
          );
        }
      });
    return () => {
      cancelled = true;
      if (retryTimer !== undefined) window.clearTimeout(retryTimer);
    };
  }, [
    activeReport,
    availableScopes.length,
    expectedProjectionVersion,
    filters,
    activeDataScope,
    loadedScopeMonth,
    provisionalPoll,
  ]);

  const report = useMemo(() => applyCustomerReportSpecificFilters(
    sourceReport,
    activeReport,
    appliedSpecificFilters,
  ), [activeReport, appliedSpecificFilters, sourceReport]);
  const overview = useMemo(
    () => overviewCards(report, activeReport),
    [activeReport, report],
  );
  const activeTab = customerReportTabs.find((tab) => tab.key === activeReport)!;
  const canExport = capabilities === undefined
    || capabilities.includes('ATTENDANCE_REPORT:EXPORT_CREATE');
  const canRecalculate = !isDemoMode()
    && (
      capabilities === undefined
        ? (sourceReport.metadata.allowedActions ?? []).includes('REPORT_RECALCULATE')
        : capabilities.includes('ATTENDANCE_REPORT:REFRESH')
          || (sourceReport.metadata.allowedActions ?? []).includes('REPORT_RECALCULATE')
    );
  const [recalculateLoading, setRecalculateLoading] = useState(false);
  const handleRecalculate = useCallback(async (
    window: 'LAST_3_DAYS' | 'LAST_7_DAYS' | 'MONTH',
  ) => {
    if (!canRecalculate || recalculateLoading) return;
    setRecalculateLoading(true);
    setReportError(null);
    try {
      await recalculateCustomerReport(
        activeDataScope.reference,
        filters.month,
        window,
      );
      refreshReport();
    } catch (error: unknown) {
      setReportError(
        error instanceof Error ? error.message : '重新计算失败，请稍后重试。',
      );
    } finally {
      setRecalculateLoading(false);
    }
  }, [
    activeDataScope.reference,
    canRecalculate,
    filters.month,
    recalculateLoading,
    refreshReport,
  ]);
  const capabilityMode = capabilities === undefined
    ? '独立演示'
    : canExport
      ? '授权导出'
      : '无导出权限';

  // Build department / employee filter options. Search text is deliberately
  // separate from the visible label so an employee remains selected by name
  // while operators can find them by name, employee number, or department.
  // Real mode: use the cached directory extracted from the attendance-detail
  // matrix load (persists across tab switches).  Employee list is filtered by
  // the currently-selected department.
  // Demo / fallback: use the scope's allow-list as before.
  const demoEmployeeDirectory = useMemo<readonly CustomerReportEmployeeDirectoryEntry[]>(() => {
    if (!isDemoMode()) return [];
    return getCustomerReportDemo({
      month: filters.month,
      department: '全部部门',
      employee: '全部员工',
    }, activeDataScope).attendanceRows.map((row) => ({
      employeeId: row.employeeId,
      employeeNo: row.employeeNo,
      employee: row.employee,
      organizationId: row.organizationId,
      department: row.department,
    }));
  }, [activeDataScope, filters.month]);

  const departmentTreeData = useMemo<DataNode[]>(() => {
    const tree = Array.isArray(organizationTree) ? organizationTree : [];
    const company = tree.find((node) => (
      node.organizationId === activeDataScope.reference
      || node.name === activeDataScope.label
    ));
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
        title: '全部授权部门',
        children: company ? toNodes(company.children) : toNodes(tree),
      },
    ];
  }, [activeDataScope.label, activeDataScope.reference, organizationTree]);

  const departmentOptions = useMemo<CustomerReportDepartmentOption[]>(() => {
    const options = !isDemoMode() && activeLiveDirectory !== null
      ? [
        { value: '全部部门', label: '全部授权部门' },
        ...Array.from(new Map(activeLiveDirectory.map((entry) => [
          entry.organizationId,
          { value: entry.organizationId, label: entry.department, organizationId: entry.organizationId },
        ])).values()).sort((left, right) => left.label.localeCompare(right.label, 'zh-CN')),
      ]
      : authorizedDepartmentOptions(activeDataScope);
    return options.map((option) => ({
      ...option,
      searchText: `${option.label} ${option.value}`,
    }));
  }, [activeDataScope, activeLiveDirectory]);

  const employeeOptions = useMemo<CustomerReportEmployeeOption[]>(() => {
    const directory = !isDemoMode() && activeLiveDirectory !== null
      ? activeLiveDirectory
      : demoEmployeeDirectory;
    const employeeByIdentity = new Map<string, CustomerReportEmployeeDirectoryEntry>();
    directory.forEach((entry) => {
      [entry.employeeId, entry.employeeNo, entry.employee]
        .filter((identity): identity is string => Boolean(identity))
        .forEach((identity) => employeeByIdentity.set(identity, entry));
    });
    const selectedOrganizationIds = collectDescendantOrganizationIds(
      organizationTree,
      filters.organizationId,
    );
    const options = !isDemoMode() && activeLiveDirectory !== null
      ? [
        { value: '全部员工', label: '全部授权员工' },
        ...directory
          .filter((entry) => (
            filters.department === '全部部门'
            || (filters.organizationId !== undefined
              && selectedOrganizationIds.has(entry.organizationId ?? ''))
            || entry.organizationId === filters.organizationId
          ))
          .map((entry) => ({
            value: entry.employeeId ?? entry.employeeNo ?? entry.employee,
            label: entry.employee,
            employeeId: entry.employeeId,
          })),
      ]
      : authorizedEmployeeOptions(activeDataScope, filters.department);
    return options.map((option) => {
      const employee = employeeByIdentity.get(option.value);
      return {
        ...option,
        employeeId: employee?.employeeId,
        employeeNo: employee?.employeeNo,
        department: employee?.department,
        searchText: [
          option.label,
          option.value,
          employee?.employeeNo,
          employee?.department,
        ].filter(Boolean).join(' '),
      };
    });
  }, [
    activeDataScope,
    activeLiveDirectory,
    demoEmployeeDirectory,
    filters.department,
    filters.organizationId,
    organizationTree,
  ]);

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
          String(value),
          activeDataScope.allowedDepartments,
        )
      ));
      setAppliedSpecificFilters((specificFilters) => (
        normalizeAnnualLeaveFilters(
          specificFilters,
          String(value),
          activeDataScope.allowedDepartments,
        )
      ));
    }
    setFilters((current) => {
      if (key === 'department') {
        return {
          ...current,
          department: String(value),
          employee: '全部员工',
          organizationId: undefined,
          employeeId: undefined,
        };
      }
      if (key === 'month') {
        const month = String(value);
        const bounds = monthBounds(month);
        return {
          ...current,
          month,
          fromDate: bounds.fromDate,
          toDate: bounds.toDate,
        };
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
      organizationId: filters.organizationId,
      employeeId: filters.employeeId,
      rowCount: rowCountForReport(report, activeReport),
      generatedAt,
      reportFilters: specificCriteriaForReport(activeReport, appliedSpecificFilters),
      dataScopeReference: activeDataScope.reference,
      dataScopeLabel: activeDataScope.label,
    };
    setExportFeedback('正在生成导出文件…');
    void downloadCustomerReportWorkbook({
      reportKey: activeReport,
      reportTitle: activeTab.label,
      month: filters.month,
      report,
      overtimeType: appliedSpecificFilters.overtimeType,
    })
      .then(() => {
        onExport?.(request);
        setSpecificFeedback('');
        setExportFeedback(
          `“${activeTab.label}”已按当前${isDemoMode() ? '筛选条件' : '屏幕'}导出。`,
        );
      })
      .catch((error: unknown) => {
        setExportFeedback(
          error instanceof Error ? error.message : '导出失败，请刷新报表后重试。',
        );
      });
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
    const nextScope = resolvedScopes.find((scope) => scope.reference === reference);
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
            {isDemoMode() ? (
              <span className="customer-report__demo-badge">客户演示数据</span>
            ) : null}
            <span>{dataAsOfLabel}</span>
            {!isDemoMode() ? (
              <span title={sourceReport.metadata.sourceVersions?.join('；')}>
                {sourceVersionLabel}
              </span>
            ) : null}
          </div>
          <h1>考勤报表中心</h1>
          <p>
            显示已钉住的核算结果；新打卡和单据在重新计算前不会改数字。OPEN 月若还没有核算结果，由得力/OA 定时同步成功后的自动任务补一次，或由有权限的人点重新计算。
            {!isDemoMode() && sourceReport.metadata.periodState === 'OPEN' ? (
              <span className="customer-report__open-badge"> 暂算 · 期间未关闭</span>
            ) : null}
            {!isDemoMode()
              && canRecalculate
              && sourceReport.metadata.sourcesNewerThanPin ? (
                <span className="customer-report__open-badge"> 来源已更新，可重新计算</span>
              ) : null}
          </p>
        </div>
        <div className="customer-report__hero-action">
          <span className="customer-report__version">
            统计期间 · {formatChineseRange(filters.fromDate, filters.toDate)}
          </span>
          <div className="customer-report__hero-buttons">
            {!isDemoMode() ? (
              <Button
                size="large"
                loading={reportLoading}
                icon={reportLoading
                  ? undefined
                  : <IconRefresh aria-hidden="true" stroke={2} />}
                onClick={refreshReport}
              >
                刷新数据
              </Button>
            ) : null}
            {canRecalculate ? (
              <>
                <Button
                  size="large"
                  loading={recalculateLoading}
                  onClick={() => { void handleRecalculate('LAST_3_DAYS'); }}
                >
                  重新计算近3天
                </Button>
                <Button
                  size="large"
                  loading={recalculateLoading}
                  onClick={() => { void handleRecalculate('LAST_7_DAYS'); }}
                >
                  重新计算近一周
                </Button>
                <Button
                  size="large"
                  loading={recalculateLoading}
                  onClick={() => { void handleRecalculate('MONTH'); }}
                >
                  重新计算本月
                </Button>
              </>
            ) : null}
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
        </div>
      </header>

      {isDemoMode() ? (
        <section className="customer-report__notice" aria-label="演示数据说明">
          <IconInfoCircle aria-hidden="true" stroke={2} />
          <p>
            <strong>演示说明：</strong>
            当前页面使用脱敏示例数据，字段、颜色和统计方式按现有电子表格样表呈现。
          </p>
        </section>
      ) : null}

      <section className="customer-report__scope-card" aria-label="数据权限">
        <div className="customer-report__scope-icon" aria-hidden="true">
          <IconShieldCheck stroke={2} />
        </div>
        <div className="customer-report__scope-copy">
          <span>数据权限已生效</span>
          <strong>{activeDataScope.actorLabel} · {activeDataScope.label}</strong>
          <p>
            系统已按{scopeTypeLabel(activeDataScope.type)}限制查询和明细范围。
          </p>
        </div>
        {/* Show scope selector only when there are multiple authorized scopes.
            Never fall back to hardcoded presets — the server is the authority. */}
        {!isDemoMode() && scopeLoadError ? (
          <span className="customer-report__scope-error" aria-live="polite">
            权限加载失败，请刷新页面
          </span>
        ) : resolvedScopes.length > 1 ? (
          <label className="customer-report__scope-selector">
            <span>权限角色</span>
            <Select<string, CustomerReportFilterOption>
              aria-label="权限角色"
              showSearch
              value={activeDataScope.reference}
              options={resolvedScopes.map((scope) => ({
                value: scope.reference,
                label: scope.actorLabel,
                searchText: `${scope.actorLabel} ${scope.label} ${scope.reference}`,
              }))}
              onChange={changeDataScope}
              optionFilterProp="searchText"
              filterOption={filterCustomerReportOption}
              notFoundContent="未找到匹配权限范围"
              optionRender={isDemoMode() ? undefined : (option) => (
                <span className="customer-report__employee-option">
                  <span>{option.data.label}</span>
                  <small>{resolvedScopes.find(
                    (scope) => scope.reference === option.data.value,
                  )?.label}</small>
                </span>
              )}
              popupMatchSelectWidth={false}
            />
          </label>
        ) : null}
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
              const month = defaultQueryPeriod().format('YYYY-MM');
              setFilters({
                month,
                department: '全部部门',
                employee: '全部员工',
                organizationId: undefined,
                employeeId: undefined,
                ...monthBounds(month),
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
            <span>起止日期</span>
            <DatePicker.RangePicker
              aria-label="起止日期"
              locale={zhCN.DatePicker}
              format="YYYY年M月D日"
              allowClear={false}
              value={
                filters.fromDate && filters.toDate
                  ? [dayjs(filters.fromDate), dayjs(filters.toDate)]
                  : undefined
              }
              presets={[
                {
                  label: '本月',
                  value: [dayjs().startOf('month'), dayjs().endOf('month')],
                },
                {
                  label: '上月',
                  value: [
                    dayjs().subtract(1, 'month').startOf('month'),
                    dayjs().subtract(1, 'month').endOf('month'),
                  ],
                },
              ]}
              onChange={(range) => {
                if (range?.[0] && range[1]) {
                  const from = range[0];
                  const to = range[1];
                  if (from.format('YYYY-MM') !== to.format('YYYY-MM')) {
                    Modal.warning({
                      title: '暂不支持跨月',
                      content: '考勤报表目前只支持同一个月内的日期筛选，跨月展示列为后续功能。请改选同一月的起止日期。',
                    });
                    return;
                  }
                  setExportFeedback('');
                  setSpecificFeedback('');
                  setFilters((current) => ({
                    ...current,
                    month: from.format('YYYY-MM'),
                    fromDate: from.format('YYYY-MM-DD'),
                    toDate: to.format('YYYY-MM-DD'),
                  }));
                }
              }}
            />
          </label>
          <label>
            <span>部门</span>
            {!isDemoMode() && organizationTree.length > 0 ? (
            <TreeSelect
              aria-label="部门"
              allowClear
              showSearch
              treeDefaultExpandAll={false}
              treeNodeFilterProp="title"
              placeholder="全部授权部门"
              value={filters.organizationId ?? ALL_DEPARTMENTS}
              treeData={departmentTreeData}
              onChange={(value) => {
                const selectedId = value == null || value === ''
                  ? ALL_DEPARTMENTS
                  : String(value);
                setExportFeedback('');
                setSpecificFeedback('');
                const department = selectedId === ALL_DEPARTMENTS
                  ? ALL_DEPARTMENTS
                  : findOrganizationTitle(departmentTreeData, selectedId)
                    ?? selectedId;
                setFilters((current) => ({
                  ...current,
                  department,
                  employee: ALL_EMPLOYEES,
                  organizationId: selectedId === ALL_DEPARTMENTS
                    ? undefined
                    : selectedId,
                  employeeId: undefined,
                }));
                setDraftSpecificFilters((specificFilters) => (
                  normalizeAnnualLeaveFilters(
                    specificFilters,
                    department,
                    activeDataScope.allowedDepartments,
                  )
                ));
                setAppliedSpecificFilters((specificFilters) => (
                  normalizeAnnualLeaveFilters(
                    specificFilters,
                    department,
                    activeDataScope.allowedDepartments,
                  )
                ));
              }}
              notFoundContent="未找到匹配部门"
              popupMatchSelectWidth={false}
            />
            ) : (
            <Select<string, CustomerReportDepartmentOption>
              aria-label="部门"
              allowClear
              showSearch
              placeholder="全部授权部门"
              value={filters.organizationId ?? filters.department}
              options={departmentOptions}
              onChange={(value, option) => {
                if (value === undefined || value === ALL_DEPARTMENTS) {
                  setExportFeedback('');
                  setSpecificFeedback('');
                  setFilters((current) => ({
                    ...current,
                    department: ALL_DEPARTMENTS,
                    employee: ALL_EMPLOYEES,
                    organizationId: undefined,
                    employeeId: undefined,
                  }));
                  return;
                }
                const selectedOption = getSingleSelectOption(option);
                if (selectedOption === undefined) return;
                setExportFeedback('');
                setSpecificFeedback('');
                const department = selectedOption.label;
                setFilters((current) => ({
                  ...current,
                  department,
                  employee: ALL_EMPLOYEES,
                  organizationId: value === ALL_DEPARTMENTS
                    ? undefined
                    : selectedOption.organizationId,
                  employeeId: undefined,
                }));
                setDraftSpecificFilters((specificFilters) => (
                  normalizeAnnualLeaveFilters(
                    specificFilters,
                    department,
                    activeDataScope.allowedDepartments,
                  )
                ));
                setAppliedSpecificFilters((specificFilters) => (
                  normalizeAnnualLeaveFilters(
                    specificFilters,
                    department,
                    activeDataScope.allowedDepartments,
                  )
                ));
              }}
              optionFilterProp="searchText"
              filterOption={filterCustomerReportOption}
              notFoundContent="未找到匹配部门"
              labelRender={isDemoMode() ? undefined : () => (
                filters.department === ALL_DEPARTMENTS
                  ? '全部授权部门'
                  : filters.department
              )}
              optionRender={(option) => option.data.label}
              popupMatchSelectWidth={false}
            />
            )}
          </label>
          <label>
            <span>员工</span>
            <Select<string, CustomerReportEmployeeOption>
              aria-label="员工"
              allowClear
              showSearch
              placeholder="全部授权员工"
              value={filters.employeeId ?? filters.employee}
              options={employeeOptions}
              onChange={(value, option) => {
                if (value === undefined || value === ALL_EMPLOYEES) {
                  setExportFeedback('');
                  setSpecificFeedback('');
                  setFilters((current) => ({
                    ...current,
                    employee: ALL_EMPLOYEES,
                    employeeId: undefined,
                  }));
                  return;
                }
                const selectedOption = getSingleSelectOption(option);
                if (selectedOption === undefined) return;
                setExportFeedback('');
                setSpecificFeedback('');
                setFilters((current) => ({
                  ...current,
                  employee: selectedOption.label,
                  employeeId: selectedOption.employeeId,
                }));
              }}
              optionFilterProp="searchText"
              filterOption={filterCustomerReportOption}
              notFoundContent="未找到匹配员工"
              labelRender={isDemoMode() ? undefined : () => (
                filters.employee === ALL_EMPLOYEES
                  ? '全部授权员工'
                  : filters.employee
              )}
              optionRender={(option) => {
                const { employeeNo, department } = option.data;
                const details = [employeeNo, department].filter(Boolean).join(' · ');
                return (
                  <span className="customer-report__employee-option">
                    <span>{option.data.label}</span>
                    {details ? <small>{details}</small> : null}
                  </span>
                );
              }}
              popupMatchSelectWidth={false}
            />
          </label>
          <div className="customer-report__filter-result">
            <span>当前范围</span>
            <strong>{filters.department} · {filters.employee}</strong>
            <small>
              {formatChineseRange(filters.fromDate, filters.toDate)}，共 {report.metadata.rowCount} 名授权员工
            </small>
          </div>
        </div>
      </section>

      {!isDemoMode() && reportLoading ? (
        <div className="customer-report__load-status" role="status" aria-live="polite">
          <IconInfoCircle aria-hidden="true" stroke={2} />
          正在汇总本月花名册和打卡。先出的是预览，漏刷可能还会变；完整核算完成后会自动刷新成同一份结果。
        </div>
      ) : null}

      {!isDemoMode() && (reportError !== null || directoryError !== null) ? (
        <div className="customer-report__load-error" role="alert">
          <IconInfoCircle aria-hidden="true" stroke={2} />
          <span>{reportError ?? directoryError}</span>
          <Button size="small" onClick={refreshReport}>重试</Button>
        </div>
      ) : null}

      {!isDemoMode() && liveReport?.metadata.truncated ? (
        <div className="customer-report__truncation-notice" role="status">
          <IconInfoCircle aria-hidden="true" stroke={2} />
          正在加载其余员工，当前为预览；全部到齐后人数会自动更新。
        </div>
      ) : null}

      <section className="customer-report__metrics" aria-label="当前范围概览">
        {overview.map((card) => (
          <MetricCard
            key={card.label}
            label={card.label}
            value={card.value}
            unit={card.unit}
            hint={card.hint}
            tone={card.tone}
          />
        ))}
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
          <ActiveReport
            reportKey={activeReport}
            report={report}
            overtimeType={appliedSpecificFilters.overtimeType}
          />
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
    case 'overtime-daily':
    case 'finance-overtime':
      return (
        <>
          <SpecificSelect
            label="加班类别"
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
    case 'daily-journal':
      return null;
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
  const searchableOptions: CustomerReportFilterOption[] = options.map((option) => ({
    ...option,
    searchText: `${option.label} ${option.value}`,
  }));
  return (
    <label className="customer-report__specific-field">
      <span>{label}</span>
      <Select<string, CustomerReportFilterOption>
        aria-label={label}
        showSearch
        value={value}
        options={searchableOptions}
        onChange={onChange}
        optionFilterProp="searchText"
        filterOption={filterCustomerReportOption}
        notFoundContent={`未找到匹配${label}`}
        popupMatchSelectWidth={false}
      />
    </label>
  );
}

function filterCustomerReportOption(
  input: string,
  option?: CustomerReportFilterOption,
): boolean {
  const query = input.trim().toLocaleLowerCase('zh-CN');
  if (!query) return true;
  return (option?.searchText ?? '')
    .toLocaleLowerCase('zh-CN')
    .includes(query);
}

function collectDescendantOrganizationIds(
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

function getSingleSelectOption<OptionType>(
  option: OptionType | OptionType[] | undefined,
): OptionType | undefined {
  return Array.isArray(option) ? option[0] : option;
}

function ActiveReport({
  reportKey,
  report,
  overtimeType,
}: {
  reportKey: CustomerReportKey;
  report: CustomerReportDemo;
  overtimeType?: string;
}) {
  switch (reportKey) {
    case 'attendance-detail':
      return <AttendanceDetailReport report={report} />;
    case 'leave':
      return <LeaveReport report={report} />;
    case 'overtime':
      return <OvertimeReport report={report} />;
    case 'overtime-daily':
      return <OvertimeDailyReport report={report} overtimeType={overtimeType} />;
    case 'finance-overtime':
      return <FinanceOvertimeReport report={report} overtimeType={overtimeType} />;
    case 'daily-journal':
      return <DailyJournalReport report={report} />;
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
              <th scope="col" className="customer-report__matrix-dept">部门</th>
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
              <AttendanceMatrixRow row={row} key={row.employeeId ?? row.employeeNo} />
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
      <td className="customer-report__matrix-dept customer-report__dept" title={visibleDepartmentPath(row.department)}>
        {departmentPathNodes(row.department)}
      </td>
      {row.days.map((day) => {
        const merged = day.merged === true;
        const content = merged
          ? (
            <div
              className={slotClassName('customer-report__attendance-cell is-merged', day.mergedStatus, day.mergedLabel)}
              style={slotStyle(day.mergedStatus)}
            >
              <span>{day.mergedLabel || day.primary}</span>
            </div>
          )
          : (
            <div className="customer-report__attendance-cell">
              <span className={slotClassName('customer-report__slot', day.primaryStatus, day.primary)} style={slotStyle(day.primaryStatus)}>
                {day.primary}
              </span>
              <small className={slotClassName('customer-report__slot', day.secondaryStatus, day.secondary)} style={slotStyle(day.secondaryStatus)}>
                {day.secondary}
              </small>
            </div>
          );
        return (
          <td key={day.day} className={day.status || day.merged ? 'has-status' : undefined}>
            <Tooltip
              title={(
                <div className="customer-report__cell-tooltip">
                  {day.note ?? `${day.primary || '无'} / ${day.secondary || '无'}`}
                </div>
              )}
              placement="top"
            >
              {content}
            </Tooltip>
          </td>
        );
      })}
    </tr>
  );
}

function slotClassName(
  base: string,
  status: AttendanceStatusKey | undefined,
  text: string | undefined,
): string {
  const classes = [base];
  if (status) classes.push(`is-${status}`);
  if ((text ?? '').includes('补签') || status === 'corrected') {
    classes.push('is-corrected');
  }
  return classes.join(' ');
}

function slotStyle(status: AttendanceStatusKey | undefined): CSSProperties | undefined {
  const color = status ? statusColor(status) : undefined;
  return color ? { backgroundColor: color } : undefined;
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
                <td className="customer-report__dept" title={visibleDepartmentPath(row.department)}>{departmentPathNodes(row.department)}</td>
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
  return (
    <ReportSheet
      title={`${report.metadata.monthLabel}加班统计`}
      subtitle="一行一张加班单，含 OA 与纸质来源"
      meta={`${report.overtimeRows.length} 张单据`}
    >
      <ScrollTable>
        <table className="customer-report__table" data-testid="report-table">
          <thead>
            <tr>
              <th scope="col">工号</th>
              <th scope="col">部门</th>
              <th scope="col">姓名</th>
              <th scope="col">加班类型</th>
              <th scope="col">加班时段</th>
              <th scope="col">小时小时</th>
              <th scope="col">审批状态</th>
              <th scope="col">来源</th>
            </tr>
          </thead>
          <tbody>
            {report.overtimeRows.length > 0 ? report.overtimeRows.map((row) => (
              <tr key={row.rowKey ?? `${row.employeeNo ?? row.employee}\u0000${row.period}`}>
                <td>{row.employeeNo ?? '—'}</td>
                <td className="customer-report__dept" title={visibleDepartmentPath(row.department)}>{departmentPathNodes(row.department)}</td>
                <td><strong>{row.employee}</strong></td>
                <td><StatusPill label={row.overtimeType} /></td>
                <td>{row.period}</td>
                <td className="customer-report__number">{row.hours.toFixed(1)}</td>
                <td><StatePill label={row.approvalState} /></td>
                <td>{row.source}</td>
              </tr>
            )) : <EmptyTableRow colSpan={8} />}
          </tbody>
        </table>
      </ScrollTable>
    </ReportSheet>
  );
}

function FinanceOvertimeReport({
  report,
  overtimeType,
}: {
  report: CustomerReportDemo;
  overtimeType?: string;
}) {
  const feeColumns = overtimeFeeColumns(overtimeType);
  const dates = [...new Set(
    report.financeOvertimeRows.flatMap((row) => row.days.map((day) => day.date.slice(0, 10))),
  )].sort();
  const weekdayTotal = report.financeOvertimeRows.reduce((sum, row) => sum + row.weekdayOvertimeHours, 0);
  const weekendTotal = report.financeOvertimeRows.reduce((sum, row) => sum + row.weekendOvertimeHours, 0);
  const holidayTotal = report.financeOvertimeRows.reduce((sum, row) => sum + row.holidayOvertimeHours, 0);
  return (
    <ReportSheet
      title={`${report.metadata.monthLabel}每日加班`}
      subtitle="一人一行：平时 / 周末 / 节假日合计，后面按日列加班小时"
      meta={`${report.financeOvertimeRows.length} 人`}
    >
      <ScrollTable>
        <table className="customer-report__table customer-report__table--finance-overtime" data-testid="report-table">
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
                <th scope="col" key={date}>{`${Number(date.slice(5, 7))}月${Number(date.slice(8, 10))}日`}</th>
              ))}
            </tr>
            <tr>
              {dates.map((date) => (
                <th scope="col" key={`${date}-wd`}>
                  {(() => {
                    const day = new Date(`${date}T00:00:00`).getDay();
                    return day === 0 ? 7 : day;
                  })()}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {report.financeOvertimeRows.length > 0 ? report.financeOvertimeRows.map((row) => {
              const byDate = new Map(row.days.map((day) => [day.date.slice(0, 10), day.hours]));
              return (
                <tr key={row.employeeNo || row.employee}>
                  <td className="customer-report__dept" title={visibleDepartmentPath(row.department)}>
                    {departmentPathNodes(row.department)}
                  </td>
                  <td>{row.employeeNo}</td>
                  <td><strong>{row.employee}</strong></td>
                  <td className="customer-report__number">{formatFinanceHours(row.weekdayOvertimeHours, true)}</td>
                  <td className="customer-report__number">{formatFinanceHours(row.weekendOvertimeHours, true)}</td>
                  <td className="customer-report__number">{formatFinanceHours(row.holidayOvertimeHours, true)}</td>
                  {feeColumns.paid ? (
                    <td className="customer-report__number">{formatFinanceHours(row.paidOvertimeHours ?? 0, true)}</td>
                  ) : null}
                  {feeColumns.compensatory ? (
                    <td className="customer-report__number">{formatFinanceHours(row.compensatoryOvertimeHours ?? 0, true)}</td>
                  ) : null}
                  {feeColumns.voluntary ? (
                    <td className="customer-report__number">{formatFinanceHours(row.voluntaryOvertimeHours ?? 0, true)}</td>
                  ) : null}
                  {dates.map((date) => {
                    const hours = byDate.get(date) ?? 0;
                    const day = row.days.find((item) => item.date.slice(0, 10) === date);
                    const fill = hours > 0 ? overtimeCellFill({
                      treatment: day?.treatment,
                      paidHours: day?.paidHours,
                      compensatoryHours: day?.compensatoryHours,
                      voluntaryHours: day?.voluntaryHours,
                    }, overtimeType) : undefined;
                    const title = overtimeTreatmentHover({
                      paidHours: day?.paidHours,
                      compensatoryHours: day?.compensatoryHours,
                      voluntaryHours: day?.voluntaryHours,
                    });
                    return (
                      <td
                        key={date}
                        className="customer-report__number"
                        style={fill}
                        title={title || undefined}
                      >
                        {formatFinanceHours(Number(hours), true)}
                      </td>
                    );
                  })}
                </tr>
              );
            }) : <EmptyTableRow colSpan={6 + (feeColumns.paid ? 1 : 0) + (feeColumns.compensatory ? 1 : 0) + (feeColumns.voluntary ? 1 : 0) + dates.length} />}
          </tbody>
          {report.financeOvertimeRows.length > 0 ? (
            <tfoot>
              <tr>
                <td>总计</td>
                <td />
                <td />
                <td className="customer-report__number">{formatFinanceHours(weekdayTotal, true)}</td>
                <td className="customer-report__number">{formatFinanceHours(weekendTotal, true)}</td>
                <td className="customer-report__number">{formatFinanceHours(holidayTotal, true)}</td>
                {feeColumns.paid ? (
                  <td className="customer-report__number">
                    {formatFinanceHours(report.financeOvertimeRows.reduce((sum, row) => sum + (row.paidOvertimeHours ?? 0), 0), true)}
                  </td>
                ) : null}
                {feeColumns.compensatory ? (
                  <td className="customer-report__number">
                    {formatFinanceHours(report.financeOvertimeRows.reduce((sum, row) => sum + (row.compensatoryOvertimeHours ?? 0), 0), true)}
                  </td>
                ) : null}
                {feeColumns.voluntary ? (
                  <td className="customer-report__number">
                    {formatFinanceHours(report.financeOvertimeRows.reduce((sum, row) => sum + (row.voluntaryOvertimeHours ?? 0), 0), true)}
                  </td>
                ) : null}
                {dates.map((date) => {
                  const total = report.financeOvertimeRows.reduce((sum, row) => {
                    const hours = row.days.find((day) => day.date.slice(0, 10) === date)?.hours ?? 0;
                    return sum + hours;
                  }, 0);
                  return <td key={date} className="customer-report__number">{formatFinanceHours(total, true)}</td>;
                })}
              </tr>
            </tfoot>
          ) : null}
        </table>
      </ScrollTable>
    </ReportSheet>
  );
}

function OvertimeDailyReport({
  report,
  overtimeType,
}: {
  report: CustomerReportDemo;
  overtimeType?: string;
}) {
  const feeColumns = overtimeFeeColumns(overtimeType);
  return (
    <ReportSheet
      title={`${report.metadata.monthLabel}加班日报`}
      subtitle="每人每天工作日 / 周末 / 节假日加班小时，供财务计薪"
      meta={`${report.overtimeDailyRows.length} 行`}
    >
      <ScrollTable>
        <table className="customer-report__table" data-testid="report-table">
          <thead>
            <tr>
              <th scope="col">工号</th>
              <th scope="col">部门</th>
              <th scope="col">姓名</th>
              <th scope="col">日期</th>
              <th scope="col">工作日加班</th>
              <th scope="col">周末加班</th>
              <th scope="col">节假日加班</th>
              {feeColumns.paid ? <th scope="col">加班费</th> : null}
              {feeColumns.compensatory ? <th scope="col">转调休</th> : null}
              {feeColumns.voluntary ? <th scope="col">义务加班</th> : null}
            </tr>
          </thead>
          <tbody>
            {report.overtimeDailyRows.length > 0 ? report.overtimeDailyRows.map((row) => (
              <tr key={`${row.employeeNo}-${row.businessDate}`}>
                <td>{row.employeeNo}</td>
                <td className="customer-report__dept" title={visibleDepartmentPath(row.department)}>{departmentPathNodes(row.department)}</td>
                <td><strong>{row.employee}</strong></td>
                <td>{row.businessDate}</td>
                <td className="customer-report__number">{formatFinanceHours(row.weekdayOvertimeHours, true)}</td>
                <td className="customer-report__number">{formatFinanceHours(row.weekendOvertimeHours, true)}</td>
                <td className="customer-report__number">{formatFinanceHours(row.holidayOvertimeHours, true)}</td>
                {feeColumns.paid ? (
                  <td className="customer-report__number">{formatFinanceHours(row.paidOvertimeHours ?? 0, true)}</td>
                ) : null}
                {feeColumns.compensatory ? (
                  <td className="customer-report__number">{formatFinanceHours(row.compensatoryOvertimeHours ?? 0, true)}</td>
                ) : null}
                {feeColumns.voluntary ? (
                  <td className="customer-report__number">{formatFinanceHours(row.voluntaryOvertimeHours ?? 0, true)}</td>
                ) : null}
              </tr>
            )) : <EmptyTableRow colSpan={9} />}
          </tbody>
        </table>
      </ScrollTable>
    </ReportSheet>
  );
}

function DailyJournalReport({ report }: { report: CustomerReportDemo }) {
  return (
    <ReportSheet
      title={`${report.metadata.monthLabel}考勤日报`}
      subtitle="一人一日一行，上下班、异常与加班供财务对账"
      meta={`${report.dailyJournalRows.length} 行`}
    >
      <ScrollTable>
        <table className="customer-report__table" data-testid="report-table">
          <thead>
            <tr>
              <th scope="col">序号</th>
              <th scope="col">部门</th>
              <th scope="col">工号</th>
              <th scope="col">姓名</th>
              <th scope="col">日期</th>
              <th scope="col">班次</th>
              <th scope="col">上班</th>
              <th scope="col">下班</th>
              <th scope="col">迟到</th>
              <th scope="col">早退</th>
              <th scope="col">旷工</th>
              <th scope="col">请假</th>
              <th scope="col">加班</th>
              <th scope="col">备注</th>
            </tr>
          </thead>
          <tbody>
            {report.dailyJournalRows.length > 0 ? report.dailyJournalRows.map((row) => (
              <tr key={`${row.employeeNo}-${row.businessDate}`}>
                <td>{row.sequence}</td>
                <td className="customer-report__dept" title={visibleDepartmentPath(row.department)}>{departmentPathNodes(row.department)}</td>
                <td>{row.employeeNo}</td>
                <td><strong>{row.employee}</strong></td>
                <td>{row.businessDate}</td>
                <td>{row.shiftLabel}</td>
                <td>{row.onDuty}</td>
                <td>{row.offDuty}</td>
                <td>{row.lateHours}</td>
                <td>{row.earlyHours}</td>
                <td>{row.absenceHours}</td>
                <td>{row.leaveType}</td>
                <td className="customer-report__number">{row.overtimeHours}</td>
                <td>{row.remark}</td>
              </tr>
            )) : <EmptyTableRow colSpan={14} />}
          </tbody>
        </table>
      </ScrollTable>
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
              <th scope="col">{Number.parseInt(report.metadata.month.slice(5, 7), 10)}月应出勤工时</th>
              <th scope="col">加班时数</th>
              <th scope="col">义务加班</th>
              <th scope="col">事假+病假+其他假期</th>
              <th scope="col">年假</th>
              <th scope="col">加班换调休</th>
              <th scope="col">实际调休</th>
              <th scope="col">个人实际出勤工时</th>
              <th scope="col">备注</th>
            </tr>
          </thead>
          <tbody>
            {report.workHoursRows.length > 0 ? report.workHoursRows.map((row) => (
              <tr key={row.rowKey ?? `${row.department}\u0000${row.employee}`}>
                <td><strong>{row.employee}</strong></td>
                <td className="customer-report__dept" title={visibleDepartmentPath(row.department)}>{departmentPathNodes(row.department)}</td>
                <td>{row.plannedHours.toFixed(1)}</td>
                <td>{row.overtimeHours.toFixed(1)}</td>
                <td>{(row.voluntaryOvertimeHours ?? 0).toFixed(1)}</td>
                <td>{row.leaveHours.toFixed(1)}</td>
                <td>{(row.annualLeaveHours ?? 0).toFixed(1)}</td>
                <td>{(row.exchangedHours ?? 0).toFixed(1)}</td>
                <td>{(row.usedTimeOffHours ?? 0).toFixed(1)}</td>
                <td className="customer-report__number"><strong>{row.actualHours.toFixed(1)}</strong></td>
                <td>{row.note}</td>
              </tr>
            )) : <EmptyTableRow colSpan={11} />}
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
              <th scope="col">异常类型</th>
              <th scope="col">工号</th>
              <th scope="col">姓名</th>
              <th scope="col">部门</th>
              <th scope="col">详情</th>
              <th scope="col">处理状态</th>
            </tr>
          </thead>
          <tbody>
            {rows.length > 0 ? rows.map((row) => (
              <AttendanceExceptionRow row={row} key={row.id} />
            )) : <EmptyTableRow colSpan={7} />}
          </tbody>
        </table>
      </ScrollTable>
      <SheetFootnote>
        当前为常用异常首版；汇总与明细均已绑定
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
      <td><strong>{row.exceptionType}</strong></td>
      <td>{row.employeeNo}</td>
      <td><strong>{row.employee}</strong></td>
      <td className="customer-report__dept" title={visibleDepartmentPath(row.department)}>{departmentPathNodes(row.department)}</td>
      <td>{row.details}</td>
      <td><StatePill label={row.state} /></td>
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
              <td className="customer-report__dept" title={visibleDepartmentPath(row.department)}>{departmentPathNodes(row.department)}</td>
              <td><strong>{row.employee}</strong></td>
              <td className="customer-report__number">{row.count}</td>
              <td>{row.details}</td>
              <td>{row.reviewer}</td>
              <td><StatePill label={row.state ?? '—'} /></td>
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
      subtitle="出勤率按实际出勤天数 ÷ 应出勤天数计算；病假计入出勤并单独展示"
      meta={`${report.attendanceRateRows.length} 人`}
    >
      <ScrollTable>
        <table className="customer-report__table" data-testid="report-table">
          <thead>
            <tr>
              <th scope="col">序号</th>
              <th scope="col">部门</th>
              <th scope="col">姓名</th>
              <th scope="col">应出勤天数</th>
              <th scope="col">实际出勤天数</th>
              <th scope="col">病假天数</th>
              <th scope="col">出勤率</th>
              <th scope="col">口径说明</th>
            </tr>
          </thead>
          <tbody>
            {report.attendanceRateRows.length > 0 ? report.attendanceRateRows.map((row) => (
              <tr key={row.id}>
                <td>{row.id}</td>
                <td className="customer-report__dept" title={visibleDepartmentPath(row.department)}>{departmentPathNodes(row.department)}</td>
                <td><strong>{row.employee}</strong></td>
                <td>{displayDays(row.scheduledDays)}</td>
                <td>{displayDays(row.actualDays)}</td>
                <td>{displayDays(row.sickLeaveDays)}</td>
                <td>
                  <div className="customer-report__rate">
                    <strong>{row.rate}</strong>
                    <i style={{ width: rateBarWidth(row.rate) }} aria-hidden="true" />
                  </div>
                </td>
                <td>{row.note}</td>
              </tr>
            )) : <EmptyTableRow colSpan={8} />}
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
        <span>已使用 <strong>{sum(report.annualLeaveRows.flatMap((row) => row.monthlyUsedDays ?? [])).toFixed(1)} 天</strong></span>
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
              <th scope="col">部门</th>
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
            )) : <EmptyTableRow colSpan={25} />}
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
      <td className="customer-report__dept" title={visibleDepartmentPath(row.department)}>{departmentPathNodes(row.department)}</td>
      <td><strong>{row.employee}</strong></td>
      <td>{row.joinedOn}</td>
      <td>{(row.companySeniority ?? 0).toFixed(1)}</td>
      <td>{(row.priorSeniority ?? 0).toFixed(1)}</td>
      <td>{(row.totalSeniority ?? 0).toFixed(1)}</td>
      <td>{(row.statutoryDays ?? 0).toFixed(1)}</td>
      <td>{row.newHireDays ? row.newHireDays.toFixed(1) : '—'}</td>
      <td>{row.availableDays.toFixed(1)}</td>
      <td>{row.availableHours.toFixed(1)}</td>
      {(row.monthlyUsedDays ?? []).map((days, index) => (
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
          {isDemoMode() ? <span>示例数据 · 已脱敏</span> : null}
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
    'overtime-daily': '按加班费/转调休/义务加班与日期核对每日加班小时',
    'finance-overtime': '按加班类别与日期筛选一人一行日历加班',
    'daily-journal': '一人一日核对上下班、异常与加班',
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
    case 'overtime-daily':
    case 'finance-overtime':
      next.overtimeType = draft.overtimeType;
      next.overtimeDay = draft.overtimeDay;
      break;
    case 'daily-journal':
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
    case 'overtime-daily':
    case 'finance-overtime':
      next.overtimeType = defaultCustomerReportSpecificFilters.overtimeType;
      next.overtimeDay = defaultCustomerReportSpecificFilters.overtimeDay;
      break;
    case 'daily-journal':
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
    case 'overtime-daily':
    case 'finance-overtime':
    case 'daily-journal':
      return {};
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
    'overtime-daily': report.overtimeDailyRows.length,
    'finance-overtime': report.financeOvertimeRows.length,
    'daily-journal': report.dailyJournalRows.length,
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

function formatChineseRange(fromDate?: string, toDate?: string): string {
  if (!fromDate || !toDate) {
    return '';
  }
  const from = dayjs(fromDate);
  const to = dayjs(toDate);
  if (!from.isValid() || !to.isValid()) {
    return '';
  }
  if (from.format('YYYY-MM') === to.format('YYYY-MM')) {
    return `${from.format('YYYY年M月D日')} 至 ${to.format('M月D日')}`;
  }
  return `${from.format('YYYY年M月D日')} 至 ${to.format('YYYY年M月D日')}`;
}

function monthBounds(month: string): { fromDate: string; toDate: string } {
  return {
    fromDate: `${month}-01`,
    toDate: `${month}-${String(daysInMonth(month)).padStart(2, '0')}`,
  };
}

/**
 * Renders the realtime calculation's `dataAsOf` instant in the business time zone. An
 * unparseable value is shown verbatim rather than silently replaced, so a bad
 * upstream timestamp stays visible instead of looking like a valid reading.
 */
function formatDataAsOf(value: string): string {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).formatToParts(parsed);
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((candidate) => candidate.type === type)?.value ?? '';
  return `${part('year')}-${part('month')}-${part('day')} ${part('hour')}:${part('minute')}`;
}

function findOrganizationTitle(nodes: DataNode[], id: string): string | undefined {
  for (const node of nodes) {
    if (String(node.key) === id) {
      return typeof node.title === 'string' ? node.title : undefined;
    }
    const nested = node.children
      ? findOrganizationTitle(node.children, id)
      : undefined;
    if (nested !== undefined) return nested;
  }
  return undefined;
}

function formatSourceVersions(values: readonly string[]): string {
  const visible = values.slice(0, 3).map((value) => (
    value.length > 48 ? `${value.slice(0, 45)}…` : value
  ));
  return values.length > visible.length
    ? `${visible.join(' · ')} · 另 ${values.length - visible.length} 项`
    : visible.join(' · ');
}

const sourceCutoffSpecifications = [
  { source: 'DELI_CLOUD', label: '得力截止' },
  { source: 'OA_ATTENDANCE', label: 'OA截止' },
] as const;

function formatSourceFreshness(values: readonly string[]): string {
  const cutoffs = sourceCutoffSpecifications.map((specification) => ({
    ...specification,
    value: realtimeSourceCutoff(values, specification.source),
  }));
  if (cutoffs.every((cutoff) => cutoff.value === undefined)) {
    return `来源版本 · ${formatSourceVersions(values)}`;
  }
  return cutoffs.map((cutoff) => {
    if (cutoff.value === undefined) return `${cutoff.label} 未提供`;
    if (cutoff.value === 'UNSYNCED') return `${cutoff.label} 未同步`;
    if (cutoff.value === 'PARTIALLY_UNSYNCED') {
      return `${cutoff.label} 部分未同步`;
    }
    return `${cutoff.label} ${formatDataAsOf(cutoff.value)}`;
  }).join(' · ');
}

const reportTypeToCustomerKey: Record<string, CustomerReportKey> = {
  ATTENDANCE_DETAIL: 'attendance-detail',
  LEAVE: 'leave',
  OVERTIME: 'overtime',
  WORK_HOURS: 'work-hours',
  EXCEPTIONS: 'exceptions',
  LATE: 'late',
  MISSED_PUNCH: 'missed-punch',
  ATTENDANCE_RATE: 'attendance-rate',
  ANNUAL_LEAVE: 'annual-leave',
};

function readCustomerReportDeepLink(): {
  reportKey?: CustomerReportKey;
  period?: string;
  companyId?: string;
  expectedProjectionVersion?: string;
} {
  const params = new URLSearchParams(window.location.search);
  const reportType = params.get('reportType') ?? '';
  const period = params.get('period') ?? '';
  const companyId = params.get('companyId') ?? '';
  const expectedProjectionVersion = params.get('expectedProjectionVersion') ?? '';
  return {
    reportKey: reportTypeToCustomerKey[reportType],
    period: /^\d{4}-(0[1-9]|1[0-2])$/.test(period) ? period : undefined,
    companyId: companyId.trim().length >= 1 && companyId.trim().length <= 36
      ? companyId.trim()
      : undefined,
    expectedProjectionVersion:
      expectedProjectionVersion.trim().length >= 1
        && expectedProjectionVersion.trim().length <= 128
        ? expectedProjectionVersion.trim()
        : undefined,
  };
}

function sum(values: number[]): number {
  return values.reduce((total, value) => total + value, 0);
}

function displayDays(value: number | undefined): string {
  return value === undefined ? '—' : String(value);
}

function rateBarWidth(rate: string): string {
  const parsed = Number.parseFloat(rate);
  return Number.isFinite(parsed) ? `${Math.min(100, Math.max(0, parsed))}%` : '0%';
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
