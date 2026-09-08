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
import szstVisionPanel from '../../assets/szst-vision-panel.jpg';
import { AppearanceToggle } from '../../shared/appearance/AppearanceToggle';
import { BrandLogo } from '../../shared/components/BrandLogo';
import { isDemoMode } from '../../shared/config/runtimeMode';
import {
  passwordPolicyChecks,
  passwordPolicyIssues,
  type PasswordPolicyIssueCode,
} from '../../shared/security/passwordPolicy';
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
  | 'service-error'
  | 'request-error'
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
        setFirstChangeError(t('login.passwordRejected'));
        setState('first-password-change');
      } else if (
        caught instanceof ApiRequestError
        && caught.code === 'CSRF_VALIDATION_FAILED'
      ) {
        setFirstChangeError(t('login.requestRejected'));
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
      } else if (
        caught instanceof ApiRequestError
        && caught.status >= 500
      ) {
        setFirstChangeError(t('login.serviceUnavailable'));
        setState('first-password-change');
      } else if (
        caught instanceof ApiRequestError
        && caught.status > 0
      ) {
        setFirstChangeError(t('login.requestRejected'));
        setState('first-password-change');
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
      <section className="login-brand" aria-label={t('login.brandPanelLabel')}>
        <img
          className="login-brand__art"
          src={szstVisionPanel}
          alt={t('login.brandPanelAlt')}
        />
      </section>
      <section className="login-panel" aria-live="polite">
        <div className="login-panel__brand" aria-label={t('app.name')}>
          <BrandLogo />
          <span>{t('app.name')}</span>
          <AppearanceToggle />
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
          {state === 'service-error' ? <Alert id="login-error" showIcon type="error" title={t('login.serviceUnavailable')} description={errorDescription} action={<Button size="small" icon={<IconRefresh stroke={2} />} onClick={() => setState('idle')}>{t('state.retry')}</Button>} /> : null}
          {state === 'request-error' ? <Alert id="login-error" showIcon type="error" title={t('login.requestRejected')} description={errorDescription} action={<Button size="small" icon={<IconRefresh stroke={2} />} onClick={() => setState('idle')}>{t('state.retry')}</Button>} /> : null}
          {state === 'success' ? <Alert showIcon type="success" title={t('login.success')} /> : null}
          {state === 'first-password-change' ? (
            <Form
              layout="vertical"
              scrollToFirstError
              onFinish={(values) => void submitFirstChange(values)}
              onFinishFailed={(info) => {
                const candidate = info.values.newPassword;
                const fieldErrors = info.errorFields.flatMap((field) => field.errors);
                const issueText = passwordPolicyIssues(candidate)
                  .map((issue) => passwordIssueLabel(t, issue.code, candidate))
                  .join('；');
                setFirstChangeError(
                  [t('login.passwordRejected'), issueText, ...fieldErrors]
                    .filter((part, index, all) => part && all.indexOf(part) === index)
                    .join(' '),
                );
              }}
            >
              <Alert showIcon type="info" icon={<IconAlertTriangle stroke={2} />} title={t('login.firstChangeNotice')} />
              {firstChangeError ? <Alert id="first-change-error" showIcon type="error" title={t('login.firstChangeFailed')} description={firstChangeError} /> : null}
              <Form.Item
                label={t('app.newPassword')}
                name="newPassword"
                validateTrigger={['onChange', 'onBlur', 'onSubmit']}
                rules={[
                  { required: true, message: t('app.newPasswordPolicy') },
                  {
                    validator: (_rule, value) => {
                      if (value === undefined || value === '') {
                        return Promise.resolve();
                      }
                      const issues = passwordPolicyIssues(value);
                      return issues.length === 0
                        ? Promise.resolve()
                        : Promise.reject(new Error(
                          issues.map((issue) => passwordIssueLabel(t, issue.code, value)).join('；'),
                        ));
                    },
                  },
                ]}
              >
                <Input.Password autoComplete="new-password" aria-describedby="first-change-help" />
              </Form.Item>
              <Form.Item shouldUpdate noStyle>
                {({ getFieldValue }) => (
                  <PasswordPolicyChecklist value={getFieldValue('newPassword')} t={t} />
                )}
              </Form.Item>
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
              <Form.Item>
                <Button block type="primary" htmlType="submit" loading={firstChangeSubmitting} disabled={firstChangeSubmitting}>{t('login.completeFirstChange')}</Button>
              </Form.Item>
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

function passwordIssueLabel(
  t: (key: string, variables?: Record<string, string>) => string,
  code: PasswordPolicyIssueCode,
  value: unknown,
): string {
  const length = typeof value === 'string' ? String(value.length) : '0';
  switch (code) {
    case 'length-short':
      return t('login.passwordTooShort', { length });
    case 'length-long':
      return t('login.passwordTooLong');
    case 'upper':
      return t('login.passwordNeedUpper');
    case 'lower':
      return t('login.passwordNeedLower');
    case 'digit':
      return t('login.passwordNeedDigit');
    case 'symbol':
      return t('login.passwordNeedSymbol');
  }
}

function PasswordPolicyChecklist({
  value,
  t,
}: {
  value: unknown;
  t: (key: string, variables?: Record<string, string>) => string;
}) {
  const checks = passwordPolicyChecks(value);
  const length = typeof value === 'string' ? value.length : 0;
  return (
    <ul id="first-change-help" className="password-policy-checklist">
      {checks.map((check) => (
        <li
          key={check.code}
          className={check.met
            ? 'password-policy-checklist__item is-met'
            : 'password-policy-checklist__item is-missing'}
        >
          {check.code === 'length-short' || check.code === 'length-long'
            ? `${t('login.passwordRuleLength')}（当前 ${length} 位）`
            : check.code === 'upper' ? t('login.passwordRuleUpper')
              : check.code === 'lower' ? t('login.passwordRuleLower')
                : check.code === 'digit' ? t('login.passwordRuleDigit')
                  : t('login.passwordRuleSymbol')}
        </li>
      ))}
    </ul>
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
  } else if (caught.status === 0) {
    setState('network-error');
  } else if (caught.status >= 500) {
    setState('service-error');
  } else if (caught.code === 'INVALID_CREDENTIALS' || caught.code === 'AUTHENTICATION_FAILED' || caught.status === 401) {
    setState('wrong-password');
  } else {
    setState('request-error');
  }
  // Keep request metadata available to diagnostics without exposing internal identifiers to employees.
  setDescription(undefined);
}
