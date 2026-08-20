// @vitest-environment jsdom
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AUTH_CONFIG } from './auth.config';
import { AuthService } from './auth.service';

/**
 * The signed-out route. Two defects made it circular: pressing "Entrar" after a
 * logout re-entered the previous account without ever showing the credentials
 * screen, and the logout redirect stopped on Keycloak's confirmation page
 * instead of returning to the application.
 */
describe('AuthService redirects', () => {
  let assigned: string;

  beforeEach(() => {
    assigned = '';
    sessionStorage.clear();
    vi.stubGlobal('location', { origin: 'http://127.0.0.1:4420', href: 'http://127.0.0.1:4420/', assign: (url: string) => { assigned = url; } });
    TestBed.configureTestingModule({});
  });

  function target(): URL {
    return new URL(assigned);
  }

  it('always asks the provider for the credentials screen on sign-in', async () => {
    await TestBed.inject(AuthService).login();

    expect(target().pathname).toContain('/protocol/openid-connect/auth');
    expect(target().searchParams.get('prompt')).toBe('login');
    expect(target().searchParams.get('code_challenge_method')).toBe('S256');
  });

  it('enters the provider at registration, where the account is created', async () => {
    await TestBed.inject(AuthService).register();

    expect(target().pathname).toContain('/protocol/openid-connect/registrations');
    // Registration is a login by definition; forcing the screen would be noise.
    expect(target().searchParams.get('prompt')).toBeNull();
  });

  it('returns to the sign-in screen after logout instead of a confirmation page', () => {
    const auth = TestBed.inject(AuthService);
    (auth as unknown as { idToken: string | null }).idToken = 'id.token.value';

    auth.logout();

    expect(target().pathname).toContain('/protocol/openid-connect/logout');
    expect(target().searchParams.get('id_token_hint')).toBe('id.token.value');
    expect(target().searchParams.get('post_logout_redirect_uri')).toBe(AUTH_CONFIG.redirectUri);
    expect(sessionStorage.getItem('auth.refresh_token')).toBeNull();
  });
});
