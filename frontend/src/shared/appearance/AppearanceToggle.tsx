import { IconMoon, IconSun } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';

import { AccessibleButton } from '../components/AccessibleButton';
import { useAppearance } from './AppearanceProvider';

export function AppearanceToggle() {
  const { t } = useTranslation();
  const { appearance, setAppearance } = useAppearance();
  const night = appearance === 'night';
  return (
    <AccessibleButton
      type="text"
      label={night ? t('app.appearanceDay') : t('app.appearanceNight')}
      icon={night
        ? <IconSun aria-hidden="true" stroke={2} />
        : <IconMoon aria-hidden="true" stroke={2} />}
      onClick={() => setAppearance(night ? 'day' : 'night')}
    >
      <span className="app-topbar__action-label">
        {night ? t('app.appearanceDay') : t('app.appearanceNight')}
      </span>
    </AccessibleButton>
  );
}
