import type { ColumnsType } from 'antd/es/table';
import type { ReactNode } from 'react';

export interface DataColumnDefinition<T> {
  key: string;
  title: string;
  render: (row: T) => ReactNode;
}

export function toAntdColumns<T extends object>(
  columns: Array<DataColumnDefinition<T>>,
): ColumnsType<T> {
  return columns.map((column) => ({
    key: column.key,
    title: column.title,
    render: (_value: unknown, row: T) => column.render(row),
  }));
}
