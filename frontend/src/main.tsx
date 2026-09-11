import 'antd/dist/reset.css';
import './styles/tokens.css';
import './styles/global.css';

import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';
import localeData from 'dayjs/plugin/localeData';
import weekday from 'dayjs/plugin/weekday';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';

import { App } from './app/App';
import { applyAppearance, readStoredAppearance } from './shared/appearance/appearance';
import './shared/i18n/i18n';

dayjs.extend(weekday);
dayjs.extend(localeData);
dayjs.locale('zh-cn');

applyAppearance(readStoredAppearance());

const rootElement = document.getElementById('root');
if (!rootElement) {
  throw new Error('root element is required');
}

createRoot(rootElement).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
);
