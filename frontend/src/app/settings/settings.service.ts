import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AuthService } from '../auth/auth.service';

export type ServiceBase = 'APOS_DESCONTO' | 'ANTES_DESCONTO';
export type CouvertMode = 'AUTOMATICO' | 'MANUAL';
export type AttendanceMode = 'GARCOM_RESPONSAVEL' | 'CENTRAL';
export type ExitMode = 'AUTOMATICA' | 'VALIDADA';

/** Campos editáveis; valores monetários e percentuais como texto decimal ("10.00"). */
export interface SettingsFields {
  serviceEnabled: boolean;
  servicePercent: string;
  serviceBase: ServiceBase;
  serviceIncludesCouvert: boolean;
  couvertEnabled: boolean;
  couvertMode: CouvertMode;
  couvertAmount: string | null;
  discountFixedAllowed: boolean;
  discountPercentAllowed: boolean;
  attendanceMode: AttendanceMode;
  exitMode: ExitMode;
  timeZone: string;
  cutoff: string;
}

export interface SettingsView extends SettingsFields {
  version: number;
  versionId: string;
  savedAt: string;
  calendarInUse: { timeZone: string; cutoff: string; since: string };
  /** Mudança de fuso/corte agendada para o próximo dia operacional. */
  pendingCalendar: {
    timeZone: string;
    cutoff: string;
    effectiveFrom: string;
    firstDate: string;
  } | null;
  today: { date: string; start: string; end: string; timeZone: string; cutoff: string };
  history: { version: number; createdAt: string; author: string | null; changed: string[] }[];
  editable: boolean;
}

/** Fusos do Brasil (IANA), com cidades de referência. */
export const TIME_ZONES: { id: string; label: string }[] = [
  { id: 'America/Sao_Paulo', label: 'Brasília / São Paulo (UTC−3)' },
  { id: 'America/Bahia', label: 'Salvador (UTC−3)' },
  { id: 'America/Fortaleza', label: 'Fortaleza (UTC−3)' },
  { id: 'America/Recife', label: 'Recife (UTC−3)' },
  { id: 'America/Maceio', label: 'Maceió / Aracaju (UTC−3)' },
  { id: 'America/Belem', label: 'Belém / Macapá (UTC−3)' },
  { id: 'America/Santarem', label: 'Santarém (UTC−3)' },
  { id: 'America/Araguaina', label: 'Palmas / Araguaína (UTC−3)' },
  { id: 'America/Noronha', label: 'Fernando de Noronha (UTC−2)' },
  { id: 'America/Campo_Grande', label: 'Campo Grande (UTC−4)' },
  { id: 'America/Cuiaba', label: 'Cuiabá (UTC−4)' },
  { id: 'America/Manaus', label: 'Manaus (UTC−4)' },
  { id: 'America/Porto_Velho', label: 'Porto Velho (UTC−4)' },
  { id: 'America/Boa_Vista', label: 'Boa Vista (UTC−4)' },
  { id: 'America/Rio_Branco', label: 'Rio Branco (UTC−5)' },
  { id: 'America/Eirunepe', label: 'Eirunepé (UTC−5)' },
];

const SECTIONS: Record<string, string> = {
  servico: 'serviço',
  couvert: 'couvert',
  desconto: 'desconto',
  atendimento: 'atendimento',
  saida: 'saída',
};

/** Partes alteradas numa versão, em português ("serviço e couvert"). */
export function changedLabel(changed: string[]): string {
  const names = changed.map((c) => SECTIONS[c] ?? c);
  if (names.length <= 1) {
    return names[0] ?? 'padrão inicial';
  }
  return `${names.slice(0, -1).join(', ')} e ${names[names.length - 1]}`;
}

/** Configuração versionada da unidade da sessão (H14). */
@Injectable({ providedIn: 'root' })
export class SettingsService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);

  load(): Observable<SettingsView> {
    return this.http.get<SettingsView>('/api/v1/establishment/settings');
  }

  save(version: number, fields: SettingsFields): Observable<SettingsView> {
    return this.auth.withCsrf(() =>
      this.http.put<SettingsView>('/api/v1/establishment/settings', { version, ...fields }),
    );
  }
}
