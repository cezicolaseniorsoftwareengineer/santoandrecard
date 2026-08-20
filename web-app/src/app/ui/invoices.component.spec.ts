import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { BankStore } from '../bank-store.service';
import { InvoiceResponse } from '../bank.models';
import { InvoicesComponent } from './invoices.component';

function invoice(overrides: Partial<InvoiceResponse> = {}): InvoiceResponse {
  return {
    id: 'inv-1',
    customerId: 'c1',
    cycle: '2026-08',
    status: 'CLOSED',
    billedTotal: 300,
    paidTotal: 0,
    balance: 300,
    dueDate: '2026-09-10',
    closedAt: '2026-09-01T03:00:00Z',
    ...overrides
  };
}

async function render(invoices: readonly InvoiceResponse[]): Promise<ComponentFixture<InvoicesComponent>> {
  await TestBed.configureTestingModule({
    imports: [InvoicesComponent],
    providers: [provideHttpClient(), provideHttpClientTesting()]
  }).compileComponents();

  const fixture = TestBed.createComponent(InvoicesComponent);
  TestBed.inject(BankStore).invoices.set(invoices);
  fixture.detectChanges();
  return fixture;
}

describe('InvoicesComponent', () => {
  it('offers the full balance by default, and keeps it editable', async () => {
    const fixture = await render([invoice({ balance: 250 })]);

    fixture.componentInstance.startPayment(invoice({ balance: 250 }));

    // Paying in full is what most people mean, so it is what the field says.
    expect(fixture.componentInstance.payAmount()).toBe(250);
  });

  it('emits what the customer chose to pay', async () => {
    const fixture = await render([invoice()]);
    const paid: { id: string; amount: number }[] = [];
    fixture.componentInstance.paid.subscribe(payment => paid.push(payment));

    fixture.componentInstance.startPayment(invoice());
    // Partial payment is a real outcome the platform supports.
    fixture.componentInstance.payAmount.set(120);
    fixture.componentInstance.confirmPayment(invoice());

    expect(paid).toEqual([{ id: 'inv-1', amount: 120 }]);
  });

  it('does not offer payment on a settled statement', async () => {
    const fixture = await render([invoice({ status: 'PAID', paidTotal: 300, balance: 0 })]);

    // The API refuses it; offering the button anyway would invite a failure the
    // customer cannot understand.
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('Paga');
    expect(fixture.nativeElement.querySelectorAll('button.primary').length).toBe(0);
  });

  it('states the status in words rather than only in colour', async () => {
    const fixture = await render([invoice({ status: 'OVERDUE' })]);

    // Colour alone excludes the readers most likely to be checking whether a
    // bill is late.
    expect(fixture.nativeElement.textContent).toContain('Em atraso');
    expect(fixture.componentInstance.tone('OVERDUE')).toBe('late');
  });

  it('adds up what is outstanding across cycles', async () => {
    const fixture = await render([
      invoice({ id: 'a', balance: 300 }),
      invoice({ id: 'b', cycle: '2026-07', balance: 150 }),
      invoice({ id: 'c', cycle: '2026-06', status: 'PAID', balance: 0 })
    ]);

    expect(fixture.componentInstance.totalOutstanding()).toBe(450);
  });

  it('asks for the lines only when a statement is expanded', async () => {
    const fixture = await render([invoice()]);
    const asked: string[] = [];
    fixture.componentInstance.itemsRequested.subscribe(id => asked.push(id));

    fixture.componentInstance.toggleItems(invoice());
    expect(asked).toEqual(['inv-1']);

    // Collapsing asks for nothing: the lines are already held.
    fixture.componentInstance.toggleItems(invoice());
    expect(asked).toEqual(['inv-1']);
    expect(fixture.componentInstance.expanded()).toBeNull();
  });

  it('says so when there is nothing to show', async () => {
    const fixture = await render([]);

    expect(fixture.nativeElement.textContent).toContain('Nenhuma fatura ainda');
  });
});
