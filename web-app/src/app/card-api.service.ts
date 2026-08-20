import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from './auth.config';
import { AdminSummary, CardBalances, CardNumberResponse, CardResponse, InterestPolicy, FundingSource, InvoiceItemResponse, InvoiceResponse, PurchaseQuote, PurchaseResponse, WalletResponse } from './bank.models';

/** Thin typed client over the card-service REST API. */
@Injectable({ providedIn: 'root' })
export class CardApi {
  private readonly http = inject(HttpClient);

  cards(): Observable<readonly CardResponse[]> {
    return this.http.get<readonly CardResponse[]>(`${API_BASE_URL}/cards`);
  }

  wallet(): Observable<WalletResponse> {
    return this.http.get<WalletResponse>(`${API_BASE_URL}/wallet`);
  }

  statement(limit = 50): Observable<readonly PurchaseResponse[]> {
    return this.http.get<readonly PurchaseResponse[]>(`${API_BASE_URL}/purchases`, { params: { limit } });
  }

  /** Issues the calling customer's own card. The limit is issuer policy, so the request carries no body. */
  issueCard(): Observable<CardResponse> {
    return this.http.post<CardResponse>(`${API_BASE_URL}/cards/self-service`, null);
  }

  /** Moves the customer's own money from the wallet onto the card. */
  loadCard(amount: number): Observable<CardBalances> {
    return this.http.post<CardBalances>(`${API_BASE_URL}/wallet/card-loads`, { amount });
  }

  setPin(cardId: string, pin: string): Observable<CardResponse> {
    return this.http.put<CardResponse>(`${API_BASE_URL}/cards/${cardId}/pin`, { pin });
  }

  /** POST because it carries a secret and every attempt is counted by the API. */
  revealNumber(cardId: string, pin: string): Observable<CardNumberResponse> {
    return this.http.post<CardNumberResponse>(`${API_BASE_URL}/cards/${cardId}/number`, { pin });
  }

  topUp(amount: number): Observable<WalletResponse> {
    return this.http.post<WalletResponse>(`${API_BASE_URL}/wallet/top-ups`, { amount });
  }

  quote(amount: number, installments: number): Observable<PurchaseQuote> {
    return this.http.post<PurchaseQuote>(`${API_BASE_URL}/purchases/quote`, { amount, installments });
  }

  purchase(merchantCategory: string, amount: number, installments: number,
           fundingSource: FundingSource = 'CARD'): Observable<PurchaseResponse> {
    return this.http.post<PurchaseResponse>(`${API_BASE_URL}/purchases`, { merchantCategory, amount, installments });
  }

  adminSummary(): Observable<AdminSummary> {
    return this.http.get<AdminSummary>(`${API_BASE_URL}/admin/summary`);
  }

  /** The rate in force. Readable by customer and administrator alike. */
  interestPolicy(): Observable<InterestPolicy> {
    return this.http.get<InterestPolicy>(`${API_BASE_URL}/interest-policy`);
  }

  setInterestPolicy(monthlyRate: number): Observable<InterestPolicy> {
    return this.http.put<InterestPolicy>(`${API_BASE_URL}/admin/interest-policy`, { monthlyRate });
  }

  /** The caller's statements, most recent cycle first. */
  invoices(limit = 12): Observable<readonly InvoiceResponse[]> {
    return this.http.get<readonly InvoiceResponse[]>(`${API_BASE_URL}/statements`, { params: { limit } });
  }

  /** The lines that made up one statement. */
  invoiceItems(id: string): Observable<readonly InvoiceItemResponse[]> {
    return this.http.get<readonly InvoiceItemResponse[]>(`${API_BASE_URL}/statements/${id}/items`);
  }

  /** Pays a statement from the wallet. Idempotent, like every command that moves money. */
  payInvoice(id: string, amount: number): Observable<InvoiceResponse> {
    return this.http.post<InvoiceResponse>(`${API_BASE_URL}/statements/${id}/payments`, { amount });
  }

  /**
   * Closes a cycle on demand.
   *
   * <p>Offered so a reader can watch a cycle bill without waiting a month. The
   * API bills only the caller's own purchases and returns an already-closed
   * cycle unchanged.
   */
  closeCycle(cycle: string): Observable<InvoiceResponse> {
    return this.http.post<InvoiceResponse>(`${API_BASE_URL}/statements/close`, { cycle });
  }
}
