import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AuthService } from '../auth/auth.service';

export type ProductionStatus = 'AGUARDANDO_PREPARO' | 'EM_PREPARO' | 'PRONTO' | 'ENTREGUE';

/** Unidades da linha numa comanda. */
export interface Destination {
  tabId: string;
  number: number;
  name: string;
  table: string | null;
  quantity: number;
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
  quantity: number;
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

export type ProductionAction = 'start' | 'ready' | 'impossible/withdraw' | 'deliver';

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
};

/** "Comanda 12 · Ana · mesa 5", com a quantidade quando a linha vai para mais de um destino. */
export function destinationLabel(d: Destination, showQuantity: boolean): string {
  const base = `Comanda ${d.number} · ${d.name} · ${d.table ? 'mesa ' + d.table : 'sem mesa'}`;
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
