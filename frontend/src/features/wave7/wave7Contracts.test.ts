import { describe, expect, it } from 'vitest';

import {
  dashboardFixture,
  reportFixture,
  todayFixture,
} from '../../test/fixtures/wave7ContractFixtures';
import {
  assertAttendanceReportExportView,
  assertAttendanceReportLegalEntityDirectory,
  assertLiveReportProjection,
  assertWave7Projection,
} from './wave7Contracts';
import { wave7ProjectionGateway } from './wave7Gateway';

describe('Wave 7 projection contracts', () => {
  it.each([todayFixture, dashboardFixture, reportFixture])(
    'accepts a complete $kind contract fixture',
    (fixture) => {
      expect(() => assertWave7Projection(fixture)).not.toThrow();
    },
  );

  it('rejects a projection without version and freshness metadata', () => {
    expect(() => assertWave7Projection({
      ...todayFixture,
      metadata: {
        periodState: 'OPEN',
      },
    })).toThrow(/projectionVersion/);
  });

  it('rejects unknown allowed actions', () => {
    expect(() => assertWave7Projection({
      ...todayFixture,
      metadata: {
        ...todayFixture.metadata,
        allowedActions: ['UNKNOWN_ACTION'],
      },
    })).toThrow(/allowedActions/);
  });

  it('accepts the formal nine-type report pagination contract', () => {
    expect(() => assertLiveReportProjection({
      ...reportFixture,
      reportType: 'EXCEPTIONS',
      formulaVersion: 'ATTENDANCE_EXCEPTIONS_V1',
      page: 0,
      size: 50,
      totalPages: 1,
      columns: [
        { key: 'employee-number', label: '工号' },
        { key: 'exception-type', label: '异常类型' },
      ],
      exportFieldAllowlist: ['employee-number', 'exception-type'],
      rows: [{
        rowReference: 'formal-row-1',
        values: {
          'employee-number': 'SYN-001',
          'exception-type': 'LATE',
        },
      }],
      rowCount: 1,
    })).not.toThrow();
  });

  it('rejects a formal report missing pagination or using an unknown type', () => {
    const liveReport = {
      ...reportFixture,
      reportType: 'ATTENDANCE_DETAIL',
      formulaVersion: 'ATTENDANCE_DETAIL_V1',
      page: 0,
      size: 50,
      totalPages: 1,
    };
    const { formulaVersion: omitted, ...missingFormula } = liveReport;
    void omitted;

    expect(() => assertLiveReportProjection(missingFormula))
      .toThrow(/formulaVersion/);
    expect(() => assertLiveReportProjection({
      ...liveReport,
      reportType: 'UNSUPPORTED',
    })).toThrow(/reportType/);
  });

  it('accepts strict synchronous and asynchronous export views', () => {
    const ready = {
      exportId: '1f9a72c2-fcd5-4e66-8a61-a8e6744d166f',
      reportType: 'ATTENDANCE_DETAIL',
      period: '2026-07',
      legalEntityId: '30000000-0000-0000-0000-000000000001',
      deliveryMode: 'SYNC',
      status: 'READY',
      purpose: '月度考勤复核',
      rowCount: 42,
      expiresAt: '2026-07-30T01:00:00Z',
      completedAt: '2026-07-29T01:00:00.123456Z',
    };

    expect(() => assertAttendanceReportExportView(ready)).not.toThrow();
    expect(() => assertAttendanceReportExportView({
      ...ready,
      deliveryMode: 'ASYNC',
      status: 'QUEUED',
      completedAt: undefined,
    })).not.toThrow();
  });

  it('accepts an authorized company directory and rejects duplicate ids', () => {
    const directory = {
      period: '2026-07',
      legalEntities: [
        { legalEntityId: 'company-a', name: '神州半导体' },
        { legalEntityId: 'company-b', name: '神州科技' },
      ],
    };

    expect(() =>
      assertAttendanceReportLegalEntityDirectory(directory))
      .not.toThrow();
    expect(() => assertAttendanceReportLegalEntityDirectory({
      ...directory,
      legalEntities: [
        directory.legalEntities[0],
        directory.legalEntities[0],
      ],
    })).toThrow(/duplicate ids/);
  });

  it('rejects malformed or internally inconsistent export views', () => {
    const ready = {
      exportId: '1f9a72c2-fcd5-4e66-8a61-a8e6744d166f',
      reportType: 'ATTENDANCE_DETAIL',
      period: '2026-07',
      legalEntityId: '30000000-0000-0000-0000-000000000001',
      deliveryMode: 'SYNC',
      status: 'READY',
      purpose: '月度考勤复核',
      rowCount: 42,
      expiresAt: '2026-07-30T01:00:00Z',
      completedAt: '2026-07-29T01:00:00Z',
    };

    expect(() => assertAttendanceReportExportView({
      ...ready,
      deliveryMode: 'SYNC',
      status: 'QUEUED',
      completedAt: undefined,
    })).toThrow(/synchronous/);
    expect(() => assertAttendanceReportExportView({
      ...ready,
      purpose: 'x',
    })).toThrow(/2 至 200/);
    expect(() => assertAttendanceReportExportView({
      ...ready,
      rowCount: Number.MAX_SAFE_INTEGER + 1,
    })).toThrow(/safe integer/);
    expect(() => assertAttendanceReportExportView({
      ...ready,
      completedAt: null,
    })).toThrow(/completedAt/);
  });

  it('fails closed before the upstream projections are synchronized', async () => {
    await expect(wave7ProjectionGateway.loadToday()).rejects.toMatchObject({
      status: 503,
      code: 'WAVE7_UPSTREAM_PENDING',
      retryable: false,
    });
  });
});
