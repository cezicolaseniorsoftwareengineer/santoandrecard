import { CommonModule, CurrencyPipe, DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthService } from './auth.service';
import { BrlInputDirective } from './brl-input.directive';
import { BankStore } from './bank-store.service';
import { MerchantCategory, PurchaseQuote } from './bank.models';

type CustomerView = 'overview' | 'shopping' | 'statement';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule, CurrencyPipe, DatePipe, BrlInputDirective],
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
  /** Kept mounted through the fade so the black surface does not blink away. */
  readonly splash = signal(true);
  readonly busy = signal(false);
  readonly session = this.store.session;
  readonly isAdmin = computed(() => this.session()?.role === 'ADMIN');

  depositAmount = 250;
  transferAmount = 100;
  pin = '';
  readonly pinPrompt = signal<'none' | 'set' | 'reveal'>('none');
  category: MerchantCategory = 'Shopping';
  purchaseAmount = 600;
  installments = 3;
  monthlyRate = 0.0199;
  readonly categories: readonly MerchantCategory[] = ['Shopping', 'Padaria', 'Açougue', 'Restaurante', 'Farmácia'];

  /** Long enough for the mark to register, short enough not to be a delay. */
  private static readonly SPLASH_MS = 1100;

  /**
   * How long the splash will wait for the network before giving up on it.
   *
   * <p>A server that refuses a connection fails fast, but one that accepts it and
   * never answers does not: the request simply stays pending. Awaiting it with no
   * deadline left the user on the splash forever, which is how a hung API turned
   * into an application that never started.
   */
  private static readonly BOOT_DEADLINE_MS = 4000;

  async ngOnInit(): Promise<void> {
    const shown = this.after(AppComponent.SPLASH_MS);

    // Whichever comes first: the session and its data, or the deadline. Losing
    // the race still yields a usable interface — the login screen if the session
    // never arrived, the dashboard filling in behind its loading state if it did.
    await Promise.race([this.startSession(), this.after(AppComponent.BOOT_DEADLINE_MS)]);

    // Holding for the minimum means the splash never flashes on a fast start.
    await shown;
    this.starting.set(false);
    window.setTimeout(() => this.splash.set(false), 420);
  }

  private async startSession(): Promise<void> {
    if (await this.auth.restore()) {
      await this.reload();
    }
  }

  private after(milliseconds: number): Promise<void> {
    return new Promise(resolve => window.setTimeout(resolve, milliseconds));
  }

  login(): void {
    void this.auth.login();
  }

  register(): void {
    void this.auth.register();
  }

  logout(): void {
    this.store.clear();
    this.auth.logout();
  }

  navigate(view: CustomerView): void {
    this.view.set(view);
    this.quote.set(null);
  }

  async issueCard(): Promise<void> {
    await this.run(() => this.store.issueCard(), 'Cartão emitido e já disponível.');
  }

  async transferToCard(): Promise<void> {
    await this.run(() => this.store.loadCard(this.transferAmount), 'Saldo transferido para o cartão.');
  }

  /** Clicking the card asks for the PIN — to set one the first time, to reveal after that. */
  openCard(): void {
    if (this.store.revealedNumber()) {
      this.store.hideNumber();
      return;
    }
    this.pin = '';
    this.pinPrompt.set(this.store.card()?.pinDefined ? 'reveal' : 'set');
  }

  closePinPrompt(): void {
    this.pinPrompt.set('none');
    this.pin = '';
  }

  async submitPin(): Promise<void> {
    const setting = this.pinPrompt() === 'set';
    const pin = this.pin;
    this.busy.set(true);
    const error = setting ? await this.store.setPin(pin) : await this.store.revealNumber(pin);
    this.busy.set(false);

    if (error) {
      this.showToast(error);
      // The prompt stays open on a wrong PIN so the holder can try again, and
      // closes once the card is locked: retrying is no longer the way out.
      if (error.includes('bloqueado')) this.closePinPrompt();
      return;
    }
    this.closePinPrompt();
    this.showToast(setting ? 'PIN definido. Toque no cartão para ver o número.' : '');
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
