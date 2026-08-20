import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { AuthService } from './auth.service';
import { BankStore } from './bank-store.service';
import { PurchaseQuote } from './bank.models';
import { AdminDashboardComponent } from './ui/admin-dashboard.component';
import { AppSidebarComponent, CustomerView } from './ui/app-sidebar.component';
import { CustomerOverviewComponent } from './ui/customer-overview.component';
import { InvoicesComponent } from './ui/invoices.component';
import { LoginComponent } from './ui/login.component';
import { PinDialogComponent, PinPurpose } from './ui/pin-dialog.component';
import { PurchaseIntent, PurchaseSimulatorComponent } from './ui/purchase-simulator.component';
import { SplashComponent } from './ui/splash.component';
import { StatementComponent } from './ui/statement.component';
import { ToastComponent } from './ui/toast.component';

/**
 * The shell.
 *
 * <p>It owns three things and delegates the rest: which screen is on, the boot
 * sequence, and the single place an API call becomes either a busy cursor or a
 * message. The screens read from {@link BankStore} and report what the user
 * asked for; none of them decides what a failure means.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [
    SplashComponent, LoginComponent, ToastComponent, PinDialogComponent, AppSidebarComponent,
    AdminDashboardComponent, CustomerOverviewComponent, PurchaseSimulatorComponent, StatementComponent,
    InvoicesComponent
  ],
  templateUrl: './app.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AppComponent implements OnInit {
  readonly store = inject(BankStore);
  private readonly auth = inject(AuthService);

  readonly view = signal<CustomerView>('overview');
  readonly toast = signal('');
  readonly quote = signal<PurchaseQuote | null>(null);
  readonly starting = signal(true);
  /** Kept mounted through the fade so the black surface does not blink away. */
  readonly splash = signal(true);
  readonly busy = signal(false);
  readonly pinPrompt = signal<PinPurpose | 'none'>('none');
  readonly session = this.store.session;
  readonly isAdmin = computed(() => this.session()?.role === 'ADMIN');

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
    //
    // The deadline bounds a slow start; the catch bounds a failed one. Without
    // it a rejection anywhere in the restore chain rejects the race, abandons
    // the rest of this method and leaves the splash mounted for good — the same
    // dead end the deadline exists to prevent, reached by the other road.
    await Promise.race([this.startSession(), this.after(AppComponent.BOOT_DEADLINE_MS)])
      .catch(() => this.showToast('Não foi possível restaurar a sessão. Entre novamente.'));

    // Holding for the minimum means the splash never flashes on a fast start.
    await shown;
    this.finishBoot();
  }

  private async startSession(): Promise<void> {
    if (await this.auth.restore()) {
      await this.reload();
    }
  }

  /**
   * Leaves the splash and shows whatever the boot managed to produce.
   *
   * <p>Separate from {@link ngOnInit} so the sequence has one exit, reached on
   * every path: a restored session, no session, a slow provider or a failed
   * exchange. An interface that cannot start is worse than one that starts with
   * nothing signed in.
   */
  private finishBoot(): void {
    this.starting.set(false);
    window.setTimeout(() => this.splash.set(false), 420);
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
    // Statements are read when the screen is opened rather than on every boot:
    // a customer looking at a card balance is asking a different question.
    if (view === 'invoices') {
      void this.loadInvoices();
    }
  }

  async loadInvoices(): Promise<void> {
    const error = await this.store.loadInvoices();
    if (error) this.showToast(error);
  }

  async loadInvoiceItems(id: string): Promise<void> {
    const error = await this.store.loadInvoiceItems(id);
    if (error) this.showToast(error);
  }

  async payInvoice(payment: { id: string; amount: number }): Promise<void> {
    await this.run(() => this.store.payInvoice(payment.id, payment.amount), 'Fatura paga.');
  }

  async closeCycle(cycle: string): Promise<void> {
    await this.run(() => this.store.closeCycle(cycle), 'Ciclo fechado.');
  }

  async issueCard(): Promise<void> {
    await this.run(() => this.store.issueCard(), 'Cartão emitido e já disponível.');
  }

  async addBalance(amount: number): Promise<void> {
    await this.run(() => this.store.addBalance(amount), 'Saldo adicionado à carteira.');
  }

  async transferToCard(amount: number): Promise<void> {
    await this.run(() => this.store.loadCard(amount), 'Saldo transferido para o cartão.');
  }

  /** Clicking the card asks for the PIN — to set one the first time, to reveal after that. */
  openCard(): void {
    if (this.store.revealedNumber()) {
      this.store.hideNumber();
      return;
    }
    this.pinPrompt.set(this.store.card()?.pinDefined ? 'reveal' : 'set');
  }

  closePinPrompt(): void {
    this.pinPrompt.set('none');
  }

  async submitPin(pin: string): Promise<void> {
    const setting = this.pinPrompt() === 'set';
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

  async simulate(intent: PurchaseIntent): Promise<void> {
    this.busy.set(true);
    const result = await this.store.quote(intent.amount, intent.installments);
    this.busy.set(false);
    if (typeof result === 'string') {
      this.quote.set(null);
      this.showToast(result);
      return;
    }
    this.quote.set(result);
  }

  async confirmPurchase(intent: PurchaseIntent): Promise<void> {
    await this.run(
      () => this.store.purchase(intent.category, intent.amount, intent.installments, intent.fundingSource),
      'Compra autorizada.'
    );
    this.quote.set(null);
  }

  async applyInterestPolicy(monthlyRate: number): Promise<void> {
    await this.run(() => this.store.setInterestPolicy(monthlyRate), 'Taxa mensal atualizada.');
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
