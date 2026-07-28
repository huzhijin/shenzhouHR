/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_DEVELOPMENT_PRINCIPAL_ID?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

