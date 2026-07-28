import { Alert, Button } from 'antd';
import { useState } from 'react';
import type { ReactNode } from 'react';

import { DataTable } from '../../shared/components/DataTable';
import { PageHeader } from '../../shared/components/PagePrimitives';
import { StatusBadge } from '../../shared/components/FeedbackComponents';
import { wave7ProjectionGateway } from '../../shared/runtime/wave7ProjectionGateway';
import type {
  AttendanceRecordsProjection,
  FeedbackProjection,
  LeaveProjection,
  TodayProjection,
} from './wave7Contracts';
import type { Wave7ProjectionGateway } from './wave7Gateway';
import {
  formatDate,
  formatDateTime,
  formatHours,
  FrozenHistoryNotice,
  LockedActionReason,
  ProjectionMetadata,
  SelfServiceNavigation,
  isSyntheticMetadata,
  Wave7AsyncBoundary,
} from './Wave7Common';

interface EmployeeRouteProps {
  capabilities?: readonly string[];
  gateway?: Wave7ProjectionGateway;
}

export function EmployeeTodayRoute({
  capabilities = [],
  gateway = wave7ProjectionGateway,
}: EmployeeRouteProps) {
  return (
    <Wave7AsyncBoundary loader={gateway.loadToday} isEmpty={isTodayEmpty}>
      {(projection) => (
        <EmployeeSelfLayout capabilities={capabilities}>
          <EmployeeTodayView projection={projection} />
        </EmployeeSelfLayout>
      )}
    </Wave7AsyncBoundary>
  );
}

export function EmployeeRecordsRoute({
  capabilities = [],
  gateway = wave7ProjectionGateway,
}: EmployeeRouteProps) {
  return (
    <Wave7AsyncBoundary loader={gateway.loadRecords} isEmpty={(value) => value.records.length === 0}>
      {(projection) => (
        <EmployeeSelfLayout capabilities={capabilities}>
          <EmployeeRecordsView projection={projection} />
        </EmployeeSelfLayout>
      )}
    </Wave7AsyncBoundary>
  );
}

export function EmployeeLeaveRoute({
  capabilities = [],
  gateway = wave7ProjectionGateway,
}: EmployeeRouteProps) {
  return (
    <Wave7AsyncBoundary loader={gateway.loadLeave} isEmpty={(value) => value.accounts.length === 0}>
      {(projection) => (
        <EmployeeSelfLayout capabilities={capabilities}>
          <EmployeeLeaveView projection={projection} />
        </EmployeeSelfLayout>
      )}
    </Wave7AsyncBoundary>
  );
}

export function EmployeeFeedbackRoute({
  capabilities = [],
  gateway = wave7ProjectionGateway,
}: EmployeeRouteProps) {
  const [demoSubmitted, setDemoSubmitted] = useState(false);
  return (
    <Wave7AsyncBoundary loader={gateway.loadFeedback} isEmpty={(value) => value.items.length === 0}>
      {(projection) => (
        <EmployeeSelfLayout capabilities={capabilities}>
          <EmployeeFeedbackView
            projection={projection}
            canCreate={capabilities.includes('ATTENDANCE_FEEDBACK:CREATE')}
            onCreate={isSyntheticMetadata(projection.metadata)
              ? () => setDemoSubmitted(true)
              : undefined}
          />
          {demoSubmitted ? (
            <Alert
              showIcon
              type="success"
              title="反馈已提交"
              description="演示反馈单已进入主管处理队列。"
            />
          ) : null}
        </EmployeeSelfLayout>
      )}
    </Wave7AsyncBoundary>
  );
}

export function EmployeeTodayView({ projection }: { projection: TodayProjection }) {
  return (
    <>
      <PageHeader title="今日状态" description="班次、有效打卡与暂算结果均来自授权投影。" />
      <ProjectionMetadata metadata={projection.metadata} />
      <FrozenHistoryNotice metadata={projection.metadata} />
      <section className="content-surface wave7-today" aria-labelledby="wave7-today-heading">
        <h2 id="wave7-today-heading">{formatDate(projection.businessDate)}</h2>
        <dl className="wave7-detail-grid">
          <Detail label="班次" value={projection.shiftLabel ?? '未排班'} />
          <Detail label="首次有效打卡" value={projection.firstEffectivePunch ?? '暂无'} />
          <Detail label="末次有效打卡" value={projection.lastEffectivePunch ?? '暂无'} />
          <Detail label="考勤状态" value={<StatusBadge status={projection.attendanceStatus} />} />
          <Detail label="确认工时" value={formatHours(projection.confirmedMinutes)} />
          <Detail label="异常提示" value={projection.issueLabels.join('、') || '无'} />
        </dl>
      </section>
    </>
  );
}

export function EmployeeRecordsView({ projection }: { projection: AttendanceRecordsProjection }) {
  return (
    <>
      <PageHeader title="我的考勤" description="汇总和每日记录固定在同一投影版本。" />
      <ProjectionMetadata metadata={projection.metadata} />
      <FrozenHistoryNotice metadata={projection.metadata} />
      <dl className="wave7-metric-grid wave7-metric-grid--summary" aria-label="月度考勤汇总">
        <Metric label="应出勤" value={formatHours(projection.summary.scheduledMinutes)} />
        <Metric label="确认工时" value={formatHours(projection.summary.confirmedMinutes)} />
        <Metric label="认可加班" value={formatHours(projection.summary.recognizedOvertimeMinutes)} />
        <Metric label="请假" value={formatHours(projection.summary.leaveMinutes)} />
      </dl>
      <section className="content-surface" aria-labelledby="wave7-records-heading">
        <h2 id="wave7-records-heading">每日记录</h2>
        <DataTable
          ariaLabel="本人每日考勤记录"
          rows={projection.records}
          rowKey={(row) => row.businessDate}
          columns={[
            { key: 'date', title: '日期', render: (row) => formatDate(row.businessDate) },
            { key: 'shift', title: '班次', render: (row) => row.shiftLabel },
            { key: 'hours', title: '确认工时', render: (row) => formatHours(row.confirmedMinutes) },
            { key: 'status', title: '状态', render: (row) => <StatusBadge status={row.statusLabel} /> },
            { key: 'issues', title: '说明', render: (row) => row.issueLabels.join('、') || '无' },
            {
              key: 'evidence',
              title: '计算依据',
              render: (row) => row.explanationReference
                ? isSyntheticMetadata(projection.metadata)
                  ? '依据已生成'
                  : <code>{row.explanationReference}</code>
                : '无',
            },
          ]}
        />
      </section>
    </>
  );
}

export function EmployeeLeaveView({ projection }: { projection: LeaveProjection }) {
  return (
    <>
      <PageHeader title="我的假期" description="余额由服务端台账投影提供，页面不自行重算。" />
      <ProjectionMetadata metadata={projection.metadata} />
      <FrozenHistoryNotice metadata={projection.metadata} />
      <section className="wave7-account-grid" aria-label="本人假期与工时账户">
        {projection.accounts.map((account) => (
          <article className="content-surface wave7-account" key={account.accountReference}>
            <header>
              <h2>{account.label}</h2>
              {isSyntheticMetadata(projection.metadata)
                ? <span>台账已核对</span>
                : <code>{account.ledgerVersion}</code>}
            </header>
            <dl className="wave7-detail-grid">
              <Detail label="已发放" value={`${account.grantedHours.toFixed(2)} 小时`} />
              <Detail label="期初" value={`${account.openingHours.toFixed(2)} 小时`} />
              <Detail label="已用" value={`${account.usedHours.toFixed(2)} 小时`} />
              <Detail label="剩余" value={`${account.remainingHours.toFixed(2)} 小时`} />
              {account.equivalentDays === undefined
                ? null
                : <Detail label="8 小时制等价" value={`${account.equivalentDays.toFixed(2)} 天`} />}
              <Detail label="到期日" value={account.expiresOn ? formatDate(account.expiresOn) : '按账户策略'} />
            </dl>
          </article>
        ))}
      </section>
    </>
  );
}

export function EmployeeFeedbackView({
  projection,
  canCreate,
  onCreate,
}: {
  projection: FeedbackProjection;
  canCreate: boolean;
  onCreate?: () => void;
}) {
  const actionAllowed = projection.metadata.allowedActions.includes('FEEDBACK_CREATE');
  return (
    <>
      <PageHeader
        title="反馈中心"
        description="仅展示本人反馈、处理进度和授权回复。"
        actions={(
          <Button
            type="primary"
            disabled={!canCreate || !actionAllowed || !onCreate}
            onClick={onCreate}
          >
            提交反馈
          </Button>
        )}
      />
      <ProjectionMetadata metadata={projection.metadata} />
      <FrozenHistoryNotice metadata={projection.metadata} />
      {!canCreate || !actionAllowed
        ? <LockedActionReason>当前仅可查看反馈，不能提交新反馈。</LockedActionReason>
        : null}
      <section className="wave7-feedback-list" aria-label="本人考勤反馈">
        {projection.items.map((item) => (
          <article className="content-surface wave7-feedback" key={item.feedbackReference}>
            <header>
              <div>
                <h2>{formatDate(item.attendanceDate)} · {item.problemTypeLabel}</h2>
                {isSyntheticMetadata(projection.metadata)
                  ? <span>反馈单 {item.feedbackReference.slice(-12)}</span>
                  : <code>{item.feedbackReference}</code>}
              </div>
              <StatusBadge status={item.state} />
            </header>
            <p className="wave7-plain-text">{item.content}</p>
            {item.replyText ? <p><strong>回复：</strong>{item.replyText}</p> : null}
            {item.linkedAdjustmentResult ? <p><strong>关联结果：</strong>{item.linkedAdjustmentResult}</p> : null}
            <ol className="wave7-progress" aria-label="反馈处理进度">
              {[...item.progress]
                .sort((left, right) => left.sequence - right.sequence)
                .map((progress) => (
                  <li key={progress.sequence}>
                    <span>{progress.label}</span>
                    <time dateTime={progress.occurredAt}>{formatDateTime(progress.occurredAt)}</time>
                  </li>
                ))}
            </ol>
          </article>
        ))}
      </section>
    </>
  );
}

function EmployeeSelfLayout({ capabilities, children }: {
  capabilities: readonly string[];
  children: ReactNode;
}) {
  return (
    <div className="wave7-self-page">
      {children}
      <SelfServiceNavigation capabilities={capabilities} />
    </div>
  );
}

function Detail({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="wave7-metric">
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

function isTodayEmpty(projection: TodayProjection): boolean {
  return projection.shiftLabel === undefined
    && projection.firstEffectivePunch === undefined
    && projection.lastEffectivePunch === undefined;
}
