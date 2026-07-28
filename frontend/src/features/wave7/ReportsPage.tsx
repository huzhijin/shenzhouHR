import { Button, Input } from 'antd';
import { useState } from 'react';

import { ConfirmationDialog, StatusBadge } from '../../shared/components/FeedbackComponents';
import { DataTable, type DataColumn } from '../../shared/components/DataTable';
import { PageHeader } from '../../shared/components/PagePrimitives';
import { wave7ProjectionGateway } from '../../shared/runtime/wave7ProjectionGateway';
import type {
  ReportColumnKey,
  ReportExportProjection,
  ReportExportRequest,
  ReportProjection,
  ReportRowProjection,
} from './wave7Contracts';
import type { Wave7ProjectionGateway } from './wave7Gateway';
import {
  formatDateTime,
  FrozenHistoryNotice,
  LockedActionReason,
  ProjectionMetadata,
  Wave7AsyncBoundary,
} from './Wave7Common';

export function ReportsRoute({
  capabilities = [],
  gateway = wave7ProjectionGateway,
}: {
  capabilities?: readonly string[];
  gateway?: Wave7ProjectionGateway;
}) {
  return (
    <Wave7AsyncBoundary loader={gateway.loadReport} isEmpty={(value) => value.rows.length === 0}>
      {(projection) => (
        <ReportView
          projection={projection}
          canCreateExport={capabilities.includes('ATTENDANCE_REPORT:EXPORT_CREATE')}
        />
      )}
    </Wave7AsyncBoundary>
  );
}

export function ReportView({
  projection,
  canCreateExport,
  onCreateExport,
}: {
  projection: ReportProjection;
  canCreateExport: boolean;
  onCreateExport?: (request: ReportExportRequest) => void;
}) {
  const [exportOpen, setExportOpen] = useState(false);
  const [purpose, setPurpose] = useState('');
  const [purposeError, setPurposeError] = useState<string>();
  const projectionAllowsExport = projection.metadata.allowedActions.includes('REPORT_EXPORT_CREATE');
  const exportEnabled = canCreateExport && projectionAllowsExport && onCreateExport !== undefined;
  const reportColumns: Array<DataColumn<ReportRowProjection>> = projection.columns.map((column) => ({
    key: column.key,
    title: column.label,
    render: (row) => row.values[column.key] ?? '—',
  }));

  const confirmExport = () => {
    if (!purpose.trim()) {
      setPurposeError('请填写导出用途。');
      return;
    }
    onCreateExport?.(createReportExportRequest(
      projection,
      projection.exportFieldAllowlist,
      purpose,
    ));
    setExportOpen(false);
    setPurpose('');
    setPurposeError(undefined);
  };

  return (
    <>
      <PageHeader
        title={projection.reportTitle}
        description="汇总、明细和导出固定使用当前范围、筛选与版本。"
        actions={(
          <Button type="primary" disabled={!exportEnabled} onClick={() => setExportOpen(true)}>
            创建受控导出
          </Button>
        )}
      />
      <ProjectionMetadata metadata={projection.metadata} />
      <FrozenHistoryNotice metadata={projection.metadata} />
      {!canCreateExport || !projectionAllowsExport
        ? <LockedActionReason>当前仅可查看报表，不能创建导出。</LockedActionReason>
        : null}
      <section className="wave7-report-binding" aria-label="报表查询绑定">
        <dl>
          <div><dt>筛选期间</dt><dd>{projection.filters.period}</dd></div>
          <div><dt>状态</dt><dd>{projection.filters.status ?? '全部'}</dd></div>
          <div><dt>查询指纹</dt><dd><code>{projection.queryFingerprint}</code></dd></div>
          <div><dt>授权行数</dt><dd>{projection.rowCount}</dd></div>
        </dl>
      </section>
      <section className="content-surface" aria-labelledby="wave7-report-table-heading">
        <h2 id="wave7-report-table-heading">同版本汇总明细</h2>
        <DataTable
          ariaLabel={projection.reportTitle}
          rows={projection.rows}
          rowKey={(row) => row.rowReference}
          columns={reportColumns}
        />
      </section>
      <ConfirmationDialog
        open={exportOpen}
        title="确认创建受控导出"
        confirmText="创建导出"
        onConfirm={confirmExport}
        onCancel={() => {
          setExportOpen(false);
          setPurposeError(undefined);
        }}
        description={(
          <div className="wave7-export-confirmation">
            <p>导出将固定使用当前范围、筛选、字段白名单和投影版本，并记录审计。</p>
            <dl>
              <div><dt>范围</dt><dd>{projection.metadata.scope.label}</dd></div>
              <div><dt>版本</dt><dd><code>{projection.metadata.projectionVersion}</code></dd></div>
              <div><dt>字段数</dt><dd>{projection.exportFieldAllowlist.length}</dd></div>
            </dl>
            <label htmlFor="wave7-export-purpose">导出用途</label>
            <Input.TextArea
              id="wave7-export-purpose"
              value={purpose}
              maxLength={200}
              aria-describedby="wave7-export-purpose-help"
              aria-invalid={purposeError ? 'true' : undefined}
              onChange={(event) => {
                setPurpose(event.target.value);
                if (purposeError) setPurposeError(undefined);
              }}
            />
            <p id="wave7-export-purpose-help">请说明复核或业务用途；不能为空。</p>
            {purposeError ? <p role="alert">{purposeError}</p> : null}
          </div>
        )}
      />
    </>
  );
}

export function ReportExportStatus({
  job,
  canDownload,
  onDownload,
}: {
  job: ReportExportProjection;
  canDownload: boolean;
  onDownload?: (exportReference: string) => void;
}) {
  const downloadEnabled = job.state === 'READY'
    && job.canDownload
    && canDownload
    && onDownload !== undefined;
  return (
    <section className="content-surface wave7-export-status" aria-labelledby="wave7-export-status-heading" aria-live="polite">
      <header>
        <h2 id="wave7-export-status-heading">导出任务</h2>
        <StatusBadge status={job.state} />
      </header>
      <dl className="wave7-detail-grid">
        <div><dt>交付方式</dt><dd>{job.delivery === 'SYNCHRONOUS' ? '同步' : '异步'}</dd></div>
        <div><dt>用途</dt><dd>{job.purpose}</dd></div>
        <div><dt>申请人</dt><dd>{job.requesterLabel}</dd></div>
        <div><dt>创建时间</dt><dd>{formatDateTime(job.createdAt)}</dd></div>
        <div><dt>审计引用</dt><dd><code>{job.auditReference}</code></dd></div>
        <div><dt>任务引用</dt><dd><code>{job.exportReference}</code></dd></div>
      </dl>
      <Button
        disabled={!downloadEnabled}
        onClick={() => onDownload?.(job.exportReference)}
      >
        下载文件
      </Button>
    </section>
  );
}

export function createReportExportRequest(
  projection: ReportProjection,
  selectedFields: ReportColumnKey[],
  purpose: string,
): ReportExportRequest {
  const normalizedPurpose = purpose.trim();
  if (!normalizedPurpose) {
    throw new TypeError('导出用途不能为空');
  }
  const allowlist = new Set(projection.exportFieldAllowlist);
  if (!selectedFields.every((field) => allowlist.has(field))) {
    throw new TypeError('导出字段超出当前报表白名单');
  }
  return {
    queryFingerprint: projection.queryFingerprint,
    projectionVersion: projection.metadata.projectionVersion,
    scopeReference: projection.metadata.scope.reference,
    filters: projection.filters,
    selectedFields: [...selectedFields],
    purpose: normalizedPurpose,
  };
}

export function exportDeliveryForRowCount(
  rowCount: number,
): ReportExportProjection['delivery'] {
  if (!Number.isInteger(rowCount) || rowCount < 0) {
    throw new TypeError('导出行数必须为非负整数');
  }
  return rowCount <= 50_000 ? 'SYNCHRONOUS' : 'ASYNCHRONOUS';
}

export default ReportsRoute;
