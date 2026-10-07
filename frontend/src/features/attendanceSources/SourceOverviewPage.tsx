import { Button, Card, message } from 'antd';
import { useMemo, useState } from 'react';

import { DataTable, type DataColumn } from '../../shared/components/DataTable';
import { StatusBadge } from '../../shared/components/FeedbackComponents';
import { PageHeader, ResourcePagination } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import {
  listAttendanceSources,
  startAttendanceSourceJob,
} from './attendanceSourceApi';
import type { AttendanceSourceView } from './attendanceSourceTypes';

export function SourceOverviewPage() {
  const [messageApi, messageContextHolder] = message.useMessage();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [startingSourceId, setStartingSourceId] = useState<string>();
  const loader = useMemo(
    () => () => listAttendanceSources(page, size),
    [page, size],
  );
  const sources = useAsyncResource(
    loader,
    (sourcePage) => sourcePage.totalElements === 0,
    [page, size],
  );

  const startSync = async (source: AttendanceSourceView) => {
    setStartingSourceId(source.sourceId);
    try {
      await startAttendanceSourceJob(source.sourceId);
      void messageApi.success('已提交同步，将从上次成功水位继续。');
      sources.reload();
    } catch {
      void messageApi.error('同步未提交，请检查权限或稍后重试。');
    } finally {
      setStartingSourceId(undefined);
    }
  };

  return (
    <>
      {messageContextHolder}
      <PageHeader
        title="在线考勤来源"
        description="查看得力等外部考勤来源的连接状态、最近失败原因，并可手动重试同步。"
        breadcrumbs={[{ label: '考勤来源' }, { label: '在线来源' }]}
      />
      {sources.resource.status === 'ready' ? (
        <>
          <SourceTable
            sources={sources.resource.data.items}
            startingSourceId={startingSourceId}
            onRetry={startSync}
          />
          <ResourcePagination
            ariaLabel="在线考勤来源分页"
            page={page}
            pageSize={size}
            total={sources.resource.data.totalElements}
            onChange={(nextPage, nextSize) => {
              setPage(nextPage);
              setSize(nextSize);
            }}
          />
        </>
      ) : (
        <SourceState
          resource={sources.resource}
          onRetry={sources.reload}
          emptyDescription="尚未注册在线考勤来源。得力 API 需先在服务端启用 Deli E+ 配置、注册来源并建立员工绑定，再发起同步。"
        />
      )}
    </>
  );
}

function SourceTable({
  sources,
  startingSourceId,
  onRetry,
}: {
  sources: AttendanceSourceView[];
  startingSourceId?: string;
  onRetry: (source: AttendanceSourceView) => void;
}) {
  const columns: Array<DataColumn<AttendanceSourceView>> = [
    { key: 'name', title: '来源', render: (source) => <strong>{source.displayName}</strong> },
    { key: 'type', title: '类型', render: (source) => sourceTypeLabel(source.sourceType) },
    { key: 'state', title: '状态', render: (source) => <StatusBadge status={source.state} /> },
    { key: 'timezone', title: '时区', render: (source) => timeZoneLabel(source.timeZone) },
    {
      key: 'last-sync',
      title: '最近成功',
      render: (source) => source.lastSuccessfulSyncAt
        ? formatSyncTime(source.lastSuccessfulSyncAt)
        : '尚未同步',
    },
    {
      key: 'last-failure',
      title: '最近失败',
      render: (source) => source.lastFailureReason
        ? `${source.lastFailureReason}${source.lastFailedSyncAt ? ` · ${formatSyncTime(source.lastFailedSyncAt)}` : ''}`
        : '无',
    },
    {
      key: 'retry',
      title: '操作',
      render: (source) => (
        <Button
          size="small"
          loading={startingSourceId === source.sourceId}
          onClick={() => onRetry(source)}
        >
          手动同步
        </Button>
      ),
    },
  ];
  return (
    <Card title="考勤来源" className="content-card">
      <DataTable
        rows={sources}
        rowKey={(source) => source.sourceId}
        columns={columns}
        ariaLabel="在线考勤来源与同步进度"
      />
    </Card>
  );
}

function SourceState({
  resource,
  onRetry,
  emptyDescription,
}: {
  resource: ReturnType<typeof useAsyncResource<unknown>>['resource'];
  onRetry: () => void;
  emptyDescription: string;
}) {
  if (resource.status === 'empty') {
    return <StatePanel state="empty" description={emptyDescription} />;
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

function sourceTypeLabel(value: AttendanceSourceView['sourceType']): string {
  return ({
    DELI_CLOUD: '得力云考勤',
    OA_ATTENDANCE: '办公系统考勤单据',
    DEVICE_EXCEL: '设备表格导入',
    STANDARD_XLSX: '标准表格导入',
  } as Partial<Record<AttendanceSourceView['sourceType'], string>>)[value] ?? '其他考勤来源';
}

function timeZoneLabel(value: string): string {
  return value === 'Asia/Shanghai' ? '中国标准时间（上海）' : value;
}

function formatSyncTime(value: string): string {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return '已同步';
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'Asia/Shanghai',
  }).format(timestamp);
}

export default SourceOverviewPage;
