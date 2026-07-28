import { ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';

import { LoginPage } from '../features/auth/LoginPage';
import type { CurrentCapabilities } from '../features/session/sessionApi';
import { useSession } from '../features/session/useSession';
import { AppErrorBoundary } from '../shared/components/AppErrorBoundary';
import { AppShell } from '../shared/components/AppShell';
import { StatePanel } from '../shared/components/StatePanel';
import { translate } from '../shared/i18n/messages';
import { readCspNonce } from '../shared/security/cspNonce';
import { authorizedMenu } from './routeAuthorization';

const OrganizationPage = lazy(() => import('../features/organization/OrganizationPage'));
const EmployeesPage = lazy(() => import('../features/employee/EmployeesPage'));
const EmployeeDetailPage = lazy(() => import('../features/employee/EmployeeDetailPage'));
const PeopleImportPage = lazy(() => import('../features/peopleImport/PeopleImportPage'));
const RulesHomePage = lazy(() => import('../features/policy/RulesHomePage'));
const PolicyTemplatesPage = lazy(() => import('../features/policy/PolicyTemplatesPage'));
const PolicyTemplateDetailPage = lazy(() => import('../features/policy/PolicyTemplateDetailPage'));
const PolicyVersionPage = lazy(() => import('../features/policy/PolicyVersionPage'));
const AccountsPage = lazy(() => import('../features/access/AccountsPage'));
const AccountDetailPage = lazy(() => import('../features/access/AccountDetailPage'));
const RolesPage = lazy(() => import('../features/access/RolesPage'));
const AuditPage = lazy(() => import('../features/audit/AuditPage'));
const AuditDetailPage = lazy(() => import('../features/audit/AuditDetailPage'));
const AttendanceGroupsPage = lazy(() => import('../features/attendanceSetup/AttendanceGroupsPage'));
const ShiftsPage = lazy(() => import('../features/attendanceSetup/ShiftsPage'));
const CalendarsPage = lazy(() => import('../features/attendanceSetup/CalendarsPage'));
const AttendancePolicyPage = lazy(() => import('../features/attendanceSetup/AttendancePolicyPage'));

const theme = {
  token: {
    colorPrimary: 'var(--color-brand-primary)',
    colorPrimaryHover: 'var(--color-brand-support)',
    colorPrimaryActive: 'var(--color-brand-deep)',
    colorLink: 'var(--color-link)',
    colorLinkHover: 'var(--color-link-hover)',
    colorLinkActive: 'var(--color-link-active)',
    colorError: 'var(--color-danger)',
    colorErrorBg: 'var(--color-danger-soft)',
    colorBgLayout: 'var(--color-canvas)',
    colorBgContainer: 'var(--color-surface)',
    colorBgElevated: 'var(--color-surface)',
    colorPrimaryBg: 'var(--color-info-soft)',
    colorPrimaryBgHover: 'var(--color-surface-subtle)',
    colorText: 'var(--color-text-primary)',
    colorTextSecondary: 'var(--color-text-secondary)',
    colorTextDescription: 'var(--color-text-secondary)',
    colorTextTertiary: 'var(--color-text-secondary)',
    colorTextPlaceholder: 'var(--color-text-secondary)',
    colorTextDisabled: 'var(--color-text-disabled)',
    colorBorder: 'var(--color-border)',
    colorBorderSecondary: 'var(--color-border)',
    fontFamily: 'var(--font-ui)',
  },
};

export function App() {
  const { state, reload } = useSession();
  const cspNonce = readCspNonce();

  return (
    <AppErrorBoundary>
      <ConfigProvider
        csp={cspNonce ? { nonce: cspNonce } : undefined}
        locale={zhCN}
        theme={theme}
      >
        {state.status === 'loading' ? <StatePanel state="loading" /> : null}
        {state.status === 'error' ? (
          state.error.status === 401 ? (
            <>
              <Navigate to="/login" replace />
              <LoginPage onAuthenticated={reload} />
            </>
          ) : (
            <StatePanel
              state={state.error.status === 0 ? 'network-error' : 'error'}
              description={state.error.correlationId
                ? `${state.error.message}；${translate('error.correlationId', { correlationId: state.error.correlationId })}`
                : state.error.message}
              onRetry={state.error.retryable ? reload : undefined}
            />
          )
        ) : null}
        {state.status === 'ready' ? (
          state.session.firstPasswordChangeRequired ? (
            <>
              <Navigate to="/login" replace />
              <LoginPage onAuthenticated={reload} />
            </>
          ) : (
            <AuthorizedApplication session={state.session} reloadSession={reload} />
          )
        ) : null}
      </ConfigProvider>
    </AppErrorBoundary>
  );
}

function AuthorizedApplication({ session, reloadSession }: { session: CurrentCapabilities; reloadSession: () => void }) {
  const menu = authorizedMenu(session);
  const defaultPath = menu[0]?.path;
  const rulesLanding = session.capabilities.includes('POLICY:READ')
    ? <RulesHomePage />
    : session.capabilities.includes('ATTENDANCE_SETUP:READ')
      ? <Navigate to="/rules/attendance-groups" replace />
      : <AccessDenied />;

  return (
    <AppShell menu={menu} onSessionChanged={reloadSession}>
      <Suspense fallback={<StatePanel state="loading" />}>
        <Routes>
          <Route
            path="/"
            element={defaultPath
              ? <Navigate to={defaultPath} replace />
              : <NoAuthorizedMenu />}
          />
          <Route
            path="/login"
            element={defaultPath
              ? <Navigate to={defaultPath} replace />
              : <NoAuthorizedMenu />}
          />
          <Route path="/rules" element={rulesLanding} />
          {session.capabilities.includes('POLICY:READ') ? (
            <>
              <Route path="/rules/templates" element={<PolicyTemplatesPage capabilities={session.capabilities} />} />
              <Route path="/rules/templates/:templateId" element={<PolicyTemplateDetailPage capabilities={session.capabilities} />} />
              <Route path="/rules/templates/:templateId/versions/:versionId" element={<PolicyVersionPage capabilities={session.capabilities} />} />
            </>
          ) : (
            <>
              <Route path="/rules/templates" element={<AccessDenied />} />
              <Route path="/rules/templates/:templateId" element={<AccessDenied />} />
              <Route path="/rules/templates/:templateId/versions/:versionId" element={<AccessDenied />} />
            </>
          )}
          {session.capabilities.includes('ATTENDANCE_SETUP:READ') ? (
            <>
              <Route path="/rules/attendance-groups" element={<AttendanceGroupsPage capabilities={session.capabilities} />} />
              <Route path="/rules/shifts" element={<ShiftsPage capabilities={session.capabilities} />} />
              <Route path="/rules/calendars" element={<CalendarsPage capabilities={session.capabilities} />} />
              <Route path="/rules/attendance-policy" element={<AttendancePolicyPage capabilities={session.capabilities} />} />
              <Route path="/rules/attendance-policy/:versionId" element={<AttendancePolicyPage capabilities={session.capabilities} />} />
            </>
          ) : (
            <>
              <Route path="/rules/attendance-groups" element={<AccessDenied />} />
              <Route path="/rules/shifts" element={<AccessDenied />} />
              <Route path="/rules/calendars" element={<AccessDenied />} />
              <Route path="/rules/attendance-policy" element={<AccessDenied />} />
              <Route path="/rules/attendance-policy/:versionId" element={<AccessDenied />} />
            </>
          )}
          {session.capabilities.includes('ACCOUNT:READ') ? (
            <>
              <Route path="/access/accounts" element={<AccountsPage capabilities={session.capabilities} />} />
              <Route path="/access/accounts/:accountId" element={<AccountDetailPage capabilities={session.capabilities} />} />
            </>
          ) : (
            <>
              <Route path="/access/accounts" element={<AccessDenied />} />
              <Route path="/access/accounts/:accountId" element={<AccessDenied />} />
            </>
          )}
          {session.capabilities.includes('ROLE:READ')
            ? <Route path="/access/roles" element={<RolesPage />} />
            : <Route path="/access/roles" element={<AccessDenied />} />}
          {session.capabilities.includes('AUDIT:READ') ? (
            <>
              <Route path="/access/audit" element={<AuditPage />} />
              <Route path="/access/audit/:auditEventId" element={<AuditDetailPage />} />
            </>
          ) : (
            <>
              <Route path="/access/audit" element={<AccessDenied />} />
              <Route path="/access/audit/:auditEventId" element={<AccessDenied />} />
            </>
          )}
          {hasAllCapabilities(session, ['PEOPLE_IMPORT:READ', 'PEOPLE_IMPORT:TEMPLATE_DOWNLOAD']) ? (
            <Route path="/people/import" element={<PeopleImportPage capabilities={session.capabilities} />} />
          ) : (
            <Route path="/people/import" element={<AccessDenied />} />
          )}
          {hasAllCapabilities(session, ['MASTER_DATA:READ', 'ORGANIZATION:READ']) ? (
            <Route path="/people/organization" element={<OrganizationPage capabilities={session.capabilities} />} />
          ) : (
            <Route path="/people/organization" element={<AccessDenied />} />
          )}
          {session.capabilities.includes('MASTER_DATA:READ')
            ? <Route path="/people/employees" element={<EmployeesPage capabilities={session.capabilities} />} />
            : <Route path="/people/employees" element={<AccessDenied />} />}
          {session.capabilities.includes('EMPLOYEE:READ')
            ? <Route path="/people/employees/:employeeId" element={<EmployeeDetailPage capabilities={session.capabilities} />} />
            : <Route path="/people/employees/:employeeId" element={<AccessDenied />} />}
          {hasAllCapabilities(session, ['MASTER_DATA:READ', 'ORGANIZATION:READ']) ? (
            <Route path="/organization" element={<Navigate to="/people/organization" replace />} />
          ) : <Route path="/organization" element={defaultPath ? <AccessDenied /> : <NoAuthorizedMenu />} />}
          {hasAnyCapability(session, ['MASTER_DATA:READ', 'EMPLOYEE:READ']) ? (
            <Route path="/employees" element={<Navigate to="/people/employees" replace />} />
          ) : <Route path="/employees" element={defaultPath ? <AccessDenied /> : <NoAuthorizedMenu />} />}
          <Route
            path="*"
            element={<StatePanel
              state="404"
              description={translate('state.notFoundDescription')}
            />}
          />
        </Routes>
      </Suspense>
    </AppShell>
  );
}

function hasAnyCapability(session: CurrentCapabilities, capabilities: string[]): boolean {
  return capabilities.some((capability) => session.capabilities.includes(capability));
}

function hasAllCapabilities(session: CurrentCapabilities, capabilities: string[]): boolean {
  return capabilities.every((capability) => session.capabilities.includes(capability));
}

function AccessDenied() {
  return (
    <StatePanel
      state="403"
      title={translate('state.forbiddenTitle')}
      description={translate('state.forbiddenDescription')}
    />
  );
}

function NoAuthorizedMenu() {
  return (
    <StatePanel
      state="unauthorized"
      description={translate('app.noAuthorizedMenu')}
    />
  );
}
