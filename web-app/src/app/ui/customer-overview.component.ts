import { CurrencyPipe, DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { BankStore } from '../bank-store.service';
import { BrlInputDirective } from '../brl-input.directive';
import { CreditCardComponent } from './credit-card.component';

/**
 * The cardholder's home: what they hold, what they can spend, and the two ways
 * money moves into each.
 *
 * <p>Hiding the balance is local state — it is a shoulder-surfing control, not
 * an account setting, and it has no business outliving the screen.
 */
@Component({
  selector: 'app-customer-overview',
  standalone: true,
  imports: [FormsModule, CurrencyPipe, DatePipe, BrlInputDirective, CreditCardComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './customer-overview.component.html'
})
export class CustomerOverviewComponent {
  readonly store = inject(BankStore);

  readonly busy = input(false);
  readonly holderName = input.required<string>();

  readonly deposited = output<number>();
  readonly transferred = output<number>();
  readonly cardIssued = output<void>();
  readonly cardOpened = output<void>();
  readonly shoppingRequested = output<void>();
  readonly statementRequested = output<void>();

  readonly balanceVisible = signal(true);
  readonly depositAmount = signal(250);
  readonly transferAmount = signal(100);

  /** How much of the limit is committed, as a share, for the progress bar. */
  committedShare(creditLimit: number): number {
    return creditLimit ? (this.store.committed() / creditLimit) * 100 : 0;
  }
}
