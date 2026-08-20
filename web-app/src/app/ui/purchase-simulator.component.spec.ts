import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { MAX_INSTALLMENTS } from '../bank-store.service';
import { PurchaseIntent, PurchaseSimulatorComponent } from './purchase-simulator.component';

async function render(): Promise<ComponentFixture<PurchaseSimulatorComponent>> {
  await TestBed.configureTestingModule({
    imports: [PurchaseSimulatorComponent],
    providers: [provideHttpClient(), provideHttpClientTesting()]
  }).compileComponents();
  const fixture = TestBed.createComponent(PurchaseSimulatorComponent);
  fixture.detectChanges();
  return fixture;
}

describe('PurchaseSimulatorComponent', () => {
  it('offers every instalment count the store will accept, and no more', async () => {
    const fixture = await render();
    const options = fixture.componentInstance.installmentOptions;

    expect(options.length).toBe(MAX_INSTALLMENTS);
    expect(options[0]).toBe(1);
    expect(options[options.length - 1]).toBe(MAX_INSTALLMENTS);
  });

  it('carries the chosen funding source to the shell', async () => {
    const fixture = await render();
    const asked: PurchaseIntent[] = [];
    fixture.componentInstance.simulated.subscribe(intent => asked.push(intent));

    fixture.componentInstance.fundingSource.set('CREDIT');
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));

    expect(asked[0].fundingSource).toBe('CREDIT');
  });

  it('reports the instalment count as a number', async () => {
    const fixture = await render();
    // A select binds a string. Emitting it unconverted sends "6" to an API that
    // prices in whole instalments, and the failure surfaces as a rejected
    // purchase rather than as a type error here.
    fixture.componentInstance.installments.set('6' as unknown as number);

    expect(fixture.componentInstance.intent().installments).toBe(6);
  });

  it('emits what the shopper asked for when the form is submitted', async () => {
    const fixture = await render();
    const asked: PurchaseIntent[] = [];
    fixture.componentInstance.simulated.subscribe(intent => asked.push(intent));

    fixture.componentInstance.category.set('Padaria');
    fixture.componentInstance.amount.set(250);
    fixture.componentInstance.installments.set(3);
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));

    expect(asked).toEqual([
      // The funding source travels with the intent: it decides which account is
      // debited, and the screen must not leave the shell to guess.
      { category: 'Padaria', amount: 250, installments: 3, fundingSource: 'CARD' }
    ]);
  });

  it('shows the placeholder until the API has priced something', async () => {
    const fixture = await render();
    expect(fixture.nativeElement.textContent).toContain('O resultado aparece aqui');

    fixture.componentRef.setInput('quote', {
      principal: 600, interest: 35.82, total: 635.82, installments: 3,
      installmentAmount: 211.94, lastInstallmentAmount: 211.94, monthlyRate: 0.0199
    });
    fixture.detectChanges();

    // Every figure on the panel is the API's answer, never a sum done here.
    expect(fixture.nativeElement.textContent).toContain('Condições retornadas pela API');
    expect(fixture.nativeElement.textContent).not.toContain('O resultado aparece aqui');
  });
});
