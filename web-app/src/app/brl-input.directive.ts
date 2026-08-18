import { Directive, ElementRef, HostListener, inject } from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';

/**
 * A money field that reads as money.
 *
 * <p>`input[type=number]` cannot do this: it shows the raw value, so a customer
 * typing thirty-five thousand reais sees `35000` with no thousands separator and
 * no decimals, and the browser adds spinner arrows that make no sense for an
 * amount. This keeps the bound model a plain number while what the user sees is
 * always pt-BR currency.
 *
 * <p>Input is read as centavos, the way a card terminal does: every digit typed
 * shifts the amount, so `3500000` reads as 35.000,00. There is no invalid
 * intermediate state to validate, and no separator for the user to place.
 */
@Directive({
  selector: 'input[appBrl]',
  standalone: true,
  providers: [{ provide: NG_VALUE_ACCESSOR, useExisting: BrlInputDirective, multi: true }]
})
export class BrlInputDirective implements ControlValueAccessor {
  private static readonly FORMAT = new Intl.NumberFormat('pt-BR', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  });

  private readonly element = inject<ElementRef<HTMLInputElement>>(ElementRef).nativeElement;
  private onChange: (value: number) => void = () => undefined;
  private onTouched: () => void = () => undefined;

  writeValue(value: number | null): void {
    this.element.value = BrlInputDirective.FORMAT.format(Number.isFinite(value) ? Number(value) : 0);
  }

  registerOnChange(fn: (value: number) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(disabled: boolean): void {
    this.element.disabled = disabled;
  }

  @HostListener('input')
  handleInput(): void {
    // Only the digits matter. Anything else the user pasted or the formatter
    // wrote is re-derived, so the field cannot be driven into a broken state.
    const centavos = Number(this.element.value.replace(/\D/g, '').slice(0, 15) || '0');
    const amount = centavos / 100;
    this.element.value = BrlInputDirective.FORMAT.format(amount);
    this.moveCaretToEnd();
    this.onChange(amount);
  }

  @HostListener('blur')
  handleBlur(): void {
    this.onTouched();
  }

  /** Digits enter at the right, so the caret belongs there rather than where the click landed. */
  private moveCaretToEnd(): void {
    const end = this.element.value.length;
    this.element.setSelectionRange(end, end);
  }
}
