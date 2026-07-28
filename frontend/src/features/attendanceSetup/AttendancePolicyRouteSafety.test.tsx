import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import '../../shared/i18n/i18n';
import * as attendanceSetupApi from './attendanceSetupApi';
import {
  demoBindings,
  demoPolicyVersions,
  requiredDemoItem,
} from './attendanceSetupDemo';
import {
  isPolicyLifecycleBoundaryValid,
  policyLifecycleMinimumDate,
} from './attendancePolicyLifecycleDates';
import { AttendancePolicyPage } from './AttendancePolicyPage';

vi.mock('../../shared/config/runtimeMode', () => ({
  isDemoMode: () => true,
}));

const capabilities = [
  'ATTENDANCE_SETUP:READ',
  'ATTENDANCE_SETUP:MANAGE_POLICY',
];

describe('attendance policy route and revision safety', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    cleanup();
  });

  it('derives the lifecycle lower boundary from UTC tomorrow or version start', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-07-29T16:30:00.000Z'));

    expect(policyLifecycleMinimumDate('1970-01-01')).toBe('2026-07-30');
    expect(policyLifecycleMinimumDate('2099-06-10')).toBe('2099-06-11');
    expect(isPolicyLifecycleBoundaryValid('2099-06-11', {
      effectiveFrom: '2099-06-10',
      effectiveTo: '2099-07-01',
    })).toBe(true);
    expect(isPolicyLifecycleBoundaryValid('2099-07-01', {
      effectiveFrom: '2099-06-10',
      effectiveTo: '2099-07-01',
    })).toBe(false);
  });

  it('does not expose lifecycle actions for a detail response from another version', async () => {
    const previous = requiredDemoItem(demoPolicyVersions);
    const routed = {
      ...previous,
      scopedVersionId: '25200000-0000-0000-0000-000000000101',
      versionNumber: previous.versionNumber + 1,
      rowVersion: previous.rowVersion + 1,
    };
    vi.spyOn(attendanceSetupApi, 'getAttendancePolicyVersionContext')
      .mockResolvedValue(routed);
    const detailSpy = vi.spyOn(attendanceSetupApi, 'getAttendancePolicyVersion')
      .mockResolvedValue(previous);

    renderPolicyPage(routed.scopedVersionId);

    await waitFor(() => {
      expect(detailSpy).toHaveBeenCalledWith(
        routed.templateId,
        routed.legalEntityId,
        routed.scopedVersionId,
      );
    });
    expect(screen.getByLabelText('已发布策略版本 ID')).toHaveValue(
      routed.scopedVersionId,
    );
    const heading = await screen.findByRole(
      'heading',
      { name: '策略版本生命周期' },
    );
    const panel = requiredClosest(heading, 'section');
    expect(within(panel).queryByText(previous.scopedVersionId)).not.toBeInTheDocument();
    expect(within(panel).queryByRole('button', { name: '停用' }))
      .not.toBeInTheDocument();
  });

  it('revalidates the current binding-family head outside the visible page before editing', async () => {
    const visibleRevision = {
      ...requiredDemoItem(demoBindings),
      bindingRevisionId: '9708100000000000003-old',
      revisionNumber: 1,
    };
    const currentRevision = {
      ...visibleRevision,
      bindingRevisionId: '9708100000000000003-current',
      revisionNumber: 2,
      rowVersion: visibleRevision.rowVersion + 1,
    };
    const listSpy = vi.spyOn(attendanceSetupApi, 'listPolicyBindings')
      .mockImplementation((groupId, _asOf, page = 0, size = 100) => (
        Promise.resolve({
          items: groupId ? [currentRevision] : [visibleRevision],
          total: groupId ? 1 : 21,
          page,
          size,
        })
      ));
    const view = renderPolicyPage(requiredDemoItem(demoPolicyVersions).scopedVersionId);

    fireEvent.click(requiredItem(
      await screen.findAllByRole('button', { name: '替换绑定' }),
      0,
    ));

    await waitFor(() => {
      expect(listSpy).toHaveBeenCalledWith(
        visibleRevision.groupId,
        undefined,
        0,
        100,
      );
    });
    expect(await screen.findByText(
      '当前页面使用的并发版本已过期，请刷新后基于最新修订重试。',
    )).toBeInTheDocument();
    expect(view.baseElement.querySelector('.ant-modal')).not.toBeInTheDocument();
  });

  it('bounds deactivate and rollback dates by version start and effectiveTo', async () => {
    const base = requiredDemoItem(demoPolicyVersions);
    const bounded = {
      ...base,
      effectiveFrom: '2099-06-10',
      effectiveTo: '2099-07-01',
    };
    const contextSpy = vi.spyOn(attendanceSetupApi, 'getAttendancePolicyVersionContext')
      .mockResolvedValue(bounded);
    const detailSpy = vi.spyOn(attendanceSetupApi, 'getAttendancePolicyVersion')
      .mockResolvedValue(bounded);
    const deactivateSpy = vi.spyOn(
      attendanceSetupApi,
      'deactivateAttendancePolicyVersion',
    ).mockResolvedValue({
      ...bounded,
      deactivationEffectiveFrom: '2099-06-11',
    });
    const rollbackSpy = vi.spyOn(
      attendanceSetupApi,
      'rollbackAttendancePolicyVersion',
    ).mockResolvedValue({
      ...bounded,
      scopedVersionId: '25200000-0000-0000-0000-000000000099',
      rollbackOfScopedVersionId: '25200000-0000-0000-0000-000000000009',
      effectiveFrom: '2099-06-11',
    });

    renderPolicyPage(bounded.scopedVersionId);

    await waitFor(() => {
      expect(contextSpy).toHaveBeenCalledWith(bounded.scopedVersionId);
      expect(detailSpy).toHaveBeenCalledWith(
        bounded.templateId,
        bounded.legalEntityId,
        bounded.scopedVersionId,
      );
    }, { timeout: 5_000 });
    const boundary = await screen.findByLabelText(
      '生命周期操作生效日',
      {},
      { timeout: 5_000 },
    );
    await waitFor(() => {
      expect(boundary).toHaveValue('2099-06-11');
    });
    expect(boundary).toHaveAttribute('min', '2099-06-11');
    expect(boundary).toHaveAttribute('max', '2099-07-01');
    const lifecyclePanel = requiredClosest(boundary, 'section');
    const workbench = requiredClosest(boundary, '.attendance-lifecycle-workbench');
    const reason = requiredElement(
      workbench.querySelector<HTMLTextAreaElement>('textarea'),
      'lifecycle reason',
    );
    const rollbackTarget = within(workbench).getByLabelText('回滚目标版本 ID');
    fireEvent.change(reason, { target: { value: '日期边界测试' } });
    fireEvent.change(rollbackTarget, {
      target: { value: '25200000-0000-0000-0000-000000000009' },
    });
    fireEvent.change(boundary, { target: { value: '2099-07-01' } });

    fireEvent.click(within(workbench).getByRole('button', { name: '停用' }));
    expect(deactivateSpy).not.toHaveBeenCalled();
    expect(await within(lifecyclePanel).findByText(
      '生命周期操作生效日必须不早于允许下限，并处于当前版本有效期内。',
    )).toBeInTheDocument();

    fireEvent.click(within(workbench).getByRole('button', { name: '回滚' }));
    expect(rollbackSpy).not.toHaveBeenCalled();

    fireEvent.change(boundary, { target: { value: '2099-06-11' } });
    fireEvent.click(within(workbench).getByRole('button', { name: '停用' }));
    await waitFor(() => {
      expect(deactivateSpy).toHaveBeenCalledWith(
        bounded,
        '2099-06-11',
        '日期边界测试',
      );
    });
  });
});

function renderPolicyPage(versionId: string) {
  return render(
    <MemoryRouter initialEntries={[`/rules/attendance-policy/${versionId}`]}>
      <Routes>
        <Route
          path="/rules/attendance-policy/:versionId"
          element={<AttendancePolicyPage capabilities={capabilities} />}
        />
      </Routes>
    </MemoryRouter>,
  );
}

function requiredClosest(element: HTMLElement, selector: string): HTMLElement {
  const match = element.closest<HTMLElement>(selector);
  if (!match) throw new Error(`Missing closest element: ${selector}`);
  return match;
}

function requiredItem<T>(items: readonly T[], index: number): T {
  const item = items[index];
  if (item === undefined) throw new Error(`Missing item at index ${index}`);
  return item;
}

function requiredElement<T extends Element>(element: T | null, label: string): T {
  if (!element) throw new Error(`Missing element: ${label}`);
  return element;
}
