import { CommonModule, CurrencyPipe, DatePipe, registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { BankStore } from './bank-store.service';
import { MerchantCategory, PurchaseSimulation, UserRole } from './bank.models';

type CustomerView = 'overview' | 'shopping' | 'statement' | 'invoice';

registerLocaleData(localePt, 'pt-BR');

@Component({ selector: 'app-root', standalone: true, imports: [CommonModule, FormsModule, CurrencyPipe, DatePipe], templateUrl: './app.component.html', styleUrl: './app.component.css', changeDetection: ChangeDetectionStrategy.OnPush })
export class AppComponent {
  readonly store = inject(BankStore);
  readonly view = signal<CustomerView>('overview');
  readonly balanceVisible = signal(true);
  readonly toast = signal('');
  readonly simulation = signal<PurchaseSimulation | null>(null);
  readonly isAdmin = computed(() => this.store.session()?.role === 'ADMIN');
  email = 'cliente@santoandre.demo';
  password = '';
  role: UserRole = 'CUSTOMER';
  depositAmount = 250;
  category: MerchantCategory = 'Shopping';
  merchant = 'Shopping Santo André';
  purchaseAmount = 600;
  installments = 3;
  readonly categories: readonly MerchantCategory[] = ['Shopping', 'Padaria', 'Açougue', 'Restaurante', 'Farmácia'];

  login(): void {
    if (this.password.length < 6 || !this.store.login(this.email, this.role)) this.showToast('Informe e-mail e senha com pelo menos 6 caracteres.');
  }
  logout(): void { this.store.logout(); this.view.set('overview'); }
  navigate(view: CustomerView): void { this.view.set(view); this.simulation.set(null); }
  addBalance(): void { this.showToast(this.store.addBalance(this.depositAmount) ? 'Saldo demonstrativo adicionado.' : 'Informe um valor entre R$ 0,01 e R$ 50.000,00.'); }
  simulate(): void {
    const result = this.store.simulatePurchase(this.category, this.merchant, this.purchaseAmount, this.installments);
    this.simulation.set(result);
    if (!result) this.showToast('Revise o estabelecimento, o valor e as parcelas.');
  }
  private showToast(message: string): void { this.toast.set(message); window.setTimeout(() => this.toast.set(''), 3500); }
}
