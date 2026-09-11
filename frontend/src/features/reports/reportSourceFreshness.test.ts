import { describe, expect, it } from 'vitest';

import { isProvisionalRealtimeSource, realtimeSourceCutoff } from './reportSourceFreshness';

const digest = (value: string) => value.repeat(64);

describe('realtime source freshness', () => {
  it('uses the oldest committed cutoff when one source type has multiple sources', () => {
    const values = [
      'MODEL:ATTENDANCE-RULES-V1',
      `SOURCE.DELI_CLOUD:2026-08-12T02:00:00Z:${digest('a')}`,
      `SOURCE.DELI_CLOUD:2026-08-12T01:30:00Z:${digest('b')}`,
    ];

    expect(realtimeSourceCutoff(values, 'DELI_CLOUD'))
      .toBe('2026-08-12T01:30:00Z');
  });

  it('does not hide an unsynchronized source behind another committed source', () => {
    const values = [
      `SOURCE.OA_ATTENDANCE:2026-08-12T02:00:00Z:${digest('c')}`,
      `SOURCE.OA_ATTENDANCE:UNSYNCED:${digest('d')}`,
    ];

    expect(realtimeSourceCutoff(values, 'OA_ATTENDANCE'))
      .toBe('PARTIALLY_UNSYNCED');
  });

  it('reports fully unsynchronized and absent source types separately', () => {
    const values = [
      `SOURCE.DELI_CLOUD:UNSYNCED:${digest('e')}`,
      `SOURCE.DELI_CLOUD:UNSYNCED:${digest('f')}`,
    ];

    expect(realtimeSourceCutoff(values, 'DELI_CLOUD')).toBe('UNSYNCED');
    expect(realtimeSourceCutoff(values, 'OA_ATTENDANCE')).toBeUndefined();
  });

  it('detects the punch-roster preview so the sheet can refresh onto the final calc', () => {
    expect(isProvisionalRealtimeSource(['WORKBENCH-PUNCH:V1'])).toBe(true);
    expect(isProvisionalRealtimeSource(['ATTENDANCE.PROVISIONAL:V1'])).toBe(true);
    expect(isProvisionalRealtimeSource([
      'SOURCE.DELI_CLOUD:2026-08-12T02:00:00Z:' + 'a'.repeat(64),
    ])).toBe(false);
  });
});
