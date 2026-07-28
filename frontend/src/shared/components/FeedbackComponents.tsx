import { IconCopy } from '@tabler/icons-react';
import { Alert, Button, Descriptions, Drawer, Modal, Tag, Timeline, message } from 'antd';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import i18n from '../i18n/i18n';

export function StatusBadge({ status }: { status: string }) {
  const tone = statusTone(status);
  return <Tag className={`status-badge status-badge--${tone}`}>{statusLabel(status)}</Tag>;
}

export function ConfirmationDialog({ open, title, description, confirmText = i18n.t('common.confirm'), danger = false, processing = false, onConfirm, onCancel }: {
  open: boolean;
  title: string;
  description: ReactNode;
  confirmText?: string;
  danger?: boolean;
  processing?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <Modal
      open={open}
      title={title}
      okText={confirmText}
      cancelText={i18n.t('common.cancel')}
      okButtonProps={{ danger, loading: processing }}
      onOk={onConfirm}
      onCancel={onCancel}
      destroyOnHidden
    >
      <div aria-live="polite">{description}</div>
    </Modal>
  );
}

export function DetailDrawer({ open, title, children, onClose }: {
  open: boolean;
  title: string;
  children: ReactNode;
  onClose: () => void;
}) {
  return (
    <Drawer open={open} title={title} size="var(--size-drawer)" onClose={onClose}>
      {children}
    </Drawer>
  );
}

export function CorrelationIdDisplay({ correlationId }: { correlationId?: string }) {
  const { t } = useTranslation();
  if (!correlationId) return null;
  const copy = () => {
    void navigator.clipboard?.writeText(correlationId);
    void message.success(t('common.correlationCopied'));
  };
  return (
    <span className="correlation-id">
      {t('common.correlation')}<code>{correlationId}</code>
      <Button type="text" size="small" aria-label={t('common.copyCorrelation')} icon={<IconCopy stroke={2} />} onClick={copy} />
    </span>
  );
}

export function AuditTimeline({ entries }: {
  entries: Array<{ title: string; time: string; result: string; detail?: string }>;
}) {
  return (
    <Timeline
      items={entries.map((entry) => ({
        color: entry.result === 'SUCCESS' ? 'green' : 'red',
        content: (
          <div>
            <strong>{entry.title}</strong>
            <p>{entry.time} · {statusLabel(entry.result)}</p>
            {entry.detail ? <p>{entry.detail}</p> : null}
          </div>
        ),
      }))}
    />
  );
}

export function ReadOnlyDetails({ items }: {
  items: Array<{ label: string; value: ReactNode }>;
}) {
  return <Descriptions column={{ xs: 1, sm: 2, lg: 3 }} items={items.map((item, index) => ({ key: index, children: item.value, label: item.label }))} />;
}

export function OperationFeedback({ kind, message: feedbackMessage }: {
  kind: 'success' | 'error' | 'warning' | 'info';
  message: string;
}) {
  return <Alert showIcon type={kind} title={feedbackMessage} role="status" />;
}

function statusTone(status: string): 'success' | 'warning' | 'danger' | 'info' | 'neutral' {
  const normalized = status.trim().toUpperCase();
  if (['ACTIVE', 'PUBLISHED', 'VALIDATED', 'SUCCESS', 'SUCCEEDED', 'RESOLVED', 'APPROVED', 'PASS', 'CLOSED', 'CONFIRMED', 'READY', 'MATCHED', 'COMPLETED', 'COMPLETED_SUCCESS'].includes(normalized)) return 'success';
  if (['DRAFT', 'RESET_PENDING', 'FIRST_CHANGE_REQUIRED', 'AWAITING_CONFIRMATION', 'PARTIALLY_PUBLISHED', 'PARTIALLY_QUARANTINED', 'BLOCKED_BY_FROZEN_PERIOD', 'QUEUED', 'IN_PROGRESS', 'RUNNING', 'VALIDATING', 'PUBLISHING', 'OPEN', 'FROZEN', 'WARNING', 'PENDING', 'PROBATION', 'LEAVE_PENDING', 'PREPARING', 'REOPENED'].includes(normalized)) return 'warning';
  if (['LOCKED', 'FAILED', 'FAILURE', 'DENIED', 'INVALID', 'VALIDATION_FAILED', 'PUBLISH_FAILED', 'REJECTED', 'ERROR', 'CONFLICT', 'BLOCKING', 'AMBIGUOUS', 'OUT_OF_SCOPE'].includes(normalized)) return 'danger';
  if (['REVOKED', 'DISABLED', 'INACTIVE', 'TERMINATED', 'ROLLED_BACK', 'VOIDED', 'CANCELLED', 'EXPIRED', 'UNCHANGED', 'REVERSAL'].includes(normalized)) return 'neutral';
  return 'info';
}

export function statusLabel(status: string): string {
  const normalized = status.trim().toUpperCase();
  return ({
    ACTIVE: i18n.t('status.active'),
    DISABLED: i18n.t('status.disabled'),
    LOCKED: i18n.t('status.locked'),
    DRAFT: i18n.t('status.draft'),
    PUBLISHED: i18n.t('status.published'),
    VALIDATED: i18n.t('status.validated'),
    INACTIVE: i18n.t('status.inactive'),
    ROLLED_BACK: i18n.t('status.rolledBack'),
    REVOKED: i18n.t('status.revoked'),
    EXPIRED: i18n.t('status.expired'),
    SUCCESS: i18n.t('status.success'),
    FAILED: i18n.t('status.failed'),
    FAILURE: '失败',
    DENIED: '已拒绝',
    SUCCEEDED: '已成功',
    QUEUED: '排队中',
    AWAITING_CONFIRMATION: '待确认发布',
    PARTIALLY_QUARANTINED: '部分隔离',
    BLOCKED_BY_FROZEN_PERIOD: '冻结期间阻断',
    VALIDATION_FAILED: '预检失败',
    PARTIALLY_PUBLISHED: '部分发布',
    PUBLISH_FAILED: '发布失败',
    VOIDED: '已作废/冲正',
    RESOLVED: '已解决',
    IN_PROGRESS: '处理中',
    SUBMITTED: '已提交',
    VALIDATING: '正在预检',
    PUBLISHING: '正在发布',
    OPEN: '开放中',
    CLOSED: '已月结',
    FROZEN: '已冻结',
    APPROVED: '已通过',
    REJECTED: '已驳回',
    UNKNOWN: '未识别',
    PASS: '已通过',
    NOT_VERIFIED: '未验证',
    BLOCKING: '阻断',
    WARNING: '警告',
    RUNNING: '运行中',
    CANCELLED: '已取消',
    MODIFIED: '已修改',
    SUPPLEMENTED: '已补充',
    TERMINATED: '已离职',
    PROBATION: '试用期',
    LEAVE_PENDING: '离职办理中',
    CONFIRMED: '已确认',
    PENDING: '待确认',
    CONFLICT: '存在冲突',
    ERROR: '错误',
    ADDED: '新增',
    UPDATED: '更新',
    UNCHANGED: '无变化',
    UPLOADED: '已上传',
    MAPPED: '已完成映射',
    PRECHECKED: '预检完成',
    PREPARING: '准备中',
    READY: '已就绪',
    REOPENED: '已重新开放',
    COMPLETED: '已完成',
    COMPLETED_SUCCESS: '已成功完成',
    MATCHED: '已匹配',
    UNMATCHED: '未匹配',
    AMBIGUOUS: '匹配不唯一',
    OUT_OF_SCOPE: '超出授权范围',
    NONE: '无',
    EXACT: '完全重复',
    NEAR_PENDING: '疑似重复待确认',
    OPENING_IMPORT: '期初导入',
    ADJUSTMENT: '调整',
    REVERSAL: '冲正',
    INITIAL_EXCEL: '期初电子表格',
    LOCAL: '本地维护',
  } as Record<string, string>)[normalized] ?? status;
}
