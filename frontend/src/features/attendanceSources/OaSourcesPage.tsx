import { Alert, Card, Tag } from 'antd';
import { useMemo } from 'react';

import { DataTable, type DataColumn } from '../../shared/components/DataTable';
import { statusLabel } from '../../shared/components/FeedbackComponents';
import { PageHeader } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import {
  listAttendanceSources,
  listOaDocuments,
} from './attendanceSourceApi';
import type { OaDocumentView } from './attendanceSourceTypes';

export function OaSourcesPage() {
  const loader = useMemo(
    () => async () => {
      const sources = await listAttendanceSources(0, 100);
      const oa = sources.items.find((source) => source.sourceType === 'OA_ATTENDANCE');
      const documents = oa
        ? await listOaDocuments(oa.sourceId, 0, 100)
        : { items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 };
      return { oa, documents };
    },
    [],
  );
  const result = useAsyncResource(
    loader,
    ({ oa }) => oa === undefined,
    [],
  );

  return (
    <>
      <PageHeader
        title="办公系统考勤单据"
        description="保留办公系统单据源版本与半开区间；未知、草稿、驳回和撤销状态不会自动成为有效考勤证据。"
        breadcrumbs={[{ label: '考勤来源' }, { label: '办公系统单据' }]}
      />
      <Alert
        showIcon
        type="warning"
        title="只读证据边界"
        description="本页不写回办公系统，不同步组织主数据，也不以最后写入覆盖同级冲突。"
      />
      {result.resource.status === 'ready' ? (
        <Card
          className="content-card"
          title={`${result.resource.data.oa?.displayName ?? '办公系统'} · 版本化单据`}
        >
          <DocumentTable documents={result.resource.data.documents.items} />
        </Card>
      ) : (
        <AsyncState resource={result.resource} onRetry={result.reload} />
      )}
    </>
  );
}

function DocumentTable({ documents }: { documents: OaDocumentView[] }) {
  const columns: Array<DataColumn<OaDocumentView>> = [
    { key: 'external-id', title: '外部单据编号', render: (document) => document.sourceDocumentId },
    { key: 'version', title: '源版本', render: (document) => document.sourceVersion },
    { key: 'type', title: '单据类型', render: (document) => documentTypeLabel(document.documentType) },
    { key: 'status', title: '源状态', render: (document) => <Tag>{statusLabel(document.sourceStatus)}</Tag> },
    {
      key: 'effective',
      title: '有效候选',
      render: (document) => (
        <Tag color={document.effectiveCandidate ? 'green' : 'default'}>
          {document.effectiveCandidate ? '是' : '否'}
        </Tag>
      ),
    },
    {
      key: 'interval',
      title: '有效时间范围',
      render: (document) => document.intervalStart && document.intervalEndExclusive
        ? `${formatTimestamp(document.intervalStart)} 至 ${formatTimestamp(document.intervalEndExclusive)}`
        : '未形成有效区间',
    },
  ];
  if (documents.length === 0) {
    return <StatePanel state="empty" description="当前来源尚无可读取的办公系统单据版本。" />;
  }
  return (
    <DataTable
      rows={documents}
      rowKey={(document) => document.documentId}
      columns={columns}
      ariaLabel="办公系统版本化考勤单据"
    />
  );
}

function AsyncState({
  resource,
  onRetry,
}: {
  resource: ReturnType<typeof useAsyncResource<unknown>>['resource'];
  onRetry: () => void;
}) {
  if (resource.status === 'empty') {
    return <StatePanel state="empty" description="当前作用域内没有办公系统考勤来源。" />;
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

function documentTypeLabel(value: string): string {
  return ({
    LEAVE: '请假',
    OVERTIME: '加班',
    TRAVEL: '出差',
    OUTING: '外出',
    CORRECTION: '补签',
    UNKNOWN: '未识别',
  } as Record<string, string>)[value] ?? '其他考勤单据';
}

function formatTimestamp(value: string): string {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return value;
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'Asia/Shanghai',
  }).format(timestamp);
}

export default OaSourcesPage;
