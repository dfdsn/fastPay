import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AuthService } from '../auth/auth.service';

/** Valores em reais vêm do servidor como texto com duas casas; a tela nunca calcula a conta. */
export interface BillLine {
  orderLineId: string;
  productName: string;
  quantity: number;
  unitPrice: string;
  lineTotal: string;
  sentAt: string;
}

export type DiscountKind = 'FIXO' | 'PERCENTUAL';
export type ServiceBase = 'APOS_DESCONTO' | 'ANTES_DESCONTO';

export interface BillPerson {
  id: string;
  name: string | null;
}

/** Desconto ativo (H22). {@code value}: o concedido; {@code amount}: o valor efetivo hoje. */
export interface BillDiscount {
  id: string;
  kind: DiscountKind;
  value: string;
  amount: string;
  effectivePercent: string;
  reason: string;
  grantedBy: BillPerson;
  grantedAt: string;
  consumptionAtGrant: string;
  authorityPercent: string;
  needsReview: boolean;
  reviewedBy: BillPerson | null;
  reviewedAt: string | null;
}

export interface BillService {
  enabled: boolean;
  percent: string;
  base: ServiceBase;
  includesCouvert: boolean;
  waived: boolean;
  baseAmount: string;
  amount: string;
}

export interface DiscountPolicy {
  fixedAllowed: boolean;
  percentAllowed: boolean;
  canGrant: boolean;
  myLimitPercent: string;
}

/** Conta como a equipe vê (H22). {@code reviewPending}: desconto acima da autoridade, cobrança bloqueada. */
export interface Bill {
  tabId: string;
  open: boolean;
  lines: BillLine[];
  consumption: string;
  discount: BillDiscount | null;
  couvert: string;
  service: BillService;
  total: string;
  reviewPending: boolean;
  policy: DiscountPolicy;
}

/** Conta como o cliente vê: valores, sem motivo nem nomes da equipe. */
export interface GuestBill {
  tabId: string;
  open: boolean;
  lines: BillLine[];
  consumption: string;
  discount: string | null;
  couvert: string;
  service: BillService;
  total: string;
}

/** Conta da comanda (H22): o servidor confere permissão, limite e formato a cada chamada. */
@Injectable({ providedIn: 'root' })
export class BillsService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  bill(tabId: string): Observable<Bill> {
    return this.http.get<Bill>(`/api/v1/tabs/${tabId}/bill`);
  }

  /** {@code currentDiscountId}: o desconto que a tela mostra (nulo se nenhum); o novo substitui. */
  grant(
    tabId: string,
    currentDiscountId: string | null,
    kind: DiscountKind,
    value: string,
    reason: string,
  ): Observable<Bill> {
    return this.auth.withCsrf(() =>
      this.http.put<Bill>(`/api/v1/tabs/${tabId}/bill/discount`, { currentDiscountId, kind, value, reason }),
    );
  }

  remove(tabId: string, discountId: string, reason: string): Observable<Bill> {
    return this.auth.withCsrf(() =>
      this.http.post<Bill>(`/api/v1/tabs/${tabId}/bill/discount/remove`, { discountId, reason }),
    );
  }

  review(tabId: string, discountId: string): Observable<Bill> {
    return this.auth.withCsrf(() =>
      this.http.post<Bill>(`/api/v1/tabs/${tabId}/bill/discount/review`, { discountId }),
    );
  }

  service(tabId: string, waived: boolean): Observable<Bill> {
    return this.auth.withCsrf(() => this.http.put<Bill>(`/api/v1/tabs/${tabId}/bill/service`, { waived }));
  }

  guestBill(tabId: string): Observable<GuestBill> {
    return this.http.get<GuestBill>(`/api/v1/guest/tabs/${tabId}/bill`);
  }

  guestService(tabId: string, waived: boolean): Observable<GuestBill> {
    return this.auth.withCsrf(() =>
      this.http.put<GuestBill>(`/api/v1/guest/tabs/${tabId}/bill/service`, { waived }),
    );
  }
}

/** Texto do serviço: "Serviço (10% sobre o consumo após desconto)". */
export function serviceLabel(service: BillService): string {
  const percent = Number(service.percent).toLocaleString('pt-BR', { maximumFractionDigits: 4 });
  const base = service.base === 'APOS_DESCONTO' ? 'após desconto' : 'antes do desconto';
  return `Serviço (${percent}% sobre o consumo ${base}${service.includesCouvert ? ' e o couvert' : ''})`;
}

/** Percentual com vírgula, sem zeros à direita: "12,5%". */
export function percentText(value: string): string {
  return `${Number(value).toLocaleString('pt-BR', { maximumFractionDigits: 4 })}%`;
}
