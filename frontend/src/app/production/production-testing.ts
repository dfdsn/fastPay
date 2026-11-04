import { ProductionLine } from './production.service';

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
