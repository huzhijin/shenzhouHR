import react from '@vitejs/plugin-react';
import { loadEnv, type UserConfig } from 'vite';
import { defineConfig } from 'vitest/config';

export default defineConfig(({ mode }) => {
  const environment = loadEnv(mode, process.cwd(), '');
  const demoMode = mode === 'demo';
  const apiProxyTarget = optionalEnvironmentValue(environment.VITE_API_PROXY_TARGET);
  const developmentHost = optionalEnvironmentValue(environment.VITE_DEV_HOST);
  const developmentPort = optionalPort(environment.VITE_DEV_PORT);

  return {
    cacheDir: 'node_modules/.cache/vite',
    html: {
      cspNonce: '__CSP_NONCE__',
    },
    plugins: [react()],
    build: {
      outDir: demoMode ? 'dist/demo' : 'dist/prod',
      emptyOutDir: true,
    },
    server: developmentServer({
      apiProxyTarget,
      developmentHost,
      developmentPort,
      demoMode,
    }),
    test: {
      environment: 'jsdom',
      include: ['src/**/*.test.{ts,tsx}'],
      setupFiles: ['src/test/setup.ts'],
      testTimeout: 15000,
      deps: {
        optimizer: {
          client: { enabled: false },
          ssr: { enabled: false },
        },
      },
      experimental: {
        fsModuleCache: false,
      },
    },
  };
});

function optionalEnvironmentValue(value: string | undefined): string | undefined {
  const normalized = value?.trim();
  return normalized ? normalized : undefined;
}

function optionalPort(value: string | undefined): number | undefined {
  const normalized = optionalEnvironmentValue(value);
  if (!normalized) return undefined;
  const parsed = Number.parseInt(normalized, 10);
  return Number.isInteger(parsed) && parsed >= 1 && parsed <= 65_535 ? parsed : undefined;
}

function developmentServer(options: {
  apiProxyTarget?: string;
  developmentHost?: string;
  developmentPort?: number;
  demoMode: boolean;
}): UserConfig['server'] {
  const entries: Array<[string, unknown]> = [['strictPort', true]];
  if (options.developmentHost) entries.push(['host', options.developmentHost]);
  if (options.developmentPort) entries.push(['port', options.developmentPort]);
  if (!options.demoMode && options.apiProxyTarget) {
    entries.push(['proxy', {
      '/api': {
        target: options.apiProxyTarget,
        changeOrigin: false,
      },
    }]);
  }
  return Object.fromEntries(entries) as NonNullable<UserConfig['server']>;
}
