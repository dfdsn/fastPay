import { HttpClient } from '@angular/common/http';
import { DOCUMENT, Injectable, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, switchMap, tap } from 'rxjs';

import { UnitContextService } from '../session/unit-context.service';

/** Etapa da autenticação (H09): só AUTENTICADO dá acesso às funções. */
export type AuthStage =
  | 'AUTENTICADO'
  | 'MFA_PENDENTE'
  | 'MFA_CADASTRO_OBRIGATORIO'
  | 'MFA_RECADASTRO';

export interface SessionView {
  stage: AuthStage;
  displayName: string;
  /** Prazos só existem na sessão autenticada; na etapa do MFA vêm nulos. */
  idleTimeoutSeconds: number | null;
  idleExpiresAt: string | null;
  absoluteExpiresAt: string | null;
}

export interface MfaSetup {
  /** Segredo em grupos de quatro, para digitar no aplicativo. */
  secret: string;
  otpauthUri: string;
}

export interface MfaEnrolled {
  /** Mostrados uma única vez. */
  recoveryCodes: string[];
  session: SessionView;
}

const MFA_STAGES: readonly string[] = ['MFA_PENDENTE', 'MFA_CADASTRO_OBRIGATORIO', 'MFA_RECADASTRO'];

export function isMfaStage(code: string | undefined): code is Exclude<AuthStage, 'AUTENTICADO'> {
  return code !== undefined && MFA_STAGES.includes(code);
}

/** Tela de cada etapa: depois da senha, o MFA; autenticado, a escolha de unidade. */
export function stageRoute(stage: AuthStage): string {
  switch (stage) {
    case 'AUTENTICADO':
      return '/unidades';
    case 'MFA_PENDENTE':
      return '/verificacao';
    case 'MFA_CADASTRO_OBRIGATORIO':
    case 'MFA_RECADASTRO':
      return '/configurar-verificacao';
  }
}

/** Mensagens de erro de autenticação para a UI, por código de problema do servidor. */
export const AUTH_MESSAGES: Record<string, string> = {
  CREDENCIAIS_INVALIDAS: 'E-mail ou senha incorretos.',
  MUITAS_TENTATIVAS: 'Muitas tentativas. Aguarde alguns minutos e tente novamente.',
  PAGINA_DESATUALIZADA: 'Sua página está desatualizada. Recarregue e tente novamente.',
  LINK_INVALIDO: 'Este link de redefinição não vale mais. Peça um novo.',
  CODIGO_INVALIDO: 'Código incorreto ou já usado. Confira o aplicativo e tente de novo.',
  MFA_JA_ATIVO: 'A verificação em duas etapas já está ativa nesta conta.',
  CADASTRO_NAO_INICIADO: 'Comece a configuração do autenticador de novo.',
};

/**
 * Sessão da equipe (H08). A sessão vive no servidor (cookie HttpOnly); aqui fica só o nome, a etapa
 * (H09) e os prazos para a tela. O token CSRF vem do cookie {@code XSRF-TOKEN} e o HttpClient o envia nas mutações.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly document = inject(DOCUMENT);
  private readonly units = inject(UnitContextService);

  readonly session = signal<SessionView | null>(null);

  /** Consulta a sessão no servidor; 401 significa que não há sessão. */
  refresh(): Observable<SessionView> {
    return this.http
      .get<SessionView>('/api/v1/auth/session')
      .pipe(tap((session) => this.session.set(session)));
  }

  login(email: string, password: string): Observable<SessionView> {
    return this.withCsrf(() =>
      this.http.post<SessionView>('/api/v1/auth/login', { email, password }),
    ).pipe(
      tap((session) => {
        // Nada de uma sessão anterior (outra pessoa no mesmo aparelho) fica na tela.
        this.units.clear();
        this.session.set(session);
      }),
    );
  }

  logout(): Observable<void> {
    return this.withCsrf(() => this.http.post<void>('/api/v1/auth/logout', null)).pipe(
      catchError(() => of(undefined)),
      tap(() => this.forget()),
    );
  }

  /** Segundo passo do login: código do autenticador ou de recuperação. */
  verifyMfa(code: string): Observable<SessionView> {
    return this.withCsrf(() =>
      this.http.post<SessionView>('/api/v1/auth/mfa/verify', { code }),
    ).pipe(tap((session) => this.session.set(session)));
  }

  startMfaSetup(): Observable<MfaSetup> {
    return this.withCsrf(() => this.http.post<MfaSetup>('/api/v1/auth/mfa/setup', null));
  }

  confirmMfaSetup(code: string): Observable<MfaEnrolled> {
    return this.withCsrf(() =>
      this.http.post<MfaEnrolled>('/api/v1/auth/mfa/setup/confirm', { code }),
    ).pipe(tap((enrolled) => this.session.set(enrolled.session)));
  }

  /** O servidor pediu o MFA no meio da sessão (promoção, MFA ativado em outro aparelho). */
  requireStage(stage: Exclude<AuthStage, 'AUTENTICADO'>): void {
    const current = this.session();
    this.units.clear();
    this.session.set(
      current
        ? {
            ...current,
            stage,
            idleTimeoutSeconds: null,
            idleExpiresAt: null,
            absoluteExpiresAt: null,
          }
        : null,
    );
  }

  requestPasswordReset(email: string): Observable<string> {
    return this.withCsrf(() =>
      this.http.post<{ detail: string }>('/api/v1/auth/password-reset', { email }),
    ).pipe(map((response) => response.detail));
  }

  confirmPasswordReset(token: string, newPassword: string): Observable<void> {
    return this.withCsrf(() =>
      this.http.post<void>('/api/v1/auth/password-reset/confirm', { token, newPassword }),
    ).pipe(tap(() => this.forget()));
  }

  /** Esquece a sessão local e os dados da unidade (logout, sessão vencida, senha trocada). */
  forget(): void {
    this.session.set(null);
    this.units.clear();
  }

  /** Antes da primeira mutação o navegador ainda pode não ter o cookie CSRF: uma leitura o obtém. */
  private withCsrf<T>(mutation: () => Observable<T>): Observable<T> {
    if (this.document.cookie.split('; ').some((cookie) => cookie.startsWith('XSRF-TOKEN='))) {
      return mutation();
    }
    return this.http.get('/api/v1/platform/info').pipe(
      catchError(() => of(null)),
      switchMap(() => mutation()),
    );
  }
}
