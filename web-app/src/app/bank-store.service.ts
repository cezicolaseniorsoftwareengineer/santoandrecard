import { Injectable, computed, signal } from '@angular/core';
import { AdminMetrics, CardSummary, Invoice, MerchantCategory, PurchaseSimulation, Session, Transaction, UserRole } from './bank.models';

@Injectable({ providedIn: 'root' })
export class BankStore {
  readonly session = signal<Session | null>(null);
  readonly balance = signal(2350.8);
  readonly card = signal<CardSummary>({ lastFour: '4821', limit: 8000, used: 2468.45, dueDate: '12/09/2026', status: 'ACTIVE' });
  readonly availableLimit = computed(() => this.card().limit - this.card().used);
  readonly transactions = signal<readonly Transaction[]>([
    { id: 't1', description: 'Mercado Vila Nova', category: 'Alimentação', amount: -286.4, occurredAt: '2026-08-15', installments: 1, kind: 'PURCHASE' },
    { id: 't2', description: 'Saldo adicionado', category: 'Carteira', amount: 1200, occurredAt: '2026-08-14', installments: 1, kind: 'DEPOSIT' },
    { id: 't3', description: 'Restaurante Alameda', category: 'Restaurante', amount: -189.9, occurredAt: '2026-08-12', installments: 2, kind: 'PURCHASE' },
    { id: 't4', description: 'Pagamento da fatura', category: 'Cartão', amount: -740, occurredAt: '2026-08-10', installments: 1, kind: 'PAYMENT' }
  ]);
  readonly invoices = signal<readonly Invoice[]>([
    { month: 'Agosto 2026', amount: 2468.45, status: 'OPEN' },
    { month: 'Julho 2026', amount: 1840.22, status: 'PAID' },
    { month: 'Junho 2026', amount: 1297.8, status: 'PAID' }
  ]);
  readonly adminMetrics: AdminMetrics = { customers: 1248, receivables: 318420.75, portfolioBalance: 1890350.3, openInvoices: 784, delinquencyRate: 2.18 };

  login(email: string, role: UserRole): boolean {
    if (!email.trim()) return false;
    this.session.set({ name: role === 'ADMIN' ? 'Operador Santo André' : 'Marina Oliveira', role });
    return true;
  }

  logout(): void { this.session.set(null); }

  addBalance(amount: number): boolean {
    if (!Number.isFinite(amount) || amount <= 0 || amount > 50000) return false;
    this.balance.update(current => current + amount);
    this.transactions.update(items => [{ id: crypto.randomUUID(), description: 'Saldo adicionado', category: 'Carteira', amount, occurredAt: new Date().toISOString().slice(0, 10), installments: 1, kind: 'DEPOSIT' }, ...items]);
    return true;
  }

  simulatePurchase(category: MerchantCategory, merchant: string, amount: number, installments: number): PurchaseSimulation | null {
    if (!merchant.trim() || !Number.isFinite(amount) || amount <= 0 || amount > this.availableLimit() || !Number.isInteger(installments) || installments < 1 || installments > 12) return null;
    const monthlyRate = installments > 6 ? 0.0149 : 0;
    const totalAmount = monthlyRate === 0 ? amount : amount * Math.pow(1 + monthlyRate, installments);
    return { category, merchant: merchant.trim(), amount, installments, totalAmount, installmentAmount: totalAmount / installments };
  }
}
