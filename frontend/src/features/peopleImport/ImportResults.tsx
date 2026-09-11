import { IconDownload } from '@tabler/icons-react';
import { Pagination, Select } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { ApiRequestError } from '../../shared/api/apiClient';
import { AccessibleButton } from '../../shared/components/AccessibleButton';
import { DataTable } from '../../shared/components/DataTable';
import { StatePanel } from '../../shared/components/StatePanel';
import { ApiErrorState } from '../people/PeopleCommon';
import {
  downloadPeopleImportErrorReport,
  listPeopleImportDiff,
  listPeopleImportErrors,
} from './peopleImportApi';
import {
  formatPeopleImportValues,
  peopleImportFieldLabel,
  peopleImportIssueDescription,
} from './importDisplay';
import type {
  PeopleImportDiffCategory,
  PeopleImportDiffPage,
  PeopleImportIssuePage,
} from './peopleImportTypes';
import { saveDownloadedFile } from '../../shared/api/apiClient';

type ResultState =
  | { status: 'loading' }
  | { status: 'ready'; diff: PeopleImportDiffPage; issues: PeopleImportIssuePage }
  | { status: 'error'; error: ApiRequestError };

const categories: PeopleImportDiffCategory[] = [
  'ADDED',
  'UPDATED',
  'UNCHANGED',
  'CONFLICT',
  'ERROR',
];

export function ImportResults({ batchId, canDownloadReport }: {
  batchId: string;
  canDownloadReport: boolean;
}) {
  const { t } = useTranslation();
  const [category, setCategory] = useState<PeopleImportDiffCategory>();
  const [page, setPage] = useState(0);
  const [issuePage, setIssuePage] = useState(0);
  const [state, setState] = useState<ResultState>({ status: 'loading' });
  const [downloadError, setDownloadError] = useState<ApiRequestError>();

  const load = useCallback(() => {
    setState({ status: 'loading' });
    void Promise.all([
      listPeopleImportDiff(batchId, category, page, 20),
      listPeopleImportErrors(batchId, issuePage, 20),
    ]).then(([diff, issues]) => {
      setState({ status: 'ready', diff, issues });
    }).catch((caught: unknown) => {
      setState({
        status: 'error',
        error: caught instanceof ApiRequestError
          ? caught
          : new ApiRequestError(0, { code: 'PEOPLE_IMPORT_RESULTS_UNAVAILABLE' }),
      });
    });
  }, [batchId, category, issuePage, page]);

  useEffect(() => {
    load();
  }, [load]);

  const downloadReport = async () => {
    setDownloadError(undefined);
    try {
      saveDownloadedFile(await downloadPeopleImportErrorReport(batchId));
    } catch (caught: unknown) {
      setDownloadError(caught instanceof ApiRequestError
        ? caught
        : new ApiRequestError(0, { code: 'PEOPLE_IMPORT_REPORT_UNAVAILABLE' }));
    }
  };

  return (
    <div className="import-results">
      <div className="section-heading">
        <div>
          <h3>{t('peopleImport.diffTitle')}</h3>
          <p>{t('peopleImport.diffDescription')}</p>
        </div>
        <div className="button-row">
          <Select
            allowClear
            aria-label={t('peopleImport.diffCategory')}
            placeholder={t('peopleImport.allCategories')}
            value={category}
            onChange={(value) => {
              setCategory(value);
              setPage(0);
            }}
            options={Array.from(categories, (value) => ({
              value,
              label: t(`peopleImport.category.${value}`),
            }))}
          />
          <AccessibleButton
            label={t('peopleImport.downloadErrorReport')}
            icon={<IconDownload aria-hidden="true" stroke={2} />}
            disabled={!canDownloadReport}
            onClick={() => void downloadReport()}
          >
            {t('peopleImport.downloadErrorReport')}
          </AccessibleButton>
        </div>
      </div>
      {downloadError ? <ApiErrorState error={downloadError} /> : null}
      {state.status === 'loading' ? <StatePanel state="loading" /> : null}
      {state.status === 'error' ? <ApiErrorState error={state.error} onRetry={load} /> : null}
      {state.status === 'ready' && state.diff.items.length === 0 ? (
        <StatePanel state="empty" description={t('peopleImport.noDiffRows')} />
      ) : null}
      {state.status === 'ready' && state.diff.items.length > 0 ? (
        <>
          <DataTable
            rows={state.diff.items}
            rowKey={(row) => row.diffId}
            columns={[
              { key: 'row', title: t('peopleImport.rowNumber'), render: (row) => row.rowNumber },
              { key: 'entity', title: t('peopleImport.entityType'), render: (row) => t(`peopleImport.type.${row.entityType}`) },
              { key: 'category', title: t('peopleImport.diffCategory'), render: (row) => t(`peopleImport.category.${row.category}`) },
              { key: 'source', title: t('peopleImport.sourceValues'), render: (row) => formatPeopleImportValues(row.sourceValues) },
              { key: 'current', title: t('peopleImport.currentValues'), render: (row) => formatPeopleImportValues(row.currentValues) },
              { key: 'proposed', title: t('peopleImport.proposedValues'), render: (row) => formatPeopleImportValues(row.proposedValues) },
            ]}
          />
          <Pagination
            className="people-pagination"
            current={state.diff.page + 1}
            pageSize={state.diff.size}
            total={state.diff.total}
            showSizeChanger={false}
            onChange={(next) => setPage(next - 1)}
          />
        </>
      ) : null}
      {state.status === 'ready' && state.issues.items.length > 0 ? (
        <section className="section-spaced" aria-labelledby="people-import-issues-title">
          <h3 id="people-import-issues-title">{t('peopleImport.issueTitle')}</h3>
          <DataTable
            rows={state.issues.items}
            rowKey={(row) => row.issueId}
            columns={[
              { key: 'row', title: t('peopleImport.rowNumber'), render: (row) => row.rowNumber },
              { key: 'field', title: t('peopleImport.field'), render: (row) => peopleImportFieldLabel(row.field) },
              { key: 'severity', title: t('peopleImport.severity'), render: (row) => t(`peopleImport.severity.${row.severity}`) },
              {
                key: 'message',
                title: t('peopleImport.errorMessage'),
                render: (row) => peopleImportIssueDescription(row.code, row.message, row.field),
              },
            ]}
          />
          <Pagination
            className="people-pagination"
            current={state.issues.page + 1}
            pageSize={state.issues.size}
            total={state.issues.total}
            showSizeChanger={false}
            onChange={(next) => setIssuePage(next - 1)}
          />
        </section>
      ) : null}
    </div>
  );
}
