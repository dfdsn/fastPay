import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AuthService } from '../auth/auth.service';

export type InvitationStatus = 'ENVIO_PENDENTE' | 'ENVIADO' | 'VENCIDO' | 'ACEITO' | 'INVALIDADO';

export interface OwnerInvitation {
  status: InvitationStatus;
  sentAt: string | null;
  expiresAt: string | null;
  acceptedAt: string | null;
}

export interface Establishment {
  id: string;
  name: string;
  ownerEmail: string;
  document: string | null;
  /** Só CONFIGURACAO até a ativação (H39). */
  status: string;
  ownerInvitation: OwnerInvitation | null;
}

export interface EstablishmentDraft {
  name: string;
  ownerEmail: string;
  document: string | null;
}

const INVITATION_LABELS: Record<InvitationStatus, string> = {
  ENVIO_PENDENTE: 'Convite aguardando envio',
  ENVIADO: 'Convite enviado',
  VENCIDO: 'Convite vencido',
  ACEITO: 'Dono aceitou',
  INVALIDADO: 'Convite substituído',
};

export function invitationLabel(invitation: OwnerInvitation | null): string {
  return invitation ? INVITATION_LABELS[invitation.status] : 'Sem convite';
}

/** Cadastro de estabelecimentos pelo admin fastPay (H10, FR-01). Autorização sempre no servidor. */
@Injectable({ providedIn: 'root' })
export class EstablishmentsService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  list(): Observable<Establishment[]> {
    return this.http.get<Establishment[]>('/api/v1/establishments');
  }

  create(draft: EstablishmentDraft): Observable<Establishment> {
    return this.auth.withCsrf(() => this.http.post<Establishment>('/api/v1/establishments', draft));
  }

  /** Novo convite para o dono; o anterior deixa de valer. */
  resendOwnerInvitation(id: string): Observable<Establishment> {
    return this.auth.withCsrf(() =>
      this.http.post<Establishment>(`/api/v1/establishments/${id}/owner-invitation`, null),
    );
  }
}
