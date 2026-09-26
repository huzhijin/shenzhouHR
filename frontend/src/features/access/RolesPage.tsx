import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Collapse } from 'antd';

import { PageHeader } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import {
  PermissionMatrix,
  RoleOverviewCards,
  visibleAccessRoles,
} from './AccessComponents';
import { listRoles } from './accessApi';

export function RolesPage() {
  const { t } = useTranslation();
  const loader = useMemo(() => () => listRoles(), []);
  const { resource, reload } = useAsyncResource(loader, (roles) => roles.length === 0);
  const roles = resource.status === 'ready' ? visibleAccessRoles(resource.data) : [];
  return (
    <>
      <PageHeader title={t('access.roles')} description={t('access.rolesDescription')} breadcrumbs={[{ label: t('access.section') }, { label: t('access.roles') }]} />
      <section className="content-surface">
        {resource.status === 'loading' || resource.status === 'partial-loading' ? <StatePanel state={resource.status} /> : null}
        {resource.status === 'empty' ? <StatePanel state="empty" description={t('access.noRoles')} /> : null}
        {'error' in resource ? <StatePanel state={resource.status} description={resource.error.message} onRetry={reload} /> : null}
        {resource.status === 'ready' && roles.length === 0 ? <StatePanel state="empty" description={t('access.noRoles')} /> : null}
        {resource.status === 'ready' && roles.length > 0 ? (
          <>
            <RoleOverviewCards roles={roles} />
            <Collapse
              className="roles-advanced"
              items={[{
                key: 'permission-matrix',
                label: (
                  <span className="roles-advanced__label">
                    <strong>高级：查看完整权限对照</strong>
                    <span>仅在核对角色细项时需要展开</span>
                  </span>
                ),
                children: (
                  <PermissionMatrix
                    roles={roles}
                    selectedRoleIds={roles.map((role) => role.roleId)}
                    readOnly
                  />
                ),
              }]}
            />
          </>
        ) : null}
      </section>
    </>
  );
}

export default RolesPage;
