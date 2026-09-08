import { ConfigProvider, theme as antdTheme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { lazy, Suspense, type ReactNode } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';

import { LoginPage } from '../features/auth/LoginPage';
import type { CurrentCapabilities } from '../features/session/sessionApi';
import { useSession } from '../features/session/useSession';
import { AppErrorBoundary } from '../shared/components/AppErrorBoundary';
import { AppShell } from '../shared/components/AppShell';
import { StatePanel } from '../shared/components/StatePanel';
import { AppearanceProvider, useAppearance } from '../shared/appearance/AppearanceProvider';
import { isDemoMode } from '../shared/config/runtimeMode';
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
const SourceOverviewPage = lazy(() => import('../features/attendanceSources/SourceOverviewPage'));
const OaSourcesPage = lazy(() => import('../features/attendanceSources/OaSourcesPage'));
const PaperOvertimePage = lazy(() => import('../features/paperOvertime/PaperOvertimePage'));
const SourceJobsPage = lazy(() => import('../features/attendanceSources/SourceJobsPage'));
const PunchImportsPage = lazy(() => import('../features/punchImport/PunchImportsPage'));
const PunchImportDetailPage = lazy(() => import('../features/punchImport/PunchImportDetailPage'));
const DashboardRoute = lazy(() => import('../features/wave7/DashboardPage'));
const PersonalAttendanceDashboardRoute = lazy(
  () => import('../features/wave7/PersonalAttendanceDashboard')
    .then((module) => ({ default: module.PersonalAttendanceDashboardRoute })),
);
const CustomerReportsRoute = lazy(() => import('../features/reports/CustomerReportCenterPage'));
const QueryReportsRoute = lazy(() => import('../features/reports/QueryReportsPage'));
const AttendanceScreenRoute = lazy(() => import('../features/wave7/AttendanceBigScreenPage'));
const EmployeeTodayRoute = lazy(() => import('../features/wave7/EmployeeSelfServicePages')
  .then((module) => ({ default: module.EmployeeTodayRoute })));
const EmployeeRecordsRoute = lazy(() => import('../features/wave7/EmployeeSelfServicePages')
  .then((module) => ({ default: module.EmployeeRecordsRoute })));
const EmployeeLeaveRoute = lazy(() => import('../features/wave7/EmployeeSelfServicePages')
  .then((module) => ({ default: module.EmployeeLeaveRoute })));
const EmployeeFeedbackRoute = lazy(() => import('../features/wave7/EmployeeSelfServicePages')
  .then((module) => ({ default: module.EmployeeFeedbackRoute })));

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
      <AppearanceProvider>
        <ThemedConfig cspNonce={cspNonce}>
          <AppBody state={state} reload={reload} />
        </ThemedConfig>
      </AppearanceProvider>
    </AppErrorBoundary>
  );
}

function ThemedConfig({
  cspNonce,
  children,
}: {
  cspNonce: string | undefined;
  children: ReactNode;
}) {
  const { appearance } = useAppearance();
  return (
      <ConfigProvider
        csp={cspNonce ? { nonce: cspNonce } : undefined}
        locale={zhCN}
        theme={{
          ...theme,
          algorithm: appearance === 'night'
            ? antdTheme.darkAlgorithm
            : antdTheme.defaultAlgorithm,
        }}
      >
        {children}
      </ConfigProvider>
  );
}

function AppBody({
  state,
  reload,
}: {
  state: ReturnType<typeof useSession>['state'];
  reload: () => void;
}) {
  return (
    <>
        {state.status === 'loading' ? <StatePanel state="loading" /> : null}
        {state.status === 'error' ? (
          isUnauthenticatedSessionError(state.error.status) ? (
            <>
              <Navigate to="/login" replace />
              <LoginPage onAuthenticated={reload} />
            </>
          ) : (
            // The request correlation stays on the error object for diagnostics; it is not business UI.
            <StatePanel
              state={state.error.status === 0 ? 'network-error' : 'error'}
              description={state.error.message}
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
    </>
  );
}

function isUnauthenticatedSessionError(status: number): boolean {
  // This branch only handles restoration through GET /auth/session. Some
  // gateways represent an expired, revoked, or no-longer-visible session as
  // 403/404 instead of 401. In all three cases there is no protected UI that
  // can be rendered safely, so return the user to the login form.
  return status === 401 || status === 403 || status === 404;
}

function AuthorizedApplication({ session, reloadSession }: { session: CurrentCapabilities; reloadSession: () => void }) {
  const menu = authorizedMenu(session);
  const defaultPath = menu[0]?.path;
  const demoMode = isDemoMode();
  const location = useLocation();
  const canReadDashboard = session.capabilities.includes('ATTENDANCE_DASHBOARD:READ');
  const canReadSelfAttendance = session.capabilities.includes('ATTENDANCE_SELF:READ');
  const rulesLanding = session.capabilities.includes('POLICY:READ')
    ? <RulesHomePage capabilities={session.capabilities} />
    : session.capabilities.includes('ATTENDANCE_SETUP:READ')
      ? <Navigate to="/rules/attendance-groups" replace />
      : <AccessDenied />;

  if (
    demoMode
    && canReadDashboard
    && ['/attendance/screen', '/display/attendance'].includes(location.pathname)
  ) {
    return (
      <Suspense fallback={<StatePanel state="loading" />}>
        <AttendanceScreenRoute />
      </Suspense>
    );
  }

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
          {session.capabilities.includes('ATTENDANCE_SOURCE:READ') ? (
            <>
              <Route path="/sources/online" element={<SourceOverviewPage />} />
              <Route path="/sources/oa" element={<OaSourcesPage />} />
              <Route path="/sources/jobs" element={<SourceJobsPage capabilities={session.capabilities} />} />
            </>
          ) : (
            <>
              <Route path="/sources/online" element={<AccessDenied />} />
              <Route path="/sources/oa" element={<AccessDenied />} />
              <Route path="/sources/jobs" element={<AccessDenied />} />
            </>
          )}
          {session.capabilities.includes('PAPER_OVERTIME:MANAGE') ? (
            <Route path="/attendance/paper-overtime" element={<PaperOvertimePage />} />
          ) : (
            <Route path="/attendance/paper-overtime" element={<AccessDenied />} />
          )}
          {session.capabilities.includes('ATTENDANCE_PUNCH_IMPORT:READ') ? (
            <>
              <Route path="/sources/attendance-excel" element={<PunchImportsPage capabilities={session.capabilities} />} />
              <Route path="/sources/attendance-excel/:batchId" element={<PunchImportDetailPage capabilities={session.capabilities} />} />
            </>
          ) : (
            <>
              <Route path="/sources/attendance-excel" element={<AccessDenied />} />
              <Route path="/sources/attendance-excel/:batchId" element={<AccessDenied />} />
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
          <Route
            path="/workbench"
            element={canReadDashboard
              ? <DashboardRoute />
              : canReadSelfAttendance
                ? <PersonalAttendanceDashboardRoute />
                : <AccessDenied />}
          />
          {demoMode && session.capabilities.includes('ATTENDANCE_DASHBOARD:READ')
            ? <Route path="/attendance/screen" element={<AttendanceScreenRoute />} />
            : <Route path="/attendance/screen" element={<AccessDenied />} />}
          {demoMode && session.capabilities.includes('ATTENDANCE_DASHBOARD:READ')
            ? <Route path="/display/attendance" element={<AttendanceScreenRoute />} />
            : <Route path="/display/attendance" element={<AccessDenied />} />}
          {session.capabilities.includes('ATTENDANCE_REPORT:READ')
            ? (
              <Route
                path="/attendance/reports"
                element={<CustomerReportsRoute capabilities={session.capabilities} />}
              />
            )
            : <Route path="/attendance/reports" element={<AccessDenied />} />}
          {session.capabilities.includes('ATTENDANCE_REPORT_QUERY:READ')
            ? (
              <Route
                path="/attendance/queries/:sheet"
                element={<QueryReportsRoute capabilities={session.capabilities} />}
              />
            )
            : <Route path="/attendance/queries/:sheet" element={<AccessDenied />} />}
          {session.capabilities.includes('ATTENDANCE_SELF:READ') ? (
            <>
              <Route path="/me/today" element={<EmployeeTodayRoute capabilities={session.capabilities} />} />
              <Route path="/me/records" element={<EmployeeRecordsRoute capabilities={session.capabilities} />} />
            </>
          ) : (
            <>
              <Route path="/me/today" element={<AccessDenied />} />
              <Route path="/me/records" element={<AccessDenied />} />
            </>
          )}
          {session.capabilities.includes('LEAVE_SELF:READ')
            ? <Route path="/me/leave" element={<EmployeeLeaveRoute capabilities={session.capabilities} />} />
            : <Route path="/me/leave" element={<AccessDenied />} />}
          {session.capabilities.includes('ATTENDANCE_FEEDBACK:READ')
            ? <Route path="/me/feedback" element={<EmployeeFeedbackRoute capabilities={session.capabilities} />} />
            : <Route path="/me/feedback" element={<AccessDenied />} />}
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
