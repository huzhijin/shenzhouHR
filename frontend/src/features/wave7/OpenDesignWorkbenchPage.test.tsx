import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';

import { OpenDesignWorkbenchPage } from './OpenDesignWorkbenchPage';

describe('OpenDesign workbench', () => {
  afterEach(() => {
    cleanup();
  });

  it('renders the OpenDesign shell, operational summary and demo navigation', () => {
    render(
      <MemoryRouter>
        <OpenDesignWorkbenchPage />
      </MemoryRouter>,
    );

    expect(screen.getByRole('heading', { name: '考勤管理员工作台' })).toBeInTheDocument();
    const primaryNavigation = screen.getByRole('complementary', { name: '主导航' });
    expect(primaryNavigation).toHaveTextContent('组织与人员');
    expect(primaryNavigation).toHaveTextContent('规则与排班');
    expect(primaryNavigation).toHaveTextContent('数据接入');
    expect(primaryNavigation).toHaveTextContent('考勤管理');
    expect(screen.getByText('待发布离线批次')).toBeInTheDocument();
    expect(screen.getByText('数据新鲜度')).toBeInTheDocument();
    expect(screen.getByText('跨模块待办')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '进入考勤大屏' })).toHaveAttribute('href', '/attendance/screen');
    expect(screen.getByRole('link', { name: '查看统计报表' })).toHaveAttribute('href', '/attendance/reports');
    expect(screen.getAllByRole('link', { name: '数据接入' })
      .some((link) => link.getAttribute('href') === '/sources/online')).toBe(true);
  });

  it('switches between department and personal scopes without losing the shell', () => {
    render(
      <MemoryRouter>
        <OpenDesignWorkbenchPage />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('tab', { name: '部门口径' }));
    expect(screen.getByRole('tabpanel')).toHaveTextContent('制造一部 · 需处理异常');
    expect(screen.queryByText('跨模块待办')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: '个人口径' }));
    expect(screen.getByRole('tabpanel')).toHaveTextContent('个人口径仅展示当前员工本人');
    expect(screen.getByRole('heading', { name: '考勤管理员工作台' })).toBeInTheDocument();
  });

  it('turns the OpenDesign search field into working page navigation', () => {
    render(
      <MemoryRouter>
        <OpenDesignWorkbenchPage />
      </MemoryRouter>,
    );

    fireEvent.change(screen.getByRole('searchbox', { name: '搜索页面、制度或帮助' }), {
      target: { value: '报表' },
    });

    expect(screen.getByRole('listbox', { name: '工作台搜索结果' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /考勤报表中心/ })).toHaveAttribute(
      'href',
      '/attendance/reports',
    );
  });

  it('exposes the optional demo logout action to the application shell', () => {
    const onLogout = vi.fn();
    render(
      <MemoryRouter>
        <OpenDesignWorkbenchPage onLogout={onLogout} />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: '退出演示' }));
    expect(onLogout).toHaveBeenCalledOnce();
  });
});
