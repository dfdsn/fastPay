/** Limites do envio (D-13 e proposta T-03 da H16); o servidor confere de novo. */
export const MAX_LINES = 50;
export const MAX_QUANTITY = 99;
export const NOTE_MAX = 140;

/** Linha do rascunho. {@code price}: preço visto (texto, duas casas), o que vai como preço conferido. */
export interface DraftLine {
  key: string;
  productId: string;
  name: string;
  price: string;
  quantity: number;
  note: string;
  /** Marcas da última conferência do servidor. */
  unavailable?: boolean;
  previousPrice?: string;
}

/**
 * Rascunho de pedido de uma comanda, guardado só neste aparelho (NFR-11): nada vai ao servidor antes de "Enviar
 * pedido". {@code pendingKey}: envio feito sem resposta (timeout, queda); enquanto existe, o rascunho não muda e
 * só pode ser conferido ou reenviado com a mesma chave, que o servidor não duplica.
 */
export interface Draft {
  lines: DraftLine[];
  pendingKey: string | null;
}

export function emptyDraft(): Draft {
  return { lines: [], pendingKey: null };
}

/** Chave por vínculo (pessoa na unidade) e comanda: não reaproveita entre contas nem unidades. */
export function draftStorageKey(membershipId: string, tabId: string): string {
  return `fastpay.rascunho.v1.${membershipId}.${tabId}`;
}

/** Lê o rascunho guardado; armazenamento indisponível ou conteúdo estranho viram rascunho vazio. */
export function loadDraft(storageKey: string): Draft {
  try {
    const raw = localStorage.getItem(storageKey);
    if (!raw) {
      return emptyDraft();
    }
    const parsed = JSON.parse(raw) as Partial<Draft>;
    if (!Array.isArray(parsed.lines)) {
      return emptyDraft();
    }
    return {
      lines: parsed.lines.filter(
        (l): l is DraftLine =>
          !!l && typeof l.productId === 'string' && typeof l.price === 'string' && Number.isInteger(l.quantity),
      ),
      pendingKey: typeof parsed.pendingKey === 'string' ? parsed.pendingKey : null,
    };
  } catch {
    return emptyDraft();
  }
}

export function saveDraft(storageKey: string, draft: Draft): void {
  try {
    if (draft.lines.length === 0 && !draft.pendingKey) {
      localStorage.removeItem(storageKey);
    } else {
      localStorage.setItem(storageKey, JSON.stringify(draft));
    }
  } catch {
    // Sem armazenamento (aba privada, cota): o rascunho vive só nesta tela.
  }
}

/** Preço do catálogo (número em reais) como texto de duas casas. */
export function priceText(price: number): string {
  return price.toFixed(2);
}

const MONEY = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });

export function reais(value: string | number): string {
  return MONEY.format(typeof value === 'number' ? value : Number(value));
}

/** Total do rascunho em centavos inteiros (sem erro de ponto flutuante). */
export function draftTotalCents(lines: DraftLine[]): number {
  return lines.reduce((sum, l) => sum + Math.round(Number(l.price) * 100) * l.quantity, 0);
}

/**
 * Aplica a recusa PEDIDO_PRECISA_REVISAO: marca esgotados e troca o preço visto pelo atual, guardando o anterior para
 * a pessoa reconfirmar. Nada é removido sozinho.
 */
export function applyReview(
  lines: DraftLine[],
  unavailable: string[],
  priceChanges: { productId: string; currentPrice: string }[],
): DraftLine[] {
  const gone = new Set(unavailable);
  const prices = new Map(priceChanges.map((c) => [c.productId, c.currentPrice]));
  return lines.map((l) => {
    const current = prices.get(l.productId);
    return {
      ...l,
      unavailable: gone.has(l.productId),
      previousPrice: current !== undefined && current !== l.price ? l.price : undefined,
      price: current ?? l.price,
    };
  });
}

export function newKey(): string {
  return crypto.randomUUID();
}
