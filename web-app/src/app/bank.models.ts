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
  /** Present only on the response that created the purchase, absent on statements. */
  readonly remainingWalletBalance: number | null;
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
  readonly updatedAt: string;
}
