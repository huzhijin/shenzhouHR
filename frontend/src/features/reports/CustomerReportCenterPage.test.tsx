import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// This suite covers the demo sheets, so the page must resolve to demo mode
// regardless of the vitest runtime MODE.
vi.mock('../../shared/config/runtimeMode', () => ({ isDemoMode: vi.fn(() => true) }));

import {
  applyCustomerReportSpecificFilters,
  annualLevelOneOptions,
  annualLevelTwoOptions,
  attendanceLegend,
  customerReportTabs,
  defaultCustomerReportSpecificFilters,
  getCustomerReportDemo,
  normalizeAnnualLeaveFilters,
} from './customerReportDemo';
import {
  buildCustomerReportCsv,
  downloadCustomerReportCsv,
} from './customerReportExport';
import { customerReportDemoScopes } from './customerReportAccess';
import { CustomerReportCenterPage } from './CustomerReportCenterPage';

describe('customer report center demo', () => {
  afterEach(() => {
    cleanup();
  });

  it('provides the full nine-report catalog and the source color legend', () => {
    expect(customerReportTabs).toHaveLength(9);
    expect(customerReportTabs.map((tab) => tab.key)).toEqual([
      'attendance-detail',
      'leave',
      'overtime',
      'work-hours',
      'exceptions',
      'late',
      'missed-punch',
      'attendance-rate',
      'annual-leave',
    ]);
    expect(attendanceLegend.map((item) => item.label)).toEqual([
      '迟到',
      '早退',
      '漏刷',
      '加班',
      '调休',
      '外出',
      '出差',
      '事假',
      '病假',
      '年假',
      '休息日',
      '补签',
    ]);
  });

  it('filters demo rows without calling a production API', () => {
    const all = getCustomerReportDemo({
      month: '2026-06',
      department: '全部部门',
      employee: '全部员工',
    });
    const filtered = getCustomerReportDemo({
      month: '2026-06',
      department: '制造中心',
      employee: '陈思远',
    });

    expect(all.attendanceRows.length).toBeGreaterThan(filtered.attendanceRows.length);
    expect(filtered.attendanceRows).toHaveLength(1);
    expect(filtered.attendanceRows[0]?.employee).toBe('陈思远');
    expect(filtered.metadata.isDemo).toBe(true);
  });

  it('fails closed when forged filters request people outside the active data scope', () => {
    const manufacturingScope = requiredManufacturingScope();
    const forgedDepartment = getCustomerReportDemo({
      month: '2026-06',
      department: '研发中心',
      employee: '全部员工',
    }, manufacturingScope);
    const forgedEmployee = getCustomerReportDemo({
      month: '2026-06',
      department: '制造中心',
      employee: '吴昊',
    }, manufacturingScope);

    expect(forgedDepartment.metadata.dataScope.reference).toBe(manufacturingScope.reference);
    expect(allVisibleEmployees(forgedDepartment)).toEqual([]);
    expect(allVisibleEmployees(forgedEmployee)).toEqual([]);
  });

  it.each([
    ['2026-04', '04', 30],
    ['2026-05', '05', 31],
    ['2026-06', '06', 30],
  ])('keeps every monthly visible date aligned after switching to %s', (
    month,
    monthNumber,
    dayCount,
  ) => {
    const report = getCustomerReportDemo({
      month,
      department: '全部部门',
      employee: '全部员工',
    });

    expect(report.metadata.month).toBe(month);
    expect(report.attendanceRows.every((row) => row.days.length === dayCount)).toBe(true);
    expect(report.overtimeRows.every((row) => (row.dailyHours ?? []).length === dayCount)).toBe(true);
    expect(report.leaveRows.every((row) => row.period.includes(`${monthNumber}-`))).toBe(true);
    expect(report.workHoursRows
      .filter((row) => (row.note ?? '').includes('入职') || (row.note ?? '').includes('离职'))
      .every((row) => (row.note ?? '').startsWith(`${monthNumber}月`))).toBe(true);
    expect(report.lateRows.every((row) => row.details.includes(`${monthNumber}月`))).toBe(true);
    expect(report.missedPunchRows.every((row) => row.details.includes(`${monthNumber}月`))).toBe(true);
  });

  it('applies report-specific criteria to the actual report rows', () => {
    const demo = getCustomerReportDemo({
      month: '2026-06',
      department: '全部部门',
      employee: '全部员工',
    });
    const personalLeave = applyCustomerReportSpecificFilters(
      demo,
      'leave',
      { ...defaultCustomerReportSpecificFilters, leaveType: '事假' },
    );
    const missedMorningPunch = applyCustomerReportSpecificFilters(
      demo,
      'missed-punch',
      { ...defaultCustomerReportSpecificFilters, punchType: '上班缺卡' },
    );
    const highOpenAbsence = applyCustomerReportSpecificFilters(
      demo,
      'exceptions',
      {
        ...defaultCustomerReportSpecificFilters,
        exceptionType: '旷工',
        exceptionSeverity: '高',
        exceptionState: '处理中',
      },
    );

    expect(personalLeave.leaveRows.length).toBeLessThan(demo.leaveRows.length);
    expect(personalLeave.leaveRows.every((row) => row.type === '事假')).toBe(true);
    expect(missedMorningPunch.missedPunchRows.length).toBeLessThan(demo.missedPunchRows.length);
    expect(missedMorningPunch.missedPunchRows.every((row) => row.details.includes('上班'))).toBe(true);
    expect(highOpenAbsence.attendanceExceptionRows).toHaveLength(1);
    expect(highOpenAbsence.attendanceExceptionRows[0]).toMatchObject({
      employee: '吴昊',
      exceptionType: '旷工',
      severity: '高',
      state: '处理中',
    });
  });

  it('normalizes annual-leave hierarchy against the global department', () => {
    expect(annualLevelOneOptions('制造中心')).toEqual(['全部一级部门', '制造中心']);
    expect(annualLevelTwoOptions('全部一级部门', '制造中心')).toEqual([
      '全部二级部门',
      '晶圆制造部',
    ]);
    expect(annualLevelTwoOptions('研发中心', '全部部门')).toEqual([
      '全部二级部门',
      '产品研发部',
    ]);

    const normalized = normalizeAnnualLeaveFilters({
      ...defaultCustomerReportSpecificFilters,
      annualLevelOne: '研发中心',
      annualLevelTwo: '产品研发部',
    }, '制造中心');
    expect(normalized.annualLevelOne).toBe('全部一级部门');
    expect(normalized.annualLevelTwo).toBe('全部二级部门');

    const manufacturing = getCustomerReportDemo({
      month: '2026-06',
      department: '制造中心',
      employee: '全部员工',
    });
    const conflictSafeResult = applyCustomerReportSpecificFilters(
      manufacturing,
      'annual-leave',
      {
        ...defaultCustomerReportSpecificFilters,
        annualLevelOne: '研发中心',
        annualLevelTwo: '产品研发部',
      },
    );
    expect(conflictSafeResult.annualLeaveRows).toHaveLength(manufacturing.annualLeaveRows.length);
    expect(conflictSafeResult.annualLeaveRows.every(
      (row) => row.departmentLevelOne === '制造中心',
    )).toBe(true);
  });

  it('renders filters, report navigation, the matrix legend and export feedback', () => {
    const onExport = vi.fn();
    render(<CustomerReportCenterPage onExport={onExport} />);

    expect(screen.getByRole('heading', { name: '考勤报表中心' })).toBeInTheDocument();
    expect(screen.getByText('客户演示数据')).toBeInTheDocument();
    expect(screen.getByLabelText('月份')).toBeInTheDocument();
    expect(screen.getByLabelText('部门')).toBeInTheDocument();
    expect(screen.getByLabelText('员工')).toBeInTheDocument();
    expect(screen.getAllByRole('tab')).toHaveLength(9);
    expect(screen.getByRole('tabpanel', { name: '月度考勤明细矩阵' })).toBeInTheDocument();
    expect(screen.getByLabelText('考勤状态颜色图例')).toHaveTextContent('迟到早退漏刷加班调休外出出差事假病假年假休息日补签');
    expect(screen.getByTestId('report-scroll-region')).toHaveClass('customer-report__table-scroll');

    fireEvent.click(screen.getByRole('button', { name: '导出当前报表' }));

    expect(onExport).toHaveBeenCalledWith(expect.objectContaining({
      reportKey: 'attendance-detail',
    }));
    expect(screen.getByRole('status')).toHaveTextContent('“月度考勤明细矩阵”已按当前筛选条件导出');
  });

  it('fuzzy-searches authorized departments and employees by name, number, or department', () => {
    render(<CustomerReportCenterPage />);

    const departmentSelect = screen.getByLabelText('部门');
    fireEvent.mouseDown(departmentSelect);
    fireEvent.change(departmentSelect, { target: { value: '研发' } });

    expect(screen.getByRole('option', { name: '研发中心' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: '制造中心' })).not.toBeInTheDocument();
    fireEvent.keyDown(departmentSelect, {
      key: 'Enter',
      code: 'Enter',
      keyCode: 13,
      which: 13,
    });
    dismissReportSelect(departmentSelect);

    const employeeSelect = screen.getByLabelText('员工');
    fireEvent.mouseDown(employeeSelect);
    fireEvent.change(employeeSelect, { target: { value: '张' } });
    expect(screen.getByRole('option', { name: /^张伟/ })).toBeInTheDocument();

    fireEvent.change(employeeSelect, { target: { value: 'sz0318' } });
    expect(screen.getByRole('option', { name: /^张伟/ })).toBeInTheDocument();
    expect(screen.getByText('SZ0318 · 研发中心')).toBeInTheDocument();

    fireEvent.change(employeeSelect, { target: { value: '研发中心' } });
    expect(screen.getByRole('option', { name: /^张伟/ })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /^林晓雯/ })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: /^周晴/ })).not.toBeInTheDocument();

    fireEvent.change(employeeSelect, { target: { value: 'sz0318' } });
    fireEvent.keyDown(employeeSelect, {
      key: 'Enter',
      code: 'Enter',
      keyCode: 13,
      which: 13,
    });
    expect(screen.getByTestId('report-table')).toHaveTextContent('SZ0318');
    expect(screen.getByTestId('report-table')).not.toHaveTextContent('SZ0342');

    fireEvent.click(screen.getByRole('button', { name: '重置筛选' }));
    expect(screen.getByText('全部部门 · 全部员工')).toBeInTheDocument();
  });

  it('disables export without create permission and never invokes the export callback', () => {
    const onExport = vi.fn();
    render(
      <CustomerReportCenterPage
        capabilities={['ATTENDANCE_REPORT:READ']}
        onExport={onExport}
      />,
    );

    const exportButton = screen.getByRole('button', { name: '导出当前报表' });
    expect(exportButton).toBeDisabled();

    fireEvent.click(exportButton);

    expect(onExport).not.toHaveBeenCalled();
    expect(screen.queryByText('导出任务已创建')).not.toBeInTheDocument();
  });

  it('switches between department-owner and self scopes without leaking other people', () => {
    const companyScope = requiredCompanyScope();
    const manufacturingScope = requiredManufacturingScope();
    const selfScope = requiredSelfScope();
    render(
      <CustomerReportCenterPage
        dataScopes={[companyScope, manufacturingScope, selfScope]}
      />,
    );

    selectPermissionRole(manufacturingScope.actorLabel);

    const manufacturingTable = screen.getByTestId('report-table');
    expect(within(manufacturingTable).getAllByRole('row')).toHaveLength(4);
    expect(manufacturingTable).toHaveTextContent('陈思远');
    expect(manufacturingTable).toHaveTextContent('周晴');
    expect(manufacturingTable).toHaveTextContent('赵凯');
    expect(manufacturingTable).not.toHaveTextContent('张伟');
    expect(manufacturingTable).not.toHaveTextContent('林晓雯');
    expect(manufacturingTable).not.toHaveTextContent('蒋宁');
    expect(manufacturingTable).not.toHaveTextContent('吴昊');
    expect(manufacturingTable).not.toHaveTextContent('沈佳');

    selectPermissionRole(selfScope.actorLabel);

    const selfTable = screen.getByTestId('report-table');
    expect(within(selfTable).getAllByRole('row')).toHaveLength(2);
    expect(selfTable).toHaveTextContent('陈思远');
    expect(selfTable).not.toHaveTextContent('周晴');
    expect(selfTable).not.toHaveTextContent('赵凯');
    expect(selfTable).not.toHaveTextContent('张伟');
  });

  it('offers all four final demo roles in the permission selector', () => {
    const expectedScopes = [
      requiredCompanyScope(),
      requiredExecutiveScope(),
      requiredManufacturingScope(),
      requiredSelfScope(),
    ];
    render(<CustomerReportCenterPage />);

    fireEvent.mouseDown(screen.getByLabelText('权限角色'));

    const expectedLabels = new Set(expectedScopes.map((scope) => scope.actorLabel));
    const roleOptions = screen.getAllByRole('option').filter(
      (option) => expectedLabels.has(option.textContent ?? ''),
    );
    expect(customerReportDemoScopes).toHaveLength(4);
    expect(roleOptions).toHaveLength(4);
    expectedScopes.forEach((scope) => {
      expect(roleOptions.some((option) => option.textContent === scope.actorLabel)).toBe(true);
    });
  });

  it('updates rendered report dates when the month selector changes', () => {
    render(<CustomerReportCenterPage />);

    fireEvent.mouseDown(screen.getByLabelText('月份'));
    fireEvent.click(screen.getByText('2026年05月'));
    fireEvent.click(screen.getByRole('tab', { name: '请假统计' }));

    expect(screen.getByRole('heading', {
      name: '江苏神州半导体科技有限公司2026年05月请假统计',
    })).toBeInTheDocument();
    expect(screen.getByTestId('report-table')).toHaveTextContent('05-05 13:30 ～ 05-06 17:30');
    expect(screen.getByTestId('report-table')).not.toHaveTextContent('06-05 13:30 ～ 06-06 17:30');
  });

  it('builds and downloads a BOM-prefixed CSV with filters and only visible rows', () => {
    const source = getCustomerReportDemo({
      month: '2026-05',
      department: '制造中心',
      employee: '全部员工',
    });
    const report = applyCustomerReportSpecificFilters(
      source,
      'leave',
      { ...defaultCustomerReportSpecificFilters, leaveType: '事假' },
    );
    const download = buildCustomerReportCsv({
      reportKey: 'leave',
      reportTitle: '请假统计',
      month: '2026-05',
      department: '制造中心',
      employee: '全部员工',
      generatedAt: '2026-07-28T14:00:00.000Z',
      reportFilters: { 请假类型: '事假' },
      report,
    });

    expect(download.fileName).toBe('2026-05_请假统计.csv');
    expect(download.csv.startsWith('\uFEFF')).toBe(true);
    expect(download.csv).toContain('"报表名称","请假统计"');
    expect(download.csv).toContain('"月份","2026年05月"');
    expect(download.csv).toContain('"部门","制造中心"');
    expect(download.csv).toContain('"请假类型","事假"');
    expect(download.csv).toContain('"陈思远"');
    expect(download.csv).toContain('"05-05 13:30 ～ 05-06 17:30"');
    expect(download.csv).not.toContain('"周晴"');

    const originalCreateObjectUrl = URL.createObjectURL;
    const originalRevokeObjectUrl = URL.revokeObjectURL;
    const createObjectUrl = vi.fn(() => 'blob:customer-report');
    const revokeObjectUrl = vi.fn();
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      value: createObjectUrl,
    });
    Object.defineProperty(URL, 'revokeObjectURL', {
      configurable: true,
      value: revokeObjectUrl,
    });
    const anchorClick = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

    try {
      downloadCustomerReportCsv(download);

      expect(createObjectUrl).toHaveBeenCalledWith(expect.any(Blob));
      expect(anchorClick).toHaveBeenCalledOnce();
      expect(revokeObjectUrl).toHaveBeenCalledWith('blob:customer-report');
    } finally {
      anchorClick.mockRestore();
      restoreUrlMethod('createObjectURL', originalCreateObjectUrl);
      restoreUrlMethod('revokeObjectURL', originalRevokeObjectUrl);
    }
  });

  it('exports classified overtime and dedicated sick-leave day columns', () => {
    const report = getCustomerReportDemo({
      month: '2026-06',
      department: '全部部门',
      employee: '全部员工',
    });
    const commonRequest = {
      month: '2026-06',
      department: '全部部门',
      employee: '全部员工',
      generatedAt: '2026-08-17T08:00:00.000Z',
      reportFilters: {},
      report,
    };

    const overtime = buildCustomerReportCsv({
      ...commonRequest,
      reportKey: 'overtime',
      reportTitle: '加班统计',
    }).csv;
    expect(overtime).toContain('"计薪加班","转调休加班","义务加班","汇总加班"');
    expect(overtime).not.toContain('"平时加班"');
    expect(overtime).not.toContain('"周末加班"');
    expect(overtime).not.toContain('"法定节假日加班"');

    const attendanceRate = buildCustomerReportCsv({
      ...commonRequest,
      reportKey: 'attendance-rate',
      reportTitle: '出勤率统计',
    }).csv;
    expect(attendanceRate).toContain(
      '"应出勤天数","实际出勤天数","病假天数","出勤率"',
    );
    expect(attendanceRate).toContain('"2","100.00%","病假计入实际出勤"');
  });

  it('exports only rows authorized by the active data scope', () => {
    const manufacturingScope = requiredManufacturingScope();
    const report = getCustomerReportDemo({
      month: '2026-06',
      department: '全部部门',
      employee: '全部员工',
    }, manufacturingScope);
    const download = buildCustomerReportCsv({
      reportKey: 'exceptions',
      reportTitle: '考勤异常总览',
      month: '2026-06',
      department: '全部部门',
      employee: '全部员工',
      generatedAt: '2026-07-28T14:00:00.000Z',
      reportFilters: {
        异常类型: '全部异常',
        异常级别: '全部级别',
        处理状态: '全部状态',
      },
      report,
    });

    expect(download.csv).toContain('"考勤异常总览"');
    expect(download.csv).toContain('"陈思远"');
    expect(download.csv).toContain('"周晴"');
    expect(download.csv).toContain('"赵凯"');
    expect(download.csv).not.toContain('"张伟"');
    expect(download.csv).not.toContain('"林晓雯"');
    expect(download.csv).not.toContain('"蒋宁"');
    expect(download.csv).not.toContain('"吴昊"');
    expect(download.csv).not.toContain('"沈佳"');
    expect(download.csv).not.toContain('"研发中心"');
    expect(download.csv).not.toContain('"职能中心"');
  });

  it('switches to the annual-leave report with the orange source-table treatment', () => {
    render(<CustomerReportCenterPage />);

    fireEvent.click(screen.getByRole('tab', { name: '年休假汇总' }));

    expect(screen.getByRole('tabpanel', { name: '年休假汇总' })).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '2026年员工年休假统计汇总' })).toBeInTheDocument();
    expect(screen.getByTestId('report-table')).toHaveClass('customer-report__table--annual');
  });

  it('links annual-leave level-two choices to level one and resets stale choices', () => {
    render(<CustomerReportCenterPage />);
    fireEvent.click(screen.getByRole('tab', { name: '年休假汇总' }));

    selectReportOption('一级部门', '研发中心');
    openReportSelect('二级部门');
    expect(screen.getByRole('option', { name: '全部二级部门' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: '产品研发部' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: '晶圆制造部' })).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('二级部门'), {
      target: { value: '产品研发部' },
    });
    fireEvent.keyDown(screen.getByLabelText('二级部门'), {
      key: 'Enter',
      code: 'Enter',
      keyCode: 13,
      which: 13,
    });
    expectSelectedReportOption('二级部门', '产品研发部');

    selectReportOption('一级部门', '制造中心');
    expectSelectedReportOption('二级部门', '全部二级部门');
  });

  it('allows manual fuzzy input in reusable report-specific selectors', () => {
    render(<CustomerReportCenterPage />);
    fireEvent.click(screen.getByRole('tab', { name: '年休假汇总' }));

    const levelOne = screen.getByLabelText('一级部门');
    fireEvent.mouseDown(levelOne);
    fireEvent.change(levelOne, { target: { value: '研发' } });

    expect(screen.getByRole('option', { name: '研发中心' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: '制造中心' })).not.toBeInTheDocument();
  });

  it('shows criteria matched to every report and query/reset updates the visible result', () => {
    render(<CustomerReportCenterPage />);

    expect(screen.getByLabelText('考勤状态')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: '请假统计' }));
    expect(screen.getByLabelText('请假类型')).toBeInTheDocument();
    expect(screen.getByTestId('specific-result-count')).toHaveTextContent('8 条');
    selectReportOption('请假类型', '事假');
    fireEvent.click(screen.getByRole('button', { name: '查询请假统计' }));
    expect(screen.getByTestId('specific-result-count')).toHaveTextContent('2 条');
    fireEvent.click(screen.getByRole('button', { name: '重置请假统计筛选' }));
    expect(screen.getByTestId('specific-result-count')).toHaveTextContent('8 条');

    fireEvent.click(screen.getByRole('tab', { name: '加班汇总与每日加班' }));
    expect(screen.getByLabelText('加班类型')).toBeInTheDocument();
    expect(screen.getByLabelText('加班日期')).toBeInTheDocument();
    expect(screen.getAllByRole('columnheader', { name: '计薪加班' }).length).toBeGreaterThan(0);
    expect(screen.getAllByRole('columnheader', { name: '转调休加班' }).length).toBeGreaterThan(0);
    expect(screen.getAllByRole('columnheader', { name: '义务加班' }).length).toBeGreaterThan(0);
    expect(screen.queryByRole('columnheader', { name: '平时加班' })).not.toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: '周末加班' })).not.toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: '法定加班' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: '个人月度工时' }));
    expect(screen.getByLabelText('在职状态')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: '考勤异常总览' }));
    expect(screen.getByLabelText('异常类型')).toBeInTheDocument();
    expect(screen.getByLabelText('异常级别')).toBeInTheDocument();
    expect(screen.getByLabelText('处理状态')).toBeInTheDocument();
    selectReportOption('异常类型', '旷工');
    selectReportOption('异常级别', '高');
    selectReportOption('处理状态', '处理中');
    fireEvent.click(screen.getByRole('button', { name: '查询考勤异常总览' }));
    expect(screen.getByTestId('specific-result-count')).toHaveTextContent('1 条');
    expect(screen.getByTestId('report-table')).toHaveTextContent('吴昊');
    expect(screen.getByTestId('report-table')).not.toHaveTextContent('沈佳');

    fireEvent.click(screen.getByRole('tab', { name: '迟到统计' }));
    expect(screen.getByLabelText('迟到次数')).toBeInTheDocument();
    expect(screen.getByLabelText('迟到时长级别')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: '忘打卡统计' }));
    expect(screen.getByLabelText('缺卡时段')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: '出勤率统计' }));
    expect(screen.getByLabelText('出勤类型')).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '应出勤天数' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '实际出勤天数' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '病假天数' })).toBeInTheDocument();
    expect(screen.getByTestId('report-table')).toHaveTextContent('病假计入实际出勤');

    fireEvent.click(screen.getByRole('tab', { name: '年休假汇总' }));
    expect(screen.getByLabelText('年休假余额状态')).toBeInTheDocument();
    expect(screen.getByLabelText('一级部门')).toBeInTheDocument();
    expect(screen.getByLabelText('二级部门')).toBeInTheDocument();
  });
});

function restoreUrlMethod(
  key: 'createObjectURL' | 'revokeObjectURL',
  value: typeof URL.createObjectURL | typeof URL.revokeObjectURL | undefined,
) {
  if (value) {
    Object.defineProperty(URL, key, { configurable: true, value });
    return;
  }
  Reflect.deleteProperty(URL, key);
}

function requiredCompanyScope() {
  const scope = customerReportDemoScopes.find((candidate) => (
    candidate.type === 'COMPANY'
    && candidate.actorLabel.toLowerCase().includes('hr')
  ));
  if (!scope) throw new Error('缺少公司 HR 演示数据范围');
  return scope;
}

function requiredExecutiveScope() {
  const scope = customerReportDemoScopes.find((candidate) => (
    candidate.type === 'COMPANY'
    && !candidate.actorLabel.toLowerCase().includes('hr')
  ));
  if (!scope) throw new Error('缺少高管演示数据范围');
  return scope;
}

function requiredManufacturingScope() {
  const scope = customerReportDemoScopes.find((candidate) => (
    candidate.type === 'ORGANIZATION'
    && candidate.allowedDepartments.includes('制造中心')
  ));
  if (!scope) throw new Error('缺少制造中心部门负责人演示数据范围');
  return scope;
}

function requiredSelfScope() {
  const scope = customerReportDemoScopes.find((candidate) => (
    candidate.type === 'SELF'
    && candidate.allowedEmployees.includes('陈思远')
  ));
  if (!scope) throw new Error('缺少陈思远本人演示数据范围');
  return scope;
}

function selectPermissionRole(actorLabel: string) {
  fireEvent.mouseDown(screen.getByLabelText('权限角色'));
  const options = screen.getAllByText(actorLabel);
  fireEvent.click(options.at(-1)!);
}

function openReportSelect(label: string) {
  fireEvent.mouseDown(document.body);
  fireEvent.click(document.body);
  screen.getAllByRole('combobox')
    .filter((select) => select.getAttribute('aria-expanded') === 'true')
    .forEach(dismissReportSelect);
  const select = screen.getByLabelText(label);
  fireEvent.mouseDown(select);
  fireEvent.change(select, { target: { value: '' } });
}

function selectReportOption(label: string, optionLabel: string) {
  openReportSelect(label);
  const select = screen.getByLabelText(label);
  fireEvent.change(select, { target: { value: optionLabel } });
  fireEvent.keyDown(select, {
    key: 'Enter',
    code: 'Enter',
    keyCode: 13,
    which: 13,
  });
  dismissReportSelect(select);
}

function dismissReportSelect(select: HTMLElement) {
  fireEvent.keyDown(select, {
    key: 'Escape',
    code: 'Escape',
    keyCode: 27,
    which: 27,
  });
  fireEvent.blur(select);
  fireEvent.mouseDown(document.body);
  fireEvent.click(document.body);
}

function expectSelectedReportOption(label: string, optionLabel: string) {
  expect(screen.getByLabelText(label).closest('.ant-select')).toHaveTextContent(optionLabel);
}

function allVisibleEmployees(
  report: ReturnType<typeof getCustomerReportDemo>,
): string[] {
  return [
    ...report.attendanceRows,
    ...report.leaveRows,
    ...report.overtimeRows,
    ...report.workHoursRows,
    ...report.attendanceExceptionRows,
    ...report.lateRows,
    ...report.missedPunchRows,
    ...report.attendanceRateRows,
    ...report.annualLeaveRows,
  ].map((row) => row.employee);
}
