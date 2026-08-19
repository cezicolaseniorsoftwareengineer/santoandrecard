import { HttpRequest, HttpHandlerFn, HttpResponse } from '@angular/common/http';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { API_BASE_URL } from './auth.config';
import { idempotencyInterceptor } from './idempotency.interceptor';

/**
 * The API refuses a money-moving request that carries no idempotency key. The
 * interface sent none, and every top-up, card load and purchase answered 400 —
 * proven against a running service with a real customer token before this
 * interceptor existed.
 */
describe('idempotencyInterceptor', () => {
  function send(request: HttpRequest<unknown>): HttpRequest<unknown> {
    let seen!: HttpRequest<unknown>;
    const next: HttpHandlerFn = handled => {
      seen = handled;
      return of(new HttpResponse({ status: 200 }));
    };
    idempotencyInterceptor(request, next).subscribe();
    return seen;
  }

  it('adds a key to a request that moves money', () => {
    const sent = send(new HttpRequest('POST', `${API_BASE_URL}/wallet/top-ups`, { amount: 100 }));

    expect(sent.headers.get('Idempotency-Key')).toBeTruthy();
  });

  it('gives each call its own key, so two transfers are never collapsed into one', () => {
    const first = send(new HttpRequest('POST', `${API_BASE_URL}/wallet/top-ups`, { amount: 100 }));
    const second = send(new HttpRequest('POST', `${API_BASE_URL}/wallet/top-ups`, { amount: 100 }));

    expect(first.headers.get('Idempotency-Key')).not.toBe(second.headers.get('Idempotency-Key'));
  });

  it('never overwrites a key the caller supplied', () => {
    // A retry of a call already sent has to reuse the original key, or it stops
    // being a retry and becomes a second payment.
    const request = new HttpRequest('POST', `${API_BASE_URL}/wallet/top-ups`, { amount: 100 },
      { headers: new HttpRequest('POST', '/', null).headers.set('Idempotency-Key', 'chosen-by-caller') });

    expect(send(request).headers.get('Idempotency-Key')).toBe('chosen-by-caller');
  });

  it('leaves reads alone', () => {
    const sent = send(new HttpRequest('GET', `${API_BASE_URL}/wallet`));

    expect(sent.headers.has('Idempotency-Key')).toBe(false);
  });

  it('leaves other hosts alone', () => {
    // The key says something about this API's contract and belongs to no one else.
    const sent = send(new HttpRequest('POST', 'https://example.invalid/anything', {}));

    expect(sent.headers.has('Idempotency-Key')).toBe(false);
  });
});
