import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AuthService } from '../auth/auth.service';
import { MembershipRole, Permission } from '../session/unit-context.service';
import { InvitationStatus } from '../team/team.service';

export type GrantStatus = 'ATIVA' | 'VENCIDA' | 'ENCERRADA';

export interface SupportGrant {
  id: string;
  establishmentId: string;
  establishmentName: string;
  supportEmail: string;
  reason: string;
  grantedAt: string;
  expiresAt: string;
  revokedAt: string | null;
  status: GrantStatus;
  consultations: number;
  lastConsultationAt: string | null;
}

export interface SupportGrants {
  grants: SupportGrant[];
  maxHours: number;
}

export interface ActiveSupportGrant {
  id: string;
  establishmentId: string;
  establishmentName: string;
  reason: string;
  grantedAt: string;
  expiresAt: string;
}

export interface SupportTeam {
  members: {
    displayName: string;
    email: string;
    role: MembershipRole;
    active: boolean;
    delegated: Permission[];
  }[];
  pendingInvitations: {
    email: string;
    role: MembershipRole;
    status: InvitationStatus;
    expiresAt: string | null;
  }[];
}

/**
 * Suporte autorizado (H11, FR-66). O dono concede e encerra na unidade da sessão; a pessoa de suporte só
 * consulta, e o servidor confere a concessão vigente e registra cada consulta.
 */
@Injectable({ providedIn: 'root' })
export class SupportService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  grants(): Observable<SupportGrants> {
    return this.http.get<SupportGrants>('/api/v1/support-grants');
  }

  grant(supportEmail: string, reason: string, hours: number): Observable<SupportGrants> {
    return this.auth.withCsrf(() =>
      this.http.post<SupportGrants>('/api/v1/support-grants', { supportEmail, reason, hours }),
    );
  }

  revoke(grantId: string): Observable<SupportGrants> {
    return this.auth.withCsrf(() =>
      this.http.post<SupportGrants>(`/api/v1/support-grants/${grantId}/revoke`, null),
    );
  }

  myGrants(): Observable<ActiveSupportGrant[]> {
    return this.http.get<ActiveSupportGrant[]>('/api/v1/support/grants');
  }

  team(establishmentId: string): Observable<SupportTeam> {
    return this.http.get<SupportTeam>(`/api/v1/support/units/${establishmentId}/team`);
  }
}
