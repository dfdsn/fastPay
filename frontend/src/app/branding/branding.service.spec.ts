import { initials } from './branding.service';

describe('initials', () => {
  it('usa as primeiras letras das palavras significativas', () => {
    expect(initials('Bar da Praia')).toBe('BP');
    expect(initials('Adega Centro')).toBe('AC');
    expect(initials('  pizzaria  ')).toBe('P');
    expect(initials('Ó Bar')).toBe('ÓB');
    expect(initials('Bar & Cia. do Zé')).toBe('BC');
    expect(initials('3 Irmãos')).toBe('3I');
  });
});
