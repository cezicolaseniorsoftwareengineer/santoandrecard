import { CurrencyPipe, DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { BankStore } from '../bank-store.service';
import { BrlInputDirective } from '../brl-input.directive';
import { InvoiceResponse, InvoiceStatus } from '../bank.models';

/**
 * Faturas: what the cardholder owes for each cycle, and paying one.
 *
 * <p>The amount offered defaults to the whole balance, because paying in full is
 * what most people mean. It stays editable, because partial payment is a real
 * outcome the platform supports and hiding it would make the screen claim
 * otherwise.
 */
@Component({
  selector: 'app-invoices',
  standalone: true,
  imports: [FormsModule, CurrencyPipe, DatePipe, BrlInputDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './invoices.component.html'
})
export class InvoicesComponent {
  readonly store = inject(BankStore);

  readonly busy = input(false);

  readonly paid = output<{ id: string; amount: number }>();
  readonly cycleClosed = output<string>();
  readonly itemsRequested = output<string>();

  /** Which statement has its lines expanded. One at a time; the rest stay closed. */
  readonly expanded = signal<string | null>(null);
  readonly payingId = signal<string | null>(null);
  readonly payAmount = signal(0);

  /** The cycle that would close now, offered so a reader can watch one bill. */
  readonly currentCycle = computed(() => {
    const now = new Date();
    return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
  });

  readonly totalOutstanding = computed(() =>
    this.store.invoices().reduce((sum, invoice) => sum + invoice.balance, 0));

  toggleItems(invoice: InvoiceResponse): void {
    if (this.expanded() === invoice.id) {
      this.expanded.set(null);
      return;
    }
    this.expanded.set(invoice.id);
    this.itemsRequested.emit(invoice.id);
  }

  /** Opens the payment field with the full balance already filled in. */
  startPayment(invoice: InvoiceResponse): void {
    this.payingId.set(invoice.id);
    this.payAmount.set(invoice.balance);
  }

  cancelPayment(): void {
    this.payingId.set(null);
  }

  confirmPayment(invoice: InvoiceResponse): void {
    this.paid.emit({ id: invoice.id, amount: this.payAmount() });
    this.payingId.set(null);
  }

  /** Portuguese label for a status the API states in English. */
  label(status: InvoiceStatus): string {
    switch (status) {
      case 'OPEN': return 'Em aberto';
      case 'CLOSED': return 'Fechada';
      case 'PARTIALLY_PAID': return 'Paga em parte';
      case 'PAID': return 'Paga';
      case 'OVERDUE': return 'Em atraso';
    }
  }

  /** Drives the colour of the badge, so late reads as late at a glance. */
  tone(status: InvoiceStatus): string {
    switch (status) {
      case 'PAID': return 'ok';
      case 'OVERDUE': return 'late';
      case 'PARTIALLY_PAID': return 'partial';
      default: return 'neutral';
    }
  }
}
