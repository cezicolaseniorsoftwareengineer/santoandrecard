/**
 * Endpoints resolved at runtime rather than compiled into the bundle.
 *
 * The same built image has to run against the local Compose stack and inside the
 * cluster, where the identity provider and the API answer on different hosts.
 * Baking them in at build time would mean a rebuild per environment, so they are
 * read from `/config.json` during bootstrap.
 *
 * The values below are the local development endpoints and are used verbatim
 * when that file is absent, which keeps `npm start` working with nothing extra
 * to serve.
 */
export interface RuntimeConfig {
  readonly issuer: string;
  readonly clientId: string;
  readonly scope: string;
  readonly apiBaseUrl: string;
}

const DEFAULTS: RuntimeConfig = {
  issuer: 'http://localhost:8180/realms/card-platform',
  clientId: 'card-service',
  scope: 'openid profile email',
  apiBaseUrl: 'http://localhost:8080/api/v1'
};

export let AUTH_CONFIG = {
  issuer: DEFAULTS.issuer,
  clientId: DEFAULTS.clientId,
  redirectUri: `${window.location.origin}/`,
  scope: DEFAULTS.scope
};

export let API_BASE_URL = DEFAULTS.apiBaseUrl;

/**
 * Applies `/config.json` before the application boots. A missing or malformed
 * file falls back to the development defaults instead of failing the bootstrap:
 * an interface that cannot start gives the operator less to work with than one
 * that starts pointing at the documented local endpoints.
 */
export async function loadRuntimeConfig(): Promise<void> {
  try {
    const response = await fetch('config.json', { cache: 'no-store' });
    if (!response.ok) return;
    const config = (await response.json()) as Partial<RuntimeConfig>;
    AUTH_CONFIG = {
      issuer: config.issuer ?? DEFAULTS.issuer,
      clientId: config.clientId ?? DEFAULTS.clientId,
      redirectUri: `${window.location.origin}/`,
      scope: config.scope ?? DEFAULTS.scope
    };
    API_BASE_URL = config.apiBaseUrl ?? DEFAULTS.apiBaseUrl;
  } catch {
    // Network failure or invalid JSON: the defaults already hold.
  }
}
