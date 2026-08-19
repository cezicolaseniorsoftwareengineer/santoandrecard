import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { Session } from '../bank.models';

export type CustomerView = 'overview' | 'shopping' | 'statement';

/** Navigation and the signed-in identity. What it offers follows the role. */
@Component({
  selector: 'app-sidebar',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app-sidebar.component.html'
})
export class AppSidebarComponent {
  readonly session = input.required<Session>();
  readonly isAdmin = input.required<boolean>();
  readonly view = input.required<CustomerView>();

  readonly navigated = output<CustomerView>();
  readonly signedOut = output<void>();
}
