import { Alert, Card, Tag } from 'antd';
import { useMemo } from 'react';

import { DataTable, type DataColumn } from '../../shared/components/DataTable';
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
        title="OA 考勤单据"
        description="保留 OA 单据源版本与半开区间；未知、草稿、驳回和撤销状态不会自动成为有效考勤证据。"
        breadcrumbs={[{ label: '考勤来源' }, { label: 'OA 单据' }]}
      />
      <Alert
        showIcon
        type="warning"
        title="只读证据边界"
        description="本页不写回 OA，不同步组织主数据，也不以最后写入覆盖同级冲突。"
      />
      {result.resource.status === 'ready' ? (
        <Card
          className="content-card"
          title={`${result.resource.data.oa?.displayName ?? 'OA'} · 版本化单据`}
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
    { key: 'external-id', title: '外部单据 ID', render: (document) => document.sourceDocumentId },
    { key: 'version', title: '源版本', render: (document) => document.sourceVersion },
    { key: 'type', title: '单据类型', render: (document) => document.documentType },
    { key: 'status', title: '源状态', render: (document) => <Tag>{document.sourceStatus}</Tag> },
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
      title: '[开始, 结束)',
      render: (document) => document.intervalStart && document.intervalEndExclusive
        ? `${document.intervalStart} → ${document.intervalEndExclusive}`
        : '未形成有效区间',
    },
  ];
  if (documents.length === 0) {
    return <StatePanel state="empty" description="当前来源尚无可读取的 OA 单据版本。" />;
  }
  return (
    <DataTable
      rows={documents}
      rowKey={(document) => document.documentId}
      columns={columns}
      ariaLabel="OA 版本化考勤单据"
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
    return <StatePanel state="empty" description="当前作用域内没有 OA 考勤来源。" />;
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

export default OaSourcesPage;
