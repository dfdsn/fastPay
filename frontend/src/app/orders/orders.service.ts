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
  /** Unidades que ainda contam em todas as comandas (H23); {@code quantity} é o enviado. */
  activeQuantity: number;
  cancelledQuantity: number;
  /** Unidades ativas nesta comanda e as transferidas para outras (H25). */
  inTabQuantity: number;
  transferredQuantity: number;
}

/** Item de pedido de outra comanda com unidades ativas nesta: recebido por transferência (H25). */
export interface ReceivedLine {
  id: string;
  productName: string;
  unitPrice: string;
  quantity: number;
  lineTotal: string;
  note: string | null;
  sectorId: string | null;
  sentAt: string;
  origin: { id: string; number: number; name: string; table: string | null } | null;
}

/** Resultado da transferência (H25): unidades que ficaram na origem e as que o destino tem agora. */
export interface Transferred {
  transferId: string;
  orderLineId: string;
  productName: string;
  fromTabId: string;
  toTabId: string;
  quantity: number;
  remainingInFrom: number;
  quantityInTo: number;
  status: string;
  version: number;
}

/** Resultado do cancelamento (H23): unidades da linha agora e se a produção precisa dar ciência. */
export interface Cancelled {
  cancellationId: string;
  orderLineId: string;
  tabId: string;
  productName: string;
  quantity: number;
  originalQuantity: number;
  cancelledQuantity: number;
  remainingInTab: number;
  requiresAck: boolean;
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

  /**
   * Cancela unidades de um item da comanda (H23, permissão de cancelar). {@code expectedQuantity}: as unidades ativas
   * mostradas; mudou (outra correção), o servidor recusa e a tela relê.
   */
  cancel(tabId: string, orderLineId: string, quantity: number, expectedQuantity: number, reason: string): Observable<Cancelled> {
    return this.auth.withCsrf(() =>
      this.http.post<Cancelled>(`/api/v1/tabs/${tabId}/items/${orderLineId}/cancel`, {
        quantity,
        expectedQuantity,
        reason,
      }),
    );
  }

  /** Itens recebidos de outras comandas por transferência (H25). */
  receivedOfTab(tabId: string): Observable<ReceivedLine[]> {
    return this.http.get<ReceivedLine[]>('/api/v1/orders/received', { params: new HttpParams().set('tabId', tabId) });
  }

  /**
   * Transfere unidades inteiras de um item para outra comanda aberta (H25, permissão de transferir).
   * {@code expectedQuantity}: as unidades mostradas nesta comanda; mudou, o servidor recusa e a tela relê.
   */
  transfer(
    tabId: string,
    orderLineId: string,
    toTabId: string,
    quantity: number,
    expectedQuantity: number,
    reason: string,
  ): Observable<Transferred> {
    return this.auth.withCsrf(() =>
      this.http.post<Transferred>(`/api/v1/tabs/${tabId}/items/${orderLineId}/transfer`, {
        toTabId,
        quantity,
        expectedQuantity,
        reason,
      }),
    );
  }

  retryForwarding(orderId: string): Observable<SentOrder> {
    return this.auth.withCsrf(() =>
      this.http.post<SentOrder>(`/api/v1/orders/${orderId}/forwarding/retry`, null),
    );
  }
}
