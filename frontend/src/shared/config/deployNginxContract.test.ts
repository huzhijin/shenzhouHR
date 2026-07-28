import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const requiredSecurityHeaders = [
  'Strict-Transport-Security',
  'X-Content-Type-Options',
  'Referrer-Policy',
  'Permissions-Policy',
  'Content-Security-Policy',
] as const;

describe('production Nginx response-header contract', () => {
  it('keeps security headers at server scope while locations override caching', () => {
    const nginx = readRepositoryFile('deploy/nginx/shenzhouhr.conf');
    const firstLocation = nginx.indexOf('    location ');

    expect(firstLocation, 'TLS server must define request locations').toBeGreaterThan(0);

    const serverDirectives = nginx.slice(0, firstLocation);
    const locationDirectives = nginx.slice(firstLocation);

    for (const header of requiredSecurityHeaders) {
      expect(serverDirectives).toContain(`add_header ${header} `);
    }

    expect(locationDirectives).not.toMatch(/^\s*add_header\s+/m);
    expect(serverDirectives).toContain(
      'add_header Cache-Control $shenzhouhr_cache_control always;',
    );
    expect(locationDirectives).toContain(
      'set $shenzhouhr_cache_control "public, max-age=31536000, immutable";',
    );
    expect(locationDirectives).toContain('proxy_hide_header Cache-Control;');
  });

  it('delivers one per-request nonce through HTML, Vite, Nginx, and App', () => {
    const nginx = readRepositoryFile('deploy/nginx/shenzhouhr.conf');
    const html = readRepositoryFile('frontend/index.html');
    const viteConfig = readRepositoryFile('frontend/vite.config.ts');
    const nonceReader = readRepositoryFile('frontend/src/shared/security/cspNonce.ts');
    const noncePlaceholder = '__CSP_NONCE__';

    expect(html).not.toContain("'unsafe-inline'");
    expect(html).toContain(`'nonce-${noncePlaceholder}'`);
    expect(html.split(noncePlaceholder)).toHaveLength(2);
    expect(viteConfig).toContain(`cspNonce: '${noncePlaceholder}'`);
    expect(nginx).toContain(`sub_filter ${noncePlaceholder} $request_id;`);
    expect(nginx).toContain('sub_filter_once off;');
    expect(nginx).toContain("'nonce-$request_id'");
    expect(nonceReader).toContain('meta[property="csp-nonce"][nonce]');
  });

  it('emits the nonce meta element consumed by Ant Design in production HTML', () => {
    const testDirectory = dirname(fileURLToPath(import.meta.url));
    const frontendRoot = resolve(testDirectory, '../../..');
    const viteCli = resolve(frontendRoot, 'node_modules/vite/bin/vite.js');
    execFileSync(
      process.execPath,
      [
        viteCli,
        'build',
        '--configLoader',
        'runner',
        '--logLevel',
        'silent',
      ],
      {
        cwd: frontendRoot,
        stdio: 'pipe',
      },
    );
    const html = readFileSync(`${frontendRoot}/dist/prod/index.html`, 'utf8');
    expect(html).toContain(
      '<meta property="csp-nonce" nonce="__CSP_NONCE__">',
    );
    expect(html).toMatch(/<(script|link)[^>]+nonce="__CSP_NONCE__"/);

    const deliveredHtml = html.replaceAll('__CSP_NONCE__', 'request-7f3b');
    expect(deliveredHtml).toContain(
      '<meta property="csp-nonce" nonce="request-7f3b">',
    );
    expect(deliveredHtml).not.toContain('__CSP_NONCE__');
  }, 60_000);
});

function readRepositoryFile(relativePath: string): string {
  const testDirectory = dirname(fileURLToPath(import.meta.url));
  const repositoryRoot = resolve(testDirectory, '../../../..');
  return readFileSync(resolve(repositoryRoot, relativePath), 'utf8');
}
