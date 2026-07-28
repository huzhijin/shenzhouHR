export function readCspNonce(): string | undefined {
  if (typeof document === 'undefined') {
    return undefined;
  }
  return document.querySelector<HTMLMetaElement>(
    'meta[property="csp-nonce"][nonce]',
  )?.nonce || undefined;
}
