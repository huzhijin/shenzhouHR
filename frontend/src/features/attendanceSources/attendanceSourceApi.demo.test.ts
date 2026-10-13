import { beforeEach, describe, expect, it } from 'vitest';

import { isDemoMode } from '../../shared/config/runtimeMode';
import {
  listAttendanceSourceJobs,
  resetAttendanceSourceDemoState,
  retryAttendanceSourceJob,
} from './attendanceSourceApi';

describe.runIf(isDemoMode())('attendance source demo state', () => {
  beforeEach(() => {
    resetAttendanceSourceDemoState();
  });

  it('keeps a retried source job queued in subsequent list reads', async () => {
    const before = await listAttendanceSourceJobs();
    const retryable = before.items.find(
      (job) => job.state === 'PARTIALLY_QUARANTINED',
    );
    expect(retryable).toBeDefined();

    const retried = await retryAttendanceSourceJob(retryable!, '客户演示重试');
    expect(retried).toMatchObject({
      state: 'QUEUED',
      safeErrorSummary: null,
      completedAt: null,
      rowVersion: retryable!.rowVersion + 1,
    });

    const after = await listAttendanceSourceJobs();
    expect(after.items.find((job) => job.jobId === retried.jobId)).toEqual(retried);
  });
});
