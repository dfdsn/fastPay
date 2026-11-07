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

/**
 * Couvert na visita (H26): cobrado nesta comanda, em outra comanda da mesma visita (não se repete), dispensado na visita
 * (indevido ou isenção), aguardando a inclusão do valor correto (correção) ou sem couvert.
 */
export type CouvertState = 'COBRADO' | 'COBRADO_EM_OUTRA' | 'DISPENSADO' | 'AGUARDANDO_CORRECAO' | 'SEM_COUVERT';
export type CouvertOrigin = 'AUTOMATICO' | 'MANUAL' | 'CORRECAO';
export type CouvertReason = 'INDEVIDO' | 'ISENCAO' | 'DUPLICIDADE' | 'CORRECAO';

export interface EndedCouvert {
  id: string;
  origin: CouvertOrigin;
  amount: string;
  endKind: 'CANCELADO' | 'DEVOLVIDO' | 'NAO_REPETIDO';
  reasonKind: CouvertReason | null;
  reason: string | null;
  endedBy: BillPerson;
  endedAt: string;
}

/** Couvert da comanda como a equipe vê (H26); o servidor diz o que cada pessoa pode fazer. */
export interface CouvertDetail {
  mode: 'AUTOMATICO' | 'MANUAL' | null;
  state: CouvertState;
  chargeId: string | null;
  origin: CouvertOrigin | null;
  amount: string;
  chargedOnTab: { id: string; number: number; name: string } | null;
  canInclude: boolean;
  includeAmount: string | null;
  canCancel: boolean;
  canCorrect: boolean;
  history: EndedCouvert[];
}

/**
 * Conta como a equipe vê (H22). {@code reviewPending}: desconto acima da autoridade, cobrança bloqueada.
 * {@code closing}: em fechamento (H28), só o serviço muda.
 */
export interface Bill {
  tabId: string;
  open: boolean;
  closing: boolean;
  lines: BillLine[];
  consumption: string;
  discount: BillDiscount | null;
  couvert: string;
  couvertDetail: CouvertDetail;
  service: BillService;
  total: string;
  reviewPending: boolean;
  policy: DiscountPolicy;
}

/** Conta como o cliente vê: valores, sem motivo nem nomes da equipe. */
export interface GuestBill {
  tabId: string;
  open: boolean;
  closing: boolean;
  lines: BillLine[];
  consumption: string;
  discount: string | null;
  couvert: string;
  couvertState: CouvertState;
  service: BillService;
  total: string;
}

/** Atendimento da comanda (H28). */
export type ClosingState = 'ABERTA' | 'EM_FECHAMENTO' | 'ENCERRADA';
export type PendingStage = 'AGUARDANDO_PRODUCAO' | 'AGUARDANDO_PREPARO' | 'EM_PREPARO' | 'PRONTO' | 'IMPOSSIVEL';

export interface PendingItem {
  orderLineId: string;
  productName: string;
  quantity: number;
  stage: PendingStage;
}

/** O que impede iniciar o fechamento agora (só enquanto aberta). */
export interface ClosingBlockers {
  pendingItems: PendingItem[];
  openIssue: boolean;
  discountReview: boolean;
}

/** Versão consolidada da conta: a que a cobrança usa. */
export interface BillVersion {
  id: string;
  number: number;
  total: string;
  at: string;
}

/** Como a comanda foi encerrada (H29): conta zerada ou abandono sem consumo. */
export type ClosureKind = 'SEM_VALOR' | 'ABANDONO';

/** Encerramento como a equipe vê: {@code closedByName} nulo quando foi o cliente; motivo só no abandono. */
export interface ClosureView {
  kind: ClosureKind;
  closedAt: string;
  closedByName: string | null;
  byConsumer: boolean;
  reason: string | null;
}

export interface GuestClosure {
  kind: ClosureKind;
  closedAt: string;
}

/** Situação apresentada da comanda encerrada (FR-41: não é pagamento). */
export function closureText(kind: ClosureKind): string {
  return kind === 'SEM_VALOR' ? 'Encerrada sem valor a pagar' : 'Encerrada sem consumo (abandono)';
}

/** Fechamento como a equipe vê (H28, H29); o servidor confere de novo ao iniciar, reabrir ou encerrar. */
export interface Closing {
  tabId: string;
  state: ClosingState;
  closingId: string | null;
  startedAt: string | null;
  version: BillVersion | null;
  total: string;
  blockers: ClosingBlockers | null;
  closure: ClosureView | null;
  canStart: boolean;
  canReopen: boolean;
  canCloseWithoutValue: boolean;
  canAbandon: boolean;
}

export interface GuestClosing {
  tabId: string;
  state: ClosingState;
  startedAt: string | null;
  versionNumber: number | null;
  total: string;
  blockers: ClosingBlockers | null;
  closure: GuestClosure | null;
  canStart: boolean;
  canCloseWithoutValue: boolean;
}

const STAGE_TEXT: Record<PendingStage, string> = {
  AGUARDANDO_PRODUCAO: 'a caminho da produção',
  AGUARDANDO_PREPARO: 'aguardando preparo',
  EM_PREPARO: 'em preparo',
  PRONTO: 'pronto, falta entregar',
  IMPOSSIVEL: 'sem como atender, falta resolver',
};

/** Motivos que impedem o fechamento, em frases para a tela. */
export function blockerTexts(blockers: ClosingBlockers | null): string[] {
  if (!blockers) {
    return [];
  }
  const texts = blockers.pendingItems.map((p) => `${p.quantity} × ${p.productName}: ${STAGE_TEXT[p.stage]}.`);
  if (blockers.openIssue) {
    texts.push('Há um problema informado ainda não resolvido.');
  }
  if (blockers.discountReview) {
    texts.push('O desconto precisa ser revisado por alguém com limite.');
  }
  return texts;
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

  /** Inclui o couvert manual (H26), com o valor da configuração vigente. */
  includeCouvert(tabId: string): Observable<Bill> {
    return this.auth.withCsrf(() => this.http.post<Bill>(`/api/v1/tabs/${tabId}/bill/couvert`, {}));
  }

  cancelCouvert(tabId: string, chargeId: string, reasonKind: CouvertReason, reason: string): Observable<Bill> {
    return this.auth.withCsrf(() =>
      this.http.post<Bill>(`/api/v1/tabs/${tabId}/bill/couvert/cancel`, { chargeId, reasonKind, reason }),
    );
  }

  /** Inclusão de correção: o valor correto, depois de cancelar ou devolver por correção. */
  correctCouvert(tabId: string, amount: string, reason: string): Observable<Bill> {
    return this.auth.withCsrf(() =>
      this.http.post<Bill>(`/api/v1/tabs/${tabId}/bill/couvert/correction`, { amount, reason }),
    );
  }

  closing(tabId: string): Observable<Closing> {
    return this.http.get<Closing>(`/api/v1/tabs/${tabId}/closing`);
  }

  /** {@code expectedTotal}: o total que a tela mostra; se a conta mudou, o servidor recusa. */
  startClosing(tabId: string, expectedTotal: string): Observable<Closing> {
    return this.auth.withCsrf(() => this.http.post<Closing>(`/api/v1/tabs/${tabId}/closing`, { expectedTotal }));
  }

  reopen(tabId: string, closingId: string, reason: string): Observable<Closing> {
    return this.auth.withCsrf(() =>
      this.http.post<Closing>(`/api/v1/tabs/${tabId}/closing/reopen`, { closingId, reason }),
    );
  }

  /** Encerra a conta zerada (H29, FR-41): sem pagamento. */
  closeWithoutValue(tabId: string): Observable<Closing> {
    return this.auth.withCsrf(() => this.http.post<Closing>(`/api/v1/tabs/${tabId}/closing/without-value`, {}));
  }

  /** Encerra a comanda abandonada sem consumo, com motivo (H29, FR-42). */
  abandon(tabId: string, reason: string): Observable<Closing> {
    return this.auth.withCsrf(() => this.http.post<Closing>(`/api/v1/tabs/${tabId}/closing/abandon`, { reason }));
  }

  guestClosing(tabId: string): Observable<GuestClosing> {
    return this.http.get<GuestClosing>(`/api/v1/guest/tabs/${tabId}/closing`);
  }

  guestStartClosing(tabId: string, expectedTotal: string): Observable<GuestClosing> {
    return this.auth.withCsrf(() =>
      this.http.post<GuestClosing>(`/api/v1/guest/tabs/${tabId}/closing`, { expectedTotal }),
    );
  }

  guestCloseWithoutValue(tabId: string): Observable<GuestClosing> {
    return this.auth.withCsrf(() =>
      this.http.post<GuestClosing>(`/api/v1/guest/tabs/${tabId}/closing/without-value`, {}),
    );
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

/** Motivos de cancelar o couvert (H26), na ordem do seletor. */
export const COUVERT_REASONS: { value: CouvertReason; label: string; hint: string }[] = [
  { value: 'INDEVIDO', label: 'Cobrança indevida', hint: 'Dispensa o couvert na visita inteira.' },
  { value: 'ISENCAO', label: 'Isenção', hint: 'Dispensa o couvert na visita inteira.' },
  { value: 'DUPLICIDADE', label: 'Duplicidade', hint: 'Só quando outro couvert da visita continua cobrado.' },
  { value: 'CORRECAO', label: 'Correção de valor', hint: 'Depois, inclua o valor correto.' },
];

/** Frase sobre o couvert da visita, para a equipe e para o cliente; nula quando não há o que explicar. */
export function couvertNote(state: CouvertState, otherTab?: { number: number } | null): string | null {
  switch (state) {
    case 'COBRADO_EM_OUTRA':
      return otherTab
        ? `Couvert já cobrado na comanda ${otherTab.number}, da mesma visita: não se repete.`
        : 'Couvert já cobrado em outra comanda desta visita: não se repete.';
    case 'DISPENSADO':
      return 'Couvert dispensado nesta visita.';
    case 'AGUARDANDO_CORRECAO':
      return 'O couvert saiu por correção e aguarda a inclusão do valor correto.';
    default:
      return null;
  }
}
