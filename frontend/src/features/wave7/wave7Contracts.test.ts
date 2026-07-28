import { describe, expect, it } from 'vitest';

import {
  dashboardFixture,
  reportFixture,
  todayFixture,
} from '../../test/fixtures/wave7ContractFixtures';
import { assertWave7Projection } from './wave7Contracts';
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

  it('fails closed before the upstream projections are synchronized', async () => {
    await expect(wave7ProjectionGateway.loadToday()).rejects.toMatchObject({
      status: 503,
      code: 'WAVE7_UPSTREAM_PENDING',
      retryable: false,
    });
  });
});
