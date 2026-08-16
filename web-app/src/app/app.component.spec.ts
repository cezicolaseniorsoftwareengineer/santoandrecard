import { TestBed } from '@angular/core/testing';
import { AppComponent } from './app.component';

describe('AppComponent', () => {
  beforeEach(async () => { await TestBed.configureTestingModule({ imports: [AppComponent] }).compileComponents(); });

  it('renders the demonstrative login first', () => {
    const fixture = TestBed.createComponent(AppComponent); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Acesse sua conta');
    expect(fixture.nativeElement.textContent).toContain('Ambiente de demonstração');
  });

  it('renders the customer dashboard after customer login', () => {
    const fixture = TestBed.createComponent(AppComponent); const component = fixture.componentInstance;
    component.password = Array(7).fill('x').join(''); component.login(); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Saldo disponível');
    expect(fixture.nativeElement.textContent).toContain('Limite disponível');
  });

  it('renders only consolidated indicators for an administrator', () => {
    const fixture = TestBed.createComponent(AppComponent); const component = fixture.componentInstance;
    component.role = 'ADMIN'; component.email = 'admin@santoandre.demo';
    component.password = Array(7).fill('x').join(''); component.login(); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Controle completo da carteira');
    expect(fixture.nativeElement.textContent).not.toContain('Adicionar saldo');
  });
});
