import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiRequestError } from '../../shared/api/apiClient';
import '../../shared/i18n/i18n';
import * as attendanceSetupApi from './attendanceSetupApi';
import {
  demoBindings,
  demoPolicyImpact,
  demoPolicyVersions,
  requiredDemoItem,
} from './attendanceSetupDemo';
import { AttendancePolicyPage } from './AttendancePolicyPage';
import type {
  AttendancePolicyVersionView,
  PolicyImpactView,
} from './attendanceSetupTypes';

vi.mock('../../shared/config/runtimeMode', () => ({
  isDemoMode: () => true,
}));

const capabilities = [
  'ATTENDANCE_SETUP:READ',
  'ATTENDANCE_SETUP:MANAGE_POLICY',
];
const mealVersion = requiredDemoItem(demoPolicyVersions);
const lateVersion = requiredDemoItem(demoPolicyVersions, 1);

describe('attendance policy route and impact isolation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    cleanup();
  });

  it('runs impact preview only from an explicitly selected binding row', async () => {
    const selectedBinding = requiredDemoItem(demoBindings, 1);
    const preview: PolicyImpactView = {
      ...demoPolicyImpact,
      groupCount: 7,
      assignmentCount: 23,
    };
    const previewSpy = vi.spyOn(attendanceSetupApi, 'previewPolicyImpact')
      .mockResolvedValue(preview);

    renderPolicyPage(mealVersion.scopedVersionId);

    const versionCells = await screen.findAllByText(selectedBinding.policyVersionId);
    const row = versionCells
      .map((cell) => cell.closest('tr'))
      .find((candidate) => candidate !== null) ?? null;
    expect(row).not.toBeNull();
    const impactSection = screen.getByRole(
      'heading',
      { name: '真实范围影响预览' },
    ).closest('section');
    expect(within(requiredElement(impactSection))
      .queryByRole('button', { name: '预览影响' })).not.toBeInTheDocument();

    fireEvent.click(within(requiredElement(row)).getByRole('button', { name: '预览影响' }));

    await waitFor(() => {
      expect(previewSpy).toHaveBeenCalledWith({
        policyKind: selectedBinding.policyKind,
        policyVersionId: selectedBinding.policyVersionId,
        groupId: selectedBinding.groupId,
        groupRevisionId: selectedBinding.groupRevisionId,
        effectiveFrom: selectedBinding.effectiveFrom,
        effectiveTo: selectedBinding.effectiveTo,
        reason: selectedBinding.changeReason,
      });
    });
    expect(screen.getByText(
      `${selectedBinding.policyVersionId} · ${selectedBinding.groupId}`,
    )).toBeInTheDocument();
    expect(within(requiredElement(impactSection)).getByText('23')).toBeInTheDocument();
  });

  it('hides route-A lifecycle and mutations while route B is unresolved', async () => {
    const routeB = deferred<AttendancePolicyVersionView>();
    vi.spyOn(attendanceSetupApi, 'getAttendancePolicyVersionContext')
      .mockImplementation((versionId) => {
        if (versionId === mealVersion.scopedVersionId) return Promise.resolve(mealVersion);
        if (versionId === lateVersion.scopedVersionId) return routeB.promise;
        return Promise.reject(new Error(`unexpected version ${versionId}`));
      });

    renderPolicyPage(mealVersion.scopedVersionId);
    expect(await screen.findByText('策略版本生命周期')).toBeInTheDocument();

    changePolicyRoute(lateVersion.scopedVersionId);
    await waitFor(() => {
      expect(attendanceSetupApi.getAttendancePolicyVersionContext)
        .toHaveBeenCalledWith(lateVersion.scopedVersionId);
    });
    expect(screen.queryByText('策略版本生命周期')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '运行试算' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '新建策略绑定' })).toBeDisabled();

    await act(async () => {
      routeB.resolve(lateVersion);
    });
    expect(await screen.findByText('策略版本生命周期')).toBeInTheDocument();
    expect(screen.getByText('迟到分钟宽限')).toBeInTheDocument();
  }, 30_000);

  it('fails route A to a missing version closed without restoring A controls', async () => {
    const missing = deferred<AttendancePolicyVersionView>();
    vi.spyOn(attendanceSetupApi, 'getAttendancePolicyVersionContext')
      .mockImplementation((versionId) => versionId === mealVersion.scopedVersionId
        ? Promise.resolve(mealVersion)
        : missing.promise);

    const view = renderPolicyPage(mealVersion.scopedVersionId);
    expect(await screen.findByText('策略版本生命周期')).toBeInTheDocument();

    changePolicyRoute('missing-policy-version');
    await waitFor(() => {
      expect(attendanceSetupApi.getAttendancePolicyVersionContext)
        .toHaveBeenCalledWith('missing-policy-version');
    });
    expect(screen.queryByText('策略版本生命周期')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '运行试算' })).not.toBeInTheDocument();

    await act(async () => {
      missing.reject(new ApiRequestError(404, {
        code: 'RESOURCE_NOT_AVAILABLE',
        retryable: false,
      }));
    });
    await waitFor(() => {
      expect(view.container.querySelector('[data-state="404"]')).toBeInTheDocument();
    });
    expect(screen.queryByText('策略版本生命周期')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '运行试算' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '新建策略绑定' })).toBeDisabled();
  }, 30_000);
});

function renderPolicyPage(versionId: string) {
  return render(
    <MemoryRouter initialEntries={[`/rules/attendance-policy/${versionId}`]}>
      <Routes>
        <Route
          path="/rules/attendance-policy"
          element={<AttendancePolicyPage capabilities={capabilities} />}
        />
        <Route
          path="/rules/attendance-policy/:versionId"
          element={<AttendancePolicyPage capabilities={capabilities} />}
        />
      </Routes>
    </MemoryRouter>,
  );
}

function changePolicyRoute(versionId: string) {
  fireEvent.change(screen.getByLabelText('已发布策略版本 ID'), {
    target: { value: versionId },
  });
  fireEvent.click(screen.getByRole('button', { name: '选择' }));
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

function requiredElement<T extends Element>(value: T | null): T {
  if (!value) throw new Error('required test element is missing');
  return value;
}
