import { HttpClient } from '@angular/common/http';
import { DOCUMENT, Injectable, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, switchMap, tap } from 'rxjs';

import { UnitContextService } from '../session/unit-context.service';

export interface SessionView {
  displayName: string;
  idleTimeoutSeconds: number;
  idleExpiresAt: string;
  absoluteExpiresAt: string;
}

/** Mensagens de erro de autenticação para a UI, por código de problema do servidor. */
export const AUTH_MESSAGES: Record<string, string> = {
  CREDENCIAIS_INVALIDAS: 'E-mail ou senha incorretos.',
  MUITAS_TENTATIVAS: 'Muitas tentativas. Aguarde alguns minutos e tente novamente.',
  PAGINA_DESATUALIZADA: 'Sua página está desatualizada. Recarregue e tente novamente.',
  LINK_INVALIDO: 'Este link de redefinição não vale mais. Peça um novo.',
};

/**
 * Sessão da equipe (H08). A sessão vive no servidor (cookie HttpOnly); aqui fica só o nome e os prazos
 * para a tela. O token CSRF vem do cookie {@code XSRF-TOKEN} e o HttpClient o envia nas mutações.
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
