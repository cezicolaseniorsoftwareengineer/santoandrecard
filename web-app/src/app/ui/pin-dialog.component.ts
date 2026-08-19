import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

export type PinPurpose = 'set' | 'reveal';

/**
 * Asks for the four digits that stand between a stolen session and a card
 * number. The dialog holds the PIN only while it is open and hands it straight
 * to the caller; nothing here stores or logs it.
 */
@Component({
  selector: 'app-pin-dialog',
  standalone: true,
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './pin-dialog.component.html'
})
export class PinDialogComponent {
  readonly purpose = input.required<PinPurpose>();
  readonly busy = input(false);

  readonly confirmed = output<string>();
  readonly dismissed = output<void>();

  readonly pin = signal('');

  submit(): void {
    this.confirmed.emit(this.pin());
    // Cleared on the way out rather than left in the field: a dialog reopened
    // after a wrong attempt must not offer the previous guess back.
    this.pin.set('');
  }
}
