import { Select } from 'antd';

import type {
  GrantableCompany,
  GrantableOrganization,
} from './accessApi';

interface CompanySelectProps {
  companies: readonly GrantableCompany[];
  value?: string;
  onChange?: (value: string | undefined) => void;
  disabled?: boolean;
  'aria-label'?: string;
}

export function GrantableCompanySelect({
  companies,
  ...props
}: CompanySelectProps) {
  return (
    <Select<string>
      {...props}
      allowClear
      showSearch
      optionFilterProp="searchText"
      placeholder="请先选择公司"
      options={companies.map(companyOption)}
      style={{ width: '100%' }}
    />
  );
}

export function GrantableCompanyMultiSelect({
  companies,
  value,
  onChange,
  'aria-label': ariaLabel,
}: {
  companies: readonly GrantableCompany[];
  value?: string[];
  onChange?: (value: string[]) => void;
  'aria-label'?: string;
}) {
  return (
    <Select<string[]>
      mode="multiple"
      allowClear
      showSearch
      aria-label={ariaLabel}
      value={value}
      onChange={onChange}
      optionFilterProp="searchText"
      placeholder="勾选可查看的公司"
      options={companies.map(companyOption)}
      maxTagCount="responsive"
      style={{ width: '100%' }}
    />
  );
}

export function GrantableOrganizationSelect({
  organizations,
  companySelected,
  ...props
}: {
  organizations: readonly GrantableOrganization[];
  companySelected: boolean;
  value?: string;
  onChange?: (value: string | undefined) => void;
  disabled?: boolean;
  'aria-label'?: string;
}) {
  return (
    <Select<string>
      {...props}
      allowClear
      showSearch
      disabled={props.disabled || !companySelected}
      optionFilterProp="searchText"
      placeholder={companySelected ? '请选择部门' : '请先选择公司'}
      options={grantableOrganizationOptions(organizations)}
      style={{ width: '100%' }}
    />
  );
}

export function grantableOrganizationOptions(
  organizations: readonly GrantableOrganization[],
) {
  const byId = new Map(organizations.map((organization) => (
    [organization.organizationId, organization] as const
  )));
  const pathCache = new Map<string, string[]>();
  const pathFor = (
    organization: GrantableOrganization,
    visiting = new Set<string>(),
  ): string[] => {
    const cached = pathCache.get(organization.organizationId);
    if (cached) return cached;
    if (visiting.has(organization.organizationId)) return [organization.name];
    const nextVisiting = new Set(visiting).add(organization.organizationId);
    const parent = organization.parentOrganizationId
      ? byId.get(organization.parentOrganizationId)
      : undefined;
    const path = parent
      ? [...pathFor(parent, nextVisiting), organization.name]
      : [organization.name];
    pathCache.set(organization.organizationId, path);
    return path;
  };

  return organizations.map((organization) => {
    const path = pathFor(organization).join(' / ');
    const descendantHint = organization.canIncludeDescendants
      ? ''
      : ' · 仅本部门';
    return {
      value: organization.organizationId,
      label: `${path}（${organization.code}）${descendantHint}`,
      searchText: `${path} ${organization.code}${descendantHint}`,
    };
  });
}

function companyOption(company: GrantableCompany) {
  return {
    value: company.companyId,
    label: company.name,
    searchText: `${company.name} ${company.code}`,
  };
}
