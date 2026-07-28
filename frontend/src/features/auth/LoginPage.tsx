import {
  IconAlertTriangle,
  IconLock,
  IconRefresh,
} from '@tabler/icons-react';
import { Alert, Button, Form, Input } from 'antd';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate } from 'react-router-dom';

import { ApiRequestError } from '../../shared/api/apiClient';
import { BrandLogo } from '../../shared/components/BrandLogo';
import { isDemoMode } from '../../shared/config/runtimeMode';
import {
  completeFirstPasswordChange,
  login,
} from './authApi';

type LoginState =
  | 'idle'
  | 'processing'
  | 'wrong-password'
  | 'locked'
  | 'first-password-change'
  | 'session-expired'
  | 'network-error'
  | 'success';

export function LoginPage({ onAuthenticated }: { onAuthenticated: () => void }) {
  const { t } = useTranslation();
  const [form] = Form.useForm();
  const [firstChangeCurrentPassword, setFirstChangeCurrentPassword] = useState('');
  const [state, setState] = useState<LoginState>(
    useLocation().state === 'SESSION_EXPIRED' ? 'session-expired' : 'idle',
  );
  const [errorDescription, setErrorDescription] = useState<string>();
  const navigate = useNavigate();

  const submitLogin = async (values: { username: string; password: string }) => {
    setState('processing');
    setErrorDescription(undefined);
    try {
      const result = await login(values.username, values.password);
      if ('code' in result && result.code === 'FIRST_PASSWORD_CHANGE_REQUIRED') {
        setFirstChangeCurrentPassword(values.password);
        setState('first-password-change');
        return;
      }
      if (!('code' in result) && result.firstPasswordChangeRequired) {
        setFirstChangeCurrentPassword(values.password);
        setState('first-password-change');
        return;
      }
      setState('success');
      onAuthenticated();
      navigate('/', { replace: true });
    } catch (caught: unknown) {
      handleLoginError(caught, setState, setErrorDescription, t);
    }
  };

  const submitFirstChange = async (values: { newPassword: string }) => {
    setState('processing');
    try {
      await completeFirstPasswordChange(firstChangeCurrentPassword, values.newPassword);
      setFirstChangeCurrentPassword('');
      setState('success');
      onAuthenticated();
      navigate('/', { replace: true });
    } catch (caught: unknown) {
      handleLoginError(caught, setState, setErrorDescription, t);
    }
  };

  return (
    <main className="login-page">
      <section className="login-brand" aria-label={t('login.systemDescription')}>
        <BrandLogo />
        <div>
          <p className="login-brand__eyebrow">{t('app.companyName')}</p>
          <h1>{t('app.name')}</h1>
          <p>{t('login.entryDescription')}</p>
        </div>
        <dl className="login-security-note">
          <div><dt>{t('login.localAccount')}</dt><dd>{t('login.localAccountDescription')}</dd></div>
          <div><dt>{t('login.authorization')}</dt><dd>{t('login.authorizationDescription')}</dd></div>
          <div><dt>{t('login.audit')}</dt><dd>{t('login.auditDescription')}</dd></div>
        </dl>
      </section>
      <section className="login-panel" aria-live="polite">
        <div className="login-card">
          <div className="login-card__heading">
            <IconLock aria-hidden="true" stroke={2} />
            <div><h2>{state === 'first-password-change' ? t('login.firstChangeTitle') : t('login.title')}</h2><p>{t('login.description')}</p></div>
          </div>
          {state === 'session-expired' ? <Alert showIcon type="warning" title={t('login.sessionExpired')} /> : null}
          {state === 'wrong-password' ? <Alert id="login-error" showIcon type="error" title={t('login.invalidCredentials')} description={errorDescription} /> : null}
          {state === 'locked' ? <Alert id="login-error" showIcon type="error" title={t('login.locked')} description={errorDescription ?? t('login.lockedDescription')} /> : null}
          {state === 'network-error' ? <Alert id="login-error" showIcon type="error" title={t('login.networkError')} description={errorDescription} action={<Button size="small" icon={<IconRefresh stroke={2} />} onClick={() => setState('idle')}>{t('state.retry')}</Button>} /> : null}
          {state === 'success' ? <Alert showIcon type="success" title={t('login.success')} /> : null}
          {state === 'first-password-change' ? (
            <Form layout="vertical" onFinish={(values) => void submitFirstChange(values)}>
              <Alert showIcon type="info" icon={<IconAlertTriangle stroke={2} />} title={t('login.firstChangeNotice')} />
              <Form.Item label={t('app.newPassword')} name="newPassword" rules={[{ required: true, min: 12, message: t('app.newPasswordLength') }]}>
                <Input.Password autoComplete="new-password" aria-describedby="first-change-help" />
              </Form.Item>
              <p id="first-change-help" className="form-help">{t('login.passwordHelp')}</p>
              <Form.Item
                label={t('app.confirmPassword')}
                name="confirmation"
                dependencies={['newPassword']}
                rules={[
                  { required: true, message: t('app.confirmPasswordRequired') },
                  ({ getFieldValue }) => ({ validator: (_rule, value) => value === getFieldValue('newPassword') ? Promise.resolve() : Promise.reject(new Error(t('app.passwordMismatch'))) }),
                ]}
              >
                <Input.Password autoComplete="new-password" />
              </Form.Item>
              <Button block type="primary" htmlType="submit">{t('login.completeFirstChange')}</Button>
            </Form>
          ) : (
            <Form form={form} layout="vertical" onFinish={(values) => void submitLogin(values)}>
              <Form.Item label={t('login.username')} name="username" rules={[{ required: true, message: t('login.usernameRequired') }]}>
                <Input autoComplete="username" aria-describedby="login-help login-error" />
              </Form.Item>
              <Form.Item label={t('login.password')} name="password" rules={[{ required: true, message: t('login.passwordRequired') }]}>
                <Input.Password autoComplete="current-password" aria-describedby="login-help login-error" />
              </Form.Item>
              <p id="login-help" className="form-help">{t('login.securityHelp')}</p>
              <Button block type="primary" htmlType="submit" loading={state === 'processing'}>{t('login.submit')}</Button>
            </Form>
          )}
          {isDemoMode() ? <Alert className="login-demo-note" showIcon type="warning" title={t('login.demoNotice')} /> : null}
        </div>
      </section>
    </main>
  );
}

function handleLoginError(
  caught: unknown,
  setState: (state: LoginState) => void,
  setDescription: (description?: string) => void,
  t: (key: string, variables?: Record<string, string>) => string,
) {
  if (!(caught instanceof ApiRequestError)) {
    setState('network-error');
    setDescription(t('login.connectionFailed'));
    return;
  }
  if (caught.code === 'ACCOUNT_LOCKED' || caught.code === 'LOCKED') {
    setState('locked');
  } else if (caught.code === 'FIRST_PASSWORD_CHANGE_REQUIRED' || caught.code === 'FIRST_CHANGE_REQUIRED') {
    setState('first-password-change');
  } else if (caught.code === 'SESSION_EXPIRED') {
    setState('session-expired');
  } else if (caught.status === 0 || caught.status >= 500) {
    setState('network-error');
  } else if (caught.code === 'INVALID_CREDENTIALS' || caught.code === 'AUTHENTICATION_FAILED' || caught.status === 401) {
    setState('wrong-password');
  } else {
    setState('wrong-password');
  }
  setDescription(caught.correlationId ? t('error.correlationId', { correlationId: caught.correlationId }) : undefined);
}
