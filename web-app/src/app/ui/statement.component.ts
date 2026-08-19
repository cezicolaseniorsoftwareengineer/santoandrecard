import { CurrencyPipe, DatePipe, PercentPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, output } from '@angular/core';
import { BankStore } from '../bank-store.service';

/** Every purchase on the account, with the pricing it was actually sold at. */
@Component({
  selector: 'app-statement',
  standalone: true,
  imports: [CurrencyPipe, DatePipe, PercentPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './statement.component.html'
})
export class StatementComponent {
  readonly store = inject(BankStore);
  readonly refreshed = output<void>();
}
