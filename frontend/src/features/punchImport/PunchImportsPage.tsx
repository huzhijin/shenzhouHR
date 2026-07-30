import { IconDownload, IconUpload } from '@tabler/icons-react';
import { Alert, Card, Form, Input, Select, Upload, message } from 'antd';
import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';

import { AccessibleButton } from '../../shared/components/AccessibleButton';
import { DataTable, type DataColumn } from '../../shared/components/DataTable';
import { StatusBadge } from '../../shared/components/FeedbackComponents';
import { PageHeader, ResourcePagination } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { isDemoMode } from '../../shared/config/runtimeMode';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import {
  downloadPunchTemplate,
  listPunchImports,
  uploadPunchImport,
} from './punchImportApi';
import type { PunchImportBatchView } from './punchImportTypes';

interface UploadFields {
  companyId: string;
  sourceId: string;
  reason: string;
}

export function PunchImportsPage({ capabilities }: { capabilities: string[] }) {
  const demoMode = isDemoMode();
  const navigate = useNavigate();
  const [messageApi, messageContextHolder] = message.useMessage();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [file, setFile] = useState<File>();
  const [uploading, setUploading] = useState(false);
  const [form] = Form.useForm<UploadFields>();
  const loader = useMemo(() => () => listPunchImports(page, size), [page, size]);
  const imports = useAsyncResource(
    loader,
    (result) => result.totalElements === 0,
    [page, size],
  );
  const canDownload = capabilities.includes('ATTENDANCE_PUNCH_IMPORT:TEMPLATE_DOWNLOAD');
  const canUpload = capabilities.includes('ATTENDANCE_PUNCH_IMPORT:UPLOAD');

  const downloadTemplate = async () => {
    try {
      await downloadPunchTemplate();
      void messageApi.success('模板下载已开始。');
    } catch {
      void messageApi.error('模板下载失败，请稍后重试。');
    }
  };

  const submitUpload = async (values: UploadFields) => {
    if (!file) {
      void messageApi.error('请选择一个 .xlsx 文件。');
      return;
    }
    if (file.size > 20 * 1024 * 1024) {
      void messageApi.error('文件不能超过 20 兆字节。');
      return;
    }
    setUploading(true);
    try {
      const batch = await uploadPunchImport(
        file,
        values.companyId,
        values.sourceId,
        values.reason,
      );
      void messageApi.success('文件已创建为草稿；服务端仍会执行完整安全校验。');
      navigate(`/sources/attendance-excel/${encodeURIComponent(batch.batchId)}`);
    } catch {
      void messageApi.error('上传未提交，请检查文件策略和当前权限。');
    } finally {
      setUploading(false);
    }
  };

  return (
    <>
      {messageContextHolder}
      <PageHeader
        title="外部考勤电子表格导入"
        description="导入原始打卡事实，不导入迟到、旷工、认可加班或任何计算结果。服务端逐行预检后才允许发布。"
        breadcrumbs={[{ label: '考勤来源' }, { label: '电子表格导入' }]}
        actions={canDownload ? (
          <AccessibleButton
            label="下载六工作表电子表格模板"
            icon={<IconDownload aria-hidden="true" stroke={2} />}
            onClick={() => void downloadTemplate()}
          >
            下载模板
          </AccessibleButton>
        ) : undefined}
      />
      <Alert
        showIcon
        type="info"
        title="安全文件策略"
        description="仅接受 .xlsx，最大 20 兆字节、50,000 数据行；宏、公式、外部链接、嵌入对象、危险压缩包和结果列会被拒绝。"
      />
      {canUpload ? (
        <Card className="content-card" title="新建导入批次">
          <Form
            form={form}
            layout="vertical"
            initialValues={demoMode ? {
              companyId: 'LEGAL-JIANGSU',
              sourceId: 'SRC-XLS-OFFLINE-A',
              reason: '客户演示导入',
            } : undefined}
            onFinish={(values) => void submitUpload(values)}
          >
            <div className="form-grid">
              <Form.Item
                label="公司"
                name="companyId"
                rules={[{ required: true, message: '请选择公司' }]}
              >
                {demoMode ? (
                  <Select
                    options={[
                      {
                        value: 'LEGAL-JIANGSU',
                        label: '江苏神州半导体科技有限公司',
                      },
                    ]}
                  />
                ) : <Input autoComplete="off" placeholder="请输入公司 ID" />}
              </Form.Item>
              <Form.Item
                label="文件来源"
                name="sourceId"
                rules={[{ required: true, message: '请选择文件来源' }]}
              >
                {demoMode ? (
                  <Select
                    options={[
                      {
                        value: 'SRC-XLS-OFFLINE-A',
                        label: '离线考勤文件（一号厂区）',
                      },
                    ]}
                  />
                ) : <Input autoComplete="off" placeholder="请输入文件来源 ID" />}
              </Form.Item>
              <Form.Item
                label="变更原因"
                name="reason"
                rules={[{ required: true, min: 2, message: '请输入至少 2 个字符的原因' }]}
              >
                <Input autoComplete="off" />
              </Form.Item>
            </div>
            <Upload
              accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
              maxCount={1}
              beforeUpload={(selected) => {
                setFile(selected);
                return false;
              }}
              onRemove={() => {
                setFile(undefined);
              }}
            >
              <AccessibleButton
                label="选择电子表格文件"
                icon={<IconUpload aria-hidden="true" stroke={2} />}
              >
                选择文件
              </AccessibleButton>
            </Upload>
            <AccessibleButton
              type="primary"
              htmlType="submit"
              loading={uploading}
              label="上传并创建草稿"
              icon={<IconUpload aria-hidden="true" stroke={2} />}
            >
              上传并创建草稿
            </AccessibleButton>
          </Form>
        </Card>
      ) : null}
      {imports.resource.status === 'ready' ? (
        <>
          <Card className="content-card" title="导入批次">
            <BatchTable batches={imports.resource.data.items} />
          </Card>
          <ResourcePagination
            ariaLabel="考勤电子表格导入批次分页"
            page={page}
            pageSize={size}
            total={imports.resource.data.totalElements}
            onChange={(nextPage, nextSize) => {
              setPage(nextPage);
              setSize(nextSize);
            }}
          />
        </>
      ) : (
        <ImportState resource={imports.resource} onRetry={imports.reload} />
      )}
    </>
  );
}

function BatchTable({ batches }: { batches: PunchImportBatchView[] }) {
  const columns: Array<DataColumn<PunchImportBatchView>> = [
    {
      key: 'file',
      title: '文件',
      render: (batch) => (
        <Link to={`/sources/attendance-excel/${encodeURIComponent(batch.batchId)}`}>
          {batch.originalFilename}
        </Link>
      ),
    },
    { key: 'state', title: '状态', render: (batch) => <StatusBadge status={batch.state} /> },
    { key: 'rows', title: '总行数', render: (batch) => String(batch.totalRows) },
    { key: 'valid', title: '可发布', render: (batch) => String(batch.validRows) },
    { key: 'invalid', title: '阻断', render: (batch) => String(batch.invalidRows) },
    { key: 'affected', title: '影响员工', render: (batch) => String(batch.affectedEmployees) },
    { key: 'created', title: '创建时间', render: (batch) => formatDateTime(batch.createdAt) },
  ];
  return (
    <DataTable
      rows={batches}
      rowKey={(batch) => batch.batchId}
      columns={columns}
      ariaLabel="考勤电子表格导入批次与服务端状态"
    />
  );
}

function ImportState({
  resource,
  onRetry,
}: {
  resource: ReturnType<typeof useAsyncResource<unknown>>['resource'];
  onRetry: () => void;
}) {
  if (resource.status === 'empty') {
    return <StatePanel state="empty" description="当前作用域内还没有考勤电子表格导入批次。" />;
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

function formatDateTime(value: string): string {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return value;
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'Asia/Shanghai',
  }).format(timestamp);
}

export default PunchImportsPage;
