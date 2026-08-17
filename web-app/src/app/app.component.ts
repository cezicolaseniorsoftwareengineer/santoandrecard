import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from './auth.service';
import { BankStore } from './bank-store.service';
import { MerchantCategory, PurchaseQuote } from './bank.models';

type CustomerView = 'overview' | 'shopping' | 'statement';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule, CurrencyPipe, DatePipe],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AppComponent implements OnInit {
  readonly store = inject(BankStore);
  private readonly auth = inject(AuthService);

  readonly view = signal<CustomerView>('overview');
  readonly balanceVisible = signal(true);
  readonly toast = signal('');
  readonly quote = signal<PurchaseQuote | null>(null);
  readonly starting = signal(true);
  readonly busy = signal(false);
  readonly session = this.store.session;
  readonly isAdmin = computed(() => this.session()?.role === 'ADMIN');

  depositAmount = 250;
  category: MerchantCategory = 'Shopping';
  purchaseAmount = 600;
  installments = 3;
  monthlyRate = 0.0199;
  readonly categories: readonly MerchantCategory[] = ['Shopping', 'Padaria', 'Açougue', 'Restaurante', 'Farmácia'];

  async ngOnInit(): Promise<void> {
    const restored = await this.auth.restore();
    if (restored) await this.reload();
    this.starting.set(false);
  }

  login(): void {
    void this.auth.login();
  }

  logout(): void {
    this.store.clear();
    this.auth.logout();
  }

  navigate(view: CustomerView): void {
    this.view.set(view);
    this.quote.set(null);
  }

  async addBalance(): Promise<void> {
    await this.run(() => this.store.addBalance(this.depositAmount), 'Saldo adicionado à carteira.');
  }

  async simulate(): Promise<void> {
    this.busy.set(true);
    const result = await this.store.quote(this.purchaseAmount, this.installments);
    this.busy.set(false);
    if (typeof result === 'string') {
      this.quote.set(null);
      this.showToast(result);
      return;
    }
    this.quote.set(result);
  }

  async confirmPurchase(): Promise<void> {
    await this.run(
      () => this.store.purchase(this.category, this.purchaseAmount, this.installments),
      'Compra autorizada.'
    );
    this.quote.set(null);
  }

  async applyInterestPolicy(): Promise<void> {
    await this.run(() => this.store.setInterestPolicy(this.monthlyRate), 'Taxa mensal atualizada.');
  }

  async reload(): Promise<void> {
    const error = await this.store.refresh();
    if (error) this.showToast(error);
  }

  /** Runs an API action, showing either its failure message or the success text. */
  private async run(action: () => Promise<string | null>, success: string): Promise<void> {
    this.busy.set(true);
    const error = await action();
    this.busy.set(false);
    this.showToast(error ?? success);
  }

  private showToast(message: string): void {
    this.toast.set(message);
    window.setTimeout(() => this.toast.set(''), 4000);
  }
}
