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
            <p>{entry.time} · {entry.result}</p>
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
  if (['ACTIVE', 'PUBLISHED', 'VALIDATED', 'SUCCESS'].includes(status)) return 'success';
  if (['DRAFT', 'RESET_PENDING', 'FIRST_CHANGE_REQUIRED'].includes(status)) return 'warning';
  if (['LOCKED', 'FAILED', 'INVALID'].includes(status)) return 'danger';
  if (['REVOKED', 'DISABLED', 'INACTIVE', 'ROLLED_BACK'].includes(status)) return 'neutral';
  return 'info';
}

function statusLabel(status: string): string {
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
  } as Record<string, string>)[status] ?? status;
}
