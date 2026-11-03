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
}
