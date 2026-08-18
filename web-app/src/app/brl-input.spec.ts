import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { BrlInputDirective } from './brl-input.directive';

@Component({
  standalone: true,
  imports: [FormsModule, BrlInputDirective],
  template: '<input appBrl [(ngModel)]="amount">'
})
class Host {
  amount = 250;
}

/** Typing digits must produce reais, and the model must stay a plain number. */
describe('BrlInputDirective', () => {
  let fixture: ComponentFixture<Host>;
  let input: HTMLInputElement;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [Host] }).compileComponents();
    fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    await fixture.whenStable();
    input = fixture.nativeElement.querySelector('input');
  });

  it('shows the bound amount as pt-BR currency', () => {
    expect(input.value).toBe('250,00');
  });

  it('reads typed digits as centavos', () => {
    type(input, '3500000');
    expect(input.value).toBe('35.000,00');
    expect(fixture.componentInstance.amount).toBe(35000);
  });

  it('keeps cents rather than whole reais', () => {
    type(input, '1999');
    expect(input.value).toBe('19,99');
    expect(fixture.componentInstance.amount).toBe(19.99);
  });

  it('ignores anything that is not a digit', () => {
    type(input, 'R$ 1.234,56abc');
    expect(fixture.componentInstance.amount).toBe(1234.56);
  });

  function type(field: HTMLInputElement, text: string): void {
    field.value = text;
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }
});
