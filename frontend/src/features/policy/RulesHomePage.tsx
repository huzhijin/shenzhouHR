import {
  IconAdjustments,
  IconCalendar,
  IconClock,
  IconShieldCheck,
  IconUsersGroup,
  type Icon,
} from '@tabler/icons-react';
import { Button, Card } from 'antd';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';

import { PageHeader } from '../../shared/components/PagePrimitives';

interface RulesHomePageProps {
  capabilities: readonly string[];
}

interface SettingLink {
  titleKey: string;
  descriptionKey: string;
  path: string;
  icon: Icon;
}

const dailySettingLinks: readonly SettingLink[] = [
  {
    titleKey: 'rules.shiftTitle',
    descriptionKey: 'rules.shiftDescription',
    path: '/rules/shifts',
    icon: IconClock,
  },
  {
    titleKey: 'rules.calendarTitle',
    descriptionKey: 'rules.calendarDescription',
    path: '/rules/calendars',
    icon: IconCalendar,
  },
  {
    titleKey: 'rules.groupTitle',
    descriptionKey: 'rules.groupDescription',
    path: '/rules/attendance-groups',
    icon: IconUsersGroup,
  },
  {
    titleKey: 'rules.policyTitle',
    descriptionKey: 'rules.policyDescription',
    path: '/rules/attendance-policy',
    icon: IconShieldCheck,
  },
];

export function RulesHomePage({ capabilities }: RulesHomePageProps) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const canReadAttendanceSetup = capabilities.includes('ATTENDANCE_SETUP:READ');
  const canReadPolicyTemplates = capabilities.includes('POLICY:READ');

  return (
    <>
      <PageHeader
        title={t('rules.title')}
        description={t('rules.description')}
        breadcrumbs={[{ label: t('rules.title') }]}
      />
      {canReadAttendanceSetup ? (
        <section className="rules-hub-section" aria-labelledby="rules-daily-settings-title">
          <header className="rules-hub-section__header">
            <h2 id="rules-daily-settings-title">{t('rules.setupTitle')}</h2>
            <p>{t('rules.setupDescription')}</p>
          </header>
          <div className="rules-hub-grid">
            {dailySettingLinks.map((setting, index) => {
              const SettingIcon = setting.icon;
              const settingTitle = t(setting.titleKey);
              return (
                <Card className="rules-hub-card" key={setting.path}>
                  <div className="rules-hub-card__meta">
                    <span>{t('rules.step', { step: index + 1 })}</span>
                    <SettingIcon aria-hidden="true" stroke={2} />
                  </div>
                  <h3>{settingTitle}</h3>
                  <p>{t(setting.descriptionKey)}</p>
                  <Button
                    type="primary"
                    aria-label={`${settingTitle}：${t('rules.openSetting')}`}
                    onClick={() => navigate(setting.path)}
                  >
                    {t('rules.openSetting')}
                  </Button>
                </Card>
              );
            })}
          </div>
        </section>
      ) : null}
      {canReadPolicyTemplates ? (
        <section className="rules-hub-section" aria-labelledby="rules-advanced-settings-title">
          <header className="rules-hub-section__header">
            <h2 id="rules-advanced-settings-title">{t('rules.advancedSectionTitle')}</h2>
            <p>{t('rules.advancedSectionDescription')}</p>
          </header>
          <Card className="rules-hub-card rules-hub-card--advanced">
            <div className="rules-hub-card__meta">
              <span>{t('rules.advancedSectionTitle')}</span>
              <IconAdjustments aria-hidden="true" stroke={2} />
            </div>
            <h3>{t('rules.advancedTitle')}</h3>
            <p>{t('rules.advancedDescription')}</p>
            <Button onClick={() => navigate('/rules/templates')}>
              {t('rules.enterTemplates')}
            </Button>
          </Card>
        </section>
      ) : null}
    </>
  );
}

export default RulesHomePage;
