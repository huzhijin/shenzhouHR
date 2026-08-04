import {
  IconAdjustments,
  IconCalendar,
  IconClock,
  IconFileAnalytics,
  IconBuildingCommunity,
  IconKey,
  IconLogout,
  IconMenu2,
  IconShieldLock,
  IconShieldCheck,
  IconFileSpreadsheet,
  IconServer,
  IconRefresh,
  IconFileDescription,
  IconUsers,
  IconUsersGroup,
  IconUser,
} from '@tabler/icons-react';
import { Drawer, Form, Input, Layout, Menu, Modal, message, type MenuProps } from 'antd';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate } from 'react-router-dom';

import type { MenuItem } from '../../features/session/sessionApi';
import { logout } from '../../features/session/sessionApi';
import { changePassword } from '../../features/auth/authApi';
import { listReferenceCompanies } from '../../features/referenceData/referenceDataApi';
import { selectedMenuKey } from '../../app/routeAuthorization';
import { BrandLogo } from './BrandLogo';
import { AccessibleButton } from './AccessibleButton';
import { isDemoMode } from '../config/runtimeMode';
import { translate } from '../i18n/messages';
import { passwordMeetsPolicy } from '../security/passwordPolicy';

const { Header, Sider, Content } = Layout;

interface AppShellProps {
  menu: MenuItem[];
  children: ReactNode;
  onSessionChanged: () => void;
}

const menuIcons = {
  organization: IconBuildingCommunity,
  employees: IconUsers,
  rules: IconAdjustments,
  'rule-templates': IconAdjustments,
  accounts: IconUsers,
  roles: IconShieldLock,
  audit: IconFileAnalytics,
  'attendance-groups': IconUsersGroup,
  'attendance-shifts': IconClock,
  'attendance-calendars': IconCalendar,
  'attendance-policies': IconShieldCheck,
  'attendance-sources-online': IconServer,
  'attendance-sources-oa': IconFileDescription,
  'attendance-source-jobs': IconRefresh,
  'attendance-punch-imports': IconFileSpreadsheet,
  workbench: IconFileAnalytics,
  'personal-workbench': IconUser,
  'my-attendance': IconClock,
  'my-leave': IconCalendar,
  'attendance-feedback': IconFileAnalytics,
  'attendance-screen': IconFileAnalytics,
  'attendance-reports': IconFileAnalytics,
  'self-today': IconClock,
  'self-records': IconCalendar,
  'self-leave': IconCalendar,
  'self-feedback': IconFileAnalytics,
  'people-import': IconFileSpreadsheet,
  'people-organization': IconBuildingCommunity,
  'people-employees': IconUsers,
};

const navigationGroups = [
  {
    key: 'workspace',
    label: 'navigation.workspace',
    matches: (item: MenuItem) => ![
      '/people/',
      '/rules',
      '/sources/',
      '/access/',
    ].some((prefix) => item.path.startsWith(prefix)),
  },
  {
    key: 'people',
    label: 'navigation.people',
    matches: (item: MenuItem) => item.path.startsWith('/people/'),
  },
  {
    key: 'attendance',
    label: 'navigation.attendance',
    matches: (item: MenuItem) => item.path === '/rules' || item.path.startsWith('/rules/'),
  },
  {
    key: 'sources',
    label: 'navigation.sources',
    matches: (item: MenuItem) => item.path.startsWith('/sources/'),
  },
  {
    key: 'administration',
    label: 'navigation.administration',
    matches: (item: MenuItem) => item.path.startsWith('/access/'),
  },
] as const;

const personalMenuKeys = new Set([
  'personal-workbench',
  'my-attendance',
  'my-leave',
  'attendance-feedback',
  'self-today',
  'self-records',
  'self-leave',
  'self-feedback',
]);

export function AppShell({ menu, children, onSessionChanged }: AppShellProps) {
  const { t } = useTranslation();
  const [collapsed, setCollapsed] = useState(false);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [passwordProcessing, setPasswordProcessing] = useState(false);
  const [visibleCompanyNames, setVisibleCompanyNames] = useState<string[]>([]);
  const [passwordForm] = Form.useForm();
  const navigate = useNavigate();
  const location = useLocation();
  const demoMode = isDemoMode();
  const selectedKey = selectedMenuKey(menu, location.pathname);
  const showsCompanyContext = menu.length > 0
    && menu.some((item) => !personalMenuKeys.has(item.key));
  const topbarTitle = showsCompanyContext && visibleCompanyNames.length === 1
    ? t('app.currentCompany', { name: visibleCompanyNames[0] })
    : showsCompanyContext && visibleCompanyNames.length > 1
      ? t('app.multipleCompanies', { count: visibleCompanyNames.length })
      : t('app.name');

  useEffect(() => {
    setVisibleCompanyNames([]);
    if (!showsCompanyContext) {
      return undefined;
    }
    let active = true;
    void listReferenceCompanies()
      .then((companies) => {
        if (!active) return;
        setVisibleCompanyNames(companies
          .map((company) => company.companyName.trim())
          .filter((companyName) => companyName.length > 0));
      })
      .catch(() => {
        // Company context is helpful orientation, but must never block navigation.
      });
    return () => {
      active = false;
    };
  }, [menu, showsCompanyContext]);

  const openMenuItem = ({ key }: { key: string }) => {
    const item = menu.find((candidate) => candidate.key === key);
    if (item) {
      navigate(item.path);
      setMobileMenuOpen(false);
    }
  };

  const signOut = async () => {
    try {
      await logout();
      onSessionChanged();
      navigate('/login', { replace: true });
    } catch {
      void message.error(t('app.signOutFailed'));
    }
  };

  const submitPasswordChange = async (values: { currentPassword: string; newPassword: string }) => {
    setPasswordProcessing(true);
    try {
      await changePassword(values.currentPassword, values.newPassword);
      void message.success(t('app.passwordChanged'));
      setPasswordOpen(false);
      passwordForm.resetFields();
      onSessionChanged();
      navigate('/login', { replace: true });
    } catch {
      void message.error(t('app.passwordChangeFailed'));
    } finally {
      setPasswordProcessing(false);
    }
  };

  const navigation = (
    <ResponsiveNavigation
      menu={menu}
      selectedKey={selectedKey}
      onOpen={openMenuItem}
      theme={demoMode ? 'light' : 'dark'}
    />
  );

  return (
    <Layout className={`app-layout${demoMode ? ' app-layout--demo-open-design' : ''}`}>
      <a
        className="skip-link"
        href="#main-content"
        aria-label={translate('app.skipToContent')}
      >
        {translate('app.skipToContent')}
      </a>
      <Sider
        className="app-sidebar"
        width="var(--size-sidebar-expanded)"
        collapsedWidth="var(--size-sidebar-collapsed)"
        collapsed={collapsed}
        onCollapse={setCollapsed}
      >
        <div className="app-brand">
          <BrandLogo compact={collapsed} />
        </div>
        <nav aria-label={translate('app.navigation')}>{navigation}</nav>
      </Sider>
      <Layout className="app-workspace">
        <Header className="app-topbar">
          <AccessibleButton className="mobile-menu-trigger" type="text" label={translate('app.openNavigation')} iconOnly icon={<IconMenu2 aria-hidden="true" stroke={2} size="var(--size-icon-md)" />} onClick={() => setMobileMenuOpen(true)} />
          <span id="mobile-menu-trigger-label" className="sr-only">
            {translate('app.openNavigation')}
          </span>
          <span className="app-topbar__title" title={topbarTitle}>{topbarTitle}</span>
          {demoMode ? (
            <span className="app-environment app-environment--demo">
              {translate('app.demoEnvironment')}
            </span>
          ) : null}
          <AccessibleButton
            type="text"
            label={t('app.changePassword')}
            icon={<IconKey aria-hidden="true" stroke={2} />}
            onClick={() => setPasswordOpen(true)}
          >
            <span className="app-topbar__action-label">{t('app.changePassword')}</span>
          </AccessibleButton>
          <AccessibleButton
            type="text"
            label={t('app.signOut')}
            icon={<IconLogout aria-hidden="true" stroke={2} />}
            onClick={() => void signOut()}
          >
            <span className="app-topbar__action-label">{t('app.signOut')}</span>
          </AccessibleButton>
        </Header>
        <Content id="main-content" className="app-content" tabIndex={-1}>
          {children}
        </Content>
      </Layout>
      <Drawer
        title={translate('app.name')}
        placement="left"
        open={mobileMenuOpen}
        onClose={() => setMobileMenuOpen(false)}
      >
        <nav aria-label={translate('app.mobileNavigation')}>{navigation}</nav>
      </Drawer>
      <Modal
        open={passwordOpen}
        title={t('app.changePassword')}
        okText={t('app.confirmChange')}
        cancelText={t('app.cancel')}
        confirmLoading={passwordProcessing}
        onOk={() => void passwordForm.submit()}
        onCancel={() => setPasswordOpen(false)}
      >
        <Form form={passwordForm} layout="vertical" onFinish={(values) => void submitPasswordChange(values)}>
          <Form.Item label={t('app.currentPassword')} name="currentPassword" rules={[{ required: true, message: t('app.currentPasswordRequired') }]}><Input.Password autoComplete="current-password" /></Form.Item>
          <Form.Item
            label={t('app.newPassword')}
            name="newPassword"
            extra={t('app.newPasswordPolicy')}
            rules={[
              { required: true, message: t('app.newPasswordPolicy') },
              {
                validator: (_rule, value) => (
                  value === undefined || passwordMeetsPolicy(value)
                    ? Promise.resolve()
                    : Promise.reject(new Error(t('app.newPasswordPolicy')))
                ),
              },
            ]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Form.Item
            label={t('app.confirmPassword')}
            name="confirmation"
            dependencies={['newPassword']}
            rules={[
              { required: true, message: t('app.confirmPasswordRequired') },
              ({ getFieldValue }) => ({ validator: (_rule, value) => value === getFieldValue('newPassword') ? Promise.resolve() : Promise.reject(new Error(t('app.passwordMismatch'))) }),
            ]}
          ><Input.Password autoComplete="new-password" /></Form.Item>
        </Form>
      </Modal>
    </Layout>
  );
}

export function ResponsiveNavigation({ menu, selectedKey, onOpen, theme = 'dark' }: {
  menu: MenuItem[];
  selectedKey?: string;
  onOpen: (value: { key: string }) => void;
  theme?: 'light' | 'dark';
}) {
  const items = useMemo<MenuProps['items']>(() => {
    const menuItem = (item: MenuItem) => {
      const Icon = menuIcons[item.key as keyof typeof menuIcons] ?? IconBuildingCommunity;
      return {
        key: item.key,
        icon: <Icon aria-hidden="true" stroke={2} size="var(--size-icon-md)" />,
        label: item.label,
      };
    };
    return navigationGroups
      .map((group) => ({
        type: 'group' as const,
        key: `navigation-${group.key}`,
        label: translate(group.label),
        children: menu.filter(group.matches).map(menuItem),
      }))
      .filter((group) => group.children.length > 0);
  }, [menu]);
  return (
    <Menu
      mode="inline"
      theme={theme}
      items={items}
      selectedKeys={selectedKey ? [selectedKey] : []}
      onClick={onOpen}
    />
  );
}
