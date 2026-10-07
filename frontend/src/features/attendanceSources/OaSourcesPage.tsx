import { Alert, Card, Select, Tag } from 'antd';
import { useMemo, useState } from 'react';

import { DataTable, type DataColumn } from '../../shared/components/DataTable';
import { statusLabel } from '../../shared/components/FeedbackComponents';
import { PageHeader, ResourcePagination } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import {
  listAttendanceSources,
  listOaDocuments,
} from './attendanceSourceApi';
import type { OaDocumentView } from './attendanceSourceTypes';

const DOCUMENT_TYPE_OPTIONS = [
  { value: 'LEAVE', label: '请假' },
  { value: 'LEAVE_REVOCATION', label: '销假' },
  { value: 'OVERTIME', label: '加班' },
  { value: 'TRIP', label: '出差' },
  { value: 'OUTING', label: '外出' },
  { value: 'PUNCH_CORRECTION', label: '补签' },
  { value: 'TIME_OFF', label: '调休' },
  { value: 'EXEMPT_PUNCH', label: '免打卡' },
];

export function OaSourcesPage() {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [documentType, setDocumentType] = useState<string>();
  const loader = useMemo(
    () => async () => {
      const sources = await listAttendanceSources(0, 100);
      const oa = sources.items.find((source) => source.sourceType === 'OA_ATTENDANCE');
      const documents = oa
        ? await listOaDocuments(oa.sourceId, page, size, documentType)
        : { items: [], page, size, totalElements: 0, totalPages: 0 };
      return { oa, documents };
    },
    [page, size, documentType],
  );
  const result = useAsyncResource(
    loader,
    ({ oa }) => oa === undefined,
    [page, size, documentType],
  );

  return (
    <>
      <PageHeader
        title="办公系统考勤单据"
        description="查看从办公系统同步的请假、加班、出差、外出、补签、调休和免打卡单据。"
        breadcrumbs={[{ label: '考勤来源' }, { label: '办公系统单据' }]}
      />
      <Alert
        showIcon
        type="info"
        title="数据仅供查看"
        description="单据更新来自办公系统同步；草稿、驳回和撤销单据不会计入考勤。"
      />
      {result.resource.status === 'ready' ? (
        <Card
          className="content-card"
          title={`${result.resource.data.oa?.displayName ?? '办公系统'} · 考勤单据`}
        >
          <label className="query-report__field" style={{ display: 'block', maxWidth: 280, marginBottom: 16 }}>
            <span>单据类型</span>
            <Select
              allowClear
              placeholder="全部类型（请假、外出、出差、加班等）"
              value={documentType}
              options={DOCUMENT_TYPE_OPTIONS}
              onChange={(value) => {
                setDocumentType(value);
                setPage(0);
              }}
              popupMatchSelectWidth={false}
              style={{ width: '100%' }}
            />
          </label>
          <DocumentTable documents={result.resource.data.documents.items} />
          <ResourcePagination
            ariaLabel="办公系统考勤单据分页"
            page={page}
            pageSize={size}
            total={result.resource.data.documents.totalElements}
            onChange={(nextPage, nextSize) => {
              setPage(nextPage);
              setSize(nextSize);
            }}
          />
        </Card>
      ) : (
        <AsyncState resource={result.resource} onRetry={result.reload} />
      )}
    </>
  );
}

function DocumentTable({ documents }: { documents: OaDocumentView[] }) {
  const columns: Array<DataColumn<OaDocumentView>> = [
    { key: 'employee-no', title: '工号', render: (document) => document.employeeNumber ?? '—' },
    { key: 'employee-name', title: '姓名', render: (document) => document.employeeName ?? '—' },
    { key: 'department', title: '部门', render: (document) => document.department ?? '—' },
    { key: 'type', title: '单据类型', render: (document) => documentTypeLabel(document.documentType) },
    {
      key: 'detail',
      title: '说明',
      render: (document) => documentDetail(document),
    },
    {
      key: 'hours',
      title: '时长（小时）',
      render: (document) => formatHours(document),
    },
    {
      key: 'interval',
      title: '时间',
      render: (document) => document.intervalStart && document.intervalEndExclusive
        ? `${formatTimestamp(document.intervalStart)} 至 ${formatTimestamp(document.intervalEndExclusive)}`
        : '未形成有效区间',
    },
    {
      key: 'status',
      title: '单据状态',
      render: (document) => <Tag>{statusLabel(document.sourceStatus)}</Tag>,
    },
    {
      key: 'effective',
      title: '计入考勤',
      render: (document) => (
        <Tag color={document.effectiveCandidate ? 'green' : 'default'}>
          {document.effectiveCandidate ? '是' : '否'}
        </Tag>
      ),
    },
  ];
  if (documents.length === 0) {
    return (
      <StatePanel
        state="empty"
        description="当前筛选条件下没有办公系统考勤单据。仅连接数据库不会自动生成单据，还需完成映射确认与同步入库。"
      />
    );
  }
  return (
    <DataTable
      rows={documents}
      rowKey={(document) => document.documentId}
      columns={columns}
      ariaLabel="办公系统考勤单据"
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
    return (
      <StatePanel
        state="empty"
        description="尚未注册办公系统考勤来源。仅连接 OA 数据库不会自动展示，需先确认表字段与状态映射，并实现、启用同步入库。"
      />
    );
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
  return DOCUMENT_TYPE_OPTIONS.find((item) => item.value === value)?.label
    ?? ({ UNKNOWN: '未识别', TRAVEL: '出差', CORRECTION: '补签' } as Record<string, string>)[value]
    ?? '其他考勤单据';
}

function leaveTypeLabel(value: string | null | undefined): string {
  if (!value) return '请假';
  return ({
    ANNUAL: '年假',
    SICK: '病假',
    MARRIAGE: '婚假',
    MATERNITY: '产假',
    PATERNITY: '陪产假',
    BEREAVEMENT: '丧假',
    WORK_INJURY: '工伤假',
    PRENATAL_NURSING: '产检假',
    PERSONAL: '事假',
    COMPENSATORY: '调休',
  } as Record<string, string>)[value] ?? value;
}

function documentDetail(document: OaDocumentView): string {
  switch (document.documentType) {
    case 'OVERTIME':
      if ((document.timeOffCreditMinutes ?? 0) > 0) return '加班，结算为转调休';
      if ((document.payrollCreditMinutes ?? 0) > 0) return '加班，结算为计薪加班（加班费）';
      if (document.overtimeTreatment === 'DUTY_UNPAID') return '加班，义务加班不计薪';
      return '加班';
    case 'LEAVE':
      return `请${leaveTypeLabel(document.leaveType)}`;
    case 'TIME_OFF':
      return '使用调休';
    case 'LEAVE_REVOCATION':
      return '销假，撤销原请假';
    case 'OUTING':
      return '因公/因私外出';
    case 'TRIP':
    case 'TRAVEL':
      return '出差';
    case 'PUNCH_CORRECTION':
    case 'CORRECTION':
      return '补签打卡';
    case 'EXEMPT_PUNCH':
      return '免打卡';
    default:
      return documentTypeLabel(document.documentType);
  }
}

function formatHours(document: OaDocumentView): string {
  const minutes = document.recognizedWorkMinutes
    ?? document.payrollCreditMinutes
    ?? document.timeOffCreditMinutes;
  if (minutes == null || minutes <= 0) return '—';
  const hours = minutes / 60;
  return Number.isInteger(hours) ? String(hours) : hours.toFixed(1);
}

function formatTimestamp(value: string): string {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return '—';
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'Asia/Shanghai',
  }).format(timestamp);
}

export default OaSourcesPage;
