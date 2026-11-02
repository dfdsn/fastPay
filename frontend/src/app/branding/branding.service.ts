import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AuthService } from '../auth/auth.service';

export interface BrandingView {
  establishmentId: string;
  name: string;
  /** Ausente: sem logo; as telas mostram as iniciais do nome. */
  logoFileId: string | null;
}

const CONNECTIVES = new Set(['a', 'o', 'e', 'da', 'de', 'do', 'das', 'dos']);

/** Iniciais do nome da unidade, para quando não há logo (FR-67): até duas letras, sem "da", "de", "do"... */
export function initials(name: string): string {
  const words = name
    .split(/\s+/)
    .map((w) => w.replace(/[^\p{L}\p{N}]/gu, ''))
    .filter((w) => w.length > 0);
  const significant = words.filter((w) => !CONNECTIVES.has(w.toLocaleLowerCase('pt-BR')));
  const chosen = (significant.length > 0 ? significant : words).slice(0, 2);
  return chosen.map((w) => w[0].toLocaleUpperCase('pt-BR')).join('');
}

/** Nome e logo da unidade da sessão (H13). */
@Injectable({ providedIn: 'root' })
export class BrandingService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  load(): Observable<BrandingView> {
    return this.http.get<BrandingView>('/api/v1/establishment');
  }

  setLogo(file: File): Observable<BrandingView> {
    const body = new FormData();
    body.append('file', file);
    return this.auth.withCsrf(() =>
      this.http.put<BrandingView>('/api/v1/establishment/logo', body),
    );
  }

  removeLogo(): Observable<BrandingView> {
    return this.auth.withCsrf(() => this.http.delete<BrandingView>('/api/v1/establishment/logo'));
  }
}
