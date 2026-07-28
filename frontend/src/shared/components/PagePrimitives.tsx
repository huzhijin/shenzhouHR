import { IconAdjustments } from '@tabler/icons-react';
import { Breadcrumb, Button, Input, Pagination, Select, Space } from 'antd';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

export function Breadcrumbs({ items }: {
  items: Array<{ label: string; path?: string }>;
}) {
  return (
    <Breadcrumb
      className="breadcrumbs"
      items={items.map((item) => ({
        title: item.path ? <Link to={item.path}>{item.label}</Link> : item.label,
      }))}
    />
  );
}

export function PageHeader({ title, description, actions, breadcrumbs }: {
  title: string;
  description?: string;
  actions?: ReactNode;
  breadcrumbs?: Array<{ label: string; path?: string }>;
}) {
  return (
    <>
      {breadcrumbs ? <Breadcrumbs items={breadcrumbs} /> : null}
      <header className="page-header">
        <div>
          <h1>{title}</h1>
          {description ? <p>{description}</p> : null}
        </div>
        {actions ? <PageActionBar>{actions}</PageActionBar> : null}
      </header>
    </>
  );
}

export function PageActionBar({ children }: { children: ReactNode }) {
  return <Space className="page-action-bar" wrap>{children}</Space>;
}

export function ResourcePagination({
  ariaLabel,
  page,
  pageSize,
  total,
  pageSizeOptions = [20, 50, 100],
  onChange,
}: {
  ariaLabel: string;
  page: number;
  pageSize: number;
  total: number;
  pageSizeOptions?: number[];
  onChange: (page: number, pageSize: number) => void;
}) {
  if (total <= pageSize) return null;
  return (
    <nav className="resource-pagination" aria-label={ariaLabel}>
      <Pagination
        current={page + 1}
        pageSize={pageSize}
        total={total}
        pageSizeOptions={pageSizeOptions.map(String)}
        showSizeChanger
        showTotal={(count) => String(count)}
        onChange={(nextPage, nextPageSize) => {
          onChange(nextPageSize === pageSize ? nextPage - 1 : 0, nextPageSize);
        }}
      />
    </nav>
  );
}

export function QueryFilterBar({ query, onQueryChange, placeholder, children, onAdvanced }: {
  query: string;
  onQueryChange: (value: string) => void;
  placeholder: string;
  children?: ReactNode;
  onAdvanced?: () => void;
}) {
  const { t } = useTranslation();
  return (
    <div className="query-filter-bar" role="search">
      <Input.Search
        allowClear
        value={query}
        aria-label={placeholder}
        placeholder={placeholder}
        onChange={(event) => onQueryChange(event.target.value)}
      />
      {children}
      {onAdvanced ? (
        <Button icon={<IconAdjustments aria-hidden="true" stroke={2} />} onClick={onAdvanced}>
          {t('common.advancedFilter')}
        </Button>
      ) : null}
    </div>
  );
}

export function AdvancedFilterPanel({ label, value, options, onChange }: {
  label: string;
  value?: string;
  options: Array<{ label: string; value: string }>;
  onChange: (value?: string) => void;
}) {
  const { t } = useTranslation();
  return (
    <section className="advanced-filter-panel" aria-label={t('common.advancedFilter')}>
      <label htmlFor="advanced-filter-select">{label}</label>
      <Select
        id="advanced-filter-select"
        allowClear
        value={value}
        options={options}
        onChange={onChange}
      />
    </section>
  );
}
