import { IconAlertTriangle, IconPlayerPlay, IconTrash } from '@tabler/icons-react';
import { Alert, Card, Descriptions, Space, Statistic, message } from 'antd';
import { useMemo, useState } from 'react';
import { useParams } from 'react-router-dom';

import { AccessibleButton } from '../../shared/components/AccessibleButton';
import { ConfirmationDialog, StatusBadge } from '../../shared/components/FeedbackComponents';
import { DataTable, type DataColumn } from '../../shared/components/DataTable';
import { PageHeader } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import {
  getPunchImport,
  listPunchImportIssues,
  listPunchImportRows,
  precheckPunchImport,
  publishPunchImport,
  voidOrReversePunchImport,
} from './punchImportApi';
import type {
  PunchImportBatchView,
  PunchImportIssueView,
  PunchImportRowView,
} from './punchImportTypes';

type PendingAction = 'precheck' | 'strict-publish' | 'partial-publish' | 'void';

export function PunchImportDetailPage({ capabilities }: { capabilities: string[] }) {
  const { batchId = '' } = useParams();
  const [messageApi, messageContextHolder] = message.useMessage();
  const [pendingAction, setPendingAction] = useState<PendingAction>();
  const [processing, setProcessing] = useState(false);
  const loader = useMemo(
    () => () => Promise.all([
      getPunchImport(batchId),
      listPunchImportIssues(batchId, 0, 100),
      listPunchImportRows(batchId, 0, 100),
    ]),
    [batchId],
  );
  const detail = useAsyncResource(loader, () => false, [batchId]);

  const applyAction = async () => {
    if (detail.resource.status !== 'ready' || !pendingAction) return;
    const batch = detail.resource.data[0];
    setProcessing(true);
    try {
      if (pendingAction === 'precheck') {
        await precheckPunchImport(batch, '人工确认执行完整预检');
      } else if (pendingAction === 'strict-publish') {
        await publishPunchImport(batch, 'STRICT', '人工确认严格发布');
      } else if (pendingAction === 'partial-publish') {
        await publishPunchImport(batch, 'VALID_ROWS_ONLY', '人工确认仅发布有效行');
      } else {
        await voidOrReversePunchImport(batch, '人工确认作废或冲正已发布证据');
      }
      void messageApi.success('操作已提交，页面将重新读取服务端状态。');
      setPendingAction(undefined);
      detail.reload();
    } catch {
      void messageApi.error('操作未提交；请刷新状态、版本与期间保护结果。');
    } finally {
      setProcessing(false);
    }
  };

  return (
    <>
      {messageContextHolder}
      <PageHeader
        title="考勤电子表格批次详情"
        description="所有预检、发布、部分发布和冲正都由服务端状态、校验令牌、权限、版本与期间保护共同决定。"
        breadcrumbs={[
          { label: '电子表格导入', path: '/sources/attendance-excel' },
          { label: batchId ? batchDisplayNumber(batchId) : '批次' },
        ]}
      />
      {detail.resource.status === 'ready' ? (
        <BatchDetail
          batch={detail.resource.data[0]}
          issues={detail.resource.data[1].items}
          rows={detail.resource.data[2].items}
          capabilities={capabilities}
          onAction={setPendingAction}
        />
      ) : (
        <DetailState resource={detail.resource} onRetry={detail.reload} />
      )}
      <ConfirmationDialog
        open={pendingAction !== undefined}
        title={actionTitle(pendingAction)}
        description={actionDescription(pendingAction)}
        confirmText="确认提交"
        danger={pendingAction === 'partial-publish' || pendingAction === 'void'}
        processing={processing}
        onConfirm={() => void applyAction()}
        onCancel={() => setPendingAction(undefined)}
      />
    </>
  );
}

function BatchDetail({
  batch,
  issues,
  rows,
  capabilities,
  onAction,
}: {
  batch: PunchImportBatchView;
  issues: PunchImportIssueView[];
  rows: PunchImportRowView[];
  capabilities: string[];
  onAction: (action: PendingAction) => void;
}) {
  const canPrecheck = capabilities.includes('ATTENDANCE_PUNCH_IMPORT:PRECHECK');
  const canPublish = capabilities.includes('ATTENDANCE_PUNCH_IMPORT:PUBLISH');
  const canPartial = capabilities.includes('ATTENDANCE_PUNCH_IMPORT:PARTIAL_PUBLISH');
  const canVoid = capabilities.includes('ATTENDANCE_PUNCH_IMPORT:VOID_OR_REVERSE');
  const canReadRows = capabilities.includes('ATTENDANCE_PUNCH_IMPORT:RAW_ROW_READ');

  return (
    <>
      <BatchStateAlert batch={batch} />
      <Card
        className="content-card"
        title={batch.originalFilename}
        extra={<StatusBadge status={batch.state} />}
      >
        <Descriptions
          column={{ xs: 1, sm: 2, lg: 3 }}
          items={[
            { key: 'batch', label: '批次编号', children: batchDisplayNumber(batch.batchId) },
            { key: 'sha', label: '文件摘要', children: <code>{batch.fileSha256}</code> },
            { key: 'scope', label: '公司', children: companyLabel(batch.legalEntityId) },
            { key: 'source', label: '来源', children: sourceLabel(batch.sourceId) },
            {
              key: 'range',
              label: '影响日期',
              children: batch.affectedDateFrom && batch.affectedDateTo
                ? `${batch.affectedDateFrom} 至 ${batch.affectedDateTo}`
                : '预检后确定',
            },
            { key: 'version', label: '资源版本', children: String(batch.rowVersion) },
          ]}
        />
        <Space wrap>
          <Statistic title="总行数" value={batch.totalRows} />
          <Statistic title="有效行" value={batch.validRows} />
          <Statistic title="阻断行" value={batch.invalidRows} />
          <Statistic title="精确重复" value={batch.exactDuplicateRows} />
          <Statistic title="近重复待审" value={batch.nearDuplicateRows} />
        </Space>
        <Space wrap className="page-action-bar">
          {canPrecheck && ['DRAFT', 'VALIDATION_FAILED', 'BLOCKED_BY_FROZEN_PERIOD'].includes(batch.state) ? (
            <AccessibleButton
              label="执行完整预检"
              icon={<IconPlayerPlay aria-hidden="true" stroke={2} />}
              onClick={() => onAction('precheck')}
            >
              执行预检
            </AccessibleButton>
          ) : null}
          {canPublish && batch.state === 'AWAITING_CONFIRMATION' && batch.precheckTokenPresent ? (
            <AccessibleButton
              type="primary"
              disabled={batch.invalidRows > 0}
              label={batch.invalidRows > 0
                ? `严格发布不可用：仍有 ${batch.invalidRows} 条阻断行`
                : '严格发布全部行'}
              icon={<IconPlayerPlay aria-hidden="true" stroke={2} />}
              onClick={() => onAction('strict-publish')}
            >
              严格发布
            </AccessibleButton>
          ) : null}
          {canPartial && batch.state === 'AWAITING_CONFIRMATION' && batch.precheckTokenPresent ? (
            <AccessibleButton
              danger
              label="仅发布有效行"
              icon={<IconAlertTriangle aria-hidden="true" stroke={2} />}
              onClick={() => onAction('partial-publish')}
            >
              仅发布有效行
            </AccessibleButton>
          ) : null}
          {canVoid && ['PUBLISHED', 'PARTIALLY_PUBLISHED'].includes(batch.state) ? (
            <AccessibleButton
              danger
              label="作废或冲正此批次"
              icon={<IconTrash aria-hidden="true" stroke={2} />}
              onClick={() => onAction('void')}
            >
              作废/冲正
            </AccessibleButton>
          ) : null}
        </Space>
      </Card>
      <Card className="content-card" title="预检问题">
        <IssueTable issues={issues} />
      </Card>
      {canReadRows ? (
        <Card className="content-card" title="原始行与匹配结果">
          <RowTable rows={rows} />
        </Card>
      ) : null}
    </>
  );
}

function BatchStateAlert({ batch }: { batch: PunchImportBatchView }) {
  const details: Partial<Record<PunchImportBatchView['state'], [string, string, 'info' | 'warning' | 'error' | 'success']>> = {
    VALIDATING: ['正在预检', '请等待服务端完成逐行解析、匹配、重复与期间检查。', 'info'],
    VALIDATION_FAILED: ['预检失败', '修正文件或字段映射后重新预检；没有发布任何证据。', 'error'],
    BLOCKED_BY_FROZEN_PERIOD: ['期间已冻结', '期间重开后必须重新预检，旧校验令牌已失效。', 'warning'],
    PUBLISHING: ['正在发布', '请等待事务完成，不要依据客户端状态推断成功。', 'info'],
    PUBLISHED: ['严格发布成功', '所有发布对象与重算意图已在同一事务提交。', 'success'],
    PARTIALLY_PUBLISHED: ['部分发布成功', '仅有效行已发布，未发布行和问题仍完整保留。', 'warning'],
    PUBLISH_FAILED: ['发布失败', '发布事务未提交原始记录、生效记录与重算任务；刷新后按稳定原因重试。', 'error'],
    VOIDED: ['已作废/冲正', '原文件、原始行、事实记录和事件均未删除，已追加冲正生命周期与重算任务。', 'warning'],
  };
  const detail = details[batch.state];
  return detail
    ? <Alert showIcon title={detail[0]} description={detail[1]} type={detail[2]} />
    : null;
}

function IssueTable({ issues }: { issues: PunchImportIssueView[] }) {
  if (issues.length === 0) return <StatePanel state="empty" description="当前预检没有问题。" />;
  const columns: Array<DataColumn<PunchImportIssueView>> = [
    { key: 'row', title: '行号', render: (issue) => String(issue.rowNumber) },
    { key: 'field', title: '字段', render: (issue) => issueFieldLabel(issue.field) },
    { key: 'severity', title: '级别', render: (issue) => issue.severity === 'BLOCKING' ? '阻断' : '警告' },
    { key: 'code', title: '问题原因', render: (issue) => issueCodeLabel(issue.code) },
    { key: 'message', title: '安全说明', render: (issue) => issue.safeMessage },
  ];
  return (
    <DataTable
      rows={issues}
      rowKey={(issue) => issue.issueId}
      columns={columns}
      ariaLabel="考勤导入预检问题"
    />
  );
}

function RowTable({ rows }: { rows: PunchImportRowView[] }) {
  if (rows.length === 0) return <StatePanel state="empty" description="当前批次没有可读取的行。" />;
  const columns: Array<DataColumn<PunchImportRowView>> = [
    { key: 'number', title: '行号', render: (row) => String(row.rowNumber) },
    { key: 'employee', title: '员工号', render: (row) => row.employeeNumber ?? '—' },
    { key: 'time', title: '源打卡时间', render: (row) => row.punchTime ?? '—' },
    { key: 'timezone', title: '源时区', render: (row) => timeZoneLabel(row.sourceTimeZone) },
    { key: 'match', title: '匹配', render: (row) => matchStateLabel(row.matchState) },
    { key: 'duplicate', title: '重复', render: (row) => duplicateStateLabel(row.duplicateState) },
    { key: 'publishable', title: '可发布', render: (row) => row.publishable ? '是' : '否' },
  ];
  return (
    <DataTable
      rows={rows}
      rowKey={(row) => row.rowId}
      columns={columns}
      ariaLabel="考勤导入原始行、匹配和重复状态"
    />
  );
}

function DetailState({
  resource,
  onRetry,
}: {
  resource: ReturnType<typeof useAsyncResource<unknown>>['resource'];
  onRetry: () => void;
}) {
  if (resource.status === 'loading' || resource.status === 'partial-loading') {
    return <StatePanel state={resource.status} />;
  }
  if ('error' in resource) {
    return (
      <StatePanel
        state={resource.status}
        description={resource.error.message}
        onRetry={resource.error.retryable ? onRetry : undefined}
      />
    );
  }
  return <StatePanel state="empty" description="批次详情不可用。" />;
}

function actionTitle(action?: PendingAction): string {
  if (action === 'precheck') return '执行完整预检';
  if (action === 'strict-publish') return '严格发布';
  if (action === 'partial-publish') return '仅发布有效行';
  if (action === 'void') return '作废或冲正批次';
  return '确认操作';
}

function actionDescription(action?: PendingAction): string {
  if (action === 'partial-publish') {
    return '这是独立授权的高风险动作。服务端只会发布有效行，并保留全部无效行和对账计数。';
  }
  if (action === 'void') {
    return '不会删除任何原文件、行或证据；服务端将在开放期间追加冲正生命周期和重算意图。';
  }
  if (action === 'strict-publish') {
    return '服务端将重新验证预检令牌、资源版本、权限、范围和期间状态后，在一个事务中发布全部行。';
  }
  return '服务端将解析所有行并重新计算匹配、重复、配置、期间和影响范围。';
}

function companyLabel(value: string): string {
  return value === 'LEGAL-JIANGSU' ? '江苏神州半导体科技有限公司' : value;
}

function batchDisplayNumber(value: string): string {
  const demoMatch = /^ATT-XLS-DEMO-(\d+)$/.exec(value);
  return demoMatch ? `导入批次 ${Number(demoMatch[1])}` : value;
}

function sourceLabel(value: string): string {
  return value === 'SRC-XLS-OFFLINE-A' ? '离线考勤文件（一号厂区）' : value;
}

function issueFieldLabel(value: string | null): string {
  if (!value) return '整行';
  return ({
    employeeNumber: '员工号',
    punchTime: '打卡时间',
    sourceTimeZone: '来源时区',
  } as Record<string, string>)[value] ?? '其他字段';
}

function issueCodeLabel(value: string): string {
  return ({
    EMPLOYEE_NOT_FOUND: '未找到有效员工',
    NEAR_DUPLICATE_PENDING: '疑似重复待确认',
  } as Record<string, string>)[value] ?? '需人工处理';
}

function timeZoneLabel(value: string | null): string {
  if (!value) return '—';
  return value === 'Asia/Shanghai' ? '中国标准时间（上海）' : value;
}

function matchStateLabel(value: PunchImportRowView['matchState']): string {
  return ({
    MATCHED: '已匹配',
    UNMATCHED: '未匹配',
    AMBIGUOUS: '匹配结果不唯一',
    OUT_OF_SCOPE: '不在授权范围',
  } as Record<PunchImportRowView['matchState'], string>)[value];
}

function duplicateStateLabel(value: PunchImportRowView['duplicateState']): string {
  return ({
    NONE: '无重复',
    EXACT: '精确重复',
    NEAR_PENDING: '疑似重复待确认',
  } as Record<PunchImportRowView['duplicateState'], string>)[value];
}

export default PunchImportDetailPage;
