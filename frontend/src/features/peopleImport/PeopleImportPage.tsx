import { IconHistory, IconRefresh } from '@tabler/icons-react';
import { Drawer, Select } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';

import { ApiRequestError } from '../../shared/api/apiClient';
import { AccessibleButton } from '../../shared/components/AccessibleButton';
import { DataTable } from '../../shared/components/DataTable';
import { PageHeader } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { ApiErrorState, formatDateTime, PeopleContextStrip } from '../people/PeopleCommon';
import { importTaskLabel } from './importDisplay';
import { ImportWizard } from './ImportWizard';
import {
  getPeopleImportBatch,
  listPeopleImportBatches,
  listPeopleImportTemplates,
} from './peopleImportApi';
import type {
  PeopleImportBatchDetail,
  PeopleImportBatchSummary,
  PeopleImportTemplateVersion,
} from './peopleImportTypes';

type PageState =
  | { status: 'loading' }
  | {
    status: 'ready';
    templates: PeopleImportTemplateVersion[];
    batches: PeopleImportBatchSummary[];
    batch?: PeopleImportBatchDetail;
  }
  | { status: 'error'; error: ApiRequestError };

export function PeopleImportPage({ capabilities }: { capabilities: string[] }) {
  const { t } = useTranslation();
  const [searchParams, setSearchParams] = useSearchParams();
  const [state, setState] = useState<PageState>({ status: 'loading' });
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyStatus, setHistoryStatus] = useState<string>();

  const load = useCallback(() => {
    setState({ status: 'loading' });
    const batchId = searchParams.get('batch');
    void Promise.all([
      listPeopleImportTemplates(),
      listPeopleImportBatches(),
      batchId ? getPeopleImportBatch(batchId) : Promise.resolve(undefined),
    ]).then(([templates, batches, selected]) => {
      setState({
        status: 'ready',
        templates: templates.items,
        batches: batches.items,
        batch: selected,
      });
    }).catch((caught: unknown) => {
      setState({
        status: 'error',
        error: caught instanceof ApiRequestError
          ? caught
          : new ApiRequestError(0, { code: 'PEOPLE_IMPORT_PAGE_UNAVAILABLE' }),
      });
    });
  }, [searchParams]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    if (state.status !== 'ready' || !state.batch) return undefined;
    if (!['VALIDATING', 'PUBLISHING'].includes(state.batch.status)) return undefined;
    const timer = window.setInterval(() => {
      void getPeopleImportBatch(state.batch?.batchId ?? '').then((batch) => {
        setState((current) => current.status === 'ready' ? { ...current, batch } : current);
      }).catch((caught: unknown) => {
        setState({
          status: 'error',
          error: caught instanceof ApiRequestError
            ? caught
            : new ApiRequestError(0, { code: 'PEOPLE_IMPORT_POLL_UNAVAILABLE' }),
        });
      });
    }, 1000);
    return () => window.clearInterval(timer);
  }, [state.status === 'ready' ? state.batch?.batchId : undefined, state.status === 'ready' ? state.batch?.status : undefined]);

  const updateBatch = (batch: PeopleImportBatchDetail) => {
    setSearchParams({ batch: batch.batchId }, { replace: true });
    setState((current) => current.status === 'ready'
      ? {
        ...current,
        batch,
        batches: [
          batch,
          ...current.batches.filter((item) => item.batchId !== batch.batchId),
        ],
      }
      : current);
  };

  const chooseBatch = (batchId: string) => {
    setHistoryOpen(false);
    setSearchParams({ batch: batchId });
  };

  return (
    <>
      <PageHeader
        title={t('peopleImport.title')}
        description={t('peopleImport.description')}
        breadcrumbs={[
          { label: t('people.section') },
          { label: t('peopleImport.title') },
        ]}
        actions={(
          <>
            <AccessibleButton
              label={t('peopleImport.history')}
              icon={<IconHistory aria-hidden="true" stroke={2} />}
              onClick={() => setHistoryOpen(true)}
            >
              {t('peopleImport.history')}
            </AccessibleButton>
            <AccessibleButton
              label={t('common.refresh')}
              icon={<IconRefresh aria-hidden="true" stroke={2} />}
              onClick={load}
            >
              {t('common.refresh')}
            </AccessibleButton>
          </>
        )}
      />
      {state.status === 'loading' ? <StatePanel state="loading" /> : null}
      {state.status === 'error' ? <ApiErrorState error={state.error} onRetry={load} /> : null}
      {state.status === 'ready' ? (
        <>
          <PeopleContextStrip items={[
            {
              label: t('peopleImport.batchId'),
              value: state.batch
                ? importTaskLabel(state.batch.createdAt, state.batch.file?.originalFileName)
                : t('peopleImport.noActiveBatch'),
            },
            {
              label: t('peopleImport.batchStatus'),
              value: state.batch ? t(`peopleImport.status.${state.batch.status}`) : t('peopleImport.notStarted'),
            },
            {
              label: t('peopleImport.importType'),
              value: state.batch ? t(`peopleImport.type.${state.batch.templateType}`) : t('common.none'),
            },
            {
              label: t('people.dataFreshness'),
              value: state.batch ? formatDateTime(state.batch.updatedAt) : t('common.none'),
            },
          ]} />
          <section className="people-boundary-notice" role="note">
            <strong>{t('peopleImport.boundaryTitle')}</strong>
            <p>{t('peopleImport.boundaryDescription')}</p>
          </section>
          <ImportWizard
            templates={state.templates}
            batch={state.batch}
            capabilities={capabilities}
            onBatchChange={updateBatch}
          />
        </>
      ) : null}
      <Drawer
        title={t('peopleImport.history')}
        size="var(--size-drawer)"
        open={historyOpen}
        onClose={() => setHistoryOpen(false)}
      >
        <Select
          allowClear
          className="history-filter"
          aria-label={t('peopleImport.batchStatus')}
          placeholder={t('peopleImport.allStatuses')}
          value={historyStatus}
          onChange={setHistoryStatus}
          options={Array.from([
            'DRAFT',
            'VALIDATING',
            'VALIDATION_FAILED',
            'AWAITING_CONFIRMATION',
            'PUBLISHING',
            'PUBLISHED',
            'PUBLISH_FAILED',
            'VOIDED',
          ], (value) => ({ value, label: t(`peopleImport.status.${value}`) }))}
        />
        {state.status === 'ready' && state.batches.filter((batch) => (
          !historyStatus || batch.status === historyStatus
        )).length === 0 ? (
          <StatePanel state="empty" description={t('peopleImport.noHistory')} />
        ) : null}
        {state.status === 'ready' && state.batches.length > 0 ? (
          <DataTable
            rows={state.batches.filter((batch) => !historyStatus || batch.status === historyStatus)}
            rowKey={(row) => row.batchId}
            columns={[
              {
                key: 'batch',
                title: t('peopleImport.history'),
                render: (row) => (
                  <AccessibleButton
                    label={`${t('common.view')} ${importTaskLabel(row.createdAt)}`}
                    type="link"
                    onClick={() => chooseBatch(row.batchId)}
                  >
                    {importTaskLabel(row.createdAt)}
                  </AccessibleButton>
                ),
              },
              { key: 'type', title: t('peopleImport.importType'), render: (row) => t(`peopleImport.type.${row.templateType}`) },
              { key: 'status', title: t('peopleImport.batchStatus'), render: (row) => t(`peopleImport.status.${row.status}`) },
              { key: 'time', title: t('people.changedAt'), render: (row) => formatDateTime(row.updatedAt) },
            ]}
          />
        ) : null}
      </Drawer>
    </>
  );
}

export default PeopleImportPage;
