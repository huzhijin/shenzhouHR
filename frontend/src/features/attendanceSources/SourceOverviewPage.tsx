import { Card, Space } from 'antd';
import { useMemo, useState } from 'react';

import { DataTable, type DataColumn } from '../../shared/components/DataTable';
import { StatusBadge } from '../../shared/components/FeedbackComponents';
import { PageHeader, ResourcePagination } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import { listAttendanceSources } from './attendanceSourceApi';
import type { AttendanceSourceView } from './attendanceSourceTypes';

export function SourceOverviewPage() {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const loader = useMemo(
    () => () => listAttendanceSources(page, size),
    [page, size],
  );
  const sources = useAsyncResource(
    loader,
    (sourcePage) => sourcePage.totalElements === 0,
    [page, size],
  );

  return (
    <>
      <PageHeader
        title="在线考勤来源"
        description="查看得力等外部考勤来源的连接状态和最近同步进度。"
        breadcrumbs={[{ label: '考勤来源' }, { label: '在线来源' }]}
      />
      {sources.resource.status === 'ready' ? (
        <>
          <SourceTable sources={sources.resource.data.items} />
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
          emptyDescription="当前可用范围内没有在线考勤来源。"
        />
      )}
    </>
  );
}

function SourceTable({ sources }: { sources: AttendanceSourceView[] }) {
  const columns: Array<DataColumn<AttendanceSourceView>> = [
    { key: 'name', title: '来源', render: (source) => <strong>{source.displayName}</strong> },
    { key: 'type', title: '类型', render: (source) => sourceTypeLabel(source.sourceType) },
    { key: 'state', title: '状态', render: (source) => <StatusBadge status={source.state} /> },
    { key: 'timezone', title: '时区', render: (source) => timeZoneLabel(source.timeZone) },
    {
      key: 'last-sync',
      title: '最近同步',
      render: (source) => source.lastSuccessfulSyncAt
        ? formatSyncTime(source.lastSuccessfulSyncAt)
        : '尚未同步',
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
  return <Space />;
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
