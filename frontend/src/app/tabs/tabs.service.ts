import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AuthService } from '../auth/auth.service';

/**
 * Comanda ativa da unidade (H15). {@code guestLinked}: o cliente já confirmou o acesso pelo QR.
 * {@code attendanceMode}: modalidade de atendimento capturada na abertura (H20); {@code responsibleName}: o garçom
 * responsável, nulo na central. {@code continuesTab}: a comanda anterior cuja visita esta continua (H26).
 */
export interface Tab {
  id: string;
  visitId: string;
  number: number;
  name: string;
  table: string | null;
  settingsVersionId: string;
  openedAt: string;
  guestLinked: boolean;
  attendanceMode: 'GARCOM_RESPONSAVEL' | 'CENTRAL';
  responsibleId: string | null;
  responsibleName: string | null;
  version: number;
  continuesTab: { id: string; number: number; name: string } | null;
}

/** Comanda que pode ser a anterior de uma continuação de visita (H26), por hora de abertura. */
export interface ContinuationCandidate {
  id: string;
  number: number;
  name: string;
  table: string | null;
  openedAt: string;
  closed: boolean;
}

/** QR de vínculo: vale 2 minutos e uma vez; o SVG é desenhado pelo servidor só com formas. */
export interface BindingQr {
  tabId: string;
  link: string;
  svg: string;
  expiresAt: string;
  recovery: boolean;
}

/** Conferência presencial para recuperar o acesso do cliente (P-07). */
export type AccountCheck = 'ITEM_DA_CONTA' | 'HORA_DA_ABERTURA';

export interface RecoveryInput {
  nameChecked: boolean;
  accountCheck: AccountCheck;
  reason: string;
}

/** Comandas da unidade da sessão; a permissão é conferida no servidor a cada chamada. */
@Injectable({ providedIn: 'root' })
export class TabsService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  active(): Observable<Tab[]> {
    return this.http.get<Tab[]>('/api/v1/tabs');
  }

  find(id: string): Observable<Tab> {
    return this.http.get<Tab>(`/api/v1/tabs/${id}`);
  }

  open(name: string, table: string | null): Observable<Tab> {
    return this.auth.withCsrf(() => this.http.post<Tab>('/api/v1/tabs', { name, table }));
  }

  changeTable(tab: Tab, table: string | null): Observable<Tab> {
    return this.auth.withCsrf(() =>
      this.http.put<Tab>(`/api/v1/tabs/${tab.id}/table`, { table, version: tab.version }),
    );
  }

  /** Troca o garçom responsável (H20, gerente); só na modalidade garçom responsável. */
  reassign(tab: Tab, responsibleId: string): Observable<Tab> {
    return this.auth.withCsrf(() =>
      this.http.put<Tab>(`/api/v1/tabs/${tab.id}/responsible`, {
        responsibleId,
        version: tab.version,
      }),
    );
  }

  continuationCandidates(tabId: string): Observable<ContinuationCandidate[]> {
    return this.http.get<ContinuationCandidate[]>(`/api/v1/tabs/${tabId}/visit-continuation/candidates`);
  }

  /** Vincula a comanda à visita da anterior (H26): só com a permanência confirmada pessoalmente. */
  linkVisit(tab: Tab, previousTabId: string, stayConfirmed: boolean): Observable<Tab> {
    return this.auth.withCsrf(() =>
      this.http.post<Tab>(`/api/v1/tabs/${tab.id}/visit-continuation`, {
        previousTabId,
        stayConfirmed,
        version: tab.version,
      }),
    );
  }

  bindingQr(tabId: string): Observable<BindingQr> {
    return this.auth.withCsrf(() =>
      this.http.post<BindingQr>(`/api/v1/tabs/${tabId}/binding-qr`, null),
    );
  }

  recover(tabId: string, input: RecoveryInput): Observable<BindingQr> {
    return this.auth.withCsrf(() =>
      this.http.post<BindingQr>(`/api/v1/tabs/${tabId}/access-recovery`, input),
    );
  }
}
