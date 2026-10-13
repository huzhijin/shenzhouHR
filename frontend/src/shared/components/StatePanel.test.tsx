import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import '../i18n/i18n';
import { StatePanel } from './StatePanel';

describe('StatePanel', () => {
  afterEach(cleanup);

  it('uses browser-compatible numeric SVG dimensions', () => {
    const { container } = render(
      <StatePanel
        state="empty"
        title="本人考勤工作台尚未生成"
      />,
    );

    const icon = container.querySelector('.state-panel > svg');
    expect(icon).toHaveAttribute('width', '32');
    expect(icon).toHaveAttribute('height', '32');
    expect(icon).not.toHaveAttribute('width', expect.stringContaining('var('));
    expect(icon).not.toHaveAttribute('height', expect.stringContaining('var('));
  });
});
