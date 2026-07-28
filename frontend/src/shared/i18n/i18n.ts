import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';

import { zhCNMessages } from './messages';

void i18n
  .use(initReactI18next)
  .init({
    lng: 'zh-CN',
    fallbackLng: 'zh-CN',
    keySeparator: false,
    resources: {
      'zh-CN': {
        translation: zhCNMessages,
      },
    },
    interpolation: {
      escapeValue: false,
    },
  });

export default i18n;
