import { Table } from 'antd';
import type { ReactNode } from 'react';

import {
  toAntdColumns,
  type DataColumnDefinition,
} from './dataTableColumns';

export type DataColumn<T> = DataColumnDefinition<T>;

export function DataTable<T extends object>({ rows, rowKey, columns, loading = false, ariaLabel }: {
  rows: T[];
  rowKey: (row: T) => string;
  columns: Array<DataColumn<T>>;
  loading?: boolean;
  ariaLabel?: string;
}) {
  const antdColumns = toAntdColumns(columns);
  const mobileRecords: ReactNode[] = rows.map((row) => (
    <article className="record-card" key={rowKey(row)}>
      {columns.map((column) => (
        <div className="record-card__field" data-label={column.title} key={column.key}>
          <span className="record-card__label">{column.title}</span>
          <span>{column.render(row)}</span>
        </div>
      ))}
    </article>
  ));
  const tableLabel = ariaLabel
    ?? columns.reduce((label, column, index) => (
      `${label}${index === 0 ? '' : '、'}${column.title}`
    ), '');
  return (
    <div className="responsive-data">
      <div
        className="data-table-scroll"
        role="region"
        aria-label={tableLabel}
        tabIndex={0}
      >
        <Table<T>
          rowKey={rowKey}
          columns={antdColumns}
          dataSource={rows}
          pagination={false}
          loading={loading}
          size="small"
        />
      </div>
      <div className="mobile-card-list">
        {mobileRecords}
      </div>
    </div>
  );
}
