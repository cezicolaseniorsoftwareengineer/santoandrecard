import { CurrencyPipe, PercentPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { BankStore, MAX_INSTALLMENTS } from '../bank-store.service';
import { BrlInputDirective } from '../brl-input.directive';
import { FundingSource, MerchantCategory, PurchaseQuote } from '../bank.models';

/** What the shopper is about to buy, and what it will actually cost. */
export interface PurchaseIntent {
  readonly category: MerchantCategory;
  readonly amount: number;
  readonly installments: number;
  /** Fixed at authorization; the API refuses to change it afterwards. */
  readonly fundingSource: FundingSource;
}

/**
 * Prices a purchase and confirms it.
 *
 * <p>The quote is the API's answer, not a calculation done here: the browser
 * must never be the second opinion on what something costs.
 */
@Component({
  selector: 'app-purchase-simulator',
  standalone: true,
  imports: [FormsModule, CurrencyPipe, PercentPipe, BrlInputDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './purchase-simulator.component.html'
})
export class PurchaseSimulatorComponent {
  readonly store = inject(BankStore);

  readonly busy = input(false);
  readonly quote = input<PurchaseQuote | null>(null);

  readonly simulated = output<PurchaseIntent>();
  readonly confirmed = output<PurchaseIntent>();

  readonly category = signal<MerchantCategory>('Shopping');
  readonly amount = signal(600);
  readonly installments = signal(3);
  readonly fundingSource = signal<FundingSource>('CARD');

  readonly categories: readonly MerchantCategory[] =
    ['Shopping', 'Padaria', 'Açougue', 'Restaurante', 'Farmácia'];

  /**
   * Every count the product offers, from cash up to the ceiling. Derived from
   * the same constant the store validates against, so the options offered and
   * the options accepted cannot drift apart.
   */
  readonly installmentOptions: readonly number[] =
    Array.from({ length: MAX_INSTALLMENTS }, (_, index) => index + 1);

  intent(): PurchaseIntent {
    // The select binds a string; the API is priced in whole instalments.
    return {
      category: this.category(),
      amount: this.amount(),
      installments: Number(this.installments()),
      fundingSource: this.fundingSource()
    };
  }
}
