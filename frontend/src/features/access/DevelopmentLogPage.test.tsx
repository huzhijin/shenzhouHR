import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type * as ReactI18next from 'react-i18next';
import DevelopmentLogPage from './DevelopmentLogPage';

// Mock dependencies
vi.mock('react-i18next', async (importOriginal) => {
  const actual = await importOriginal<typeof ReactI18next>();
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string) => key,
    }),
  };
});

vi.mock('../../shared/config/runtimeMode', () => ({
  isDemoMode: vi.fn(() => false),
}));

vi.mock('../../shared/api/apiClient', () => ({
  requestJson: vi.fn(),
}));

import { isDemoMode } from '../../shared/config/runtimeMode';
import { requestJson } from '../../shared/api/apiClient';

describe('DevelopmentLogPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
  });

  it('handles typing and debounce with sequence check', async () => {
    const mockData = [
      {
        deploymentId: 'test-1',
        date: '2026-10-10T10:30:00Z',
        version: 'v1.0.0',
        title: 'Test',
        description: 'Test deployment',
        details: '',
        isCurrent: false,
      },
    ];

    let callCount = 0;
    vi.mocked(requestJson).mockImplementation(() => {
      callCount++;
      return new Promise((resolve) => {
        setTimeout(() => resolve(mockData), 100);
      });
    });

    const user = userEvent.setup();
    render(<DevelopmentLogPage />);

    const input = screen.getByPlaceholderText(/搜索/);

    // Type quickly without debounce delay
    await user.type(input, 'abc');

    // Should only make one API call after debounce
    await waitFor(() => {
      expect(callCount).toBe(1);
    });
  });

  it('cancels old requests when search changes', async () => {
    const abortedCalls: AbortSignal[] = [];

    vi.mocked(requestJson).mockImplementation((_url, options) => {
      if (options?.signal) {
        abortedCalls.push(options.signal);
      }
      return new Promise((resolve) => {
        setTimeout(() => resolve([]), 500);
      });
    });

    const user = userEvent.setup();
    const { container } = render(<DevelopmentLogPage />);

    const input = container.querySelector('.search-input') as HTMLInputElement;

    // First search
    await user.type(input, 'test1');
    await waitFor(() => expect(requestJson).toHaveBeenCalledTimes(1));

    // Second search before first completes
    await user.clear(input);
    await user.type(input, 'test2');

    await waitFor(() => {
      // First request should be aborted
      expect(abortedCalls[0]?.aborted).toBe(true);
    });
  });

  it('handles component unmount during fetch', async () => {
    let capturedSignal: AbortSignal | undefined;
    let resolveRequest: ((data: unknown) => void) | undefined;

    vi.mocked(requestJson).mockImplementation((_url, options) => {
      capturedSignal = options?.signal ?? undefined;
      return new Promise((resolve) => {
        resolveRequest = resolve;
      });
    });

    const { unmount } = render(<DevelopmentLogPage />);

    await waitFor(() => expect(requestJson).toHaveBeenCalled());

    unmount();

    expect(capturedSignal?.aborted).toBe(true);

    if (resolveRequest) {
      resolveRequest([
        {
          deploymentId: 'late',
          date: '2026-10-10T10:30:00Z',
          version: 'v1.0.0',
          title: 'Late Response',
          description: '',
          details: '',
          isCurrent: false,
        },
      ]);
    }

    await new Promise((resolve) => setTimeout(resolve, 50));
  });

  it('demo mode respects sequence on unmount', async () => {
    vi.mocked(isDemoMode).mockReturnValue(true);

    const { unmount, container } = render(<DevelopmentLogPage />);

    unmount();

    await new Promise((resolve) => setTimeout(resolve, 250));

    expect(container.textContent).not.toContain('十月HR修复');
  });

  it('ignores out-of-order responses', async () => {
    vi.mocked(isDemoMode).mockReturnValue(false);

    const firstData = [
      {
        deploymentId: 'first',
        date: '2026-10-10T10:30:00Z',
        version: 'v1.0.0',
        title: 'First',
        description: '',
        details: '',
        isCurrent: false,
      },
    ];

    const secondData = [
      {
        deploymentId: 'second',
        date: '2026-10-11T10:30:00Z',
        version: 'v2.0.0',
        title: 'Second',
        description: '',
        details: '',
        isCurrent: false,
      },
    ];

    let firstResolve: (data: typeof firstData) => void;
    let secondResolve: (data: typeof secondData) => void;

    vi.mocked(requestJson)
      .mockImplementationOnce(
        () => new Promise((resolve) => {
          firstResolve = resolve as typeof firstResolve;
        })
      )
      .mockImplementationOnce(
        () => new Promise((resolve) => {
          secondResolve = resolve as typeof secondResolve;
        })
      );

    const user = userEvent.setup();
    const { container } = render(<DevelopmentLogPage />);

    const input = container.querySelector('.search-input') as HTMLInputElement;

    await user.type(input, 'first');
    await waitFor(() => expect(requestJson).toHaveBeenCalledTimes(1));

    await user.clear(input);
    await user.type(input, 'second');
    await waitFor(() => expect(requestJson).toHaveBeenCalledTimes(2));

    secondResolve!(secondData);
    await waitFor(() => {
      expect(screen.getByText('Second')).toBeInTheDocument();
    });

    firstResolve!(firstData);

    await waitFor(() => {
      expect(screen.queryByText('First')).not.toBeInTheDocument();
      expect(screen.getByText('Second')).toBeInTheDocument();
    });
  });

  it('handles error and retry', async () => {
    vi.mocked(isDemoMode).mockReturnValue(false);
    vi.mocked(requestJson)
      .mockRejectedValueOnce(new Error('Network error'))
      .mockResolvedValueOnce([]);

    const user = userEvent.setup();
    render(<DevelopmentLogPage />);

    // Wait for error to appear
    await waitFor(() => {
      expect(screen.getByText(/Network error/)).toBeInTheDocument();
    });

    // Click retry
    const retryButton = screen.getByRole('button', { name: '重 试' });
    await user.click(retryButton);

    // Should succeed
    await waitFor(() => {
      expect(screen.queryByText(/Network error/)).not.toBeInTheDocument();
    });
  });

  it('demo mode does not fetch from API', async () => {
    vi.mocked(isDemoMode).mockReturnValue(true);

    render(<DevelopmentLogPage />);

    // Wait for demo data to appear
    await waitFor(() => {
      expect(screen.getByText(/十月HR修复/)).toBeInTheDocument();
    });

    // Should never call requestJson
    expect(requestJson).not.toHaveBeenCalled();
  });
});
