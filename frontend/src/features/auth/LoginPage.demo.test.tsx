import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AppearanceProvider } from '../../shared/appearance/AppearanceProvider';
import '../../shared/i18n/i18n';
import {
  DEMO_PASSWORD,
  DEMO_USERNAME,
} from '../session/demoAuthSession';
import { LoginPage } from './LoginPage';

vi.mock('../../shared/config/runtimeMode', () => ({
  isDemoMode: () => true,
}));

describe('LoginPage demo credentials', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('shows and pre-fills the synthetic account used for the client walkthrough', () => {
    render(
      <AppearanceProvider>
        <MemoryRouter>
          <LoginPage onAuthenticated={vi.fn()} />
        </MemoryRouter>
      </AppearanceProvider>,
    );
    expect(screen.getByLabelText('神州考勤系统')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '黑夜模式' })).toBeInTheDocument();

    expect(screen.getByText(`演示账号：${DEMO_USERNAME}`)).toBeInTheDocument();
    expect(screen.getByText(DEMO_PASSWORD)).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: '用户名' })).toHaveValue(DEMO_USERNAME);
    expect(screen.getByLabelText('密码')).toHaveValue(DEMO_PASSWORD);
    expect(screen.getByRole('img', { name: /神州愿景/ })).toBeInTheDocument();
  });
});
