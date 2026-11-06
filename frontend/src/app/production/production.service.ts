import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AuthService } from '../auth/auth.service';

export type ProductionStatus = 'AGUARDANDO_PREPARO' | 'EM_PREPARO' | 'PRONTO' | 'ENTREGUE' | 'CANCELADA';

/** Unidades da linha numa comanda. */
export interface Destination {
  tabId: string;
  number: number;
  name: string;
  table: string | null;
  quantity: number;
  /** Comanda já encerrada (H24): a entrega dela não se desfaz, registra-se ocorrência. */
  closed?: boolean;
}

/**
 * Linha de produção (H18): uma sequência para toda a quantidade, sem valores. {@code sectorId} nulo: sem preparo.
 * {@code version}: a lida, enviada em cada ação; mudou (outra pessoa, transferência, cancelamento), a ação é recusada.
 */
export interface ProductionLine {
  id: string;
  orderId: string;
  sectorId: string | null;
  sectorName: string | null;
  delayLimitMinutes: number | null;
  productName: string;
  /** Unidades que ainda contam; {@code originalQuantity} e {@code cancelledQuantity}: enviado e cancelado (H23). */
  quantity: number;
  originalQuantity: number;
  cancelledQuantity: number;
  note: string | null;
  status: ProductionStatus;
  sentAt: string;
  statusSince: string;
  startedAt: string | null;
  readyAt: string | null;
  deliveredAt: string | null;
  late: boolean;
  impossible: { reason: string; at: string } | null;
  responsible: string | null;
  destinations: Destination[];
  version: number;
}

/** Linhas e o instante do servidor, para contar os tempos sem depender do relógio do aparelho. */
export interface ProductionBoard {
  now: string;
  lines: ProductionLine[];
}

/**
 * Cancelamento que espera a ciência da produção (H23, FR-20): feito em preparo ou pronto. {@code stage}: a etapa na
 * hora do cancelamento; {@code status} e {@code remaining}: a linha agora (CANCELADA quando não sobrou nada).
 */
export interface CancellationNotice {
  id: string;
  productionLineId: string;
  productName: string;
  sectorId: string | null;
  sectorName: string | null;
  stage: ProductionStatus;
  status: ProductionStatus;
  quantity: number;
  originalQuantity: number;
  cancelledQuantity: number;
  remaining: number;
  reason: string;
  cancelledBy: string | null;
  cancelledAt: string;
  tab: { id: string; number: number; name: string; table: string | null };
  acknowledgedBy: string | null;
  acknowledgedAt: string | null;
}

export interface CancellationNotices {
  now: string;
  cancellations: CancellationNotice[];
}

export interface Acknowledged {
  cancellation: CancellationNotice;
  alreadyAcknowledged: boolean;
}

/** Ocorrência de entrega registrada (H24): a linha como está, sem mudança. */
export interface DeliveryOccurrence {
  eventId: string;
  line: ProductionLine;
}

/** Motivo de desfazer entrega ou de ocorrência (H24): de 5 a 300 caracteres, conferido também no servidor. */
export const CORRECTION_REASON_MIN = 5;
export const CORRECTION_REASON_MAX = 300;

export type ProductionAction = 'start' | 'ready' | 'impossible/withdraw' | 'deliver';

/** Ação de um lote da produção (H19): só as etapas de preparo; entregar e sinalizar seguem uma linha por vez. */
export type BatchAction = 'INICIAR' | 'PRONTO';

/** Linhas por lote (H19, FR-69, D-13): o servidor recusa o lote inteiro acima disso. */
export const BATCH_MAX = 50;

/** Resultado de uma linha do lote, na ordem pedida; {@code message} explica a falha ou o "já estava feito". */
export interface BatchLine {
  lineId: string;
  status: 'CONCLUIDA' | 'IGNORADA' | 'FALHA';
  code: string | null;
  message: string | null;
}

export interface BatchResult {
  concluded: number;
  ignored: number;
  failed: number;
  lines: BatchLine[];
}

/** Fila de produção e entrega da unidade da sessão (H18); a permissão é conferida no servidor a cada chamada. */
@Injectable({ providedIn: 'root' })
export class ProductionService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  queue(sectorId: string | null): Observable<ProductionBoard> {
    const params = sectorId ? new HttpParams().set('sectorId', sectorId) : undefined;
    return this.http.get<ProductionBoard>('/api/v1/production/queue', { params });
  }

  deliveries(): Observable<ProductionBoard> {
    return this.http.get<ProductionBoard>('/api/v1/production/deliveries');
  }

  act(line: ProductionLine, action: ProductionAction): Observable<ProductionLine> {
    return this.auth.withCsrf(() =>
      this.http.post<ProductionLine>(`/api/v1/production/lines/${line.id}/${action}`, {
        version: line.version,
      }),
    );
  }

  /** Lote (H19): cada linha com a versão lida; sucesso parcial explícito, uma recusa não desfaz as demais. */
  batch(action: BatchAction, lines: ProductionLine[]): Observable<BatchResult> {
    return this.auth.withCsrf(() =>
      this.http.post<BatchResult>('/api/v1/production/batch', {
        action,
        lines: lines.map((l) => ({ id: l.id, version: l.version })),
      }),
    );
  }

  /** Cancelamentos que esperam ciência (H23), de um setor ou de todos. */
  cancellations(sectorId: string | null): Observable<CancellationNotices> {
    const params = sectorId ? new HttpParams().set('sectorId', sectorId) : undefined;
    return this.http.get<CancellationNotices>('/api/v1/production/cancellations', { params });
  }

  /** Ciência do cancelamento; repetir (ou outra pessoa já ter dado) não muda nada. */
  acknowledge(id: string): Observable<Acknowledged> {
    return this.auth.withCsrf(() =>
      this.http.post<Acknowledged>(`/api/v1/production/cancellations/${id}/acknowledge`, null),
    );
  }

  /** Entregues mais recentes (H24), para quem pode desfazer a entrega. */
  delivered(): Observable<ProductionBoard> {
    return this.http.get<ProductionBoard>('/api/v1/production/delivered');
  }

  /** Volta a linha entregue para pronta, com motivo (H24); só com todas as comandas abertas. */
  undoDelivery(line: ProductionLine, reason: string): Observable<ProductionLine> {
    return this.auth.withCsrf(() =>
      this.http.post<ProductionLine>(`/api/v1/production/lines/${line.id}/undo-delivery`, {
        version: line.version,
        reason,
      }),
    );
  }

  /** Registra a ocorrência de entrega com comanda já encerrada (H24): nada é reaberto nem devolvido. */
  deliveryOccurrence(line: ProductionLine, reason: string): Observable<DeliveryOccurrence> {
    return this.auth.withCsrf(() =>
      this.http.post<DeliveryOccurrence>(`/api/v1/production/lines/${line.id}/delivery-occurrence`, {
        version: line.version,
        reason,
      }),
    );
  }

  impossible(line: ProductionLine, reason: string): Observable<ProductionLine> {
    return this.auth.withCsrf(() =>
      this.http.post<ProductionLine>(`/api/v1/production/lines/${line.id}/impossible`, {
        version: line.version,
        reason,
      }),
    );
  }
}

export const STATUS_LABEL: Record<ProductionStatus, string> = {
  AGUARDANDO_PREPARO: 'Aguardando preparo',
  EM_PREPARO: 'Em preparo',
  PRONTO: 'Pronto',
  ENTREGUE: 'Entregue',
  CANCELADA: 'Cancelado',
};

/** "Comanda 12 · Ana · mesa 5", com a quantidade quando a linha vai para mais de um destino. */
export function destinationLabel(d: Destination, showQuantity: boolean): string {
  const base =
    `Comanda ${d.number} · ${d.name} · ${d.table ? 'mesa ' + d.table : 'sem mesa'}` +
    (d.closed ? ' · encerrada' : '');
  return showQuantity ? `${base} (${d.quantity})` : base;
}

/** Tempo decorrido em minutos ("agora", "1 min", "12 min", "1 h 05 min"). */
export function elapsed(since: string, now: number): string {
  const minutes = Math.max(0, Math.floor((now - Date.parse(since)) / 60_000));
  if (minutes < 1) {
    return 'agora';
  }
  if (minutes < 60) {
    return `${minutes} min`;
  }
  return `${Math.floor(minutes / 60)} h ${String(minutes % 60).padStart(2, '0')} min`;
}

/** Passou do limite do setor desde o envio, ainda não entregue (FR-22): só destaque visual. */
export function isLate(line: ProductionLine, now: number): boolean {
  if (line.status === 'ENTREGUE' || line.delayLimitMinutes === null) {
    return false;
  }
  return now - Date.parse(line.sentAt) > line.delayLimitMinutes * 60_000;
}
