import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AuthService } from '../auth/auth.service';
import { MembershipRole } from '../session/unit-context.service';

export interface InvitationPreview {
  establishmentName: string;
  role: MembershipRole;
  roleLabel: string;
  email: string;
  expiresAt: string;
  /** O e-mail do convite já tem conta no fastPay: aceitar exige entrar com ela. */
  accountExists: boolean;
  /** Há sessão autenticada neste navegador; o aceite será da conta dela. */
  signedIn: boolean;
}

export interface InvitationAccepted {
  establishmentId: string;
  signedIn: boolean;
}

/** Mensagens do aceite de convite, por código de problema do servidor. */
export const INVITATION_MESSAGES: Record<string, string> = {
  CONVITE_INVALIDO: 'Este convite não vale mais. Peça um novo a quem convidou você.',
  ENTRE_PARA_ACEITAR:
    'Este e-mail já tem conta no fastPay. Entre com ela e abra o link do convite de novo.',
  CONVITE_DE_OUTRA_CONTA:
    'Este convite foi enviado para outro e-mail. Saia e entre com a conta do e-mail convidado.',
  JA_PARTICIPA: 'Você já participa desta unidade.',
  MUITAS_TENTATIVAS: 'Muitas tentativas. Aguarde alguns minutos e tente novamente.',
  PAGINA_DESATUALIZADA: 'Sua página está desatualizada. Recarregue e tente novamente.',
};

/**
 * Convites (H10, FR-02). O token vai no corpo de um POST, nunca na URL: o link o leva no fragmento, que a tela
 * lê e tira da barra de endereço.
 */
@Injectable({ providedIn: 'root' })
export class InvitationService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  preview(token: string): Observable<InvitationPreview> {
    return this.auth.withCsrf(() =>
      this.http.post<InvitationPreview>('/api/v1/invitations/preview', { token }),
    );
  }

  /** Sem sessão, cria a conta com nome e senha; com sessão, aceita com a conta atual. */
  accept(
    token: string,
    account?: { displayName: string; password: string },
  ): Observable<InvitationAccepted> {
    return this.auth.withCsrf(() =>
      this.http.post<InvitationAccepted>('/api/v1/invitations/accept', { token, ...account }),
    );
  }
}
