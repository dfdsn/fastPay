import { CancellationNotice, ProductionLine } from './production.service';

/** Linha de produção de exemplo para os testes das telas (H18). */
export function linha(changes: Partial<ProductionLine> = {}): ProductionLine {
  return {
    id: 'l-1',
    orderId: 'o-1',
    sectorId: 's-cozinha',
    sectorName: 'Cozinha',
    delayLimitMinutes: 15,
    productName: 'Batata frita',
    quantity: 3,
    originalQuantity: 3,
    cancelledQuantity: 0,
    note: 'sem sal',
    status: 'AGUARDANDO_PREPARO',
    sentAt: '2026-11-04T20:00:00Z',
    statusSince: '2026-11-04T20:00:00Z',
    startedAt: null,
    readyAt: null,
    deliveredAt: null,
    late: false,
    impossible: null,
    responsible: 'Gabi Garçom',
    destinations: [{ tabId: 't-1', number: 12, name: 'Ana', table: '5', quantity: 3 }],
    version: 0,
    ...changes,
  };
}

/** Cancelamento pendente de ciência de exemplo (H23). */
export function cancelamento(changes: Partial<CancellationNotice> = {}): CancellationNotice {
  return {
    id: 'c-1',
    productionLineId: 'l-1',
    productName: 'Batata frita',
    sectorId: 's-cozinha',
    sectorName: 'Cozinha',
    stage: 'EM_PREPARO',
    status: 'EM_PREPARO',
    quantity: 1,
    originalQuantity: 3,
    cancelledQuantity: 1,
    remaining: 2,
    reason: 'Cliente desistiu',
    cancelledBy: 'Gui Gerente',
    cancelledAt: '2026-11-04T20:05:00Z',
    tab: { id: 't-1', number: 12, name: 'Ana', table: '5' },
    acknowledgedBy: null,
    acknowledgedAt: null,
    ...changes,
  };
}
