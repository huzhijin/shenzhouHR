import { IconFileAnalytics, IconGitBranch, IconShieldCheck } from '@tabler/icons-react';
import { Button, Card } from 'antd';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';

import { PageHeader } from '../../shared/components/PagePrimitives';

export function RulesHomePage() {
  const { t } = useTranslation();
  return (
    <>
      <PageHeader title={t('rules.title')} description={t('rules.description')} breadcrumbs={[{ label: t('rules.title') }]} actions={<Link to="/rules/templates"><Button type="primary">{t('rules.enterTemplates')}</Button></Link>} />
      <div className="summary-card-grid">
        <Card><IconGitBranch stroke={2} /><h2>{t('rules.traceableTitle')}</h2><p>{t('rules.traceableDescription')}</p></Card>
        <Card><IconShieldCheck stroke={2} /><h2>{t('rules.controlledTitle')}</h2><p>{t('rules.controlledDescription')}</p></Card>
        <Card><IconFileAnalytics stroke={2} /><h2>{t('rules.reviewTitle')}</h2><p>{t('rules.reviewDescription')}</p></Card>
      </div>
    </>
  );
}

export default RulesHomePage;
