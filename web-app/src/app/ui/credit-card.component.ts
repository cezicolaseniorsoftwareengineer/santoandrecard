import { UpperCasePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { CardResponse } from '../bank.models';

/**
 * The card face.
 *
 * <p>It renders the last four digits until the holder proves the PIN, and the
 * full number only for as long as they keep it revealed. The number arrives as
 * an input and is never held here.
 */
@Component({
  selector: 'app-credit-card',
  standalone: true,
  imports: [UpperCasePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './credit-card.component.html'
})
export class CreditCardComponent {
  readonly card = input.required<CardResponse>();
  readonly holderName = input.required<string>();
  readonly revealedNumber = input<string | null>(null);

  readonly opened = output<void>();
}
