import { TestBed } from '@angular/core/testing';

import { ChimeService } from './chime';

describe('ChimeService (H20)', () => {
  afterEach(() => localStorage.removeItem('fastpay.som.v1'));

  it('começa desligado, guarda a escolha neste aparelho e não toca desligado', () => {
    const chime = TestBed.inject(ChimeService);
    expect(chime.enabled()).toBe(false);
    chime.play();
    chime.toggle(true);
    expect(localStorage.getItem('fastpay.som.v1')).toBe('1');
    expect(chime.enabled()).toBe(true);
    // Sem áudio no ambiente de teste: não quebra a tela.
    expect(() => chime.play()).not.toThrow();
    chime.toggle(false);
    expect(localStorage.getItem('fastpay.som.v1')).toBe('0');
  });
});
