import {
  defaultCustomerReportDataScope,
  departmentMatchesFilter,
  isCustomerReportQueryWithinScope,
  isWithinCustomerReportScope,
  type CustomerReportDataScope,
} from './customerReportAccess';
import { joinDepartmentSegments } from './departmentPath';
import { isoWeekdayBucket, overtimeTreatmentFromHours } from './financeOvertimeLayout';

export type CustomerReportKey =
  | 'attendance-detail'
  | 'leave'
  | 'overtime'
  | 'overtime-daily'
  | 'finance-overtime'
  | 'daily-journal'
  | 'work-hours'
  | 'exceptions'
  | 'late'
  | 'missed-punch'
  | 'attendance-rate'
  | 'annual-leave';

export type AttendanceStatusKey =
  | 'late'
  | 'early'
  | 'missed'
  | 'overtime'
  | 'time-off'
  | 'out'
  | 'trip'
  | 'personal-leave'
  | 'sick-leave'
  | 'annual-leave'
  | 'marriage-leave'
  | 'maternity-leave'
  | 'paternity-leave'
  | 'bereavement-leave'
  | 'work-injury-leave'
  | 'nursing-leave'
  | 'breastfeeding-leave'
  | 'prenatal-exam-leave'
  | 'family-planning-leave'
  | 'rest-day'
  | 'corrected';

export interface CustomerReportFilters {
  month: string;
  department: string;
  employee: string;
  /** Stable formal-report identity; demo and legacy callers continue to use labels. */
  organizationId?: string;
  /** Stable formal-report identity; demo and legacy callers continue to use labels. */
  employeeId?: string;
  fromDate?: string;
  toDate?: string;
}

export interface CustomerReportSpecificFilters {
  attendanceStatus: '全部状态' | AttendanceStatusKey;
  leaveType: string;
  overtimeType: '全部类型' | '计薪加班' | '转调休加班' | '义务加班' | '加班费' | '转调休';
  overtimeDay: string;
  employmentStatus: '全部状态' | '在职' | '本月入职' | '本月离职';
  exceptionType: '全部异常' | AttendanceExceptionType;
  exceptionSeverity: '全部级别' | AttendanceExceptionSeverity;
  exceptionState: '全部状态' | AttendanceExceptionState;
  lateCount: '全部次数' | '2次及以上' | '3次及以上';
  lateLevel: '全部级别' | '10分钟以内' | '11-30分钟' | '30分钟以上';
  punchType: '全部时段' | '上班缺卡' | '下班缺卡';
  attendanceType: string;
  annualBalance: '全部余额' | '有余额' | '余额不足2天' | '已用完';
  annualLevelOne: string;
  annualLevelTwo: string;
}

export interface AttendanceDayCell {
  day: number;
  weekday: string;
  primary: string;
  secondary: string;
  status?: AttendanceStatusKey;
  primaryStatus?: AttendanceStatusKey;
  secondaryStatus?: AttendanceStatusKey;
  merged?: boolean;
  mergedLabel?: string;
  mergedStatus?: AttendanceStatusKey;
  note?: string;
}

export interface CustomerReportRowIdentity {
  rowKey?: string;
  employeeId?: string;
  organizationId?: string;
}

// 可选字段表示正式投影当前不产出该列（见 docs/reporting/task-b-attendance-reporting-prd.md
// 的缺列清单）。渲染层必须回退为占位符，绝不允许用 0 或空串冒充真实值。
export interface AttendanceDetailRow extends CustomerReportRowIdentity {
  employeeNo: string;
  department: string;
  employee: string;
  position?: string;
  days: AttendanceDayCell[];
}

export interface DailyJournalReportRow extends CustomerReportRowIdentity {
  sequence: number;
  employeeNo: string;
  department: string;
  employee: string;
  businessDate: string;
  shiftLabel: string;
  onDuty: string;
  offDuty: string;
  lateHours: string | number;
  earlyHours: string | number;
  absenceHours: string | number;
  leaveType: string;
  overtimeHours: string | number;
  remark: string;
}

export interface OvertimeDailyReportRow extends CustomerReportRowIdentity {
  employeeNo: string;
  department: string;
  employee: string;
  businessDate: string;
  weekdayOvertimeHours: number;
  weekendOvertimeHours: number;
  holidayOvertimeHours: number;
  paidOvertimeHours?: number;
  compensatoryOvertimeHours?: number;
  voluntaryOvertimeHours?: number;
}

export interface FinanceOvertimeReportRow extends CustomerReportRowIdentity {
  employeeNo: string;
  department: string;
  employee: string;
  weekdayOvertimeHours: number;
  weekendOvertimeHours: number;
  holidayOvertimeHours: number;
  paidOvertimeHours?: number;
  compensatoryOvertimeHours?: number;
  voluntaryOvertimeHours?: number;
  days: Array<{
    date: string;
    hours: number;
    dayType?: string;
    treatment?: string;
    paidHours?: number;
    compensatoryHours?: number;
    voluntaryHours?: number;
  }>;
}

export interface LeaveReportRow extends CustomerReportRowIdentity {
  id: number;
  employee: string;
  department: string;
  type: string;
  hours: number;
  period: string;
  remark?: string;
  approvalState: string;
}

export interface OvertimeReportRow extends CustomerReportRowIdentity {
  employee: string;
  employeeNo?: string;
  department: string;
  overtimeType: string;
  hours: number;
  period: string;
  overtimeDate?: string;
  approvalState: string;
  source: string;
  reason?: string;
  /** Derived from overtimeType so overview cards can still sum classifications. */
  paidHours?: number;
  compensatoryHours?: number;
  voluntaryHours?: number;
  totalHours?: number;
  classificationAvailable: boolean;
}

export interface WorkHoursReportRow extends CustomerReportRowIdentity {
  employee: string;
  department: string;
  plannedHours: number;
  overtimeHours: number;
  voluntaryOvertimeHours?: number;
  leaveHours: number;
  annualLeaveHours?: number;
  exchangedHours?: number;
  usedTimeOffHours?: number;
  actualHours: number;
  note?: string;
}

export interface ExceptionReportRow extends CustomerReportRowIdentity {
  id: number;
  employee: string;
  department: string;
  count: number;
  details: string;
  reviewer?: string;
  state?: string;
  lateMinutes?: number;
}

// 正式投影的 ExceptionType 有 22 个取值，多于原型稿的 8 类。折叠成 8 类会把
// “缺卡待补签/缺卡超期”“证据冲突”等不同处置路径显示成同一个标签，因此按后端
// 取值域逐一给出标签，不做有损归并。
export type AttendanceExceptionType =
  | '迟到'
  | '早退'
  | '上班缺卡'
  | '下班缺卡'
  | '缺卡待补签'
  | '缺卡超期'
  | '旷工'
  | '排班缺失'
  | '请假与打卡冲突'
  | '加班未审批'
  | '未报加班'
  | '加班结束晚于打卡'
  | '长时在岗待审'
  | '加班异常'
  | '证据冲突'
  | '外出出差不完整'
  | 'OA审批状态未知'
  | 'OA人员引用无效'
  | '员工未匹配'
  | '来源记录重复'
  | '来源结构变更'
  | '来源同步过期'
  | '无考勤组'
  | '无班次或日历'
  | '打卡配对歧义'
  | '跨午夜待复核'
  | '提前返回待确认'
  | '月结后来源变更'
  | '输入完整性错误'
  | '假期余额为负'
  | '年假余额为负'
  | '调休余额为负'
  // 后端新增取值时的显式兜底，避免把未知类型伪装成某个已知类别。
  | '未识别异常类型';

export type AttendanceExceptionSeverity = '高' | '中' | '低';

export type AttendanceExceptionState =
  | '待处理'
  | '待员工说明'
  | '待补签'
  | '处理中'
  | '已处理';

export interface AttendanceExceptionReportRow extends CustomerReportRowIdentity {
  id: number;
  businessDate: string;
  employeeNo: string;
  employee: string;
  department: string;
  exceptionType: AttendanceExceptionType;
  severity: AttendanceExceptionSeverity;
  shiftLabel?: string;
  scheduledWindow?: string;
  punchSummary?: string;
  exceptionMinutes?: number;
  details: string;
  evidenceSummary?: string;
  state: AttendanceExceptionState;
  owner?: string;
  dueAt?: string;
}

export interface AttendanceRateReportRow extends CustomerReportRowIdentity {
  id: number;
  employee: string;
  department: string;
  type?: string;
  hours?: number;
  scheduledDays?: number;
  actualDays?: number;
  sickLeaveDays?: number;
  rate: string;
  note?: string;
}

export interface AnnualLeaveReportRow extends CustomerReportRowIdentity {
  id: number;
  departmentLevelOne?: string;
  departmentLevelTwo?: string;
  department: string;
  employee: string;
  joinedOn?: string;
  companySeniority?: number;
  priorSeniority?: number;
  totalSeniority?: number;
  statutoryDays?: number;
  newHireDays?: number;
  availableDays: number;
  availableHours: number;
  monthlyUsedDays?: number[];
  remainingDays: number;
  note?: string;
}

export interface CustomerReportDemo {
  metadata: {
    isDemo: boolean;
    company: string;
    generatedAt: string;
    month: string;
    monthLabel: string;
    rowCount: number;
    dataScope: CustomerReportDataScope;
    periodState?: 'OPEN' | 'FROZEN' | 'CLOSED' | 'REOPENED';
    /** Exact committed source/configuration version identifiers used by the calculation. */
    sourceVersions?: readonly string[];
    truncated?: boolean;
    allowedActions?: readonly string[];
    sourcesNewerThanPin?: boolean;
  };
  attendanceRows: AttendanceDetailRow[];
  dailyJournalRows: DailyJournalReportRow[];
  overtimeDailyRows: OvertimeDailyReportRow[];
  financeOvertimeRows: FinanceOvertimeReportRow[];
  leaveRows: LeaveReportRow[];
  overtimeRows: OvertimeReportRow[];
  workHoursRows: WorkHoursReportRow[];
  attendanceExceptionRows: AttendanceExceptionReportRow[];
  lateRows: ExceptionReportRow[];
  missedPunchRows: ExceptionReportRow[];
  attendanceRateRows: AttendanceRateReportRow[];
  annualLeaveRows: AnnualLeaveReportRow[];
}

export const customerReportTabs: ReadonlyArray<{
  key: CustomerReportKey;
  label: string;
  shortLabel: string;
}> = [
  { key: 'attendance-detail', label: '月度考勤明细矩阵', shortLabel: '考勤明细' },
  { key: 'leave', label: '请假统计', shortLabel: '请假统计' },
  { key: 'overtime', label: '加班单据明细', shortLabel: '加班统计' },
  { key: 'overtime-daily', label: '加班日报', shortLabel: '加班日报' },
  { key: 'finance-overtime', label: '每日加班', shortLabel: '每日加班' },
  { key: 'daily-journal', label: '考勤日报', shortLabel: '考勤日报' },
  { key: 'work-hours', label: '个人月度工时', shortLabel: '月度工时' },
  { key: 'exceptions', label: '考勤异常总览', shortLabel: '异常总览' },
  { key: 'late', label: '迟到统计', shortLabel: '迟到统计' },
  { key: 'missed-punch', label: '忘打卡统计', shortLabel: '忘打卡' },
  { key: 'attendance-rate', label: '出勤率统计', shortLabel: '出勤率' },
  { key: 'annual-leave', label: '年休假汇总', shortLabel: '年休假' },
] as const;

/** Daytime legend fills shared with Excel export and night theme. */
export const REPORT_BADGE_COLORS: Readonly<Record<string, string>> = {
  LATE: '#ff8578',
  EARLY_DEPARTURE: '#5aa3ea',
  MISSING_PUNCH: '#be6cbb',
  RECOGNIZED_OVERTIME: '#3f8850',
  TIME_OFF: '#f1b83d',
  OUTING: '#43d4d0',
  TRIP: '#02aa92',
  PERSONAL_LEAVE: '#d8ef00',
  SICK_LEAVE: '#9c2424',
  ANNUAL_LEAVE: '#7a3434',
  MARRIAGE_LEAVE: '#e07cc0',
  MATERNITY_LEAVE: '#c45c9e',
  PATERNITY_LEAVE: '#8e6cc9',
  BEREAVEMENT_LEAVE: '#5c5c5c',
  WORK_INJURY_LEAVE: '#e07a3d',
  NURSING_LEAVE: '#3d6bb3',
  BREASTFEEDING_LEAVE: '#f4a6c8',
  PRENATAL_EXAM_LEAVE: '#7ec8e3',
  FAMILY_PLANNING_LEAVE: '#6b8f3e',
  REST_DAY: '#f2efe7',
  PUNCH_CORRECTION: '#ffffff',
};

export const attendanceLegend: ReadonlyArray<{
  key: AttendanceStatusKey;
  label: string;
  color: string;
}> = [
  { key: 'late', label: '迟到', color: '#ff8578' },
  { key: 'early', label: '早退', color: '#5aa3ea' },
  { key: 'missed', label: '漏刷', color: '#be6cbb' },
  { key: 'overtime', label: '加班', color: '#3f8850' },
  { key: 'time-off', label: '调休', color: '#f1b83d' },
  { key: 'out', label: '外出', color: '#43d4d0' },
  { key: 'trip', label: '出差', color: '#02aa92' },
  { key: 'personal-leave', label: '事假', color: '#d8ef00' },
  { key: 'sick-leave', label: '病假', color: '#9c2424' },
  { key: 'annual-leave', label: '年假', color: '#7a3434' },
  { key: 'rest-day', label: '休息日', color: '#f2efe7' },
  { key: 'corrected', label: '补签', color: '#ffffff' },
  { key: 'marriage-leave', label: '婚假', color: '#e07cc0' },
  { key: 'maternity-leave', label: '产假', color: '#c45c9e' },
  { key: 'paternity-leave', label: '陪产假', color: '#8e6cc9' },
  { key: 'bereavement-leave', label: '丧假', color: '#5c5c5c' },
  { key: 'work-injury-leave', label: '工伤假', color: '#e07a3d' },
  { key: 'nursing-leave', label: '护理假', color: '#3d6bb3' },
  { key: 'breastfeeding-leave', label: '哺乳假', color: '#f4a6c8' },
  { key: 'prenatal-exam-leave', label: '孕检假', color: '#7ec8e3' },
  { key: 'family-planning-leave', label: '计生假', color: '#6b8f3e' },
] as const;

// Generate a rolling 24-month window: 12 months back → current month.
// Updated at module-load time so the picker always covers today.
function buildMonthOptions(): readonly { value: string; label: string }[] {
  const options: { value: string; label: string }[] = [];
  const now = new Date();
  for (let offset = 12; offset >= 0; offset--) {
    const d = new Date(now.getFullYear(), now.getMonth() - offset, 1);
    const year = d.getFullYear();
    const month = String(d.getMonth() + 1).padStart(2, '0');
    options.push({
      value: `${year}-${month}`,
      label: `${year}年${month}月`,
    });
  }
  return options;
}

export const reportFilterOptions = {
  months: buildMonthOptions(),
  departments: ['全部部门', '制造中心', '研发中心', '职能中心'],
  employees: ['全部员工', '陈思远', '周晴', '张伟', '林晓雯', '赵凯', '蒋宁', '吴昊', '沈佳'],
} as const;

export const defaultCustomerReportSpecificFilters: CustomerReportSpecificFilters = {
  attendanceStatus: '全部状态',
  leaveType: '全部类型',
  overtimeType: '全部类型',
  overtimeDay: '全部日期',
  employmentStatus: '全部状态',
  exceptionType: '全部异常',
  exceptionSeverity: '全部级别',
  exceptionState: '全部状态',
  lateCount: '全部次数',
  lateLevel: '全部级别',
  punchType: '全部时段',
  attendanceType: '全部类型',
  annualBalance: '全部余额',
  annualLevelOne: '全部一级部门',
  annualLevelTwo: '全部二级部门',
};

export const reportSpecificFilterOptions = {
  leaveTypes: [
    '全部类型',
    '年假',
    '病假',
    '婚假',
    '产假',
    '陪产假',
    '丧假',
    '工伤假',
    '孕检/哺乳假',
    '事假',
    '调休',
  ],
  overtimeTypes: ['全部类型', '加班费', '转调休', '义务加班'],
  employmentStatuses: ['全部状态', '在职', '本月入职', '本月离职'],
  exceptionTypes: [
    '全部异常',
    '迟到',
    '早退',
    '上班缺卡',
    '下班缺卡',
    '旷工',
    '排班缺失',
    '请假与打卡冲突',
    '加班未审批',
    '加班异常',
  ],
  exceptionSeverities: ['全部级别', '高', '中', '低'],
  exceptionStates: ['全部状态', '待处理', '待员工说明', '待补签', '处理中', '已处理'],
  lateCounts: ['全部次数', '2次及以上', '3次及以上'],
  lateLevels: ['全部级别', '10分钟以内', '11-30分钟', '30分钟以上'],
  punchTypes: ['全部时段', '上班缺卡', '下班缺卡'],
  attendanceTypes: ['全部类型', '事假', '病假', '年假', '调休'],
  annualBalances: ['全部余额', '有余额', '余额不足2天', '已用完'],
  annualLevelOnes: ['全部一级部门', '制造中心', '研发中心', '职能中心'],
  annualLevelTwos: ['全部二级部门', '晶圆制造部', '产品研发部', '综合管理部'],
} as const;

const annualDepartmentHierarchy: Readonly<Record<string, readonly string[]>> = {
  制造中心: ['晶圆制造部'],
  研发中心: ['产品研发部'],
  职能中心: ['综合管理部'],
};

export function annualLevelOneOptions(
  globalDepartment: string,
  allowedDepartments: readonly string[] = Object.keys(annualDepartmentHierarchy),
): string[] {
  const allowed = new Set(allowedDepartments);
  const departments = globalDepartment === '全部部门'
    ? Object.keys(annualDepartmentHierarchy).filter((department) => allowed.has(department))
    : Object.hasOwn(annualDepartmentHierarchy, globalDepartment)
      && allowed.has(globalDepartment)
      ? [globalDepartment]
      : [];
  return ['全部一级部门', ...departments];
}

export function annualLevelTwoOptions(
  levelOne: string,
  globalDepartment: string,
  allowedDepartments?: readonly string[],
): string[] {
  const allowedLevelOnes = annualLevelOneOptions(
    globalDepartment,
    allowedDepartments,
  ).slice(1);
  const selectedLevelOnes = levelOne === '全部一级部门'
    ? allowedLevelOnes
    : allowedLevelOnes.includes(levelOne)
      ? [levelOne]
      : [];
  const levelTwos = selectedLevelOnes.flatMap(
    (department) => annualDepartmentHierarchy[department] ?? [],
  );
  return ['全部二级部门', ...new Set(levelTwos)];
}

export function normalizeAnnualLeaveFilters(
  filters: CustomerReportSpecificFilters,
  globalDepartment: string,
  allowedDepartments?: readonly string[],
): CustomerReportSpecificFilters {
  const levelOneOptions = annualLevelOneOptions(globalDepartment, allowedDepartments);
  const annualLevelOne = levelOneOptions.includes(filters.annualLevelOne)
    ? filters.annualLevelOne
    : defaultCustomerReportSpecificFilters.annualLevelOne;
  const levelTwoOptions = annualLevelTwoOptions(
    annualLevelOne,
    globalDepartment,
    allowedDepartments,
  );
  const annualLevelTwo = levelTwoOptions.includes(filters.annualLevelTwo)
    ? filters.annualLevelTwo
    : defaultCustomerReportSpecificFilters.annualLevelTwo;
  return { ...filters, annualLevelOne, annualLevelTwo };
}

const staff = [
  { no: 'SZ0261', employee: '陈思远', department: '制造中心', position: '设备工程师' },
  { no: 'SZ0284', employee: '周晴', department: '制造中心', position: '工艺工程师' },
  { no: 'SZ0318', employee: '张伟', department: '研发中心', position: '研发工程师' },
  { no: 'SZ0342', employee: '林晓雯', department: '研发中心', position: '产品工程师' },
  { no: 'SZ0366', employee: '赵凯', department: '制造中心', position: '生产主管' },
  { no: 'SZ0381', employee: '蒋宁', department: '职能中心', position: '人力资源专员' },
  { no: 'SZ0415', employee: '吴昊', department: '研发中心', position: '测试工程师' },
  { no: 'SZ0437', employee: '沈佳', department: '职能中心', position: '财务专员' },
] as const;

const leaveTypes = ['事假', '丧假', '病假', '婚假', '陪产假', '年假', '调休'] as const;
const weekdays = ['日', '一', '二', '三', '四', '五', '六'] as const;

const statusOverrides: Record<string, AttendanceStatusKey> = {
  '0-1': 'late',
  '0-5': 'personal-leave',
  '0-10': 'overtime',
  '0-16': 'trip',
  '1-3': 'annual-leave',
  '1-9': 'corrected',
  '1-18': 'out',
  '2-2': 'time-off',
  '2-11': 'sick-leave',
  '2-22': 'early',
  '3-6': 'missed',
  '3-12': 'corrected',
  '3-24': 'overtime',
  '4-8': 'trip',
  '4-15': 'out',
  '4-26': 'time-off',
  '5-4': 'personal-leave',
  '5-17': 'annual-leave',
  '5-25': 'missed',
  '6-7': 'overtime',
  '6-19': 'late',
  '6-29': 'corrected',
  '7-2': 'sick-leave',
  '7-13': 'early',
  '7-23': 'trip',
};

const leaveRows: LeaveReportRow[] = staff.map((person, index) => ({
  id: index + 1,
  employee: person.employee,
  department: person.department,
  type: leaveTypes[index % leaveTypes.length]!,
  hours: [11.5, 8, 28, 72, 80, 16, 7.5, 4][index]!,
  period: [
    '06-05 13:30 ～ 06-06 17:30',
    '06-09 全天',
    '06-11 ～ 06-13',
    '06-16 ～ 06-24',
    '06-02 ～ 06-12',
    '06-17 ～ 06-18',
    '06-26 09:00 ～ 17:30',
    '06-30 13:30 ～ 17:30',
  ][index]!,
  remark: index === 1 || index === 3 || index === 4 ? '带薪休假' : '审批流程已完成',
  approvalState: '已通过',
}));

function buildOvertimeRows(month: string): OvertimeReportRow[] {
  const first = staff[0]!;
  const documentDays = [18, 20, 23, 25];
  const firstPersonRows = documentDays.map((day, index) => overtimeDocument(
    first,
    month,
    day,
    index % 2 === 0 ? '调休' : '加班费',
    2.5,
    index === 0 ? '纸质' : 'OA',
  ));
  const otherRows = staff.slice(1).flatMap((person, index) => {
    const day = 4 + index * 3;
    return [overtimeDocument(
      person,
      month,
      day,
      index % 3 === 0 ? '调休' : index % 3 === 1 ? '义务加班' : '加班费',
      index % 2 === 0 ? 3 : 2.5,
      'OA',
    )];
  });
  return [...firstPersonRows, ...otherRows];
}

function overtimeDocument(
  person: (typeof staff)[number],
  month: string,
  day: number,
  overtimeType: string,
  hours: number,
  source: string,
): OvertimeReportRow {
  const date = `${month}-${String(day).padStart(2, '0')}`;
  return {
    employee: person.employee,
    employeeNo: person.no,
    department: person.department,
    overtimeType,
    hours,
    period: `${date} 17:40 ~ ${date} 22:00`,
    overtimeDate: date,
    approvalState: '已通过',
    source,
    paidHours: overtimeType === '加班费' ? hours : 0,
    compensatoryHours: overtimeType === '调休' ? hours : 0,
    voluntaryHours: overtimeType === '义务加班' ? hours : 0,
    totalHours: hours,
    classificationAvailable: true,
  };
}

function overtimeTreatmentOf(overtimeType: string): 'PAID' | 'COMPENSATORY' | 'VOLUNTARY' {
  if (overtimeType === '加班费') return 'PAID';
  if (overtimeType === '调休') return 'COMPENSATORY';
  return 'VOLUNTARY';
}

function applyOvertimeHours(
  day: FinanceOvertimeReportRow['days'][number],
  overtimeType: string,
  hours: number,
) {
  if (overtimeType === '加班费') {
    day.paidHours = (day.paidHours ?? 0) + hours;
  } else if (overtimeType === '调休') {
    day.compensatoryHours = (day.compensatoryHours ?? 0) + hours;
  } else {
    day.voluntaryHours = (day.voluntaryHours ?? 0) + hours;
  }
  const paid = day.paidHours ?? 0;
  const compensatory = day.compensatoryHours ?? 0;
  const voluntary = day.voluntaryHours ?? 0;
  const fee = paid + compensatory;
  day.hours = fee > 0 ? fee : voluntary;
  day.treatment = overtimeTreatmentFromHours(paid, compensatory, voluntary)
    || overtimeTreatmentOf(overtimeType);
}

function buildFinanceOvertimeRows(
  overtimeRows: OvertimeReportRow[],
): FinanceOvertimeReportRow[] {
  const grouped = new Map<string, FinanceOvertimeReportRow>();
  overtimeRows.forEach((row) => {
    const key = row.employeeNo ?? row.employee;
    const date = row.overtimeDate ?? '';
    const hours = row.hours;
    const bucket = date ? isoWeekdayBucket(date) : 'weekday';
    const existing = grouped.get(key);
    if (!existing) {
      const day = date
        ? {
          date,
          hours: 0,
          dayType: bucket === 'weekend' ? 'SATURDAY' : 'WEEKDAY',
          paidHours: 0,
          compensatoryHours: 0,
          voluntaryHours: 0,
        }
        : undefined;
      if (day) applyOvertimeHours(day, row.overtimeType, hours);
      const paidHours = row.paidHours ?? (row.overtimeType === '加班费' ? hours : 0);
      const compensatoryHours = row.compensatoryHours ?? (row.overtimeType === '调休' ? hours : 0);
      const voluntaryHours = row.voluntaryHours ?? (row.overtimeType === '义务加班' ? hours : 0);
      const feeHours = paidHours + compensatoryHours;
      const bucketHours = feeHours > 0 ? feeHours : voluntaryHours;
      grouped.set(key, {
        employeeNo: row.employeeNo ?? '',
        department: row.department,
        employee: row.employee,
        weekdayOvertimeHours: bucket === 'weekday' ? bucketHours : 0,
        weekendOvertimeHours: bucket === 'weekend' ? bucketHours : 0,
        holidayOvertimeHours: 0,
        paidOvertimeHours: paidHours,
        compensatoryOvertimeHours: compensatoryHours,
        voluntaryOvertimeHours: voluntaryHours,
        days: day ? [day] : [],
      });
      return;
    }
    const paidAdd = row.paidHours ?? (row.overtimeType === '加班费' ? hours : 0);
    const compensatoryAdd = row.compensatoryHours ?? (row.overtimeType === '调休' ? hours : 0);
    const voluntaryAdd = row.voluntaryHours ?? (row.overtimeType === '义务加班' ? hours : 0);
    const bucketAdd = paidAdd + compensatoryAdd > 0 ? paidAdd + compensatoryAdd : voluntaryAdd;
    if (bucket === 'weekend') {
      existing.weekendOvertimeHours += bucketAdd;
    } else {
      existing.weekdayOvertimeHours += bucketAdd;
    }
    existing.paidOvertimeHours = (existing.paidOvertimeHours ?? 0) + paidAdd;
    existing.compensatoryOvertimeHours = (existing.compensatoryOvertimeHours ?? 0) + compensatoryAdd;
    existing.voluntaryOvertimeHours = (existing.voluntaryOvertimeHours ?? 0) + voluntaryAdd;
    if (date) {
      const same = existing.days.find((item) => item.date === date);
      if (same) {
        applyOvertimeHours(same, row.overtimeType, hours);
      } else {
        const day = {
          date,
          hours: 0,
          dayType: bucket === 'weekend' ? 'SATURDAY' : 'WEEKDAY',
          paidHours: 0,
          compensatoryHours: 0,
          voluntaryHours: 0,
        };
        applyOvertimeHours(day, row.overtimeType, hours);
        existing.days.push(day);
      }
    }
  });
  return [...grouped.values()].map((row) => {
    const mixed = row.days.find((day) => day.date.slice(8, 10) === '18'
      && (day.compensatoryHours ?? 0) > 0);
    if (!mixed) {
      return row;
    }
    mixed.paidHours = (mixed.paidHours ?? 0) + 1;
    const paid = mixed.paidHours ?? 0;
    const compensatory = mixed.compensatoryHours ?? 0;
    const voluntary = mixed.voluntaryHours ?? 0;
    mixed.hours = paid + compensatory > 0 ? paid + compensatory : voluntary;
    mixed.treatment = overtimeTreatmentFromHours(paid, compensatory, voluntary) || mixed.treatment;
    return {
      ...row,
      weekdayOvertimeHours: isoWeekdayBucket(mixed.date) === 'weekday'
        ? row.weekdayOvertimeHours + 1
        : row.weekdayOvertimeHours,
      weekendOvertimeHours: isoWeekdayBucket(mixed.date) === 'weekend'
        ? row.weekendOvertimeHours + 1
        : row.weekendOvertimeHours,
      paidOvertimeHours: (row.paidOvertimeHours ?? 0) + 1,
    };
  });
}

function buildWorkHoursRows(
  month: string,
  monthlyOvertimeRows: OvertimeReportRow[],
): WorkHoursReportRow[] {
  const monthNumber = month.slice(5, 7);
  return staff.map((person, index) => {
    const leaveHours = [0, 8, 16, 0, 8, 16, 0, 4][index]!;
    const annualLeaveHours = [0, 0, 4.5, 0, 8, 0, 0, 0][index]!;
    const exchangedHours = index === 4 ? 44.5 : index === 6 ? 10.5 : 0;
    const overtimeHours = roundHours(
      monthlyOvertimeRows
        .filter((row) => row.employee === person.employee)
        .reduce((total, row) => total + (row.totalHours ?? row.hours), 0),
    );
    const plannedHours = index === 0 ? 120 : index === 4 ? 80 : index === 7 ? 88 : 168;
    const noteTemplate = [
      '22日离职',
      '12日离职',
      '全月出勤',
      '01日入职',
      '12日离职',
      '15日入职',
      '全月出勤',
      '15日入职',
    ][index]!;
    return {
      employee: person.employee,
      department: person.department,
      plannedHours,
      overtimeHours,
      leaveHours,
      annualLeaveHours,
      exchangedHours,
      actualHours: roundHours(
        plannedHours + overtimeHours - leaveHours - annualLeaveHours
          + exchangedHours - (index === 4 ? 8 : 0),
      ),
      usedTimeOffHours: index === 4 ? 8 : 0,
      note: noteTemplate === '全月出勤' ? noteTemplate : `${monthNumber}月${noteTemplate}`,
    };
  });
}

function monthlyLeaveRows(month: string): LeaveReportRow[] {
  const monthNumber = month.slice(5, 7);
  return leaveRows.map((row) => ({
    ...row,
    period: row.period.replaceAll('06-', `${monthNumber}-`),
  }));
}

function monthlyExceptionRows(
  rows: ExceptionReportRow[],
  month: string,
): ExceptionReportRow[] {
  const monthNumber = month.slice(5, 7);
  return rows.map((row) => ({
    ...row,
    details: row.details.replaceAll('06月', `${monthNumber}月`),
  }));
}

function monthlyAttendanceExceptionRows(
  rows: AttendanceExceptionReportRow[],
  month: string,
): AttendanceExceptionReportRow[] {
  return rows.map((row) => ({
    ...row,
    businessDate: row.businessDate.replace('2026-06', month),
    dueAt: row.dueAt?.replace('2026-06', month),
  }));
}

const lateRows: ExceptionReportRow[] = [
  { id: 1, employee: '陈思远', department: '制造中心', count: 2, details: '06月01日 09:06、06月15日 08:36', reviewer: '徐丽丽', state: '已复核', lateMinutes: 36 },
  { id: 2, employee: '周晴', department: '制造中心', count: 3, details: '06月18日 08:34、06月29日 08:38、06月30日 08:35', reviewer: '吴芸', state: '已复核', lateMinutes: 8 },
  { id: 3, employee: '吴昊', department: '研发中心', count: 2, details: '06月09日 09:02、06月22日 08:47', reviewer: '吴芸', state: '待说明', lateMinutes: 32 },
  { id: 4, employee: '沈佳', department: '职能中心', count: 1, details: '06月19日 08:48', reviewer: '徐丽丽', state: '已复核', lateMinutes: 18 },
];

const missedPunchRows: ExceptionReportRow[] = [
  { id: 1, employee: '林晓雯', department: '研发中心', count: 2, details: '06月18日（上班）、06月29日（上班）', reviewer: '徐丽丽', state: '待补签' },
  { id: 2, employee: '蒋宁', department: '职能中心', count: 2, details: '06月09日（上班）、06月10日（下班）', reviewer: '吴芸', state: '已补签' },
  { id: 3, employee: '赵凯', department: '制造中心', count: 1, details: '06月25日（下班）', reviewer: '吴芸', state: '待补签' },
];

const attendanceExceptionRows: AttendanceExceptionReportRow[] = [
  {
    id: 1,
    businessDate: '2026-06-01',
    employeeNo: 'SZ0261',
    employee: '陈思远',
    department: '制造中心',
    exceptionType: '迟到',
    severity: '低',
    shiftLabel: '常白班 A',
    scheduledWindow: '08:30–17:30',
    punchSummary: '09:06 / 18:18',
    exceptionMinutes: 36,
    details: '上班 09:06，计罚 36 分钟',
    evidenceSummary: '设备卡 · 月度宽限已使用',
    state: '已处理',
    owner: '徐丽丽',
    dueAt: '2026-06-03 18:00',
  },
  {
    id: 2,
    businessDate: '2026-06-18',
    employeeNo: 'SZ0284',
    employee: '周晴',
    department: '制造中心',
    exceptionType: '早退',
    severity: '中',
    shiftLabel: '常白班 A',
    scheduledWindow: '08:30–17:30',
    punchSummary: '08:24 / 16:42',
    exceptionMinutes: 48,
    details: '下班 16:42，早退 48 分钟',
    evidenceSummary: '设备卡 · 无匹配审批单',
    state: '待员工说明',
    owner: '吴芸',
    dueAt: '2026-06-20 18:00',
  },
  {
    id: 3,
    businessDate: '2026-06-18',
    employeeNo: 'SZ0342',
    employee: '林晓雯',
    department: '研发中心',
    exceptionType: '上班缺卡',
    severity: '中',
    shiftLabel: '研发弹性班',
    scheduledWindow: '09:00–18:00',
    punchSummary: '— / 18:26',
    details: '无上班卡，下班 18:26',
    evidenceSummary: '仅有下班卡 · 补签未提交',
    state: '待补签',
    owner: '徐丽丽',
    dueAt: '2026-06-25 18:00',
  },
  {
    id: 4,
    businessDate: '2026-06-25',
    employeeNo: 'SZ0366',
    employee: '赵凯',
    department: '制造中心',
    exceptionType: '下班缺卡',
    severity: '中',
    shiftLabel: '生产长白班',
    scheduledWindow: '08:00–20:00',
    punchSummary: '07:52 / —',
    details: '上班 07:52，无下班卡',
    evidenceSummary: '仅有上班卡 · OA 无补签单',
    state: '待补签',
    owner: '吴芸',
    dueAt: '2026-07-02 18:00',
  },
  {
    id: 5,
    businessDate: '2026-06-12',
    employeeNo: 'SZ0415',
    employee: '吴昊',
    department: '研发中心',
    exceptionType: '旷工',
    severity: '高',
    shiftLabel: '研发弹性班',
    scheduledWindow: '09:00–18:00',
    punchSummary: '无有效打卡',
    exceptionMinutes: 480,
    details: '应出勤，无打卡无单据',
    evidenceSummary: '无打卡 · 无已审批考勤单据',
    state: '处理中',
    owner: '吴芸',
    dueAt: '2026-06-13 12:00',
  },
  {
    id: 6,
    businessDate: '2026-06-15',
    employeeNo: 'SZ0437',
    employee: '沈佳',
    department: '职能中心',
    exceptionType: '排班缺失',
    severity: '高',
    shiftLabel: '未解析',
    scheduledWindow: '—',
    punchSummary: '08:28 / 17:46',
    details: '存在设备卡 · 当日无有效班次版本',
    evidenceSummary: '存在设备卡 · 当日无有效班次版本',
    state: '待处理',
    owner: '系统待分派',
    dueAt: '2026-06-15 12:00',
  },
  {
    id: 7,
    businessDate: '2026-06-11',
    employeeNo: 'SZ0318',
    employee: '张伟',
    department: '研发中心',
    exceptionType: '请假与打卡冲突',
    severity: '中',
    shiftLabel: '研发弹性班',
    scheduledWindow: '09:00–18:00',
    punchSummary: '08:55 / 18:21',
    details: '请假时段与打卡重叠',
    evidenceSummary: '已审批请假单 · 假因已脱敏',
    state: '待处理',
    owner: '徐丽丽',
    dueAt: '2026-06-13 18:00',
  },
  {
    id: 8,
    businessDate: '2026-06-17',
    employeeNo: 'SZ0381',
    employee: '蒋宁',
    department: '职能中心',
    exceptionType: '加班未审批',
    severity: '低',
    shiftLabel: '常白班 B',
    scheduledWindow: '08:30–17:30',
    punchSummary: '08:19 / 21:06',
    exceptionMinutes: 186,
    details: '加班时段盖住未请假的上班时段',
    evidenceSummary: '存在延时打卡 · 无已审批加班单',
    state: '待员工说明',
    owner: '徐丽丽',
    dueAt: '2026-06-19 18:00',
  },
];

const attendanceRateRows: AttendanceRateReportRow[] = [
  { id: 1, employee: '陈思远', department: '制造中心', type: '事假', hours: 8, scheduledDays: 16, actualDays: 15, sickLeaveDays: 0, rate: '93.75%', note: '按实际出勤天数 ÷ 应出勤天数' },
  { id: 2, employee: '周晴', department: '制造中心', scheduledDays: 22, actualDays: 22, sickLeaveDays: 0, rate: '100.00%', note: '按实际出勤天数 ÷ 应出勤天数' },
  { id: 3, employee: '张伟', department: '研发中心', type: '事假', hours: 16, scheduledDays: 22, actualDays: 20, sickLeaveDays: 0, rate: '90.91%', note: '按实际出勤天数 ÷ 应出勤天数' },
  { id: 4, employee: '林晓雯', department: '研发中心', type: '病假', hours: 8, scheduledDays: 18, actualDays: 18, sickLeaveDays: 1, rate: '100.00%', note: '病假计入实际出勤' },
  { id: 5, employee: '赵凯', department: '制造中心', type: '病假', hours: 16, scheduledDays: 12, actualDays: 12, sickLeaveDays: 2, rate: '100.00%', note: '病假计入实际出勤' },
  { id: 6, employee: '蒋宁', department: '职能中心', type: '年假', hours: 8, scheduledDays: 22, actualDays: 22, sickLeaveDays: 0, rate: '100.00%', note: '带薪假计入实际出勤' },
  { id: 7, employee: '吴昊', department: '研发中心', scheduledDays: 22, actualDays: 21, sickLeaveDays: 0, rate: '95.45%', note: '按实际出勤天数 ÷ 应出勤天数' },
  { id: 8, employee: '沈佳', department: '职能中心', scheduledDays: 11, actualDays: 11, sickLeaveDays: 0, rate: '100.00%', note: '按实际出勤天数 ÷ 应出勤天数' },
];

const annualLeaveRows: AnnualLeaveReportRow[] = staff.map((person, index) => {
  const statutoryDays = [10, 10, 5, 5, 10, 5, 5, 5][index]!;
  const availableDays = index === 3 ? 2.5 : statutoryDays;
  const used = Array.from({ length: 12 }, (_, monthIndex) => {
    if ((monthIndex + index) % 7 === 0) return index % 2 === 0 ? 1 : 0.5;
    return 0;
  });
  return {
    id: index + 1,
    departmentLevelOne: person.department,
    departmentLevelTwo: person.department === '制造中心' ? '晶圆制造部' : person.department === '研发中心' ? '产品研发部' : '综合管理部',
    department: joinDepartmentSegments([
      person.department,
      person.department === '制造中心' ? '晶圆制造部' : person.department === '研发中心' ? '产品研发部' : '综合管理部',
    ]),
    employee: person.employee,
    joinedOn: [`2009/09/09`, `2013/04/18`, `2018/07/02`, `2026/04/01`, `2015/11/23`, `2021/03/15`, `2023/08/08`, `2026/06/15`][index]!,
    companySeniority: [16.9, 13.2, 8, 0.3, 10.6, 5.3, 2.9, 0.1][index]!,
    priorSeniority: [0, 3, 2, 0, 4, 0, 1.5, 0][index]!,
    totalSeniority: [16.9, 16.2, 10, 0.3, 14.6, 5.3, 4.4, 0.1][index]!,
    statutoryDays,
    newHireDays: index === 3 ? 2.5 : index === 7 ? 0.5 : 0,
    availableDays,
    availableHours: availableDays * 8,
    monthlyUsedDays: used,
    remainingDays: roundHours(
      Math.max(0, availableDays - used.reduce<number>((total, days) => total + days, 0)),
    ),
    note: index === 3 || index === 7 ? '新入职按在岗天数折算' : '',
  };
});

function buildDailyJournalRows(
  attendanceRows: AttendanceDetailRow[],
  month: string,
): DailyJournalReportRow[] {
  const rows: DailyJournalReportRow[] = [];
  attendanceRows.forEach((row) => {
    row.days.forEach((day) => {
      rows.push({
        sequence: rows.length + 1,
        employeeNo: row.employeeNo,
        department: row.department,
        employee: row.employee,
        businessDate: `${month}-${String(day.day).padStart(2, '0')}`,
        shiftLabel: row.position ?? '',
        onDuty: day.primary,
        offDuty: day.secondary,
        lateHours: day.primaryStatus === 'late' || day.status === 'late' ? 0.5 : '',
        earlyHours: day.secondaryStatus === 'early' || day.status === 'early' ? 0.5 : '',
        absenceHours: '',
        leaveType: '',
        overtimeHours: day.primaryStatus === 'overtime' || day.secondaryStatus === 'overtime' || day.status === 'overtime'
          ? 2.5
          : '',
        remark: day.primary === '漏刷' || day.secondary === '漏刷' ? '漏刷' : '',
      });
    });
  });
  return rows;
}

export function getCustomerReportDemo(
  filters: CustomerReportFilters,
  dataScope: CustomerReportDataScope = defaultCustomerReportDataScope,
): CustomerReportDemo {
  const monthlyOvertimeRows = buildOvertimeRows(filters.month);
  const attendanceRows = filterPeople(
    staff.map((person, index) => ({
      employeeNo: person.no,
      department: person.department,
      employee: person.employee,
      position: person.position,
      days: buildAttendanceDays(filters.month, index).filter((day) => (
        inSelectedRange(
          `${filters.month}-${String(day.day).padStart(2, '0')}`,
          filters,
        )
      )),
    })),
    filters,
    dataScope,
  );
  const filteredLeaveRows = filterPeople(
    monthlyLeaveRows(filters.month),
    filters,
    dataScope,
  );
  const filteredOvertimeRows = filterPeople(monthlyOvertimeRows, filters, dataScope)
    .filter((row) => inSelectedRange(row.overtimeDate, filters));
  const filteredWorkHoursRows = filterPeople(
    buildWorkHoursRows(filters.month, monthlyOvertimeRows),
    filters,
    dataScope,
  );
  const filteredAttendanceExceptionRows = filterPeople(
    monthlyAttendanceExceptionRows(attendanceExceptionRows, filters.month),
    filters,
    dataScope,
  );
  const filteredLateRows = filterPeople(
    monthlyExceptionRows(lateRows, filters.month),
    filters,
    dataScope,
  );
  const filteredMissedPunchRows = filterPeople(
    monthlyExceptionRows(missedPunchRows, filters.month),
    filters,
    dataScope,
  );
  const filteredAttendanceRateRows = filterPeople(attendanceRateRows, filters, dataScope);
  const filteredAnnualLeaveRows = filterPeople(annualLeaveRows, filters, dataScope);
  const monthLabel = formatMonth(filters.month);

  return {
    metadata: {
      isDemo: true,
      company: '江苏神州半导体科技有限公司',
      generatedAt: '2026-07-28T13:45:00+08:00',
      month: filters.month,
      monthLabel,
      rowCount: attendanceRows.length,
      dataScope,
    },
    attendanceRows,
    dailyJournalRows: buildDailyJournalRows(attendanceRows, filters.month),
    overtimeDailyRows: filteredOvertimeRows.map((row) => {
      const date = row.overtimeDate ?? `${filters.month}-01`;
      const bucket = isoWeekdayBucket(date);
      return {
        employeeNo: row.employeeNo ?? '',
        department: row.department,
        employee: row.employee,
        businessDate: date,
        weekdayOvertimeHours: bucket === 'weekday' ? row.hours : 0,
        weekendOvertimeHours: bucket === 'weekend' ? row.hours : 0,
        holidayOvertimeHours: 0,
        paidOvertimeHours: row.paidHours ?? 0,
        compensatoryOvertimeHours: row.compensatoryHours ?? 0,
        voluntaryOvertimeHours: row.voluntaryHours ?? 0,
      };
    }),
    financeOvertimeRows: buildFinanceOvertimeRows(filteredOvertimeRows),
    leaveRows: filteredLeaveRows,
    overtimeRows: filteredOvertimeRows,
    workHoursRows: filteredWorkHoursRows,
    attendanceExceptionRows: filteredAttendanceExceptionRows,
    lateRows: filteredLateRows,
    missedPunchRows: filteredMissedPunchRows,
    attendanceRateRows: filteredAttendanceRateRows,
    annualLeaveRows: filteredAnnualLeaveRows,
  };
}

export function applyCustomerReportSpecificFilters(
  report: CustomerReportDemo,
  reportKey: CustomerReportKey,
  filters: CustomerReportSpecificFilters,
): CustomerReportDemo {
  switch (reportKey) {
    case 'attendance-detail':
      return {
        ...report,
        attendanceRows: filters.attendanceStatus === '全部状态'
          ? report.attendanceRows
          : report.attendanceRows.filter((row) => (
            row.days.some((day) => (
              day.status === filters.attendanceStatus
              || day.primaryStatus === filters.attendanceStatus
              || day.secondaryStatus === filters.attendanceStatus
              || day.mergedStatus === filters.attendanceStatus
            ))
          )),
      };
    case 'leave':
      return {
        ...report,
        leaveRows: filters.leaveType === '全部类型'
          ? report.leaveRows
          : report.leaveRows.filter((row) => row.type === filters.leaveType),
      };
    case 'overtime': {
      const selectedDay = filters.overtimeDay === '全部日期'
        ? undefined
        : Number(filters.overtimeDay);
      return {
        ...report,
        overtimeRows: report.overtimeRows.filter((row) => (
          matchesOvertimeType(row, filters.overtimeType)
          && (selectedDay === undefined
            || Number(row.overtimeDate?.slice(8, 10)) === selectedDay)
        )),
      };
    }
    case 'overtime-daily': {
      const selectedDay = filters.overtimeDay === '全部日期'
        ? undefined
        : Number(filters.overtimeDay);
      return {
        ...report,
        overtimeDailyRows: report.overtimeDailyRows.filter((row) => (
          matchesDailyOvertimeTreatment(row, filters.overtimeType)
          && (selectedDay === undefined
            || Number(row.businessDate.slice(8, 10)) === selectedDay)
        )),
      };
    }
    case 'finance-overtime': {
      const selectedDay = filters.overtimeDay === '全部日期'
        ? undefined
        : Number(filters.overtimeDay);
      return {
        ...report,
        financeOvertimeRows: report.financeOvertimeRows.filter((row) => (
          matchesFinanceOvertimeTreatment(row, filters.overtimeType)
          && (selectedDay === undefined
            || row.days.some((day) => Number(day.date.slice(8, 10)) === selectedDay))
        )).map((row) => projectFinanceOvertimeRow(
          selectedDay === undefined
            ? row
            : {
              ...row,
              days: row.days.filter((day) => Number(day.date.slice(8, 10)) === selectedDay),
            },
          filters.overtimeType,
        )),
      };
    }
    case 'work-hours':
      return {
        ...report,
        workHoursRows: filters.employmentStatus === '全部状态'
          ? report.workHoursRows
          : report.workHoursRows.filter((row) => {
            const note = row.note ?? '';
            if (filters.employmentStatus === '本月入职') return note.includes('入职');
            if (filters.employmentStatus === '本月离职') return note.includes('离职');
            return !note.includes('入职') && !note.includes('离职');
          }),
      };
    case 'exceptions':
      return {
        ...report,
        attendanceExceptionRows: report.attendanceExceptionRows.filter((row) => (
          (filters.exceptionType === '全部异常'
            || row.exceptionType === filters.exceptionType)
          && (filters.exceptionSeverity === '全部级别'
            || row.severity === filters.exceptionSeverity)
          && (filters.exceptionState === '全部状态'
            || row.state === filters.exceptionState)
        )),
      };
    case 'late':
      return {
        ...report,
        lateRows: report.lateRows.filter((row) => (
          matchesLateCount(row, filters.lateCount)
          && matchesLateLevel(row, filters.lateLevel)
        )),
      };
    case 'missed-punch':
      return {
        ...report,
        missedPunchRows: filters.punchType === '全部时段'
          ? report.missedPunchRows
          : report.missedPunchRows.filter((row) => (
            row.details.includes(filters.punchType === '上班缺卡' ? '上班' : '下班')
          )),
      };
    case 'attendance-rate':
      return {
        ...report,
        attendanceRateRows: filters.attendanceType === '全部类型'
          ? report.attendanceRateRows
          : report.attendanceRateRows.filter((row) => row.type === filters.attendanceType),
      };
    case 'annual-leave': {
      const levelOneIsAvailable = filters.annualLevelOne === '全部一级部门'
        || report.annualLeaveRows.some(
          (row) => row.departmentLevelOne === filters.annualLevelOne,
        );
      const effectiveLevelOne = levelOneIsAvailable
        ? filters.annualLevelOne
        : '全部一级部门';
      const levelOneRows = effectiveLevelOne === '全部一级部门'
        ? report.annualLeaveRows
        : report.annualLeaveRows.filter(
          (row) => row.departmentLevelOne === effectiveLevelOne,
        );
      const levelTwoIsAvailable = filters.annualLevelTwo === '全部二级部门'
        || levelOneRows.some(
          (row) => row.departmentLevelTwo === filters.annualLevelTwo,
        );
      const effectiveLevelTwo = levelTwoIsAvailable
        ? filters.annualLevelTwo
        : '全部二级部门';
      return {
        ...report,
        annualLeaveRows: levelOneRows.filter((row) => (
          matchesAnnualBalance(row, filters.annualBalance)
          && (effectiveLevelTwo === '全部二级部门'
            || row.departmentLevelTwo === effectiveLevelTwo)
        )),
      };
    }
    case 'overtime-daily':
    case 'finance-overtime':
    case 'daily-journal':
      return report;
  }
}

export interface CustomerReportOverviewCard {
  label: string;
  value: string | number;
  unit: string;
  hint: string;
  tone?: 'default' | 'warning';
}

const EXCEPTION_DAY_STATUSES: ReadonlySet<AttendanceStatusKey> = new Set([
  'late',
  'early',
  'missed',
]);
const LEAVE_DAY_STATUSES: ReadonlySet<AttendanceStatusKey> = new Set([
  'personal-leave',
  'sick-leave',
  'annual-leave',
  'time-off',
  'marriage-leave',
  'maternity-leave',
  'paternity-leave',
  'bereavement-leave',
  'work-injury-leave',
  'nursing-leave',
  'breastfeeding-leave',
  'prenatal-exam-leave',
  'family-planning-leave',
  'out',
  'trip',
]);

/**
 * Overview cards must come from the sheet currently on screen. Live mode only
 * loads that one report type, so reading leave/overtime/exception arrays while
 * the operator is on 工时 or 出勤率 leaves the cards at zero after a department
 * filter.
 */
export function overviewCards(
  report: CustomerReportDemo,
  reportKey: CustomerReportKey,
): CustomerReportOverviewCard[] {
  switch (reportKey) {
    case 'attendance-detail': {
      const rows = report.attendanceRows;
      let exceptionDays = 0;
      let leaveDays = 0;
      let workedDays = 0;
      for (const row of rows) {
        for (const day of row.days) {
          const statuses = [
            day.status,
            day.primaryStatus,
            day.secondaryStatus,
            day.mergedStatus,
          ].filter((status): status is AttendanceStatusKey => status !== undefined);
          if (statuses.some((status) => EXCEPTION_DAY_STATUSES.has(status))) {
            exceptionDays += 1;
          }
          if (statuses.some((status) => LEAVE_DAY_STATUSES.has(status))) {
            leaveDays += 1;
          }
          if (statuses.some((status) => status !== 'rest-day')) {
            workedDays += 1;
          }
        }
      }
      return [
        peopleCard(rows, '筛选范围内在册人员'),
        {
          label: '出勤人日',
          value: workedDays,
          unit: '日',
          hint: '不含纯休息日的格子',
        },
        {
          label: '异常人日',
          value: exceptionDays,
          unit: '日',
          hint: '迟到、早退或漏刷',
        },
        {
          label: '休假人日',
          value: leaveDays,
          unit: '日',
          hint: '请假、调休、外出或出差',
        },
      ];
    }
    case 'leave': {
      const rows = report.leaveRows;
      const hours = sumNumbers(rows.map((row) => row.hours));
      return [
        peopleCard(rows, '当前请假名单人数'),
        {
          label: '请假总时长',
          value: formatHours(hours),
          unit: '小时',
          hint: `${rows.length} 条已审批记录`,
        },
        {
          label: '请假记录',
          value: rows.length,
          unit: '条',
          hint: '当前筛选条件下的单据',
        },
        {
          label: '人均请假',
          value: formatHours(average(hours, uniquePeople(rows))),
          unit: '小时',
          hint: '总时长 ÷ 人数',
        },
      ];
    }
    case 'overtime': {
      const rows = report.overtimeRows;
      const total = sumNumbers(rows.map((row) => row.totalHours ?? 0));
      const paid = sumNumbers(rows.map((row) => row.paidHours ?? 0));
      const compensatory = sumNumbers(rows.map((row) => row.compensatoryHours ?? 0));
      return [
        peopleCard(rows, '当前加班名单人数'),
        {
          label: '加班总时长',
          value: formatHours(total),
          unit: '小时',
          hint: '计薪、转调休与义务加班汇总',
        },
        {
          label: '计薪加班',
          value: formatHours(paid),
          unit: '小时',
          hint: '当前部门范围内',
        },
        {
          label: '转调休加班',
          value: formatHours(compensatory),
          unit: '小时',
          hint: '当前部门范围内',
        },
      ];
    }
    case 'work-hours': {
      const rows = report.workHoursRows;
      return [
        peopleCard(rows, '筛选范围内在册人员'),
        {
          label: '应出勤工时',
          value: formatHours(sumNumbers(rows.map((row) => row.plannedHours))),
          unit: '小时',
          hint: '当前部门应出勤合计',
        },
        {
          label: '加班总时长',
          value: formatHours(sumNumbers(rows.map((row) => row.overtimeHours))),
          unit: '小时',
          hint: '当前部门加班合计',
        },
        {
          label: '实际出勤工时',
          value: formatHours(sumNumbers(rows.map((row) => row.actualHours))),
          unit: '小时',
          hint: '当前部门实际出勤合计',
        },
      ];
    }
    case 'exceptions': {
      const rows = report.attendanceExceptionRows;
      const open = rows.filter((row) => row.state !== '已处理').length;
      const high = rows.filter((row) => row.severity === '高').length;
      return [
        peopleCard(rows, '当前异常涉及员工'),
        {
          label: '异常总数',
          value: rows.length,
          unit: '项',
          hint: '当前筛选条件下的异常',
        },
        {
          label: '待处理异常',
          value: open,
          unit: '项',
          hint: '可在考勤异常总览中分级处理',
          tone: 'warning',
        },
        {
          label: '高风险',
          value: high,
          unit: '项',
          hint: '错误级别，建议优先处理',
          tone: high > 0 ? 'warning' : 'default',
        },
      ];
    }
    case 'late': {
      const rows = report.lateRows;
      const events = sumNumbers(rows.map((row) => row.count));
      const minutes = sumNumbers(rows.map((row) => row.lateMinutes ?? 0));
      return [
        peopleCard(rows, '当前迟到名单人数'),
        {
          label: '迟到人次',
          value: events,
          unit: '次',
          hint: '当前部门累计迟到次数',
        },
        {
          label: '累计分钟',
          value: minutes,
          unit: '分钟',
          hint: '有分钟数的迟到合计',
        },
        {
          label: '人均次数',
          value: formatHours(average(events, uniquePeople(rows))),
          unit: '次',
          hint: '迟到次数 ÷ 人数',
        },
      ];
    }
    case 'missed-punch': {
      const rows = report.missedPunchRows;
      const events = sumNumbers(rows.map((row) => row.count));
      return [
        peopleCard(rows, '当前缺卡名单人数'),
        {
          label: '缺卡人次',
          value: events,
          unit: '次',
          hint: '当前部门累计缺卡',
        },
        {
          label: '缺卡记录',
          value: rows.length,
          unit: '人',
          hint: '有缺卡的员工数',
        },
        {
          label: '人均缺卡',
          value: formatHours(average(events, uniquePeople(rows))),
          unit: '次',
          hint: '缺卡次数 ÷ 人数',
        },
      ];
    }
    case 'attendance-rate': {
      const rows = report.attendanceRateRows;
      const scheduled = rows.filter((row) => (row.scheduledDays ?? 0) > 0);
      const actual = rows.filter((row) => (row.actualDays ?? 0) > 0);
      const rates = rows
        .map((row) => parsePercent(row.rate))
        .filter((value): value is number => value !== undefined);
      const hours = rows
        .map((row) => row.hours)
        .filter((value): value is number => value !== undefined);
      return [
        {
          label: '应出勤人数',
          value: scheduled.length > 0 ? scheduled.length : uniquePeople(rows),
          unit: '人',
          hint: '当前部门应出勤人员',
        },
        {
          label: '实际出勤人数',
          value: actual.length > 0 ? actual.length : uniquePeople(rows),
          unit: '人',
          hint: '当前部门有实际出勤的人员',
        },
        {
          label: '平均出勤率',
          value: rates.length > 0
            ? `${average(sumNumbers(rates), rates.length).toFixed(1)}%`
            : '—',
          unit: '',
          hint: '按当前部门人员平均',
        },
        {
          label: '平均工时',
          value: hours.length > 0
            ? formatHours(average(sumNumbers(hours), hours.length))
            : formatHours(average(
              sumNumbers(rows.map((row) => row.actualDays ?? 0)),
              uniquePeople(rows),
            )),
          unit: hours.length > 0 ? '小时' : '天',
          hint: '当前部门人均',
        },
      ];
    }
    case 'annual-leave': {
      const rows = report.annualLeaveRows;
      const available = sumNumbers(rows.map((row) => row.availableDays));
      const used = sumNumbers(rows.flatMap((row) => row.monthlyUsedDays ?? []));
      const remaining = sumNumbers(rows.map((row) => row.remainingDays));
      return [
        peopleCard(rows, '当前年假名单人数'),
        {
          label: '可休总额',
          value: formatHours(available),
          unit: '天',
          hint: '当前部门可休年假',
        },
        {
          label: '已使用',
          value: formatHours(used),
          unit: '天',
          hint: '当前部门已休年假',
        },
        {
          label: '剩余',
          value: formatHours(remaining),
          unit: '天',
          hint: '当前部门剩余年假',
        },
      ];
    }
    case 'finance-overtime': {
      const rows = report.financeOvertimeRows;
      return [
        peopleCard(rows, '有加班记录的人数'),
        {
          label: '平时加班',
          value: formatHours(sumNumbers(rows.map((row) => row.weekdayOvertimeHours))),
          unit: '小时',
          hint: '工作日合计',
        },
        {
          label: '周末加班',
          value: formatHours(sumNumbers(rows.map((row) => row.weekendOvertimeHours))),
          unit: '小时',
          hint: '周六日合计',
        },
        {
          label: '节假日加班',
          value: formatHours(sumNumbers(rows.map((row) => row.holidayOvertimeHours))),
          unit: '小时',
          hint: '法定节假日合计',
        },
      ];
    }
    case 'overtime-daily': {
      const rows = report.overtimeDailyRows;
      return [
        peopleCard(rows, '有加班记录的人数'),
        {
          label: '工作日加班',
          value: formatHours(sumNumbers(rows.map((row) => row.weekdayOvertimeHours))),
          unit: '小时',
          hint: '当前窗口合计',
        },
        {
          label: '周末加班',
          value: formatHours(sumNumbers(rows.map((row) => row.weekendOvertimeHours))),
          unit: '小时',
          hint: '当前窗口合计',
        },
        {
          label: '节假日加班',
          value: formatHours(sumNumbers(rows.map((row) => row.holidayOvertimeHours))),
          unit: '小时',
          hint: '当前窗口合计',
        },
      ];
    }
    case 'daily-journal':
      return [
        peopleCard(report.dailyJournalRows, '日报覆盖人数'),
        {
          label: '人日',
          value: report.dailyJournalRows.length,
          unit: '行',
          hint: '一人一日一行',
        },
      ];
  }
}

function peopleCard(
  rows: ReadonlyArray<{ employeeId?: string; employee?: string; rowKey?: string }>,
  hint: string,
): CustomerReportOverviewCard {
  return {
    label: '范围员工',
    value: uniquePeople(rows),
    unit: '人',
    hint,
  };
}

function uniquePeople(
  rows: ReadonlyArray<{ employeeId?: string; employee?: string; rowKey?: string }>,
): number {
  const identities = new Set<string>();
  for (const row of rows) {
    const key = row.employeeId || row.employee || row.rowKey || '';
    if (key !== '' && key !== '—') identities.add(key);
  }
  return identities.size;
}

function sumNumbers(values: number[]): number {
  return values.reduce((total, value) => total + value, 0);
}

function average(total: number, count: number): number {
  return count > 0 ? total / count : 0;
}

function formatHours(value: number): string {
  return value.toFixed(1);
}

function parsePercent(value: string): number | undefined {
  const parsed = Number(value.replace('%', '').trim());
  return Number.isFinite(parsed) ? parsed : undefined;
}

export function formatMonth(month: string): string {
  const [year, monthNumber] = month.split('-');
  return `${year}年${monthNumber}月`;
}

function inSelectedRange(
  date: string | undefined,
  filters: CustomerReportFilters,
): boolean {
  if (!filters.fromDate || !filters.toDate || !date) {
    return true;
  }
  return date >= filters.fromDate && date <= filters.toDate;
}

function filterPeople<T extends { department: string; employee: string }>(
  rows: T[],
  filters: CustomerReportFilters,
  dataScope: CustomerReportDataScope,
): T[] {
  if (!isCustomerReportQueryWithinScope(
    filters.department,
    filters.employee,
    dataScope,
  )) {
    return [];
  }
  return rows.filter((row) => (
    isWithinCustomerReportScope(row, dataScope)
    && (filters.department === '全部部门' || departmentMatchesFilter(row.department, filters.department))
    && (filters.employee === '全部员工' || row.employee === filters.employee)
  ));
}

function buildAttendanceDays(month: string, staffIndex: number): AttendanceDayCell[] {
  const [year, monthNumber] = month.split('-').map(Number);
  const dayCount = new Date(year!, monthNumber!, 0).getDate();
  return Array.from({ length: dayCount }, (_, dayIndex) => {
    const day = dayIndex + 1;
    const weekdayIndex = new Date(Date.UTC(year!, monthNumber! - 1, day)).getUTCDay();
    const weekend = weekdayIndex === 0 || weekdayIndex === 6;
    const status = statusOverrides[`${staffIndex}-${day}`] ?? (weekend ? 'rest-day' : undefined);
    const minuteIn = 12 + ((staffIndex * 7 + day * 3) % 19);
    const minuteOut = 5 + ((staffIndex * 11 + day * 5) % 33);
    const normalCell: AttendanceDayCell = {
      day,
      weekday: weekdays[weekdayIndex]!,
      primary: `08:${String(minuteIn).padStart(2, '0')}`,
      secondary: `18:${String(minuteOut).padStart(2, '0')}`,
      status,
    };
    return applyAttendanceStatus(normalCell, status);
  });
}

function applyAttendanceStatus(
  cell: AttendanceDayCell,
  status: AttendanceStatusKey | undefined,
): AttendanceDayCell {
  if (!status) return cell;
  const leaveLabels: Partial<Record<AttendanceStatusKey, string>> = {
    'time-off': '调休',
    out: '外出',
    trip: '出差',
    'personal-leave': '事假',
    'sick-leave': '病假',
    'annual-leave': '年假',
    'marriage-leave': '婚假',
    'maternity-leave': '产假',
    'paternity-leave': '陪产假',
    'bereavement-leave': '丧假',
    'work-injury-leave': '工伤假',
    'nursing-leave': '护理假',
    'breastfeeding-leave': '哺乳假',
    'prenatal-exam-leave': '孕检假',
    'family-planning-leave': '计生假',
  };
  const leaveLabel = leaveLabels[status];
  if (leaveLabel !== undefined) {
    return {
      ...cell,
      primary: leaveLabel,
      secondary: leaveLabel,
      status,
      primaryStatus: status,
      secondaryStatus: status,
      merged: true,
      mergedLabel: leaveLabel,
      mergedStatus: status,
      note: `${leaveLabel}已审批`,
    };
  }
  if (status === 'late') {
    return {
      ...cell,
      primary: '09:06 迟到',
      primaryStatus: 'late',
      status: 'late',
      note: '迟到 36 分钟',
    };
  }
  if (status === 'early') {
    return {
      ...cell,
      secondary: '17:15 早退',
      secondaryStatus: 'early',
      status: 'early',
      note: '早退 45 分钟',
    };
  }
  if (status === 'missed') {
    return {
      ...cell,
      secondary: '漏刷',
      secondaryStatus: 'missed',
      status: 'missed',
      note: '缺少下班卡',
    };
  }
  if (status === 'overtime') {
    return {
      ...cell,
      secondary: '21:10',
      primaryStatus: 'overtime',
      secondaryStatus: 'overtime',
      status: 'overtime',
      note: `${cell.primary}\n21:10\n加班`,
    };
  }
  if (status === 'rest-day') {
    return {
      ...cell,
      primary: '',
      secondary: '',
      primaryStatus: 'rest-day',
      secondaryStatus: 'rest-day',
      status: 'rest-day',
      note: '非工作日',
    };
  }
  return {
    ...cell,
    primary: '补签08:18',
    primaryStatus: 'corrected',
    status: 'corrected',
    note: '补签已通过',
  };
}

function roundHours(value: number): number {
  return Math.round(value * 10) / 10;
}

function matchesOvertimeType(
  row: OvertimeReportRow,
  type: CustomerReportSpecificFilters['overtimeType'],
): boolean {
  if (type === '全部类型') return true;
  if (type === '计薪加班' || type === '加班费') {
    return row.overtimeType === '加班费' || (row.paidHours ?? 0) > 0;
  }
  if (type === '转调休加班' || type === '转调休') {
    return row.overtimeType === '调休' || (row.compensatoryHours ?? 0) > 0;
  }
  if (type === '义务加班') return row.overtimeType === '义务加班' || (row.voluntaryHours ?? 0) > 0;
  return true;
}

function matchesDailyOvertimeTreatment(
  row: OvertimeDailyReportRow,
  type: CustomerReportSpecificFilters['overtimeType'],
): boolean {
  if (type === '全部类型') return true;
  if (type === '计薪加班' || type === '加班费') return (row.paidOvertimeHours ?? 0) > 0;
  if (type === '转调休加班' || type === '转调休') return (row.compensatoryOvertimeHours ?? 0) > 0;
  if (type === '义务加班') return (row.voluntaryOvertimeHours ?? 0) > 0;
  return true;
}

function matchesFinanceOvertimeTreatment(
  row: FinanceOvertimeReportRow,
  type: CustomerReportSpecificFilters['overtimeType'],
): boolean {
  if (type === '全部类型') return true;
  if (type === '计薪加班' || type === '加班费') return (row.paidOvertimeHours ?? 0) > 0;
  if (type === '转调休加班' || type === '转调休') return (row.compensatoryOvertimeHours ?? 0) > 0;
  if (type === '义务加班') {
    return (row.voluntaryOvertimeHours ?? 0) > 0
      || row.days.some((day) => day.treatment === 'VOLUNTARY' || (day.voluntaryHours ?? 0) > 0);
  }
  return true;
}

function projectFinanceOvertimeRow(
  row: FinanceOvertimeReportRow,
  type: CustomerReportSpecificFilters['overtimeType'],
): FinanceOvertimeReportRow {
  if (type === '全部类型') return row;
  const days = row.days.map((day) => {
    const paidHours = day.paidHours ?? 0;
    const compensatoryHours = day.compensatoryHours ?? 0;
    const voluntaryHours = day.voluntaryHours ?? 0;
    const hours = type === '义务加班'
      ? voluntaryHours
      : (type === '转调休' || type === '转调休加班')
        ? compensatoryHours
        : paidHours;
    return {
      ...day,
      hours,
      treatment: type === '义务加班'
        ? 'VOLUNTARY'
        : (type === '转调休' || type === '转调休加班')
          ? 'COMPENSATORY'
          : 'PAID',
    };
  }).filter((day) => day.hours > 0);
  let weekdayOvertimeHours = 0;
  let weekendOvertimeHours = 0;
  days.forEach((day) => {
    if (isoWeekdayBucket(day.date) === 'weekend') {
      weekendOvertimeHours += day.hours;
    } else {
      weekdayOvertimeHours += day.hours;
    }
  });
  return {
    ...row,
    days,
    weekdayOvertimeHours,
    weekendOvertimeHours,
  };
}

function matchesLateCount(
  row: ExceptionReportRow,
  count: CustomerReportSpecificFilters['lateCount'],
): boolean {
  if (count === '2次及以上') return row.count >= 2;
  if (count === '3次及以上') return row.count >= 3;
  return true;
}

function matchesLateLevel(
  row: ExceptionReportRow,
  level: CustomerReportSpecificFilters['lateLevel'],
): boolean {
  const minutes = row.lateMinutes ?? 0;
  if (level === '10分钟以内') return minutes <= 10;
  if (level === '11-30分钟') return minutes >= 11 && minutes <= 30;
  if (level === '30分钟以上') return minutes > 30;
  return true;
}

function matchesAnnualBalance(
  row: AnnualLeaveReportRow,
  balance: CustomerReportSpecificFilters['annualBalance'],
): boolean {
  if (balance === '有余额') return row.remainingDays > 0;
  if (balance === '余额不足2天') return row.remainingDays > 0 && row.remainingDays < 2;
  if (balance === '已用完') return row.remainingDays <= 0;
  return true;
}
