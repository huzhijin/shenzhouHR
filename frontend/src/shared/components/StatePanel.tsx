import {
  IconAlertCircle,
  IconCircleCheck,
  IconDatabaseOff,
  IconLock,
  IconNetworkOff,
} from '@tabler/icons-react';
import { Alert, Button, Skeleton } from 'antd';
import { useTranslation } from 'react-i18next';

import { translate } from '../i18n/messages';
import i18n from '../i18n/i18n';

interface StatePanelProps {
  state:
    | 'loading'
    | 'partial-loading'
    | 'empty'
    | 'unauthorized'
    | 'error'
    | 'network-error'
    | '401'
    | '403'
    | '404'
    | 'conflict'
    | 'validation-error'
    | 'processing'
    | 'success'
    | 'retry'
    | 'stale';
  title?: string;
  description?: string;
  onRetry?: () => void;
}

const icons = {
  empty: IconDatabaseOff,
  unauthorized: IconLock,
  error: IconAlertCircle,
  'network-error': IconNetworkOff,
  '401': IconLock,
  '403': IconLock,
  '404': IconDatabaseOff,
  conflict: IconAlertCircle,
  'validation-error': IconAlertCircle,
  success: IconCircleCheck,
  retry: IconAlertCircle,
  stale: IconAlertCircle,
};

export function StatePanel({
  state,
  title,
  description,
  onRetry,
}: StatePanelProps) {
  const { t } = useTranslation();
  if (state === 'loading' || state === 'partial-loading' || state === 'processing') {
    return (
      <section
        className="async-state"
        data-state={state}
        aria-busy="true"
        aria-label={translate('state.loading')}
        aria-live="polite"
      >
        <Skeleton active paragraph={{ rows: 6 }} />
      </section>
    );
  }

  const normalizedState = state === '401' || state === '403' ? 'unauthorized' : state;
  const Icon = icons[state] ?? IconAlertCircle;
  return (
    <section className="async-state" data-state={state} aria-live={state === 'error' ? 'assertive' : 'polite'}>
      <div className="state-panel">
        <Icon aria-hidden="true" stroke={2} size="var(--size-icon-lg)" />
        <h2>{title ?? defaultTitle(normalizedState)}</h2>
        {description ? <p>{description}</p> : null}
        {onRetry ? <Button onClick={onRetry}>{translate('state.retry')}</Button> : null}
        {state === 'stale' ? <Alert showIcon type="warning" title={t('state.stale')} /> : null}
      </div>
    </section>
  );
}

function defaultTitle(state: Exclude<StatePanelProps['state'], 'loading' | 'partial-loading' | 'processing'>): string {
  if (state === 'empty') return translate('state.empty');
  if (state === 'unauthorized') return translate('state.unauthorized');
  if (state === '404') return i18n.t('state.notFound');
  if (state === 'conflict') return i18n.t('state.conflict');
  if (state === 'validation-error') return i18n.t('state.validation');
  if (state === 'success') return i18n.t('state.success');
  if (state === 'network-error') return i18n.t('state.networkError');
  return translate('error.requestFailed');
}
