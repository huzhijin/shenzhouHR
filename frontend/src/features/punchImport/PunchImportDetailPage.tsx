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
import type { AttendanceSourceView } from '../attendanceSources/attendanceSourceTypes';
import {
  listReferenceAttendanceSources,
  listReferenceCompanies,
  type CompanyReference,
} from '../referenceData/referenceDataApi';
import {
  getPunchImport,
  listPunchImportIssues,
  listPunchImportRows,
  precheckPunchImport,
  publishPunchImport,
  voidOrReversePunchImport,
} from './punchImportApi';
import {
  punchImportCreatedAtLabel,
  punchImportCompanyName,
  punchImportIssueDescription,
  punchImportSourceName,
  punchImportTaskLabel,
} from './punchImportDisplay';
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
      listReferenceCompanies().catch((): CompanyReference[] => []),
      listReferenceAttendanceSources().catch((): AttendanceSourceView[] => []),
    ]),
    [batchId],
  );
  const detail = useAsyncResource(loader, () => false, [batchId]);
  const taskLabel = detail.resource.status === 'ready'
    ? punchImportTaskLabel(
      detail.resource.data[0].originalFilename,
      detail.resource.data[0].createdAt,
    )
    : '导入任务详情';

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
      void messageApi.success('操作已提交，任务状态即将更新。');
      setPendingAction(undefined);
      detail.reload();
    } catch {
      void messageApi.error('操作未提交；请刷新任务状态后重试。');
    } finally {
      setProcessing(false);
    }
  };

  return (
    <>
      {messageContextHolder}
      <PageHeader
        title="考勤电子表格导入任务"
        description="查看文件预检结果，确认无误后发布有效打卡数据。"
        breadcrumbs={[
          { label: '电子表格导入', path: '/sources/attendance-excel' },
          { label: taskLabel },
        ]}
      />
      {detail.resource.status === 'ready' ? (
        <BatchDetail
          batch={detail.resource.data[0]}
          issues={detail.resource.data[1].items}
          rows={detail.resource.data[2].items}
          companyName={punchImportCompanyName(
            detail.resource.data[0].companyId,
            detail.resource.data[3],
          )}
          sourceName={punchImportSourceName(
            detail.resource.data[0].sourceId,
            detail.resource.data[4],
          )}
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
  companyName,
  sourceName,
  capabilities,
  onAction,
}: {
  batch: PunchImportBatchView;
  issues: PunchImportIssueView[];
  rows: PunchImportRowView[];
  companyName: string;
  sourceName: string;
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
            { key: 'scope', label: '公司', children: companyName },
            { key: 'source', label: '来源', children: sourceName },
            {
              key: 'created',
              label: '创建时间',
              children: punchImportCreatedAtLabel(batch.createdAt),
            },
            {
              key: 'range',
              label: '影响日期',
              children: batch.affectedDateFrom && batch.affectedDateTo
                ? `${batch.affectedDateFrom} 至 ${batch.affectedDateTo}`
                : '预检后确定',
            },
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
              label="作废或冲正此导入任务"
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
    VALIDATING: ['正在预检', '正在逐行检查员工匹配、重复记录和考勤期间，请稍候。', 'info'],
    VALIDATION_FAILED: ['预检失败', '请根据问题说明修正文件后重新预检；当前没有数据生效。', 'error'],
    BLOCKED_BY_FROZEN_PERIOD: ['考勤期间已关闭', '请先处理考勤期间状态，再重新预检。', 'warning'],
    PUBLISHING: ['正在发布', '数据正在生效，请稍候。', 'info'],
    PUBLISHED: ['发布成功', '文件中的有效打卡记录已生效。', 'success'],
    PARTIALLY_PUBLISHED: ['部分发布成功', '有效行已生效，未发布行和问题仍可继续查看。', 'warning'],
    PUBLISH_FAILED: ['发布失败', '当前没有数据生效，请刷新后重试。', 'error'],
    VOIDED: ['已作废或冲正', '原始文件和处理记录仍会保留，便于后续核对。', 'warning'],
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
    { key: 'field', title: '业务字段', render: (issue) => issueFieldLabel(issue.field) },
    { key: 'severity', title: '级别', render: (issue) => issue.severity === 'BLOCKING' ? '阻断' : '警告' },
    { key: 'reason', title: '问题原因', render: (issue) => issueReasonLabel(issue.code) },
    {
      key: 'message',
      title: '问题说明',
      render: (issue) => punchImportIssueDescription(issue.safeMessage),
    },
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
  if (rows.length === 0) return <StatePanel state="empty" description="当前导入任务没有可读取的行。" />;
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
  return <StatePanel state="empty" description="导入任务详情不可用。" />;
}

function actionTitle(action?: PendingAction): string {
  if (action === 'precheck') return '执行完整预检';
  if (action === 'strict-publish') return '严格发布';
  if (action === 'partial-publish') return '仅发布有效行';
  if (action === 'void') return '作废或冲正导入任务';
  return '确认操作';
}

function actionDescription(action?: PendingAction): string {
  if (action === 'partial-publish') {
    return '仅让预检通过的记录生效，未通过的记录和问题仍会保留。';
  }
  if (action === 'void') {
    return '作废或冲正不会删除原始文件和处理记录。';
  }
  if (action === 'strict-publish') {
    return '系统会再次确认预检结果、权限和考勤期间状态后发布全部有效数据。';
  }
  return '系统将逐行检查员工匹配、重复记录、考勤配置和影响日期。';
}

function issueFieldLabel(value: string | null): string {
  if (!value) return '整行';
  return ({
    employeeNumber: '员工号',
    punchTime: '打卡时间',
    sourceTimeZone: '来源时区',
  } as Record<string, string>)[value] ?? '其他字段';
}

function issueReasonLabel(value: string): string {
  return ({
    EMPLOYEE_NOT_FOUND: '未找到有效员工',
    NEAR_DUPLICATE_PENDING: '疑似重复待确认',
  } as Record<string, string>)[value] ?? '需人工处理';
}

function timeZoneLabel(value: string | null): string {
  if (!value) return '—';
  if (value === 'Asia/Shanghai') return '中国标准时间（上海）';
  if (value === 'UTC') return '协调世界时';
  return '其他来源时区';
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
