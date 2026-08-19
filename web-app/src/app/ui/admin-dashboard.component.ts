import { CurrencyPipe, PercentPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { BankStore } from '../bank-store.service';

/**
 * The executive view: what the portfolio holds, and the one policy the
 * administration sets.
 *
 * <p>Every figure is read from the store, which reads it from the API. Nothing
 * on this screen is computed in the browser, so the panel cannot disagree with
 * the ledger.
 */
@Component({
  selector: 'app-admin-dashboard',
  standalone: true,
  imports: [FormsModule, CurrencyPipe, PercentPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './admin-dashboard.component.html'
})
export class AdminDashboardComponent {
  readonly store = inject(BankStore);

  readonly busy = input(false);

  readonly refreshed = output<void>();
  readonly rateApplied = output<number>();

  readonly monthlyRate = signal(0.0199);
}
