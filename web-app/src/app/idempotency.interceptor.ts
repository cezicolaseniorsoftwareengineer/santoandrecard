import { HttpInterceptorFn } from '@angular/common/http';
import { API_BASE_URL } from './auth.config';

const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH']);
const HEADER = 'Idempotency-Key';

/**
 * Attaches an idempotency key to every request that can change state.
 *
 * The API requires this header on the operations that move money and refuses
 * the request without it. The interface sent none, so a top-up, a card load and
 * a purchase all failed with 400 — the interface could not move money at all.
 *
 * The key is generated per request rather than per session: it exists so that a
 * caller who did not see the response can ask again without paying twice, and
 * a key reused across two genuinely different transfers would collapse them
 * into one. Each call the user makes is a distinct intent and gets a distinct
 * key; a retry of a call already sent must reuse the original key, which is why
 * an explicitly supplied header is never overwritten.
 */
export const idempotencyInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith(API_BASE_URL)) return next(request);
  if (!MUTATING_METHODS.has(request.method.toUpperCase())) return next(request);
  if (request.headers.has(HEADER)) return next(request);

  return next(request.clone({ setHeaders: { [HEADER]: newKey() } }));
};

/**
 * `crypto.randomUUID` is unavailable outside a secure context, which includes
 * plain HTTP on anything other than localhost. Falling back keeps the interface
 * working there rather than failing every payment with a ReferenceError.
 */
function newKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('');
}
