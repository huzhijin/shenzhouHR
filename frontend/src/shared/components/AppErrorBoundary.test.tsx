import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { AppErrorBoundary } from './AppErrorBoundary';

function BrokenView(): never {
  throw new Error('synthetic render failure');
}

describe('AppErrorBoundary', () => {
  it('moves focus to the native fallback region after a render failure', () => {
    const reportError = vi.fn();
    vi.stubGlobal('reportError', reportError);

    render(
      <AppErrorBoundary>
        <BrokenView />
      </AppErrorBoundary>,
    );

    const fallback = screen.getByRole('alert');
    expect(fallback).toHaveFocus();
    expect(screen.getByRole('button', { name: '重新加载' })).toBeVisible();
    expect(reportError).toHaveBeenCalledOnce();
    vi.unstubAllGlobals();
  });
});
