import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * The black surface shown while the session is restored.
 *
 * <p>It stays mounted through its own fade, because unmounting on the first
 * frame of the transition makes the mark blink away instead of leaving.
 */
@Component({
  selector: 'app-splash',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="splash" [class.splash--leaving]="leaving()"
         role="status" aria-label="Iniciando Banco Santo André">
      <img src="assets/brand/banco-santo-andre-logo.png" alt="Banco Santo André">
    </div>
  `
})
export class SplashComponent {
  readonly leaving = input(false);
}
