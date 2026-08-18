import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { API_BASE_URL } from './auth.config';
import { AppComponent } from './app.component';
import { AuthService } from './auth.service';
import { Session } from './bank.models';

class StubAuthService {
  session = () => this.current;
  constructor(private current: Session | null = null) {}
  restore = async () => this.current !== null;
  login = async () => undefined;
  logout = () => undefined;
  token = () => null;
}

/** Answers a request as soon as the component issues it. */
async function answer(http: HttpTestingController, url: string, body: object): Promise<void> {
  for (let attempt = 0; attempt < 100; attempt++) {
    const pending = http.match(url);
    if (pending.length > 0) {
      pending.forEach(request => request.flush(body));
      return;
    }
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  throw new Error(`the component never requested ${url}`);
}

async function render(session: Session | null): Promise<ComponentFixture<AppComponent>> {
  await TestBed.configureTestingModule({
    imports: [AppComponent],
    providers: [
      provideHttpClient(),
      provideHttpClientTesting(),
      { provide: AuthService, useValue: new StubAuthService(session) }
    ]
  }).compileComponents();

  const fixture = TestBed.createComponent(AppComponent);
  const http = TestBed.inject(HttpTestingController);
  const started = fixture.componentInstance.ngOnInit();

  if (session?.role === 'ADMIN') {
    await answer(http, `${API_BASE_URL}/admin/summary`, {
      customerWallets: 3, totalWalletBalance: 1500, purchasePrincipal: 800, interestRevenue: 83.34
    });
  } else if (session?.role === 'CUSTOMER') {
    await Promise.all([
      answer(http, `${API_BASE_URL}/wallet`, { customerId: 'c1', balance: 1166.66, cardBalance: 250 }),
      answer(http, `${API_BASE_URL}/cards`, [{
        id: 'card-1', customerId: 'c1', creditLimit: 5000, currency: 'BRL',
        status: 'ACTIVE', product: 'PLATINUM', productName: 'Santo André Card Platinum',
        lastFourDigits: '9808', pinDefined: false, createdAt: '2026-08-17T00:00:00Z'
      }]),
      answer(http, `${API_BASE_URL}/purchases?limit=50`, [])
    ]);
  }

  await started;
  fixture.detectChanges();
  return fixture;
}

describe('AppComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('offers provider sign-in and no local credential fields', async () => {
    const fixture = await render(null);
    const text: string = fixture.nativeElement.textContent;

    expect(text).toContain('Acesse sua conta');
    expect(text).toContain('provedor de identidade');
    // A password box would imply the browser authenticates the user; it does not.
    expect(fixture.nativeElement.querySelector('input[type="password"]')).toBeNull();
  });

  it('shows the customer area with figures returned by the API', async () => {
    const fixture = await render({ name: 'Ana Cardoso', role: 'CUSTOMER' });
    const text: string = fixture.nativeElement.textContent;

    expect(text).toContain('Saldo disponível');
    expect(text).toContain('Ana Cardoso');
    expect(text).toContain('9808');
    expect(text).toContain('No cartão pré-pago');
    // Without a PIN the card offers to create one rather than to reveal.
    expect(text).toContain('criar seu PIN');
    // The full number never travels with the card itself.
    expect(text).not.toContain('9999');
  });

  it('finishes the boot when the API accepts the request and never answers', async () => {
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: new StubAuthService({ name: 'Ana Cardoso', role: 'CUSTOMER' }) }
      ]
    }).compileComponents();

    const fixture = TestBed.createComponent(AppComponent);
    // Nothing is flushed: every request stays pending, which is what a hung
    // server looks like to the browser. The boot has to complete anyway.
    TestBed.inject(HttpTestingController);

    await fixture.componentInstance.ngOnInit();
    fixture.detectChanges();

    expect(fixture.componentInstance.starting()).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('Saldo disponível');
  }, 15000);

  it('shows consolidated figures and no wallet actions for an administrator', async () => {
    const fixture = await render({ name: 'Operador', role: 'ADMIN' });
    const text: string = fixture.nativeElement.textContent;

    expect(text).toContain('Carteira consolidada');
    expect(text).not.toContain('Adicionar saldo');
  });
});
