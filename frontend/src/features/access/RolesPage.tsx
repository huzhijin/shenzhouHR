import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';

import { PageHeader } from '../../shared/components/PagePrimitives';
import { StatePanel } from '../../shared/components/StatePanel';
import { useAsyncResource } from '../../shared/hooks/useAsyncResource';
import { PermissionMatrix } from './AccessComponents';
import { listRoles } from './accessApi';

export function RolesPage() {
  const { t } = useTranslation();
  const loader = useMemo(() => () => listRoles(), []);
  const { resource, reload } = useAsyncResource(loader, (roles) => roles.length === 0);
  return (
    <>
      <PageHeader title={t('access.roles')} description={t('access.rolesDescription')} breadcrumbs={[{ label: t('access.section') }, { label: t('access.roles') }]} />
      <section className="content-surface">
        {resource.status === 'loading' || resource.status === 'partial-loading' ? <StatePanel state={resource.status} /> : null}
        {resource.status === 'empty' ? <StatePanel state="empty" description={t('access.noRoles')} /> : null}
        {'error' in resource ? <StatePanel state={resource.status} description={resource.error.message} onRetry={reload} /> : null}
        {resource.status === 'ready' ? <PermissionMatrix roles={resource.data} selectedRoleIds={Array.from(resource.data, (role) => role.roleId)} readOnly /> : null}
      </section>
    </>
  );
}

export default RolesPage;
