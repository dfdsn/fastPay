import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AuthService } from '../auth/auth.service';

/** Encaminhamento à produção: pendente, concluído ou na fila de falhas (pode ser reprocessado). */
export type Forwarding = 'PENDENTE' | 'CONCLUIDO' | 'FALHOU';

/** Linha enviada, com o preço do envio (texto, duas casas). */
export interface SentLine {
  id: string;
  position: number;
  productId: string;
  productName: string;
  unitPrice: string;
  quantity: number;
  lineTotal: string;
  note: string | null;
  sectorId: string | null;
}

export interface SentOrder {
  id: string;
  tabId: string;
  operationKey: string;
  sentBy: string;
  sentAt: string;
  lines: SentLine[];
  forwarding: Forwarding;
}

/** Resposta do envio; {@code replayed}: a operação já tinha chegado e este é o pedido gravado antes. */
export interface Sent {
  order: SentOrder;
  replayed: boolean;
}

/** Linha como vai ao servidor: o preço é o que a pessoa viu e confirmou. */
export interface OrderLineInput {
  productId: string;
  quantity: number;
  note: string | null;
  expectedPrice: string;
}

/** Corpo da recusa PEDIDO_PRECISA_REVISAO. */
export interface ReviewProblem {
  unavailable: string[];
  priceChanges: { productId: string; seenPrice: string; currentPrice: string }[];
}

/** Pedidos da unidade da sessão (H16); a permissão é conferida no servidor a cada chamada. */
@Injectable({ providedIn: 'root' })
export class OrdersService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  send(tabId: string, operationKey: string, lines: OrderLineInput[]): Observable<Sent> {
    return this.auth.withCsrf(() =>
      this.http.post<Sent>('/api/v1/orders', { tabId, operationKey, lines }),
    );
  }

  /** Confere se um envio interrompido chegou (404 PEDIDO_NAO_ENCONTRADO: não chegou). */
  operation(operationKey: string): Observable<SentOrder> {
    return this.http.get<SentOrder>(`/api/v1/orders/operations/${operationKey}`);
  }

  ofTab(tabId: string): Observable<SentOrder[]> {
    return this.http.get<SentOrder[]>('/api/v1/orders', { params: new HttpParams().set('tabId', tabId) });
  }

  retryForwarding(orderId: string): Observable<SentOrder> {
    return this.auth.withCsrf(() =>
      this.http.post<SentOrder>(`/api/v1/orders/${orderId}/forwarding/retry`, null),
    );
  }
}
