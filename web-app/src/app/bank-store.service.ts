import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AuthService } from './auth.service';
import { CardApi } from './card-api.service';
import { AdminSummary, CardResponse, PurchaseQuote, PurchaseResponse } from './bank.models';

/**
 * Application state backed by the card-service API. Every figure shown in the
 * interface comes from a response; nothing is computed locally, so the interface
 * cannot disagree with the ledger of record.
 */
@Injectable({ providedIn: 'root' })
export class BankStore {
  private readonly api = inject(CardApi);
  private readonly auth = inject(AuthService);

  readonly session = this.auth.session;
  readonly balance = signal<number | null>(null);
  readonly cardBalance = signal<number | null>(null);
  /** Held in memory only, and only while the holder keeps it revealed. */
  readonly revealedNumber = signal<string | null>(null);
  readonly cards = signal<readonly CardResponse[]>([]);
  readonly purchases = signal<readonly PurchaseResponse[]>([]);
  readonly adminSummary = signal<AdminSummary | null>(null);
  readonly loading = signal(false);

  readonly card = computed(() => this.cards()[0] ?? null);
  readonly committed = computed(() => this.purchases().reduce((sum, p) => sum + p.total, 0));
  readonly availableLimit = computed(() => {
    const card = this.card();
    return card ? card.creditLimit - this.committed() : 0;
  });

  /** Loads everything the signed-in role is allowed to read. */
  async refresh(): Promise<string | null> {
    const role = this.session()?.role;
    if (!role) return null;
    this.loading.set(true);
    try {
      if (role === 'ADMIN') {
        this.adminSummary.set(await firstValueFrom(this.api.adminSummary()));
        return null;
      }
      const [wallet, cards, purchases] = await Promise.all([
        firstValueFrom(this.api.wallet()),
        firstValueFrom(this.api.cards()),
        firstValueFrom(this.api.statement())
      ]);
      this.balance.set(wallet.balance);
      this.cardBalance.set(wallet.cardBalance);
      this.cards.set(cards);
      this.purchases.set(purchases);
      return null;
    } catch (error) {
      return describe(error);
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * Issues the cardholder's card. The API answers a repeat with the card already
   * held, so the list is replaced rather than appended to: retrying must never
   * make a second card appear in the interface.
   */
  async issueCard(): Promise<string | null> {
    try {
      const card = await firstValueFrom(this.api.issueCard());
      this.cards.set([card]);
      this.revealedNumber.set(null);
      return null;
    } catch (error) {
      return describe(error);
    }
  }

  /** Transfers wallet money onto the card. Both figures come back from the API. */
  async loadCard(amount: number): Promise<string | null> {
    if (!Number.isFinite(amount) || amount <= 0) return 'Informe um valor maior que zero.';
    try {
      const balances = await firstValueFrom(this.api.loadCard(round(amount)));
      this.balance.set(balances.walletBalance);
      this.cardBalance.set(balances.cardBalance);
      return null;
    } catch (error) {
      return describe(error);
    }
  }

  async setPin(pin: string): Promise<string | null> {
    const card = this.card();
    if (!card) return 'Nenhum cartão emitido.';
    if (!/^\d{4}$/.test(pin)) return 'O PIN precisa ter exatamente 4 dígitos.';
    try {
      const updated = await firstValueFrom(this.api.setPin(card.id, pin));
      this.cards.set([updated]);
      return null;
    } catch (error) {
      return describe(error);
    }
  }

  /**
   * Asks the API for the number. It is never stored: closing the card or signing
   * out drops it, and a reload has to prove the PIN again.
   */
  async revealNumber(pin: string): Promise<string | null> {
    const card = this.card();
    if (!card) return 'Nenhum cartão emitido.';
    if (!/^\d{4}$/.test(pin)) return 'O PIN precisa ter exatamente 4 dígitos.';
    try {
      const revealed = await firstValueFrom(this.api.revealNumber(card.id, pin));
      this.revealedNumber.set(revealed.formatted);
      return null;
    } catch (error) {
      return describe(error);
    }
  }

  hideNumber(): void {
    this.revealedNumber.set(null);
  }

  async addBalance(amount: number): Promise<string | null> {
    if (!Number.isFinite(amount) || amount <= 0) return 'Informe um valor maior que zero.';
    try {
      const wallet = await firstValueFrom(this.api.topUp(round(amount)));
      this.balance.set(wallet.balance);
      return null;
    } catch (error) {
      return describe(error);
    }
  }

  /** Asks the API for the quote; interest is never calculated in the browser. */
  async quote(amount: number, installments: number): Promise<PurchaseQuote | string> {
    if (!Number.isFinite(amount) || amount <= 0) return 'Informe um valor maior que zero.';
    if (!Number.isInteger(installments) || installments < 1 || installments > 24) {
      return 'O parcelamento aceita de 1 a 24 parcelas.';
    }
    try {
      return await firstValueFrom(this.api.quote(round(amount), installments));
    } catch (error) {
      return describe(error);
    }
  }

  async purchase(category: string, amount: number, installments: number): Promise<string | null> {
    try {
      const purchase = await firstValueFrom(this.api.purchase(category, round(amount), installments));
      if (purchase.remainingWalletBalance !== null) this.balance.set(purchase.remainingWalletBalance);
      this.purchases.update(items => [purchase, ...items]);
      return null;
    } catch (error) {
      return describe(error);
    }
  }

  async setInterestPolicy(monthlyRate: number): Promise<string | null> {
    if (!Number.isFinite(monthlyRate) || monthlyRate < 0 || monthlyRate > 1) {
      return 'A taxa mensal deve estar entre 0 e 1.';
    }
    try {
      await firstValueFrom(this.api.setInterestPolicy(monthlyRate));
      this.adminSummary.set(await firstValueFrom(this.api.adminSummary()));
      return null;
    } catch (error) {
      return describe(error);
    }
  }

  clear(): void {
    this.balance.set(null);
    this.cardBalance.set(null);
    this.revealedNumber.set(null);
    this.cards.set([]);
    this.purchases.set([]);
    this.adminSummary.set(null);
  }
}

function round(amount: number): number {
  return Math.round(amount * 100) / 100;
}

/** Turns an API failure into a message that reflects what actually happened. */
export function describe(error: unknown): string {
  if (!(error instanceof HttpErrorResponse)) return 'Não foi possível concluir a operação.';
  switch (error.status) {
    case 0: return 'Serviço indisponível. Verifique se o backend está no ar.';
    case 401: return 'Sua sessão expirou. Entre novamente.';
    case 403: return 'Seu perfil não tem permissão para esta operação.';
    case 409: return 'Requisição duplicada com dados diferentes.';
    case 422: return error.error?.code === 'INSUFFICIENT_FUNDS'
      ? 'Saldo insuficiente para esta compra.'
      : 'Operação recusada pelas regras da conta.';
    case 429: return 'Sistema com alto volume. Tente novamente em instantes.';
    case 503: return 'Autorização indisponível no momento. Nenhum valor foi debitado.';
    default: return error.error?.violations?.[0]?.message ?? 'Não foi possível concluir a operação.';
  }
}
