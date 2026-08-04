import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import {
  createDemoSelfAttendanceDashboardProjection,
} from '../demo/wave7Demo';
import type {
  SelfAttendanceDashboardProjection,
} from './wave7Contracts';
import { PersonalAttendanceDashboard } from './PersonalAttendanceDashboard';

describe('PersonalAttendanceDashboard', () => {
  it('renders only the current account self dashboard fields', () => {
    render(
      <PersonalAttendanceDashboard
        projection={createDemoSelfAttendanceDashboardProjection()}
      />,
    );

    expect(screen.getByRole('heading', { name: '我的考勤工作台' }))
      .toBeInTheDocument();
    expect(screen.getByText('个人专属 · 仅本人可见')).toBeInTheDocument();

    const metrics = screen.getByLabelText('本人考勤关键指标');
    expect(within(metrics).getByText('应出勤')).toBeInTheDocument();
    expect(within(metrics).getByText('确认工时')).toBeInTheDocument();
    expect(within(metrics).getByText('认可加班')).toBeInTheDocument();
    expect(within(metrics).getByText('请假')).toBeInTheDocument();
    expect(within(metrics).getByText('待处理异常')).toBeInTheDocument();
    expect(within(metrics).getByText('2 条')).toBeInTheDocument();

    expect(screen.getByRole('heading', { name: '每日考勤趋势' }))
      .toBeInTheDocument();
    expect(screen.getByRole('table', {
      hidden: true,
      name: '本人每日考勤趋势明细',
    })).toBeInTheDocument();
    const distribution = screen.getByRole('list', {
      name: '本人未解决异常类型分布',
    });
    expect(within(distribution).getByText('迟到')).toBeInTheDocument();
    expect(within(distribution).getByText('缺卡')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '我的最近异常' }))
      .toBeInTheDocument();
    expect(screen.getByText('等待复核。', { exact: false }))
      .toBeInTheDocument();

    expect(screen.queryByText('组织异常排行')).not.toBeInTheDocument();
    expect(screen.queryByText('公司范围')).not.toBeInTheDocument();
    expect(screen.queryByText('员工工号')).not.toBeInTheDocument();
    expect(screen.queryByText('案件编号')).not.toBeInTheDocument();
  });

  it('fails closed when the runtime projection scope is not SELF', () => {
    const projection = createDemoSelfAttendanceDashboardProjection();
    const unsafeProjection = {
      ...projection,
      metadata: {
        ...projection.metadata,
        scope: {
          type: 'ORGANIZATION',
          reference: 'organization:other',
          label: '其他组织',
        },
      },
    } as unknown as SelfAttendanceDashboardProjection;

    render(
      <PersonalAttendanceDashboard projection={unsafeProjection} />,
    );

    expect(screen.getByRole('heading', { name: '无法显示个人工作台' }))
      .toBeInTheDocument();
    expect(screen.getByText('个人工作台只接受当前登录账号的本人范围数据。'))
      .toBeInTheDocument();
    expect(screen.queryByText('其他组织')).not.toBeInTheDocument();
  });

  it('handles an empty personal period without exposing another scope', () => {
    const projection = createDemoSelfAttendanceDashboardProjection();

    render(
      <PersonalAttendanceDashboard
        projection={{
          ...projection,
          summary: {
            scheduledMinutes: 0,
            confirmedMinutes: 0,
            recognizedOvertimeMinutes: 0,
            leaveMinutes: 0,
            unresolvedExceptionCount: 0,
          },
          dailyTrend: [],
          today: null,
          exceptionTypeDistribution: [],
          recentExceptions: [],
        }}
      />,
    );

    expect(screen.getByText('本期暂无本人考勤趋势')).toBeInTheDocument();
    expect(screen.getByText('今天暂无排班或考勤结果')).toBeInTheDocument();
    expect(screen.getByText('当前没有未解决异常')).toBeInTheDocument();
    expect(screen.getByText('当前没有需要处理的本人异常'))
      .toBeInTheDocument();
  });
});
