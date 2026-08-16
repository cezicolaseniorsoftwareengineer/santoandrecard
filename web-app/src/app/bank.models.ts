export type UserRole = 'CUSTOMER' | 'ADMIN';
export type MerchantCategory = 'Shopping' | 'Padaria' | 'Açougue' | 'Restaurante' | 'Farmácia';
export type TransactionKind = 'PURCHASE' | 'DEPOSIT' | 'PAYMENT' | 'INTEREST';

export interface Session { readonly name: string; readonly role: UserRole; }
export interface CardSummary { readonly lastFour: string; readonly limit: number; readonly used: number; readonly dueDate: string; readonly status: 'ACTIVE'; }
export interface Transaction { readonly id: string; readonly description: string; readonly category: string; readonly amount: number; readonly occurredAt: string; readonly installments: number; readonly kind: TransactionKind; }
export interface Invoice { readonly month: string; readonly amount: number; readonly status: 'OPEN' | 'PAID'; }
export interface AdminMetrics { readonly customers: number; readonly receivables: number; readonly portfolioBalance: number; readonly openInvoices: number; readonly delinquencyRate: number; }
export interface PurchaseSimulation { readonly category: MerchantCategory; readonly merchant: string; readonly amount: number; readonly installments: number; readonly installmentAmount: number; readonly totalAmount: number; }
