import { beforeEach, describe, expect, it, vi } from 'vitest';

import type * as ApiClientExports from '../../shared/api/apiClient';
import { listReferenceCompanies } from './referenceDataApi';

const runtimeMode = vi.hoisted(() => ({ demo: false }));
const apiClient = vi.hoisted(() => ({ requestJson: vi.fn() }));

vi.mock('../../shared/config/runtimeMode', () => ({
  isDemoMode: () => runtimeMode.demo,
}));

vi.mock('../../shared/api/apiClient', async (importOriginal) => ({
  ...await importOriginal<typeof ApiClientExports>(),
  requestJson: apiClient.requestJson,
}));

describe('reference data API', () => {
  beforeEach(() => {
    runtimeMode.demo = false;
    apiClient.requestJson.mockReset();
  });

  it('normalizes the real company directory response', async () => {
    apiClient.requestJson.mockResolvedValue({
      items: [{
        companyId: 'company-1',
        code: 'SZSC',
        name: '江苏神州半导体科技股份有限公司',
      }],
    });

    await expect(listReferenceCompanies()).resolves.toEqual([{
      companyId: 'company-1',
      companyCode: 'SZSC',
      companyName: '江苏神州半导体科技股份有限公司',
    }]);
    expect(apiClient.requestJson).toHaveBeenCalledWith(
      '/api/v1/reference-data/companies',
    );
  });

  it('also accepts a direct array and legacy company field names', async () => {
    apiClient.requestJson.mockResolvedValue([{
      companyId: 'company-legacy',
      companyCode: 'LEGACY',
      companyName: '历史公司',
    }]);

    await expect(listReferenceCompanies()).resolves.toEqual([{
      companyId: 'company-legacy',
      companyCode: 'LEGACY',
      companyName: '历史公司',
    }]);
  });

  it('provides a usable Chinese company in demo mode without a request', async () => {
    runtimeMode.demo = true;

    await expect(listReferenceCompanies()).resolves.toEqual([
      expect.objectContaining({
        companyName: '江苏神州半导体科技股份有限公司',
      }),
    ]);
    expect(apiClient.requestJson).not.toHaveBeenCalled();
  });
});
