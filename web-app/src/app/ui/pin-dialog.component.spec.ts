import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { PinDialogComponent } from './pin-dialog.component';

async function render(): Promise<ComponentFixture<PinDialogComponent>> {
  await TestBed.configureTestingModule({ imports: [PinDialogComponent] }).compileComponents();
  const fixture = TestBed.createComponent(PinDialogComponent);
  fixture.componentRef.setInput('purpose', 'reveal');
  fixture.detectChanges();
  return fixture;
}

describe('PinDialogComponent', () => {
  it('hands the PIN to the caller and keeps none of it', async () => {
    const fixture = await render();
    const submitted: string[] = [];
    fixture.componentInstance.confirmed.subscribe(pin => submitted.push(pin));

    fixture.componentInstance.pin.set('1234');
    fixture.componentInstance.submit();

    expect(submitted).toEqual(['1234']);
    // A dialog reopened after a wrong attempt must not offer the previous guess
    // back, and nothing about a PIN should outlive the submit that used it.
    expect(fixture.componentInstance.pin()).toBe('');
  });

  it('never renders the PIN as readable text', async () => {
    const fixture = await render();
    const field: HTMLInputElement = fixture.nativeElement.querySelector('#pin');

    expect(field.type).toBe('password');
    expect(field.maxLength).toBe(4);
    expect(field.getAttribute('autocomplete')).toBe('off');
  });

  it('says what it is asking for, so the holder knows which PIN to type', async () => {
    const fixture = await render();
    expect(fixture.nativeElement.textContent).toContain('Informe seu PIN');

    fixture.componentRef.setInput('purpose', 'set');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Crie seu PIN');
  });
});
