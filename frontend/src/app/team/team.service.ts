import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AuthService } from '../auth/auth.service';
import { MembershipRole, Permission } from '../session/unit-context.service';

export type InvitationStatus = 'ENVIO_PENDENTE' | 'ENVIADO' | 'VENCIDO' | 'ACEITO' | 'INVALIDADO';

export interface TeamMember {
  membershipId: string;
  displayName: string;
  email: string;
  role: MembershipRole;
  active: boolean;
  since: string;
  deactivatedAt: string | null;
  delegated: Permission[];
}

export interface PendingInvitation {
  id: string;
  email: string;
  role: MembershipRole;
  status: InvitationStatus;
  createdAt: string;
  sentAt: string | null;
  expiresAt: string | null;
}

export interface DelegablePermission {
  code: Permission;
  label: string;
}

export interface TeamView {
  members: TeamMember[];
  pendingInvitations: PendingInvitation[];
  delegable: DelegablePermission[];
  currentMembershipId: string | null;
}

/** Equipe da unidade da sessão (H11). Cada chamada é autorizada no servidor pela permissão da ação. */
@Injectable({ providedIn: 'root' })
export class TeamService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  load(): Observable<TeamView> {
    return this.http.get<TeamView>('/api/v1/team');
  }

  invite(email: string, role: MembershipRole): Observable<TeamView> {
    return this.auth.withCsrf(() =>
      this.http.post<TeamView>('/api/v1/team/invitations', { email, role }),
    );
  }

  resend(invitationId: string): Observable<TeamView> {
    return this.auth.withCsrf(() =>
      this.http.post<TeamView>(`/api/v1/team/invitations/${invitationId}/resend`, null),
    );
  }

  cancel(invitationId: string): Observable<TeamView> {
    return this.auth.withCsrf(() =>
      this.http.delete<TeamView>(`/api/v1/team/invitations/${invitationId}`),
    );
  }

  changeRole(membershipId: string, role: MembershipRole): Observable<TeamView> {
    return this.auth.withCsrf(() =>
      this.http.put<TeamView>(`/api/v1/team/members/${membershipId}/role`, { role }),
    );
  }

  deactivate(membershipId: string): Observable<TeamView> {
    return this.auth.withCsrf(() =>
      this.http.post<TeamView>(`/api/v1/team/members/${membershipId}/deactivate`, null),
    );
  }

  setPermissions(membershipId: string, permissions: Permission[]): Observable<TeamView> {
    return this.auth.withCsrf(() =>
      this.http.put<TeamView>(`/api/v1/team/members/${membershipId}/permissions`, { permissions }),
    );
  }
}
