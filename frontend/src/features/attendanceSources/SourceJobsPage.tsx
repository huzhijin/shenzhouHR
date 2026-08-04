import { IconRefresh } from '@tabler/icons-react';
import { Card, message } from 'antd';
import { useMemo, useState } from 'react';

import { AccessibleButton } from '../../shared/components/AccessibleButton';
import { ConfirmationDialog, StatusBadge } from '../../shared/components/FeedbackComponents';
import { DataTable, type DataColumn } from '../../shared/components/DataTable';
import { PageHeader, ResourcePagination } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import {
  listAttendanceSourceJobs,
  retryAttendanceSourceJob,
} from './attendanceSourceApi';
import type { AttendanceSourceJobView } from './attendanceSourceTypes';

export function SourceJobsPage({ capabilities }: { capabilities: string[] }) {
  const [messageApi, messageContextHolder] = message.useMessage();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [retryTarget, setRetryTarget] = useState<AttendanceSourceJobView>();
  const [retrying, setRetrying] = useState(false);
  const loader = useMemo(
    () => () => listAttendanceSourceJobs(page, size),
    [page, size],
  );
  const jobs = useAsyncResource(
    loader,
    (result) => result.totalElements === 0,
    [page, size],
  );
  const canRetry = capabilities.includes('ATTENDANCE_SOURCE:RETRY');

  const retry = async () => {
    if (!retryTarget) return;
    setRetrying(true);
    try {
      await retryAttendanceSourceJob(retryTarget, '人工确认从最后已提交水位重试');
      void messageApi.success('已提交重试，将从上次成功位置继续。');
      setRetryTarget(undefined);
      jobs.reload();
    } catch {
      void messageApi.error('重试未提交，请刷新作业状态后再试。');
    } finally {
      setRetrying(false);
    }
  };

  return (
    <>
      {messageContextHolder}
      <PageHeader
        title="来源同步任务"
        description="查看各考勤来源的同步进度和失败原因，并可从上次成功位置重试。"
        breadcrumbs={[{ label: '考勤来源' }, { label: '同步作业' }]}
      />
      {jobs.resource.status === 'ready' ? (
        <>
          <Card className="content-card" title="同步作业">
            <JobsTable
              jobs={jobs.resource.data.items}
              canRetry={canRetry}
              onRetry={setRetryTarget}
            />
          </Card>
          <ResourcePagination
            ariaLabel="来源同步作业分页"
            page={page}
            pageSize={size}
            total={jobs.resource.data.totalElements}
            onChange={(nextPage, nextSize) => {
              setPage(nextPage);
              setSize(nextSize);
            }}
          />
        </>
      ) : (
        <JobsState resource={jobs.resource} onRetry={jobs.reload} />
      )}
      <ConfirmationDialog
        open={retryTarget !== undefined}
        title="确认重试同步"
        description="系统会从上次成功位置继续同步，已成功的数据不会重复处理。"
        confirmText="确认重试"
        processing={retrying}
        onConfirm={() => void retry()}
        onCancel={() => setRetryTarget(undefined)}
      />
    </>
  );
}

function JobsTable({
  jobs,
  canRetry,
  onRetry,
}: {
  jobs: AttendanceSourceJobView[];
  canRetry: boolean;
  onRetry: (job: AttendanceSourceJobView) => void;
}) {
  const columns: Array<DataColumn<AttendanceSourceJobView>> = [
    { key: 'source', title: '来源', render: (job) => job.sourceDisplayName },
    { key: 'status', title: '状态', render: (job) => <StatusBadge status={job.state} /> },
    { key: 'pages', title: '已同步批次', render: (job) => String(job.committedPages) },
    { key: 'facts', title: '打卡记录', render: (job) => String(job.rawFactCount) },
    { key: 'quarantine', title: '待处理记录', render: (job) => String(job.quarantinedCount) },
    { key: 'summary', title: '失败原因', render: (job) => job.safeErrorSummary ?? '—' },
    {
      key: 'action',
      title: '操作',
      render: (job) => canRetry && ['FAILED', 'PARTIALLY_QUARANTINED'].includes(job.state)
        ? (
          <AccessibleButton
            size="small"
            label={`重试${job.sourceDisplayName}同步任务`}
            icon={<IconRefresh aria-hidden="true" stroke={2} />}
            onClick={() => onRetry(job)}
          >
            重试
          </AccessibleButton>
        )
        : '—',
    },
  ];
  return (
    <DataTable
      rows={jobs}
      rowKey={(job) => job.jobId}
      columns={columns}
      ariaLabel="来源同步任务、待处理记录与重试"
    />
  );
}

function JobsState({
  resource,
  onRetry,
}: {
  resource: ReturnType<typeof useAsyncResource<unknown>>['resource'];
  onRetry: () => void;
}) {
  if (resource.status === 'empty') {
    return <StatePanel state="empty" description="当前可用范围内没有来源同步任务。" />;
  }
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
  return null;
}

export default SourceJobsPage;
