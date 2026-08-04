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
import { passwordMeetsPolicy } from '../../shared/security/passwordPolicy';
import {
  DEMO_PASSWORD,
  DEMO_USERNAME,
} from '../session/demoAuthSession';
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
  | 'password-changed-login-required'
  | 'session-expired'
  | 'network-error'
  | 'success';

export function LoginPage({ onAuthenticated }: { onAuthenticated: () => void }) {
  const { t } = useTranslation();
  const [form] = Form.useForm();
  const [firstChangeUsername, setFirstChangeUsername] = useState('');
  const [firstChangeCurrentPassword, setFirstChangeCurrentPassword] = useState('');
  const [firstChangeSubmitting, setFirstChangeSubmitting] = useState(false);
  const [firstChangeError, setFirstChangeError] = useState<string>();
  const [state, setState] = useState<LoginState>(
    useLocation().state === 'SESSION_EXPIRED' ? 'session-expired' : 'idle',
  );
  const [errorDescription, setErrorDescription] = useState<string>();
  const navigate = useNavigate();
  const demoMode = isDemoMode();

  const submitLogin = async (values: { username: string; password: string }) => {
    setState('processing');
    setErrorDescription(undefined);
    try {
      const result = await login(values.username, values.password);
      if ('code' in result && result.code === 'FIRST_PASSWORD_CHANGE_REQUIRED') {
        setFirstChangeUsername(values.username);
        setFirstChangeCurrentPassword(values.password);
        setFirstChangeError(undefined);
        setState('first-password-change');
        return;
      }
      if (!('code' in result) && result.firstPasswordChangeRequired) {
        setFirstChangeUsername(values.username);
        setFirstChangeCurrentPassword(values.password);
        setFirstChangeError(undefined);
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
    setFirstChangeSubmitting(true);
    setFirstChangeError(undefined);
    let passwordChanged = false;
    try {
      await completeFirstPasswordChange(firstChangeCurrentPassword, values.newPassword);
      passwordChanged = true;
      const result = await login(firstChangeUsername, values.newPassword);
      if (
        ('code' in result && result.code === 'FIRST_PASSWORD_CHANGE_REQUIRED')
        || (!('code' in result) && result.firstPasswordChangeRequired)
      ) {
        throw new ApiRequestError(409, {
          code: 'FIRST_PASSWORD_CHANGE_RELOGIN_REQUIRED',
          message: t('login.passwordChangedReloginRequired'),
          retryable: false,
        });
      }
      setFirstChangeUsername('');
      setFirstChangeCurrentPassword('');
      setState('success');
      onAuthenticated();
      navigate('/', { replace: true });
    } catch (caught: unknown) {
      if (passwordChanged) {
        form.setFieldsValue({
          username: firstChangeUsername,
          password: '',
        });
        setFirstChangeUsername('');
        setFirstChangeCurrentPassword('');
        setErrorDescription(t('login.passwordChangedReloginRequired'));
        setState('password-changed-login-required');
      } else if (
        caught instanceof ApiRequestError
        && caught.code === 'PASSWORD_POLICY_VIOLATION'
      ) {
        setFirstChangeError(t('app.newPasswordPolicy'));
        setState('first-password-change');
      } else if (
        caught instanceof ApiRequestError
        && (
          caught.code === 'AUTHENTICATION_REQUIRED'
          || caught.code === 'SESSION_EXPIRED'
          || caught.code === 'INVALID_CREDENTIALS'
          || caught.status === 401
        )
      ) {
        form.setFieldsValue({
          username: firstChangeUsername,
          password: '',
        });
        setFirstChangeUsername('');
        setFirstChangeCurrentPassword('');
        // Correlation metadata remains on ApiRequestError for logs, never in the login UI.
        setErrorDescription(undefined);
        setState('session-expired');
      } else {
        setFirstChangeError(t('login.connectionFailed'));
        setState('first-password-change');
      }
    } finally {
      setFirstChangeSubmitting(false);
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
        <div className="login-panel__brand" aria-label={t('app.name')}>
          <BrandLogo />
          <span>{t('app.name')}</span>
        </div>
        <div className="login-card">
          <div className="login-card__heading">
            <IconLock aria-hidden="true" stroke={2} />
            <div><h2>{state === 'first-password-change' ? t('login.firstChangeTitle') : t('login.title')}</h2><p>{t('login.description')}</p></div>
          </div>
          {state === 'session-expired' ? <Alert showIcon type="warning" title={t('login.sessionExpired')} /> : null}
          {state === 'wrong-password' ? <Alert id="login-error" showIcon type="error" title={t('login.invalidCredentials')} description={errorDescription} /> : null}
          {state === 'locked' ? <Alert id="login-error" showIcon type="error" title={t('login.locked')} description={errorDescription ?? t('login.lockedDescription')} /> : null}
          {state === 'password-changed-login-required' ? <Alert id="login-error" showIcon type="warning" title={t('login.passwordChanged')} description={errorDescription} /> : null}
          {state === 'network-error' ? <Alert id="login-error" showIcon type="error" title={t('login.networkError')} description={errorDescription} action={<Button size="small" icon={<IconRefresh stroke={2} />} onClick={() => setState('idle')}>{t('state.retry')}</Button>} /> : null}
          {state === 'success' ? <Alert showIcon type="success" title={t('login.success')} /> : null}
          {state === 'first-password-change' ? (
            <Form layout="vertical" onFinish={(values) => void submitFirstChange(values)}>
              <Alert showIcon type="info" icon={<IconAlertTriangle stroke={2} />} title={t('login.firstChangeNotice')} />
              {firstChangeError ? <Alert id="first-change-error" showIcon type="error" title={t('login.firstChangeFailed')} description={firstChangeError} /> : null}
              <Form.Item
                label={t('app.newPassword')}
                name="newPassword"
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
              <Button block type="primary" htmlType="submit" loading={firstChangeSubmitting} disabled={firstChangeSubmitting}>{t('login.completeFirstChange')}</Button>
            </Form>
          ) : (
            <Form
              form={form}
              layout="vertical"
              initialValues={demoMode ? {
                username: DEMO_USERNAME,
                password: DEMO_PASSWORD,
              } : undefined}
              onFinish={(values) => void submitLogin(values)}
            >
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
          {demoMode ? (
            <Alert
              className="login-demo-note"
              showIcon
              type="warning"
              title={`演示账号：${DEMO_USERNAME}`}
              description={(
                <>
                  <span>演示密码：</span><code>{DEMO_PASSWORD}</code>
                  <br />
                  <span>{t('login.demoNotice')}</span>
                </>
              )}
            />
          ) : null}
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
  // Keep request metadata available to diagnostics without exposing internal identifiers to employees.
  setDescription(undefined);
}
