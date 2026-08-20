import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { API_BASE_URL } from './auth.config';
import { BankStore, describe as describeError } from './bank-store.service';

describe('BankStore', () => {
  let store: BankStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    store = TestBed.inject(BankStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reads the wallet balance from the API instead of computing it locally', async () => {
    const pending = store.addBalance(100);
    const request = http.expectOne(`${API_BASE_URL}/wallet/top-ups`);
    expect(request.request.body).toEqual({ amount: 100 });
    request.flush({ customerId: 'c1', balance: 340.5 });

    expect(await pending).toBeNull();
    expect(store.balance()).toBe(340.5);
  });

  it('never sends a customer identifier in the request body', async () => {
    const pending = store.purchase('Padaria', 25, 1);
    const request = http.expectOne(`${API_BASE_URL}/purchases`);
    expect(Object.keys(request.request.body as object)).toEqual(['merchantCategory', 'amount', 'installments']);
    request.flush({
      id: 'p1', customerId: 'c1', merchantCategory: 'Padaria', principal: 25, interest: 0,
      total: 25, installments: 1, installmentAmount: 25, lastInstallmentAmount: 25,
      monthlyRate: 0.02, remainingCardBalance: 75, createdAt: '2026-08-17T00:00:00Z'
    });

    expect(await pending).toBeNull();
    // The card is what pays for a purchase, so the card balance is what moved.
    expect(store.cardBalance()).toBe(75);
  });

  it('asks the API for the quote rather than calculating interest', async () => {
    const pending = store.quote(600, 6);
    const request = http.expectOne(`${API_BASE_URL}/purchases/quote`);
    request.flush({ principal: 600, interest: 75.3, total: 675.3, installments: 6, installmentAmount: 112.55 });

    expect(await pending).toEqual({ principal: 600, interest: 75.3, total: 675.3, installments: 6, installmentAmount: 112.55 });
  });

  it('rejects an out-of-range instalment count before calling the API', async () => {
    expect(await store.quote(100, 36)).toBe('O parcelamento aceita de 1 a 12 parcelas.');
    expect(await store.quote(100, 13)).toBe('O parcelamento aceita de 1 a 12 parcelas.');
  });

  it('reports insufficient funds without discarding the balance', async () => {
    const pending = store.purchase('Shopping', 999, 1);
    http.expectOne(`${API_BASE_URL}/purchases`)
      .flush({ code: 'INSUFFICIENT_FUNDS' }, { status: 422, statusText: 'Unprocessable Entity' });

    expect(await pending).toBe('Saldo insuficiente para esta operação.');
  });

  it('states that nothing was debited when merchant authorization is unavailable', () => {
    const message = describeError(new HttpErrorResponse({ status: 503, error: { code: 'MERCHANT_AUTHORIZATION_UNAVAILABLE' } }));
    expect(message).toContain('Nenhum valor foi debitado');
  });

  it('distinguishes an unreachable backend from a denied request', () => {
    expect(describeError(new HttpErrorResponse({ status: 0 }))).toContain('Serviço indisponível');
    expect(describeError(new HttpErrorResponse({ status: 403 }))).toContain('permissão');
    expect(describeError(new HttpErrorResponse({ status: 429 }))).toContain('alto volume');
  });
});
