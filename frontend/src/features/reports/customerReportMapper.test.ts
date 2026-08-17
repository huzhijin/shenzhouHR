import { describe, expect, it } from 'vitest';

import { reportFixture } from '../../test/fixtures/wave7ContractFixtures';
import type { ReportProjection, ReportRowProjection } from '../wave7/wave7Contracts';
import {
  toAttendanceRateRows,
  toLeaveRows,
  toOvertimeRows,
} from './customerReportMapper';

describe('customer report mapper business-rule fields', () => {
  it('renders the preserved sick-leave enum as a filterable business label', () => {
    const [row] = toLeaveRows(projection({
      'employee-name': '病假员工',
      organization: '测试部门',
      'document-type': 'SICK',
      'recognized-hours': '16.00',
      'approval-state': 'APPROVED',
    }));

    expect(row).toMatchObject({ type: '病假', hours: 16 });
  });

  it('prioritizes classified overtime fields when new and legacy values coexist', () => {
    const [row] = toOvertimeRows(projection({
      'employee-name': '测试员工',
      organization: '测试部门',
      'paid-overtime-hours': '2.00',
      'compensatory-overtime-hours': '1.00',
      'voluntary-overtime-hours': '0.50',
      'total-overtime-hours': '3.50',
      'recognized-overtime-hours': '99.00',
      'weekday-overtime-hours': '88.00',
    }));

    expect(row).toMatchObject({
      paidHours: 2,
      compensatoryHours: 1,
      voluntaryHours: 0.5,
      totalHours: 3.5,
      classificationAvailable: true,
    });
  });

  it('keeps legacy overtime unclassified instead of relabelling calendar buckets', () => {
    const [row] = toOvertimeRows(projection({
      'employee-name': '旧版员工',
      organization: '旧版部门',
      'recognized-overtime-hours': '12.50',
      'weekday-overtime-hours': '8.00',
      'saturday-overtime-hours': '4.50',
    }));

    expect(row).toMatchObject({
      totalHours: 12.5,
      classificationAvailable: false,
    });
    expect(row?.paidHours).toBeUndefined();
    expect(row?.compensatoryHours).toBeUndefined();
    expect(row?.voluntaryHours).toBeUndefined();
  });

  it('maps day-based attendance and the dedicated sick-leave column', () => {
    const [row] = toAttendanceRateRows(projection({
      'employee-name': '病假员工',
      organization: '测试部门',
      'scheduled-attendance-days': '22',
      'actual-attendance-days': '22',
      'sick-leave-days': '2',
      'attendance-rate': '100.00',
    }));

    expect(row).toMatchObject({
      scheduledDays: 22,
      actualDays: 22,
      sickLeaveDays: 2,
      rate: '100.00%',
      note: '按实际出勤天数 ÷ 应出勤天数',
    });
  });

  it('keeps the zero-scheduled-days rate as N/A without a percent suffix', () => {
    const [row] = toAttendanceRateRows(projection({
      'employee-name': '无排班员工',
      organization: '测试部门',
      'scheduled-attendance-days': '0',
      'actual-attendance-days': '0',
      'sick-leave-days': '0',
      'attendance-rate': 'N/A',
    }));

    expect(row?.rate).toBe('N/A');
  });
});

function projection(
  values: ReportRowProjection['values'],
): ReportProjection {
  return {
    ...reportFixture,
    columns: [],
    exportFieldAllowlist: [],
    rowCount: 1,
    rows: [{ rowReference: 'test-row', values }],
  };
}
