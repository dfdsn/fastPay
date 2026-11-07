import { DOCUMENT } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AuthService } from '../auth/auth.service';

/**
 * Situação do consumidor neste navegador (H27). {@code pendingEmail}: conta Google que voltou do login e aguarda o
 * aviso de privacidade.
 */
export interface ConsumerSession {
  signedIn: boolean;
  email: string | null;
  idleDeadline: string | null;
  absoluteDeadline: string | null;
  pendingEmail: string | null;
  noticeVersion: string;
}

/** A comanda está no histórico de alguma conta Google; {@code mine}: da conta conectada agora. */
export interface LinkState {
  linked: boolean;
  mine: boolean;
}

export interface HistoryEntry {
  tabId: string;
  establishmentName: string;
  number: number;
  name: string;
  openedAt: string;
  closedAt: string | null;
  linkedAt: string;
}

export interface HistoryPage {
  items: HistoryEntry[];
  page: number;
  size: number;
  total: number;
}

/** Resultado do login Google que o servidor devolve na volta, em {@code ?google=}. */
export const GOOGLE_RESULT_MESSAGE: Record<string, string> = {
  conectado: 'Você entrou com Google.',
  recusado: 'Login com Google cancelado. Sua comanda continua funcionando neste aparelho.',
  falhou: 'Não foi possível entrar com Google. Tente de novo; sua comanda continua funcionando neste aparelho.',
  indisponivel: 'Entrar com Google não está disponível agora. Sua comanda continua funcionando neste aparelho.',
  limite: 'Muitas tentativas de entrar com Google. Aguarde alguns minutos e tente de novo.',
};

/**
 * Login Google opcional e histórico do consumidor (H27). Entrar é uma navegação para o servidor, que leva ao Google
 * e volta para a tela de origem; nenhum token do Google passa pelo Angular.
 */
@Injectable({ providedIn: 'root' })
export class ConsumerService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly document = inject(DOCUMENT);

  session(): Observable<ConsumerSession> {
    return this.http.get<ConsumerSession>('/api/v1/consumer/session');
  }

  /** Vai para o Google; a volta é para {@code returnTo} (só telas do consumidor). */
  signInWithGoogle(returnTo: string): void {
    this.document.location.assign(`/api/v1/consumer/google/start?returnTo=${encodeURIComponent(returnTo)}`);
  }

  acceptPrivacy(noticeVersion: string): Observable<{ returnTo: string }> {
    return this.auth.withCsrf(() =>
      this.http.post<{ returnTo: string }>('/api/v1/consumer/privacy/accept', { noticeVersion }),
    );
  }

  declinePrivacy(): Observable<void> {
    return this.auth.withCsrf(() => this.http.post<void>('/api/v1/consumer/privacy/decline', null));
  }

  signOut(): Observable<void> {
    return this.auth.withCsrf(() => this.http.post<void>('/api/v1/consumer/logout', null));
  }

  linkState(tabId: string): Observable<LinkState> {
    return this.http.get<LinkState>(`/api/v1/guest/tabs/${tabId}/account-link`);
  }

  /** Guarda a comanda no histórico; o servidor exige a confirmação explícita. */
  link(tabId: string): Observable<LinkState> {
    return this.auth.withCsrf(() =>
      this.http.post<LinkState>(`/api/v1/guest/tabs/${tabId}/account-link`, { confirmed: true }),
    );
  }

  history(page: number): Observable<HistoryPage> {
    return this.http.get<HistoryPage>('/api/v1/consumer/history', { params: { page } });
  }
}

/** Só caminhos das telas do consumidor; qualquer outra coisa volta para "Minhas comandas". */
export function safeReturn(returnTo: string | null): string {
  return returnTo && /^\/(minhas-comandas|minha-comanda\/[0-9a-fA-F-]{36}|meu-historico)$/.test(returnTo)
    ? returnTo
    : '/minhas-comandas';
}
