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
  IconUsers,
  IconUsersGroup,
} from '@tabler/icons-react';
import { Drawer, Form, Input, Layout, Menu, Modal, message } from 'antd';
import { useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate } from 'react-router-dom';

import type { MenuItem } from '../../features/session/sessionApi';
import { logout } from '../../features/session/sessionApi';
import { changePassword } from '../../features/auth/authApi';
import { selectedMenuKey } from '../../app/routeAuthorization';
import { BrandLogo } from './BrandLogo';
import { AccessibleButton } from './AccessibleButton';
import { isDemoMode } from '../config/runtimeMode';
import { translate } from '../i18n/messages';

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
};

export function AppShell({ menu, children, onSessionChanged }: AppShellProps) {
  const { t } = useTranslation();
  const [collapsed, setCollapsed] = useState(false);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [passwordProcessing, setPasswordProcessing] = useState(false);
  const [passwordForm] = Form.useForm();
  const navigate = useNavigate();
  const location = useLocation();
  const demoMode = isDemoMode();
  const selectedKey = selectedMenuKey(menu, location.pathname);

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
    />
  );

  return (
    <Layout className="app-layout">
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
          <span className="app-topbar__title">{translate('app.companyName')}</span>
          <span className={`app-environment${demoMode ? ' app-environment--demo' : ''}`}>
            {demoMode ? translate('app.demoEnvironment') : t('app.localDevelopment')}
          </span>
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
          <Form.Item label={t('app.newPassword')} name="newPassword" rules={[{ required: true, min: 12, message: t('app.newPasswordLength') }]}><Input.Password autoComplete="new-password" /></Form.Item>
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

export function ResponsiveNavigation({ menu, selectedKey, onOpen }: {
  menu: MenuItem[];
  selectedKey?: string;
  onOpen: (value: { key: string }) => void;
}) {
  const items = useMemo(() => {
    return menu.map((item) => {
      const Icon = menuIcons[item.key as keyof typeof menuIcons] ?? IconBuildingCommunity;
      return {
        key: item.key,
        icon: <Icon aria-hidden="true" stroke={2} size="var(--size-icon-md)" />,
        label: item.label,
      };
    });
  }, [menu]);
  return (
    <Menu
      mode="inline"
      theme="dark"
      items={items}
      selectedKeys={selectedKey ? [selectedKey] : []}
      onClick={onOpen}
    />
  );
}
