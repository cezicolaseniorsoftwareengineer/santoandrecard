import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { API_BASE_URL } from './auth.config';
import { AuthService } from './auth.service';

/**
 * Attaches the access token to API calls only. Sending it to any other host would
 * hand the user's credential to a third party.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  if (!request.url.startsWith(API_BASE_URL)) return next(request);

  const token = inject(AuthService).token();
  if (!token) return next(request);

  return next(request.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
};
