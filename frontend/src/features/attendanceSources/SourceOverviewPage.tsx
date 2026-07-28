import { Alert, Card, Descriptions, Space, Tag } from 'antd';
import { useMemo, useState } from 'react';

import { DataTable, type DataColumn } from '../../shared/components/DataTable';
import { StatusBadge, statusLabel } from '../../shared/components/FeedbackComponents';
import { PageHeader, ResourcePagination } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { isDemoMode } from '../../shared/config/runtimeMode';
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
  const demoMode = isDemoMode();
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
        title={demoMode ? '客户演示场景' : '集成验证边界'}
        description={demoMode
          ? '当前使用合成数据演示来源配置、水位、新鲜度和异常处理，不连接客户真实设备或办公系统。'
          : '契约状态与真实联调状态分开显示，未验证项不会被标记为已上线。'}
      />
      {sources.resource.status === 'ready' ? (
        <>
          {demoMode
            ? <DemoIntegrationSummary />
            : <IntegrationStatus status={sources.resource.data[1]} />}
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

function DemoIntegrationSummary() {
  return (
    <Card title="演示链路状态" className="content-card">
      <Descriptions
        column={{ xs: 1, sm: 2, lg: 3 }}
        items={[
          { key: 'deli', label: '在线考勤设备', children: <Tag color="green">演示数据就绪</Tag> },
          { key: 'oa', label: '办公系统考勤单据', children: <Tag color="green">演示数据就绪</Tag> },
          { key: 'file', label: '离线电子表格导入', children: <Tag color="green">演示流程就绪</Tag> },
        ]}
      />
    </Card>
  );
}

function IntegrationStatus({ status }: { status: IntegrationStatusView }) {
  return (
    <Card title="集成验证边界" className="content-card">
      <Descriptions
        column={{ xs: 1, sm: 2, lg: 3 }}
        items={[
          { key: 'deli-stub', label: '得力契约桩', children: <Tag color="green">{statusLabel(status.deliContractStub)}</Tag> },
          { key: 'oa-stub', label: '办公系统契约模拟', children: <Tag color="green">{statusLabel(status.oaContractStub)}</Tag> },
          { key: 'file-stub', label: '文件端口契约', children: <Tag color="green">{statusLabel(status.filePortContract)}</Tag> },
          { key: 'deli-live', label: '得力真实联调', children: <Tag>{statusLabel(status.deliLive)}</Tag> },
          { key: 'oa-live', label: '办公系统真实联调', children: <Tag>{statusLabel(status.oaLive)}</Tag> },
          { key: 'storage-live', label: '生产文件存储', children: <Tag>{statusLabel(status.productionFileStorage)}</Tag> },
        ]}
      />
    </Card>
  );
}

function SourceTable({ sources }: { sources: AttendanceSourceView[] }) {
  const columns: Array<DataColumn<AttendanceSourceView>> = [
    { key: 'name', title: '来源', render: (source) => <strong>{source.displayName}</strong> },
    { key: 'type', title: '类型', render: (source) => sourceTypeLabel(source.sourceType) },
    { key: 'state', title: '状态', render: (source) => <StatusBadge status={source.state} /> },
    { key: 'timezone', title: '时区', render: (source) => timeZoneLabel(source.timeZone) },
    { key: 'watermark', title: '已提交水位', render: (source) => watermarkLabel(source.committedWatermark) },
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

function watermarkLabel(value: string | null): string {
  if (!value) return '尚未同步';
  const page = /^page-(\d{4})(\d{2})(\d{2})-(\d+)$/.exec(value);
  if (page) return `${page[1]}-${page[2]}-${page[3]} · 第 ${Number(page[4])} 页`;
  const document = /^document-version-(\d+)$/.exec(value);
  if (document) return `单据版本 ${Number(document[1])}`;
  return value;
}

export default SourceOverviewPage;
