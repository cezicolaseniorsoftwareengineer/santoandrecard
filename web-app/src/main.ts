import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { LOCALE_ID } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { AppComponent } from './app/app.component';
import { authInterceptor } from './app/auth.interceptor';
import { loadRuntimeConfig } from './app/auth.config';

// Registering the locale data is not enough on its own: LOCALE_ID must also be
// set, otherwise money renders as R$1,116.66 instead of R$ 1.116,66.
registerLocaleData(localePt, 'pt-BR');

// The endpoints must be resolved before anything can call the API or start an
// authorization flow, so the bootstrap waits on them.
loadRuntimeConfig()
  .then(() =>
    bootstrapApplication(AppComponent, {
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        { provide: LOCALE_ID, useValue: 'pt-BR' }
      ]
    })
  )
  .catch((error: unknown) => console.error(error));
