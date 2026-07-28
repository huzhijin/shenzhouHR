import { Alert, Card, Descriptions, Space, Tag } from 'antd';
import { useMemo, useState } from 'react';

import { DataTable, type DataColumn } from '../../shared/components/DataTable';
import { StatusBadge } from '../../shared/components/FeedbackComponents';
import { PageHeader, ResourcePagination } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import {
  getSourceIntegrationStatus,
  listAttendanceSources,
} from './attendanceSourceApi';
import type {
  AttendanceSourceView,
  IntegrationStatusView,
} from './attendanceSourceTypes';

export function SourceOverviewPage() {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const loader = useMemo(
    () => () => Promise.all([
      listAttendanceSources(page, size),
      getSourceIntegrationStatus(),
    ]),
    [page, size],
  );
  const sources = useAsyncResource(
    loader,
    ([sourcePage]) => sourcePage.totalElements === 0,
    [page, size],
  );

  return (
    <>
      <PageHeader
        title="在线考勤来源"
        description="读取得力等外部打卡来源的配置修订、水位与新鲜度；本页不提供组织同步或上游回写。"
        breadcrumbs={[{ label: '考勤来源' }, { label: '在线来源' }]}
      />
      <Alert
        showIcon
        type="info"
        title="W4 预发布实现"
        description="契约桩与真实联调状态分开显示；W3 FINAL 尚未作为本工作区的已通过前置。"
      />
      {sources.resource.status === 'ready' ? (
        <>
          <IntegrationStatus status={sources.resource.data[1]} />
          <SourceTable sources={sources.resource.data[0].items} />
          <ResourcePagination
            ariaLabel="在线考勤来源分页"
            page={page}
            pageSize={size}
            total={sources.resource.data[0].totalElements}
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
          emptyDescription="当前作用域内没有可读取的在线考勤来源。"
        />
      )}
    </>
  );
}

function IntegrationStatus({ status }: { status: IntegrationStatusView }) {
  return (
    <Card title="集成验证边界" className="content-card">
      <Descriptions
        column={{ xs: 1, sm: 2, lg: 3 }}
        items={[
          { key: 'deli-stub', label: '得力契约桩', children: <Tag color="green">{status.deliContractStub}</Tag> },
          { key: 'oa-stub', label: 'OA 契约桩', children: <Tag color="green">{status.oaContractStub}</Tag> },
          { key: 'file-stub', label: '文件端口契约', children: <Tag color="green">{status.filePortContract}</Tag> },
          { key: 'deli-live', label: '得力真实联调', children: <Tag>{status.deliLive}</Tag> },
          { key: 'oa-live', label: 'OA 真实联调', children: <Tag>{status.oaLive}</Tag> },
          { key: 'storage-live', label: '生产文件存储', children: <Tag>{status.productionFileStorage}</Tag> },
        ]}
      />
    </Card>
  );
}

function SourceTable({ sources }: { sources: AttendanceSourceView[] }) {
  const columns: Array<DataColumn<AttendanceSourceView>> = [
    { key: 'name', title: '来源', render: (source) => <strong>{source.displayName}</strong> },
    { key: 'type', title: '类型', render: (source) => source.sourceType },
    { key: 'state', title: '状态', render: (source) => <StatusBadge status={source.state} /> },
    { key: 'timezone', title: '时区', render: (source) => source.timeZone },
    { key: 'watermark', title: '已提交水位', render: (source) => source.committedWatermark ?? '尚未同步' },
    { key: 'revision', title: '配置修订', render: (source) => String(source.configurationRevision) },
  ];
  return (
    <Card title="来源与水位" className="content-card">
      <DataTable
        rows={sources}
        rowKey={(source) => source.sourceId}
        columns={columns}
        ariaLabel="在线考勤来源与已提交水位"
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

export default SourceOverviewPage;
