import { TestBed } from '@angular/core/testing';
import { BankStore } from './bank-store.service';

describe('BankStore', () => {
  let store: BankStore;
  beforeEach(() => { TestBed.configureTestingModule({}); store = TestBed.inject(BankStore); });

  it('authenticates the selected demonstrative role without storing a password', () => {
    expect(store.login('admin@santoandre.demo', 'ADMIN')).toBe(true);
    expect(store.session()).toEqual({ name: 'Operador Santo André', role: 'ADMIN' });
  });

  it('rejects invalid deposits and preserves the balance', () => {
    const balance = store.balance();
    expect(store.addBalance(-10)).toBe(false);
    expect(store.addBalance(50001)).toBe(false);
    expect(store.balance()).toBe(balance);
  });

  it('adds a valid demonstrative deposit and transaction', () => {
    const balance = store.balance();
    expect(store.addBalance(100)).toBe(true);
    expect(store.balance()).toBe(balance + 100);
    expect(store.transactions()[0].kind).toBe('DEPOSIT');
  });

  it('calculates an interest-free purchase through six installments', () => {
    const result = store.simulatePurchase('Shopping', 'Loja Teste', 600, 6);
    expect(result?.installmentAmount).toBe(100);
    expect(result?.totalAmount).toBe(600);
  });

  it('applies demonstrative compound interest above six installments', () => {
    const result = store.simulatePurchase('Restaurante', 'Bistrô', 1000, 10);
    expect(result?.totalAmount).toBeGreaterThan(1000);
    expect(result?.installmentAmount).toBeCloseTo((1000 * Math.pow(1.0149, 10)) / 10, 2);
  });

  it('rejects purchases above the available card limit', () => {
    expect(store.simulatePurchase('Padaria', 'Pão', store.availableLimit() + 1, 1)).toBeNull();
  });
});
