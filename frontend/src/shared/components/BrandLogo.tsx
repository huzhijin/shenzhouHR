import { useState } from 'react';

import shenzhouLogo from '../../assets/shenzhou-logo.svg';
import { translate } from '../i18n/messages';

export function BrandLogo({ compact = false }: { compact?: boolean }) {
  const [failed, setFailed] = useState(false);
  const companyName = translate('app.companyName');
  if (failed) {
    return <span className={`brand-logo__fallback${compact ? ' brand-logo__fallback--compact' : ''}`}>{companyName}</span>;
  }
  return (
    <img alt={companyName}
      className={`brand-logo${compact ? ' brand-logo--compact' : ''}`}
      src={shenzhouLogo}
      width="163"
      height="37"
      onError={() => setFailed(true)}
    />
  );
}
