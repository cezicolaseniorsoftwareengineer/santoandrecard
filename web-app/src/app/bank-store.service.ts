import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AuthService } from './auth.service';
import { CardApi } from './card-api.service';
import { AdminSummary, CardResponse, FundingSource, InvoiceItemResponse, InvoiceResponse, PurchaseQuote, PurchaseResponse } from './bank.models';

/** Product rule: a purchase is paid in cash or split over at most twelve months. */
export const MAX_INSTALLMENTS = 12;

/** Ceiling on the administered monthly rate: 0.60 is 60% a month. */
export const MAX_MONTHLY_RATE = 0.6;

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
  /** The administered rate in force. Null until it has been read from the API. */
  readonly monthlyRate = signal<number | null>(null);
  readonly loading = signal(false);
  /** Statements of the signed-in customer, most recent cycle first. */
  readonly invoices = signal<readonly InvoiceResponse[]>([]);
  /** Lines of whichever statement is open on screen, keyed by its id. */
  readonly invoiceItems = signal<readonly InvoiceItemResponse[]>([]);

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
        const [summary, policy] = await Promise.all([
          firstValueFrom(this.api.adminSummary()),
          firstValueFrom(this.api.interestPolicy())
        ]);
        this.adminSummary.set(summary);
        this.monthlyRate.set(policy.monthlyRate);
        return null;
      }
      const [wallet, cards, purchases, policy] = await Promise.all([
        firstValueFrom(this.api.wallet()),
        firstValueFrom(this.api.cards()),
        firstValueFrom(this.api.statement()),
        // The purchase screen states the rate it prices with, so it has to read it.
        firstValueFrom(this.api.interestPolicy())
      ]);
      this.balance.set(wallet.balance);
      this.cardBalance.set(wallet.cardBalance);
      this.monthlyRate.set(policy.monthlyRate);
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

  /**
   * Statements. Read separately from the dashboard because a customer looking at
   * a card balance is asking a different question from one looking at a bill,
   * and loading both on every screen makes the first slower for no reason.
   */
  async loadInvoices(): Promise<string | null> {
    try {
      this.invoices.set(await firstValueFrom(this.api.invoices()));
      return null;
    } catch (error) {
      return describe(error);
    }
  }

  async loadInvoiceItems(id: string): Promise<string | null> {
    try {
      this.invoiceItems.set(await firstValueFrom(this.api.invoiceItems(id)));
      return null;
    } catch (error) {
      return describe(error);
    }
  }

  /**
   * Pays a statement from the wallet.
   *
   * <p>The wallet balance is reloaded afterwards rather than adjusted here: the
   * API is the authority on what a payment left behind, and a figure decremented
   * in the browser is a second opinion nobody asked for.
   */
  async payInvoice(id: string, amount: number): Promise<string | null> {
    if (!Number.isFinite(amount) || amount <= 0) return 'Informe um valor maior que zero.';
    try {
      await firstValueFrom(this.api.payInvoice(id, round(amount)));
      await this.loadInvoices();
      await this.refresh();
      return null;
    } catch (error) {
      return describe(error);
    }
  }

  /** Closes a cycle on demand, so a reader can watch one bill. */
  async closeCycle(cycle: string): Promise<string | null> {
    try {
      await firstValueFrom(this.api.closeCycle(cycle));
      await this.loadInvoices();
      return null;
    } catch (error) {
      return describe(error);
    }
  }

  /** Asks the API for the quote; interest is never calculated in the browser. */
  async quote(amount: number, installments: number): Promise<PurchaseQuote | string> {
    if (!Number.isFinite(amount) || amount <= 0) return 'Informe um valor maior que zero.';
    if (!Number.isInteger(installments) || installments < 1 || installments > MAX_INSTALLMENTS) {
      return `O parcelamento aceita de 1 a ${MAX_INSTALLMENTS} parcelas.`;
    }
    try {
      return await firstValueFrom(this.api.quote(round(amount), installments));
    } catch (error) {
      return describe(error);
    }
  }

  async purchase(category: string, amount: number, installments: number,
                 fundingSource: FundingSource = 'CARD'): Promise<string | null> {
    try {
      const purchase = await firstValueFrom(
        this.api.purchase(category, round(amount), installments, fundingSource));
      // On the prepaid path the card pays, so the card balance is the figure
      // that moved. On credit nothing was spent — a debt was created — and the
      // API says so by leaving the remaining balance out.
      if (purchase.remainingCardBalance !== null) this.cardBalance.set(purchase.remainingCardBalance);
      this.purchases.update(items => [purchase, ...items]);
      return null;
    } catch (error) {
      return describe(error);
    }
  }

  async setInterestPolicy(monthlyRate: number): Promise<string | null> {
    if (!Number.isFinite(monthlyRate) || monthlyRate < 0 || monthlyRate > MAX_MONTHLY_RATE) {
      return `A taxa mensal deve estar entre 0% e ${MAX_MONTHLY_RATE * 100}%.`;
    }
    try {
      const policy = await firstValueFrom(this.api.setInterestPolicy(monthlyRate));
      this.monthlyRate.set(policy.monthlyRate);
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
    this.invoices.set([]);
    this.invoiceItems.set([]);
    this.monthlyRate.set(null);
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
    case 422:
      switch (error.error?.code) {
        case 'INSUFFICIENT_FUNDS': return 'Saldo insuficiente para esta operação.';
        // The statement itself refused: already paid, already closed, or asked
        // for more than it owes. Retrying unchanged will fail the same way.
        case 'STATEMENT_STATE': return 'Esta fatura não aceita esse pagamento. Confira o valor em aberto.';
        default: return 'Operação recusada pelas regras da conta.';
      }
    case 404: return error.error?.code === 'STATEMENT_NOT_FOUND'
      ? 'Fatura não encontrada.'
      : 'Recurso não encontrado.';
    case 429: return 'Sistema com alto volume. Tente novamente em instantes.';
    case 503: return 'Autorização indisponível no momento. Nenhum valor foi debitado.';
    default: return error.error?.violations?.[0]?.message ?? 'Não foi possível concluir a operação.';
  }
}
