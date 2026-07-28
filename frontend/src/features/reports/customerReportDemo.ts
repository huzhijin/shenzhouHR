import {
  defaultCustomerReportDataScope,
  isCustomerReportQueryWithinScope,
  isWithinCustomerReportScope,
  type CustomerReportDataScope,
} from './customerReportAccess';

export type CustomerReportKey =
  | 'attendance-detail'
  | 'leave'
  | 'overtime'
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
  | 'rest-day'
  | 'corrected';

export interface CustomerReportFilters {
  month: string;
  department: string;
  employee: string;
}

export interface CustomerReportSpecificFilters {
  attendanceStatus: '全部状态' | AttendanceStatusKey;
  leaveType: string;
  overtimeType: '全部类型' | '工作日加班' | '周末加班' | '法定节假日加班';
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
  note?: string;
}

export interface AttendanceDetailRow {
  employeeNo: string;
  department: string;
  employee: string;
  position: string;
  days: AttendanceDayCell[];
}

export interface LeaveReportRow {
  id: number;
  employee: string;
  department: string;
  type: string;
  hours: number;
  period: string;
  remark: string;
  approvalState: string;
}

export interface OvertimeReportRow {
  employee: string;
  department: string;
  weekdayHours: number;
  weekendHours: number;
  statutoryHours: number;
  exchangedHours: number;
  dailyHours: number[];
}

export interface WorkHoursReportRow {
  employee: string;
  department: string;
  plannedHours: number;
  overtimeHours: number;
  leaveHours: number;
  annualLeaveHours: number;
  exchangedHours: number;
  actualHours: number;
  note: string;
}

export interface ExceptionReportRow {
  id: number;
  employee: string;
  department: string;
  count: number;
  details: string;
  reviewer: string;
  state: string;
  lateMinutes?: number;
}

export type AttendanceExceptionType =
  | '迟到'
  | '早退'
  | '上班缺卡'
  | '下班缺卡'
  | '旷工'
  | '排班缺失'
  | '请假与打卡冲突'
  | '加班未审批';

export type AttendanceExceptionSeverity = '高' | '中' | '低';

export type AttendanceExceptionState =
  | '待处理'
  | '待员工说明'
  | '待补签'
  | '处理中'
  | '已处理';

export interface AttendanceExceptionReportRow {
  id: number;
  businessDate: string;
  employeeNo: string;
  employee: string;
  department: string;
  exceptionType: AttendanceExceptionType;
  severity: AttendanceExceptionSeverity;
  shiftLabel: string;
  scheduledWindow: string;
  punchSummary: string;
  exceptionMinutes?: number;
  evidenceSummary: string;
  state: AttendanceExceptionState;
  owner: string;
  dueAt: string;
}

export interface AttendanceRateReportRow {
  id: number;
  employee: string;
  department: string;
  type: string;
  hours: number;
  rate: string;
  note: string;
}

export interface AnnualLeaveReportRow {
  id: number;
  departmentLevelOne: string;
  departmentLevelTwo: string;
  department: string;
  employee: string;
  joinedOn: string;
  companySeniority: number;
  priorSeniority: number;
  totalSeniority: number;
  statutoryDays: number;
  newHireDays: number;
  availableDays: number;
  availableHours: number;
  monthlyUsedDays: number[];
  remainingDays: number;
  note: string;
}

export interface CustomerReportDemo {
  metadata: {
    isDemo: true;
    company: string;
    generatedAt: string;
    month: string;
    monthLabel: string;
    rowCount: number;
    dataScope: CustomerReportDataScope;
  };
  attendanceRows: AttendanceDetailRow[];
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
  { key: 'overtime', label: '加班汇总与每日加班', shortLabel: '加班统计' },
  { key: 'work-hours', label: '个人月度工时', shortLabel: '月度工时' },
  { key: 'exceptions', label: '考勤异常总览', shortLabel: '异常总览' },
  { key: 'late', label: '迟到统计', shortLabel: '迟到统计' },
  { key: 'missed-punch', label: '忘打卡统计', shortLabel: '忘打卡' },
  { key: 'attendance-rate', label: '出勤率统计', shortLabel: '出勤率' },
  { key: 'annual-leave', label: '年休假汇总', shortLabel: '年休假' },
] as const;

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
] as const;

export const reportFilterOptions = {
  months: [
    { value: '2026-06', label: '2026年06月' },
    { value: '2026-05', label: '2026年05月' },
    { value: '2026-04', label: '2026年04月' },
  ],
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
  leaveTypes: ['全部类型', '事假', '丧假', '病假', '婚假', '陪产假', '年假', '调休'],
  overtimeTypes: ['全部类型', '工作日加班', '周末加班', '法定节假日加班'],
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
  return staff.map((person, index) => {
    const dailyHours = Array.from({ length: daysInMonth(month) }, (_, dayIndex) => {
      const day = dayIndex + 1;
      if ((day + index * 3) % 13 === 0) return 3;
      if ((day + index) % 17 === 0) return 2.5;
      if ((day + index * 2) % 23 === 0) return 3.5;
      return 0;
    });
    const weekdayHours = roundHours(
      dailyHours.reduce<number>((total, hours) => total + hours, 0),
    );
    return {
      employee: person.employee,
      department: person.department,
      weekdayHours,
      weekendHours: index % 3 === 0 ? 7.5 : index % 4 === 0 ? 4 : 0,
      statutoryHours: 0,
      exchangedHours: index === 4 ? 10.5 : 0,
      dailyHours,
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
      monthlyOvertimeRows[index]!.weekdayHours + monthlyOvertimeRows[index]!.weekendHours,
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
        plannedHours + overtimeHours - leaveHours - annualLeaveHours - exchangedHours,
      ),
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
    dueAt: row.dueAt.replace('2026-06', month),
  }));
}

function daysInMonth(month: string): number {
  const [year, monthNumber] = month.split('-').map(Number);
  return new Date(year!, monthNumber!, 0).getDate();
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
    evidenceSummary: '存在延时打卡 · 无已审批加班单',
    state: '待员工说明',
    owner: '徐丽丽',
    dueAt: '2026-06-19 18:00',
  },
];

const attendanceRateRows: AttendanceRateReportRow[] = [
  { id: 1, employee: '陈思远', department: '制造中心', type: '事假', hours: 11, rate: '91.6%', note: '含月中离职折算' },
  { id: 2, employee: '周晴', department: '制造中心', type: '事假', hours: 11.5, rate: '93.2%', note: '全月应出勤 168 小时' },
  { id: 3, employee: '张伟', department: '研发中心', type: '事假', hours: 16, rate: '90.5%', note: '全月应出勤 168 小时' },
  { id: 4, employee: '林晓雯', department: '研发中心', type: '事假', hours: 16.5, rate: '89.8%', note: '含入职日折算' },
  { id: 5, employee: '赵凯', department: '制造中心', type: '病假', hours: 16, rate: '80.0%', note: '月中离职' },
  { id: 6, employee: '蒋宁', department: '职能中心', type: '年假', hours: 8, rate: '95.2%', note: '全月应出勤 168 小时' },
  { id: 7, employee: '吴昊', department: '研发中心', type: '调休', hours: 7.5, rate: '95.5%', note: '全月应出勤 168 小时' },
  { id: 8, employee: '沈佳', department: '职能中心', type: '事假', hours: 4, rate: '95.5%', note: '含入职日折算' },
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
    department: person.department,
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
      days: buildAttendanceDays(filters.month, index),
    })),
    filters,
    dataScope,
  );
  const filteredLeaveRows = filterPeople(
    monthlyLeaveRows(filters.month),
    filters,
    dataScope,
  );
  const filteredOvertimeRows = filterPeople(monthlyOvertimeRows, filters, dataScope);
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
            row.days.some((day) => day.status === filters.attendanceStatus)
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
          && (selectedDay === undefined || (row.dailyHours[selectedDay - 1] ?? 0) > 0)
        )),
      };
    }
    case 'work-hours':
      return {
        ...report,
        workHoursRows: filters.employmentStatus === '全部状态'
          ? report.workHoursRows
          : report.workHoursRows.filter((row) => {
            if (filters.employmentStatus === '本月入职') return row.note.includes('入职');
            if (filters.employmentStatus === '本月离职') return row.note.includes('离职');
            return !row.note.includes('入职') && !row.note.includes('离职');
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
  }
}

export function formatMonth(month: string): string {
  const [year, monthNumber] = month.split('-');
  return `${year}年${monthNumber}月`;
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
    && (filters.department === '全部部门' || row.department === filters.department)
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
  const values: Record<AttendanceStatusKey, Pick<AttendanceDayCell, 'primary' | 'secondary' | 'note'>> = {
    late: { primary: '09:06 迟到', secondary: cell.secondary, note: '迟到 36 分钟' },
    early: { primary: cell.primary, secondary: '17:15 早退', note: '早退 45 分钟' },
    missed: { primary: cell.primary, secondary: '漏刷', note: '缺少下班卡' },
    overtime: { primary: cell.primary, secondary: '21:10 加班', note: '加班 3 小时' },
    'time-off': { primary: '调休', secondary: '8.0小时', note: '调休 1 天' },
    out: { primary: '外出', secondary: '客户现场', note: '外出已审批' },
    trip: { primary: '出差', secondary: '苏州', note: '出差已审批' },
    'personal-leave': { primary: '事假', secondary: '8.0小时', note: '事假已审批' },
    'sick-leave': { primary: '病假', secondary: '8.0小时', note: '病假已审批' },
    'annual-leave': { primary: '年假', secondary: '8.0小时', note: '年假已审批' },
    'rest-day': { primary: '休息日', secondary: '—', note: '非工作日' },
    corrected: { primary: '补签 08:18', secondary: cell.secondary, note: '补签已通过' },
  };
  return { ...cell, ...values[status], status };
}

function roundHours(value: number): number {
  return Math.round(value * 10) / 10;
}

function matchesOvertimeType(
  row: OvertimeReportRow,
  type: CustomerReportSpecificFilters['overtimeType'],
): boolean {
  if (type === '工作日加班') return row.weekdayHours > 0;
  if (type === '周末加班') return row.weekendHours > 0;
  if (type === '法定节假日加班') return row.statutoryHours > 0;
  return true;
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
