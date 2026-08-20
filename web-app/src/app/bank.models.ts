export type UserRole = 'CUSTOMER' | 'ADMIN';

/**
 * Merchant categories are sent to the API as free text up to 64 characters. The
 * list below is the set offered in the interface, not a contract enforced by the
 * backend.
 */
export type MerchantCategory = 'Shopping' | 'Padaria' | 'Açougue' | 'Restaurante' | 'Farmácia';

export interface Session {
  readonly name: string;
  readonly role: UserRole;
}

// Response shapes below mirror the card-service payloads exactly. Monetary values
// arrive as JSON numbers with two decimals.

export interface CardResponse {
  readonly id: string;
  readonly customerId: string;
  readonly creditLimit: number;
  readonly currency: string;
  readonly status: 'ACTIVE' | 'BLOCKED' | 'CANCELLED';
  readonly product: 'PLATINUM';
  readonly productName: string;
  readonly lastFourDigits: string;
  readonly pinDefined: boolean;
  readonly createdAt: string;
}

export interface CardNumberResponse {
  readonly number: string;
  readonly formatted: string;
}

export interface CardBalances {
  readonly customerId: string;
  readonly walletBalance: number;
  readonly cardBalance: number;
}

export interface WalletResponse {
  readonly customerId: string;
  readonly balance: number;
  readonly cardBalance: number;
}

export interface PurchaseQuote {
  readonly principal: number;
  readonly interest: number;
  readonly total: number;
  readonly installments: number;
  readonly installmentAmount: number;
  /** Carries the rounding remainder, so the instalments add up to the total. */
  readonly lastInstallmentAmount: number;
  /** The administered rate this plan was priced with. */
  readonly monthlyRate: number;
}

export interface PurchaseResponse {
  readonly id: string;
  readonly customerId: string;
  readonly merchantCategory: string;
  readonly principal: number;
  readonly interest: number;
  readonly total: number;
  readonly installments: number;
  readonly installmentAmount: number;
  readonly lastInstallmentAmount: number;
  readonly monthlyRate: number;
  /**
   * Card balance left after the purchase. The card is what pays, so this is the
   * figure that changed. Present only on the response that created the purchase,
   * absent on statements.
   */
  readonly remainingCardBalance: number | null;
  readonly createdAt: string;
}

export interface AdminSummary {
  readonly customerWallets: number;
  readonly totalWalletBalance: number;
  readonly purchasePrincipal: number;
  readonly interestRevenue: number;
}

export interface InterestPolicy {
  readonly monthlyRate: number;
  /** Null until an administrator has set a rate for the tenant. */
  readonly updatedAt: string | null;
}

/**
 * A billing cycle's statement — the fatura.
 *
 * <p>Named `invoice` here rather than `statement` because the interface already
 * calls the list of purchases an extrato and reads it through `statement()`.
 * Two different things sharing one word in the same file is how a reader ends up
 * paying the wrong screen.
 */
/**
 * What pays for a purchase.
 *
 * <p>`CARD` spends the prepaid balance and settles at once. `CREDIT` creates a
 * debt that a statement bills and a payment settles. The choice is fixed at
 * authorization and cannot be changed afterwards.
 */
export type FundingSource = 'CARD' | 'CREDIT';

export type InvoiceStatus = 'OPEN' | 'CLOSED' | 'PARTIALLY_PAID' | 'PAID' | 'OVERDUE';

export interface InvoiceResponse {
  readonly id: string;
  readonly customerId: string;
  /** `2026-08`, the month the cycle closes in. */
  readonly cycle: string;
  readonly status: InvoiceStatus;
  readonly billedTotal: number;
  readonly paidTotal: number;
  /** Derived by the API from the two above; never a third stored figure. */
  readonly balance: number;
  readonly dueDate: string | null;
  readonly closedAt: string | null;
}

export interface InvoiceItemResponse {
  readonly id: string;
  readonly sourceType: 'PURCHASE' | 'INSTALLMENT' | 'ADJUSTMENT';
  readonly sourceId: string;
  readonly description: string;
  readonly amount: number;
  readonly occurredAt: string;
}
