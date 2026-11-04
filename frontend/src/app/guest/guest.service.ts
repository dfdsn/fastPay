import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AuthService } from '../auth/auth.service';

/** O que o cliente vê antes de confirmar que a comanda é dele. */
export interface BindingPreview {
  establishmentName: string;
  number: number;
  name: string;
  expiresAt: string;
}

/** Comanda vista pelo cliente avulso. {@code open}: ainda em atendimento. */
export interface GuestTab {
  tabId: string;
  establishmentName: string;
  number: number;
  name: string;
  table: string | null;
  openedAt: string;
  open: boolean;
}

export type IssueStatus = 'ABERTO' | 'EM_ATENDIMENTO' | 'TRATADO';

/** Item da comanda que o cliente pode apontar num problema (H21): sem valores. */
export interface GuestItem {
  orderLineId: string;
  productName: string;
  quantity: number;
  sentAt: string;
}

/** Problema como o cliente o vê (H21). {@code item} nulo: problema da conta. */
export interface GuestIssue {
  id: string;
  item: { orderLineId: string; productName: string | null } | null;
  description: string;
  status: IssueStatus;
  afterClosure: boolean;
  createdAt: string;
  attendedAt: string | null;
  concludedAt: string | null;
  resolution: string | null;
}

export interface GuestIssues {
  now: string;
  closed: boolean;
  items: GuestItem[];
  issues: GuestIssue[];
}

export const ISSUE_STATUS_LABEL: Record<IssueStatus, string> = {
  ABERTO: 'Aberto',
  EM_ATENDIMENTO: 'Em atendimento',
  TRATADO: 'Tratado',
};

/**
 * Cliente sem login (H15). O token do QR vai no corpo, nunca na URL; o acesso confirmado fica num cookie do servidor
 * (HttpOnly), que esta tela não lê.
 */
@Injectable({ providedIn: 'root' })
export class GuestService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  preview(token: string): Observable<BindingPreview> {
    return this.auth.withCsrf(() =>
      this.http.post<BindingPreview>('/api/v1/guest/bindings/preview', { token }),
    );
  }

  confirm(token: string): Observable<GuestTab> {
    return this.auth.withCsrf(() =>
      this.http.post<GuestTab>('/api/v1/guest/bindings/confirm', { token }),
    );
  }

  tabs(): Observable<GuestTab[]> {
    return this.http.get<GuestTab[]>('/api/v1/guest/tabs');
  }

  tab(id: string): Observable<GuestTab> {
    return this.http.get<GuestTab>(`/api/v1/guest/tabs/${id}`);
  }

  issues(tabId: string): Observable<GuestIssues> {
    return this.http.get<GuestIssues>(`/api/v1/guest/tabs/${tabId}/issues`);
  }

  /** {@code orderLineId} nulo: problema da conta. */
  report(tabId: string, orderLineId: string | null, description: string): Observable<GuestIssue> {
    return this.auth.withCsrf(() =>
      this.http.post<GuestIssue>(`/api/v1/guest/tabs/${tabId}/issues`, { orderLineId, description }),
    );
  }
}
