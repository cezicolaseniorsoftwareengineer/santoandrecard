import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** One line of feedback, announced politely so a screen reader hears it too. */
@Component({
  selector: 'app-toast',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (message()) {
      <div class="toast" role="status" aria-live="polite">{{ message() }}</div>
    }
  `
})
export class ToastComponent {
  readonly message = input('');
}
