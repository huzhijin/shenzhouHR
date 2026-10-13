import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiRequestError } from '../../shared/api/apiClient';
import '../../shared/i18n/i18n';
import * as attendanceSetupApi from './attendanceSetupApi';
import {
  demoBindings,
  demoGroups,
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

    expect(await screen.findByRole('button', { name: '绑定规则到考勤组' }))
      .toBeEnabled();

    const row = (await screen.findAllByText(selectedBinding.changeReason))
      .map((label) => label.closest('tr'))
      .find((candidate) => candidate !== null) ?? null;
    expect(row).not.toBeNull();
    expect(requiredElement(row)).toHaveTextContent('迟到宽限');
    expect(requiredElement(row)).toHaveTextContent('一号厂 A 班四班两倒（FAB-A-4D2N）');
    expect(requiredElement(row)).not.toHaveTextContent(selectedBinding.policyVersionId);
    expect(requiredElement(row)).not.toHaveTextContent(selectedBinding.groupId);
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
    const group = requiredDemoItem(demoGroups);
    expect(within(requiredElement(impactSection)).getByText(
      `${group.name}（${group.code}）`,
    )).toBeInTheDocument();
    expect(screen.queryByText(selectedBinding.policyVersionId)).not.toBeInTheDocument();
    expect(screen.queryByText(selectedBinding.groupId)).not.toBeInTheDocument();
    expect(within(requiredElement(impactSection)).getByText('23')).toBeInTheDocument();
  });

  it.each([
    ['DRAFT', '当前为草稿版本，请先校验并发布后再绑定考勤组'],
    ['VALIDATED', '当前版本已校验，请先发布后再绑定考勤组'],
  ] as const)(
    'keeps the binding action disabled for a %s version',
    async (status, hint) => {
      const version: AttendancePolicyVersionView = {
        ...mealVersion,
        scopedVersionId: `meal-${status.toLowerCase()}`,
        status,
        publishedAt: null,
        validation: {
          valid: status === 'VALIDATED',
          issues: [],
          validatedAt: status === 'VALIDATED' ? '2026-08-05T08:00:00Z' : null,
        },
      };
      vi.spyOn(attendanceSetupApi, 'getAttendancePolicyVersionContext')
        .mockResolvedValue(version);
      vi.spyOn(attendanceSetupApi, 'listAttendancePolicyVersions').mockResolvedValue({
        items: [version],
        total: 1,
        page: 0,
        size: 20,
      });
      vi.spyOn(attendanceSetupApi, 'getAttendancePolicyVersion')
        .mockResolvedValue(version);

      renderPolicyPage(version.scopedVersionId);

      const button = await screen.findByRole('button', { name: '绑定规则到考勤组' });
      expect(button).toBeDisabled();
      expect(button).toHaveAttribute('title', hint);
    },
  );

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

    await chooseVisiblePolicyVersion(lateVersion);
    await waitFor(() => {
      expect(attendanceSetupApi.getAttendancePolicyVersionContext)
        .toHaveBeenCalledWith(lateVersion.scopedVersionId);
    });
    expect(screen.queryByText('策略版本生命周期')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '运行试算' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '绑定规则到考勤组' })).toBeDisabled();

    await act(async () => {
      routeB.resolve(lateVersion);
    });
    expect(await screen.findByText('策略版本生命周期')).toBeInTheDocument();
    expect(screen.getAllByText('迟到宽限').length).toBeGreaterThan(0);
    expect(screen.queryByText(lateVersion.scopedVersionId)).not.toBeInTheDocument();
  }, 30_000);

  it('fails route A to a missing version closed without restoring A controls', async () => {
    const missing = deferred<AttendancePolicyVersionView>();
    vi.spyOn(attendanceSetupApi, 'getAttendancePolicyVersionContext')
      .mockImplementation((versionId) => versionId === mealVersion.scopedVersionId
        ? Promise.resolve(mealVersion)
        : missing.promise);

    const view = renderPolicyPage(mealVersion.scopedVersionId, 'missing-policy-version');
    expect(await screen.findByText('策略版本生命周期')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('link', { name: '打开失效的策略链接' }));
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
    const bindingButton = screen.getByRole('button', { name: '绑定规则到考勤组' });
    expect(bindingButton).toBeDisabled();
    expect(bindingButton).toHaveAttribute('title', '请先选择公司并打开一个规则版本');
  }, 30_000);
});

function renderPolicyPage(versionId: string, directLinkTarget?: string) {
  return render(
    <MemoryRouter initialEntries={[`/rules/attendance-policy/${versionId}`]}>
      {directLinkTarget ? (
        <Link to={`/rules/attendance-policy/${directLinkTarget}`}>
          打开失效的策略链接
        </Link>
      ) : null}
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

async function chooseVisiblePolicyVersion(version: AttendancePolicyVersionView) {
  const tabLabel = {
    MEAL_DEDUCTION: '用餐时段扣除',
    LATE_GRACE: '迟到宽限',
    MONTHLY_LATE_EXEMPTION: '每月迟到豁免',
    PUNCH_WINDOW: '打卡取卡窗口',
    PERIOD_CLOSE: '月结封账',
  }[version.policyKind];
  fireEvent.click(screen.getByTitle(tabLabel));

  const lifecycleHeading = await screen.findByRole(
    'heading',
    { name: '策略版本生命周期' },
  );
  const lifecycle = requiredElement(lifecycleHeading.closest('section'));
  const versionButton = (await within(lifecycle).findAllByRole(
    'button',
    { name: `版本 ${version.versionNumber}` },
  )).find((button) => button.closest('tr') !== null);
  fireEvent.click(requiredElement(versionButton ?? null));
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
