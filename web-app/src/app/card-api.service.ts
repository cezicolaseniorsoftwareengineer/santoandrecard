import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from './auth.config';
import { AdminSummary, CardResponse, InterestPolicy, PurchaseQuote, PurchaseResponse, WalletResponse } from './bank.models';

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

  topUp(amount: number): Observable<WalletResponse> {
    return this.http.post<WalletResponse>(`${API_BASE_URL}/wallet/top-ups`, { amount });
  }

  quote(amount: number, installments: number): Observable<PurchaseQuote> {
    return this.http.post<PurchaseQuote>(`${API_BASE_URL}/purchases/quote`, { amount, installments });
  }

  purchase(merchantCategory: string, amount: number, installments: number): Observable<PurchaseResponse> {
    return this.http.post<PurchaseResponse>(`${API_BASE_URL}/purchases`, { merchantCategory, amount, installments });
  }

  adminSummary(): Observable<AdminSummary> {
    return this.http.get<AdminSummary>(`${API_BASE_URL}/admin/summary`);
  }

  setInterestPolicy(monthlyRate: number): Observable<InterestPolicy> {
    return this.http.put<InterestPolicy>(`${API_BASE_URL}/admin/interest-policy`, { monthlyRate });
  }
}
