import { Injectable, computed, signal } from '@angular/core';
import { AUTH_CONFIG } from './auth.config';
import { Session, UserRole } from './bank.models';

interface TokenResponse {
  readonly access_token: string;
  readonly refresh_token?: string;
  readonly id_token?: string;
  readonly expires_in: number;
}

interface AccessTokenClaims {
  readonly preferred_username?: string;
  readonly name?: string;
  readonly realm_access?: { readonly roles?: readonly string[] };
  readonly exp?: number;
}

const VERIFIER_KEY = 'auth.pkce.verifier';
const STATE_KEY = 'auth.pkce.state';
const REFRESH_KEY = 'auth.refresh_token';

/**
 * OpenID Connect authorization code flow with PKCE against Keycloak.
 *
 * The access token is held in memory only. The refresh token lives in
 * sessionStorage so a page reload does not force a new login; sessionStorage is
 * cleared when the tab closes and is not shared with other tabs or origins.
 * Neither token is written to localStorage, which would survive indefinitely.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly accessToken = signal<string | null>(null);
  private readonly claims = signal<AccessTokenClaims | null>(null);
  /**
   * Kept only to be handed back on logout as `id_token_hint`. Without it
   * Keycloak cannot tell which session is ending, so it stops on a confirmation
   * page instead of returning to the application.
   */
  private idToken: string | null = null;
  private refreshTimer: number | null = null;

  readonly session = computed<Session | null>(() => {
    const claims = this.claims();
    if (!claims) return null;
    const role = this.roleFrom(claims);
    if (!role) return null;
    return { name: claims.name ?? claims.preferred_username ?? 'Usuário', role };
  });

  readonly isAuthenticated = computed(() => this.session() !== null);

  token(): string | null {
    return this.accessToken();
  }

  /**
   * Redirects the browser to Keycloak. Resolves only if the redirect is blocked.
   *
   * `prompt=login` forces the credentials screen even when a provider session
   * cookie survives. Signing out and pressing "Entrar" has to be an opportunity
   * to choose an account, not a silent re-entry into the previous one.
   */
  async login(): Promise<void> {
    return this.authorize('auth', { prompt: 'login' });
  }

  /**
   * Same authorization code flow, entered at the provider's registration screen.
   * Account creation stays in the identity provider: the application never sees
   * or stores a password, and a self-registered account arrives already carrying
   * the realm's default `customer` role.
   */
  async register(): Promise<void> {
    return this.authorize('registrations');
  }

  private async authorize(
    endpoint: 'auth' | 'registrations',
    extra: Record<string, string> = {}
  ): Promise<void> {
    const verifier = this.randomString(64);
    const state = this.randomString(32);
    sessionStorage.setItem(VERIFIER_KEY, verifier);
    sessionStorage.setItem(STATE_KEY, state);

    const params = new URLSearchParams({
      response_type: 'code',
      client_id: AUTH_CONFIG.clientId,
      redirect_uri: AUTH_CONFIG.redirectUri,
      scope: AUTH_CONFIG.scope,
      state,
      code_challenge: await this.challengeFor(verifier),
      code_challenge_method: 'S256',
      ...extra
    });
    window.location.assign(`${AUTH_CONFIG.issuer}/protocol/openid-connect/${endpoint}?${params}`);
  }

  /**
   * Completes the flow when Keycloak redirects back. Returns true when a session
   * was established, either from the authorization code or from a stored refresh
   * token after a page reload.
   */
  async restore(): Promise<boolean> {
    const url = new URL(window.location.href);
    const code = url.searchParams.get('code');
    const returnedState = url.searchParams.get('state');

    if (code) {
      const expectedState = sessionStorage.getItem(STATE_KEY);
      const verifier = sessionStorage.getItem(VERIFIER_KEY);
      sessionStorage.removeItem(STATE_KEY);
      sessionStorage.removeItem(VERIFIER_KEY);
      // Clear the code from the address bar before anything else, so it is not
      // kept in browser history or leaked through a copied URL.
      window.history.replaceState({}, document.title, url.pathname);

      // A mismatched state means the response does not belong to the request this
      // tab started, so the code is discarded rather than exchanged.
      if (!verifier || !expectedState || expectedState !== returnedState) return false;
      return this.exchange(new URLSearchParams({
        grant_type: 'authorization_code',
        client_id: AUTH_CONFIG.clientId,
        code,
        redirect_uri: AUTH_CONFIG.redirectUri,
        code_verifier: verifier
      }));
    }

    const refreshToken = sessionStorage.getItem(REFRESH_KEY);
    if (!refreshToken) return false;
    return this.refresh(refreshToken);
  }

  logout(): void {
    const idToken = this.idToken;
    this.clear();
    const params = new URLSearchParams({
      client_id: AUTH_CONFIG.clientId,
      post_logout_redirect_uri: AUTH_CONFIG.redirectUri
    });
    // The hint is what makes this a logout rather than a question: with it
    // Keycloak ends the session and redirects straight back to the sign-in
    // screen; without it the browser stops on a confirmation page, and a user
    // who does not confirm keeps a live provider session behind a signed-out
    // interface. A single front-channel redirect also avoids racing a
    // background request against the navigation that cancels it.
    if (idToken) params.set('id_token_hint', idToken);
    window.location.assign(`${AUTH_CONFIG.issuer}/protocol/openid-connect/logout?${params}`);
  }

  private async refresh(refreshToken: string): Promise<boolean> {
    return this.exchange(new URLSearchParams({
      grant_type: 'refresh_token',
      client_id: AUTH_CONFIG.clientId,
      refresh_token: refreshToken
    }));
  }

  private async exchange(body: URLSearchParams): Promise<boolean> {
    try {
      const response = await fetch(`${AUTH_CONFIG.issuer}/protocol/openid-connect/token`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body
      });
      if (!response.ok) {
        this.clear();
        return false;
      }
      const token = (await response.json()) as TokenResponse;
      this.accessToken.set(token.access_token);
      this.claims.set(this.decode(token.access_token));
      if (token.id_token) this.idToken = token.id_token;
      if (token.refresh_token) sessionStorage.setItem(REFRESH_KEY, token.refresh_token);
      this.scheduleRefresh(token.expires_in);
      return this.session() !== null;
    } catch {
      this.clear();
      return false;
    }
  }

  /** Renews shortly before expiry so an in-flight request is not rejected mid-session. */
  private scheduleRefresh(expiresInSeconds: number): void {
    if (this.refreshTimer !== null) window.clearTimeout(this.refreshTimer);
    const aheadOfExpiry = Math.max((expiresInSeconds - 30) * 1000, 5000);
    this.refreshTimer = window.setTimeout(() => {
      const refreshToken = sessionStorage.getItem(REFRESH_KEY);
      if (refreshToken) void this.refresh(refreshToken);
      else this.clear();
    }, aheadOfExpiry);
  }

  private clear(): void {
    if (this.refreshTimer !== null) window.clearTimeout(this.refreshTimer);
    this.refreshTimer = null;
    this.accessToken.set(null);
    this.claims.set(null);
    this.idToken = null;
    sessionStorage.removeItem(REFRESH_KEY);
  }

  /**
   * Reads the role for display and navigation only. The token signature is not
   * verified here: the browser is not a trust boundary, and every decision that
   * matters is enforced by the API against the signed token.
   */
  private roleFrom(claims: AccessTokenClaims): UserRole | null {
    const roles = claims.realm_access?.roles ?? [];
    if (roles.includes('admin')) return 'ADMIN';
    if (roles.includes('customer')) return 'CUSTOMER';
    return null;
  }

  private decode(token: string): AccessTokenClaims | null {
    const payload = token.split('.')[1];
    if (!payload) return null;
    try {
      const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
      return JSON.parse(decodeURIComponent(escape(json))) as AccessTokenClaims;
    } catch {
      return null;
    }
  }

  private randomString(length: number): string {
    const bytes = crypto.getRandomValues(new Uint8Array(length));
    return this.base64Url(bytes.buffer);
  }

  private async challengeFor(verifier: string): Promise<string> {
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
    return this.base64Url(digest);
  }

  private base64Url(buffer: ArrayBuffer): string {
    const bytes = new Uint8Array(buffer);
    let binary = '';
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  }
}
