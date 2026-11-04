import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AuthService } from '../auth/auth.service';
import { ProductionLine } from '../production/production.service';

export type CallKind = 'ITEM_PRONTO' | 'IMPOSSIBILIDADE';
export type AttendanceMode = 'GARCOM_RESPONSAVEL' | 'CENTRAL';

/**
 * Chamado de atendimento em aberto (H20). {@code mode}: a modalidade da comanda, capturada na abertura.
 * {@code owner}: o garçom responsável da comanda ou quem assumiu na central; nulo na central sem dono.
 * {@code line}: o item, com a versão para entregar.
 */
export interface AttendanceCall {
  id: string;
  kind: CallKind;
  mode: AttendanceMode;
  tab: { id: string; number: number; name: string; table: string | null };
  owner: { id: string; name: string } | null;
  mine: boolean;
  quantity: number;
  createdAt: string;
  claimedAt: string | null;
  line: ProductionLine | null;
  version: number;
}

export interface AttendanceQueue {
  now: string;
  calls: AttendanceCall[];
}

export interface Attendant {
  id: string;
  displayName: string;
}

/** Fila de atendimento da unidade da sessão (H20); a permissão é conferida no servidor a cada chamada. */
@Injectable({ providedIn: 'root' })
export class AttendanceService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  queue(): Observable<AttendanceQueue> {
    return this.http.get<AttendanceQueue>('/api/v1/attendance/calls');
  }

  claim(call: AttendanceCall): Observable<AttendanceCall> {
    return this.auth.withCsrf(() =>
      this.http.post<AttendanceCall>(`/api/v1/attendance/calls/${call.id}/claim`, {}),
    );
  }

  assign(call: AttendanceCall, assigneeId: string): Observable<AttendanceCall> {
    return this.auth.withCsrf(() =>
      this.http.post<AttendanceCall>(`/api/v1/attendance/calls/${call.id}/assign`, {
        assigneeId,
        version: call.version,
      }),
    );
  }

  attendants(): Observable<Attendant[]> {
    return this.http.get<Attendant[]>('/api/v1/attendance/attendants');
  }
}

export const KIND_LABEL: Record<CallKind, string> = {
  ITEM_PRONTO: 'Pronto para entregar',
  IMPOSSIBILIDADE: 'Não é possível atender',
};
