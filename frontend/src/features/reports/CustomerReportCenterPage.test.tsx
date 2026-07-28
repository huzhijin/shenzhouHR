import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

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
import { CustomerReportCenterPage } from './CustomerReportCenterPage';

describe('customer report center demo', () => {
  afterEach(() => {
    cleanup();
  });

  it('provides the full eight-report catalog and the source color legend', () => {
    expect(customerReportTabs).toHaveLength(8);
    expect(customerReportTabs.map((tab) => tab.key)).toEqual([
      'attendance-detail',
      'leave',
      'overtime',
      'work-hours',
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
    expect(report.overtimeRows.every((row) => row.dailyHours.length === dayCount)).toBe(true);
    expect(report.leaveRows.every((row) => row.period.includes(`${monthNumber}-`))).toBe(true);
    expect(report.workHoursRows
      .filter((row) => row.note.includes('入职') || row.note.includes('离职'))
      .every((row) => row.note.startsWith(`${monthNumber}月`))).toBe(true);
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

    expect(personalLeave.leaveRows.length).toBeLessThan(demo.leaveRows.length);
    expect(personalLeave.leaveRows.every((row) => row.type === '事假')).toBe(true);
    expect(missedMorningPunch.missedPunchRows.length).toBeLessThan(demo.missedPunchRows.length);
    expect(missedMorningPunch.missedPunchRows.every((row) => row.details.includes('上班'))).toBe(true);
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
    expect(screen.getAllByRole('tab')).toHaveLength(8);
    expect(screen.getByRole('tabpanel', { name: '月度考勤明细矩阵' })).toBeInTheDocument();
    expect(screen.getByLabelText('考勤状态颜色图例')).toHaveTextContent('迟到早退漏刷加班调休外出出差事假病假年假休息日补签');
    expect(screen.getByTestId('report-scroll-region')).toHaveClass('customer-report__table-scroll');

    fireEvent.click(screen.getByRole('button', { name: '导出当前报表' }));

    expect(onExport).toHaveBeenCalledWith(expect.objectContaining({
      reportKey: 'attendance-detail',
      month: '2026-06',
    }));
    expect(screen.getByRole('status')).toHaveTextContent('已生成“月度考勤明细矩阵”演示导出任务');
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

    const levelOne = screen.getByLabelText('一级部门');
    const levelTwo = screen.getByLabelText('二级部门');
    fireEvent.change(levelOne, { target: { value: '研发中心' } });

    expect(within(levelTwo).getAllByRole('option').map((option) => option.textContent)).toEqual([
      '全部二级部门',
      '产品研发部',
    ]);
    fireEvent.change(levelTwo, { target: { value: '产品研发部' } });
    expect(levelTwo).toHaveValue('产品研发部');

    fireEvent.change(levelOne, { target: { value: '制造中心' } });
    expect(levelTwo).toHaveValue('全部二级部门');
    expect(within(levelTwo).getAllByRole('option').map((option) => option.textContent)).toEqual([
      '全部二级部门',
      '晶圆制造部',
    ]);
  });

  it('shows criteria matched to every report and query/reset updates the visible result', () => {
    render(<CustomerReportCenterPage />);

    expect(screen.getByLabelText('考勤状态')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: '请假统计' }));
    expect(screen.getByLabelText('请假类型')).toBeInTheDocument();
    expect(screen.getByTestId('specific-result-count')).toHaveTextContent('8 条');
    fireEvent.change(screen.getByLabelText('请假类型'), { target: { value: '事假' } });
    fireEvent.click(screen.getByRole('button', { name: '查询请假统计' }));
    expect(screen.getByTestId('specific-result-count')).toHaveTextContent('2 条');
    fireEvent.click(screen.getByRole('button', { name: '重置请假统计筛选' }));
    expect(screen.getByTestId('specific-result-count')).toHaveTextContent('8 条');

    fireEvent.click(screen.getByRole('tab', { name: '加班汇总与每日加班' }));
    expect(screen.getByLabelText('加班类型')).toBeInTheDocument();
    expect(screen.getByLabelText('加班日期')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: '个人月度工时' }));
    expect(screen.getByLabelText('在职状态')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: '迟到统计' }));
    expect(screen.getByLabelText('迟到次数')).toBeInTheDocument();
    expect(screen.getByLabelText('迟到时长级别')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: '忘打卡统计' }));
    expect(screen.getByLabelText('缺卡时段')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: '出勤率统计' }));
    expect(screen.getByLabelText('出勤类型')).toBeInTheDocument();

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
