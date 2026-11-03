import {
  DraftLine,
  applyReview,
  draftStorageKey,
  draftTotalCents,
  loadDraft,
  priceText,
  saveDraft,
} from './order-draft';

function linha(productId: string, price: string, quantity = 1): DraftLine {
  return { key: productId + quantity, productId, name: productId, price, quantity, note: '' };
}

describe('rascunho de pedido', () => {
  beforeEach(() => localStorage.clear());

  it('guarda por pessoa na unidade e comanda, sem misturar', () => {
    const minha = draftStorageKey('vinculo-1', 'comanda-1');
    saveDraft(minha, { lines: [linha('p1', '9.90', 2)], pendingKey: 'k-1' });
    expect(loadDraft(minha)).toEqual({ lines: [linha('p1', '9.90', 2)], pendingKey: 'k-1' });
    expect(loadDraft(draftStorageKey('vinculo-2', 'comanda-1')).lines).toEqual([]);
    expect(loadDraft(draftStorageKey('vinculo-1', 'comanda-2')).lines).toEqual([]);
    // Vazio e sem envio pendente não ocupa o armazenamento.
    saveDraft(minha, { lines: [], pendingKey: null });
    expect(localStorage.getItem(minha)).toBeNull();
  });

  it('conteúdo estranho vira rascunho vazio', () => {
    localStorage.setItem('k', '{nao e json');
    expect(loadDraft('k')).toEqual({ lines: [], pendingKey: null });
    localStorage.setItem('k', JSON.stringify({ lines: [{ productId: 'p', price: 3, quantity: 1 }] }));
    expect(loadDraft('k').lines).toEqual([]);
  });

  it('marca esgotados e troca o preço guardando o anterior para reconfirmar', () => {
    const revisado = applyReview(
      [linha('a', '10.00'), linha('b', '5.00'), linha('c', '3.00')],
      ['b'],
      [{ productId: 'a', currentPrice: '11.50' }],
    );
    expect(revisado[0]).toMatchObject({ price: '11.50', previousPrice: '10.00', unavailable: false });
    expect(revisado[1]).toMatchObject({ price: '5.00', previousPrice: undefined, unavailable: true });
    expect(revisado[2]).toMatchObject({ price: '3.00', unavailable: false });
  });

  it('soma em centavos sem erro de ponto flutuante', () => {
    expect(draftTotalCents([linha('a', '0.10', 3), linha('b', '0.20')])).toBe(50);
    expect(priceText(35.9)).toBe('35.90');
  });
});
