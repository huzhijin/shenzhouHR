import { afterEach, describe, expect, it } from 'vitest';

import { readCspNonce } from './cspNonce';

describe('readCspNonce', () => {
  afterEach(() => {
    document.head.replaceChildren();
  });

  it('reads the request nonce from the Vite-generated meta element', () => {
    const meta = document.createElement('meta');
    meta.setAttribute('property', 'csp-nonce');
    meta.nonce = 'request-7f3b';
    document.head.append(meta);

    expect(readCspNonce()).toBe('request-7f3b');
  });

  it('does not accept a meta element without a nonce', () => {
    const meta = document.createElement('meta');
    meta.setAttribute('property', 'csp-nonce');
    document.head.append(meta);

    expect(readCspNonce()).toBeUndefined();
  });
});
