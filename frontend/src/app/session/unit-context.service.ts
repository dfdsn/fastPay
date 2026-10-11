import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

export type MembershipRole = 'ADMIN_ESTABELECIMENTO' | 'GERENTE' | 'GARCOM' | 'PRODUCAO';

export interface UnitView {
  establishmentId: string;
  name: string;
  role: MembershipRole;
}

export interface UnitsResponse {
  displayName: string;
  units: UnitView[];
  currentEstablishmentId?: string | null;
}

const ROLE_LABELS: Record<MembershipRole, string> = {
  ADMIN_ESTABELECIMENTO: 'Administrador do estabelecimento',
  GERENTE: 'Gerente',
  GARCOM: 'Garçom',
  PRODUCAO: 'Produção',
};

export function roleLabel(role: MembershipRole): string {
  return ROLE_LABELS[role];
}

/** Códigos de problema que significam "esta sessão não tem unidade válida": voltar à seleção. */
export const UNIT_LOST_CODES = new Set(['UNIDADE_NAO_AUTORIZADA', 'UNIDADE_NAO_SELECIONADA']);

export function problemCode(error: unknown): string | undefined {
  if (error instanceof HttpErrorResponse && error.error && typeof error.error === 'object') {
    const code = (error.error as { code?: unknown }).code;
    return typeof code === 'string' ? code : undefined;
  }
  return undefined;
}

/**
 * Unidade ativa da sessão (H07). A escolha vale no servidor, que revalida o vínculo em cada operação;
 * aqui fica só o que a tela precisa mostrar. Trocar ou perder a unidade executa as limpezas registradas
 * pelas áreas que guardam dados da unidade, para nada da anterior aparecer na nova.
 */
@Injectable({ providedIn: 'root' })
export class UnitContextService {
  private readonly http = inject(HttpClient);
  private readonly cleaners = new Set<() => void>();

  readonly current = signal<UnitView | null>(null);

  units(): Observable<UnitsResponse> {
    return this.http.get<UnitsResponse>('/api/v1/session/units');
  }

  /** Consulta a unidade ativa no servidor (revalidada lá). */
  refresh(): Observable<UnitView> {
    return this.http.get<UnitView>('/api/v1/session/unit').pipe(tap((unit) => this.adopt(unit)));
  }

  select(establishmentId: string): Observable<UnitView> {
    return this.http
      .put<UnitView>('/api/v1/session/unit', { establishmentId })
      .pipe(tap((unit) => this.adopt(unit)));
  }

  leave(): Observable<void> {
    return this.http.delete<void>('/api/v1/session/unit').pipe(tap(() => this.clear()));
  }

  /** Registra limpeza de dados da unidade; devolve a função que cancela o registro. */
  onUnitCleared(cleaner: () => void): () => void {
    this.cleaners.add(cleaner);
    return () => this.cleaners.delete(cleaner);
  }

  /** Esquece a unidade local e limpa os dados dela. */
  clear(): void {
    this.current.set(null);
    for (const cleaner of [...this.cleaners]) {
      cleaner();
    }
  }

  private adopt(unit: UnitView): void {
    const previous = this.current();
    if (previous && previous.establishmentId !== unit.establishmentId) {
      this.clear();
    }
    this.current.set(unit);
  }
}
