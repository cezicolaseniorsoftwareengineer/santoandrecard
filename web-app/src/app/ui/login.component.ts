import { ChangeDetectionStrategy, Component, output } from '@angular/core';

/**
 * The signed-out screen. It holds no credentials and never will: both buttons
 * hand the browser to the identity provider, which is the only place a password
 * is ever typed.
 */
@Component({
  selector: 'app-login',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './login.component.html'
})
export class LoginComponent {
  readonly signIn = output<void>();
  readonly signUp = output<void>();
}
