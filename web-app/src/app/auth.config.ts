/** Runtime endpoints. Overridden per environment at build time. */
export const AUTH_CONFIG = {
  issuer: 'http://localhost:8180/realms/card-platform',
  clientId: 'card-service',
  redirectUri: `${window.location.origin}/`,
  scope: 'openid profile email'
} as const;

export const API_BASE_URL = 'http://localhost:8080/api/v1';
