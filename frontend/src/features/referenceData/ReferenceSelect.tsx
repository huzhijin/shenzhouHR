import { Select } from 'antd';
import type { CSSProperties, ReactNode } from 'react';

import type { ReferenceOption } from './referenceDataLabels';
import type { ReferenceOptionsState } from './useReferenceOptions';

export interface BusinessSelectProps {
  // 表单值保留内部主键用于接口提交；界面必须始终通过业务目录解析名称，
  // 目录暂不可用时也不能把原始主键回显给使用者。
  value?: string;
  onChange?: (value: string | undefined) => void;
  disabled?: boolean;
  allowClear?: boolean;
  placeholder?: string;
  selectedLabel?: string;
  id?: string;
  className?: string;
  style?: CSSProperties;
  size?: 'small' | 'middle' | 'large';
  status?: 'error' | 'warning';
  'aria-label'?: string;
  'aria-describedby'?: string;
}

interface ReferenceSelectProps extends BusinessSelectProps {
  state: ReferenceOptionsState;
  remoteSearch?: boolean;
  onSearch?: (value: string) => void;
  emptyText?: string;
  fallbackSelectedText?: string;
}

export function ReferenceSelect({
  state,
  remoteSearch = false,
  onSearch,
  emptyText = '暂无可选项',
  fallbackSelectedText = '已选业务对象',
  selectedLabel,
  value,
  onChange,
  style,
  ...props
}: ReferenceSelectProps) {
  const options = withSelectedOption(state.options, value, selectedLabel);
  return (
    <Select<string>
      {...props}
      value={value}
      onChange={onChange}
      options={options}
      loading={state.status === 'loading'}
      showSearch
      filterOption={remoteSearch ? false : filterReferenceOption}
      onSearch={onSearch}
      optionFilterProp="searchText"
      notFoundContent={notFoundContent(state.status, emptyText)}
      labelRender={({ label }) => usableLabel(label, value)
        ? label
        : fallbackSelectedText}
      style={{ width: '100%', ...style }}
    />
  );
}

function withSelectedOption(
  options: ReferenceOption[],
  value?: string,
  selectedLabel?: string,
): ReferenceOption[] {
  if (
    !value
    || !selectedLabel?.trim()
    || options.some((option) => option.value === value)
  ) {
    return options;
  }
  return [
    ...options,
    {
      value,
      label: selectedLabel.trim(),
      searchText: selectedLabel.trim(),
    },
  ];
}

function usableLabel(label: ReactNode, value?: string): boolean {
  if (label === undefined || label === null || label === '') return false;
  return String(label) !== value;
}

function filterReferenceOption(
  input: string,
  option?: { searchText?: string; label?: ReactNode },
): boolean {
  const normalized = input.trim().toLocaleLowerCase('zh-CN');
  if (!normalized) return true;
  return String(option?.searchText ?? option?.label ?? '')
    .toLocaleLowerCase('zh-CN')
    .includes(normalized);
}

function notFoundContent(
  status: ReferenceOptionsState['status'],
  emptyText: string,
): ReactNode {
  if (status === 'loading') {
    return <span role="status">正在加载…</span>;
  }
  if (status === 'error') {
    return <span role="alert">加载失败，请稍后重试</span>;
  }
  return emptyText;
}
