import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { failureMessage, problemCode } from '../session/unit-context.service';
import {
  AttendanceMode,
  CouvertMode,
  ExitMode,
  ServiceBase,
  SettingsFields,
  SettingsService,
  SettingsView,
  TIME_ZONES,
  changedLabel,
} from './settings.service';

/**
 * Configuração da unidade (H14): serviço, couvert, formatos de desconto, atendimento, saída, fuso e corte do dia
 * operacional. O administrador altera; o gerente consulta. Cada mudança cria uma versão nova que vale para as
 * comandas abertas depois de salvar; as já abertas mantêm a da abertura. Fuso e corte mudam só no início do
 * próximo dia operacional. O servidor valida tudo; a tela mostra a recusa dele no campo.
 */
@Component({
  selector: 'fp-settings-page',
  imports: [
    DatePipe,
    ReactiveFormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
  ],
  template: `
    <h1>Configuração da unidade</h1>
    <div role="status" aria-live="polite">
      @if (state() === 'loading') {
        <mat-progress-spinner mode="indeterminate" diameter="32" aria-hidden="true" />
        <p>Carregando…</p>
      } @else if (state() === 'error') {
        <p>Não foi possível carregar. Verifique sua conexão e tente novamente.</p>
      }
      @if (notice(); as message) {
        <p>{{ message }}</p>
      }
    </div>
    <div role="alert">
      @if (failure(); as message) {
        <p class="error">{{ message }}</p>
      }
    </div>
    @if (state() === 'error' || outdated()) {
      <button mat-stroked-button type="button" (click)="load()">Recarregar</button>
    }

    @if (view(); as current) {
      <p class="hint">
        Versão {{ current.version }}. Mudanças valem para comandas abertas depois de salvar; as já abertas
        mantêm a configuração da abertura.
        @if (!current.editable) {
          Só o administrador do estabelecimento altera.
        }
      </p>
      <form [formGroup]="form" (ngSubmit)="save()" novalidate>
        <mat-card appearance="outlined">
          <mat-card-header><mat-card-title><h2>Taxa de serviço</h2></mat-card-title></mat-card-header>
          <mat-card-content>
            <label class="check">
              <input type="checkbox" formControlName="serviceEnabled" />
              Cobrar taxa de serviço
            </label>
            <mat-form-field appearance="outline">
              <mat-label>Percentual do serviço (%)</mat-label>
              <input matInput formControlName="servicePercent" inputmode="decimal" autocomplete="off" />
              <mat-hint>De 0 a 20, com até duas casas (ex.: 10 ou 12,5).</mat-hint>
            </mat-form-field>
            @if (fieldError('servicePercent'); as message) {
              <p class="error" id="erro-servicePercent">{{ message }}</p>
            }
            <fieldset>
              <legend>Base de cálculo</legend>
              @for (option of serviceBases; track option.value) {
                <label class="check">
                  <input type="radio" formControlName="serviceBase" [value]="option.value" />
                  {{ option.label }}
                </label>
              }
            </fieldset>
            <label class="check">
              <input type="checkbox" formControlName="serviceIncludesCouvert" />
              Incluir o couvert na base do serviço
            </label>
            <p class="hint">Na conferência o cliente pode retirar o serviço antes de pagar.</p>
          </mat-card-content>
        </mat-card>

        <mat-card appearance="outlined">
          <mat-card-header><mat-card-title><h2>Couvert</h2></mat-card-title></mat-card-header>
          <mat-card-content>
            <label class="check">
              <input type="checkbox" formControlName="couvertEnabled" />
              Cobrar couvert
            </label>
            <mat-form-field appearance="outline">
              <mat-label>Valor por pessoa (R$)</mat-label>
              <input matInput formControlName="couvertAmount" inputmode="decimal" autocomplete="off" />
              <mat-hint>De R$ 0,01 a R$ 200,00 (ex.: 15,00).</mat-hint>
            </mat-form-field>
            @if (fieldError('couvertAmount'); as message) {
              <p class="error">{{ message }}</p>
            }
            <fieldset>
              <legend>Lançamento</legend>
              @for (option of couvertModes; track option.value) {
                <label class="check">
                  <input type="radio" formControlName="couvertMode" [value]="option.value" />
                  {{ option.label }}
                </label>
              }
            </fieldset>
          </mat-card-content>
        </mat-card>

        <mat-card appearance="outlined">
          <mat-card-header><mat-card-title><h2>Desconto</h2></mat-card-title></mat-card-header>
          <mat-card-content>
            <p class="hint">Um desconto por comanda, sobre o consumo (nunca sobre o couvert), com motivo.</p>
            <label class="check">
              <input type="checkbox" formControlName="discountFixedAllowed" />
              Aceitar desconto em reais
            </label>
            <label class="check">
              <input type="checkbox" formControlName="discountPercentAllowed" />
              Aceitar desconto percentual
            </label>
          </mat-card-content>
        </mat-card>

        <mat-card appearance="outlined">
          <mat-card-header>
            <mat-card-title><h2>Atendimento e saída</h2></mat-card-title>
          </mat-card-header>
          <mat-card-content>
            <fieldset>
              <legend>Atendimento</legend>
              @for (option of attendanceModes; track option.value) {
                <label class="check">
                  <input type="radio" formControlName="attendanceMode" [value]="option.value" />
                  {{ option.label }}
                </label>
              }
            </fieldset>
            <fieldset>
              <legend>Saída do cliente</legend>
              @for (option of exitModes; track option.value) {
                <label class="check">
                  <input type="radio" formControlName="exitMode" [value]="option.value" />
                  {{ option.label }}
                </label>
              }
            </fieldset>
          </mat-card-content>
        </mat-card>

        <mat-card appearance="outlined">
          <mat-card-header><mat-card-title><h2>Dia operacional</h2></mat-card-title></mat-card-header>
          <mat-card-content>
            <p>
              Hoje: dia {{ current.today.date | date: 'dd/MM/yyyy' : 'UTC' }}, de
              {{ current.today.start | date: 'dd/MM HH:mm' : offset(current.today.start, current.today.timeZone) }}
              a {{ current.today.end | date: 'dd/MM HH:mm' : offset(current.today.end, current.today.timeZone) }}
              ({{ zoneLabel(current.today.timeZone) }}).
            </p>
            @if (current.pendingCalendar; as pending) {
              <p class="pending">
                Mudança agendada: {{ zoneLabel(pending.timeZone) }}, corte às {{ pending.cutoff }}, a partir do dia
                {{ pending.firstDate | date: 'dd/MM/yyyy' : 'UTC' }}. Para cancelar, volte ao fuso e corte em uso
                ({{ zoneLabel(current.calendarInUse.timeZone) }}, {{ current.calendarInUse.cutoff }}) e salve.
              </p>
            }
            <mat-form-field appearance="outline">
              <mat-label>Fuso horário</mat-label>
              <select matNativeControl formControlName="timeZone">
                @for (zone of zones; track zone.id) {
                  <option [value]="zone.id">{{ zone.label }}</option>
                }
              </select>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Horário de corte</mat-label>
              <input matInput type="time" formControlName="cutoff" min="00:00" max="12:00" step="60" />
              <mat-hint>Entre 00:00 e 12:00. Ex.: 06:00 conta a madrugada no dia anterior.</mat-hint>
            </mat-form-field>
            @if (fieldError('cutoff'); as message) {
              <p class="error">{{ message }}</p>
            }
            <p class="hint">
              Mudança de fuso ou corte vale só a partir do próximo dia operacional; o que já passou não muda de dia.
            </p>
          </mat-card-content>
        </mat-card>

        @if (current.editable) {
          <button mat-flat-button type="submit" [disabled]="busy()">Salvar configuração</button>
        }
      </form>

      <mat-card appearance="outlined">
        <mat-card-header><mat-card-title><h2>Histórico</h2></mat-card-title></mat-card-header>
        <mat-card-content>
          <ul aria-label="Versões da configuração">
            @for (entry of current.history; track entry.version) {
              <li>
                Versão {{ entry.version }}, {{ entry.createdAt | date: 'dd/MM/yyyy HH:mm' }}:
                {{ label(entry.changed) }}{{ entry.author ? ', por ' + entry.author : '' }}.
              </li>
            }
          </ul>
        </mat-card-content>
      </mat-card>
    }
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      gap: 16px;
      max-width: 640px;
      margin: 0 auto;
    }
    form {
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    h1,
    h2 {
      font: inherit;
      font-weight: 500;
      margin: 0;
    }
    fieldset {
      border: none;
      margin: 8px 0;
      padding: 0;
    }
    legend {
      font-weight: 500;
    }
    .check {
      display: flex;
      gap: 8px;
      align-items: center;
      min-height: 32px;
    }
    mat-form-field {
      display: block;
      margin-top: 8px;
    }
    .hint {
      color: var(--mat-sys-on-surface-variant);
    }
    .pending {
      font-weight: 500;
    }
    .error {
      color: var(--mat-sys-error);
    }
  `,
})
export class SettingsPage {
  private readonly service = inject(SettingsService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly zones = TIME_ZONES;
  protected readonly serviceBases: { value: ServiceBase; label: string }[] = [
    { value: 'APOS_DESCONTO', label: 'Consumo depois do desconto (padrão)' },
    { value: 'ANTES_DESCONTO', label: 'Consumo antes do desconto' },
  ];
  protected readonly couvertModes: { value: CouvertMode; label: string }[] = [
    { value: 'AUTOMATICO', label: 'Automático, na abertura da comanda' },
    { value: 'MANUAL', label: 'Lançado pela equipe' },
  ];
  protected readonly attendanceModes: { value: AttendanceMode; label: string }[] = [
    { value: 'GARCOM_RESPONSAVEL', label: 'Garçom responsável: quem abre a comanda atende' },
    { value: 'CENTRAL', label: 'Central: fila compartilhada, cada chamado é assumido por uma pessoa' },
  ];
  protected readonly exitModes: { value: ExitMode; label: string }[] = [
    { value: 'AUTOMATICA', label: 'Automática depois de quitar' },
    { value: 'VALIDADA', label: 'Validada por um funcionário' },
  ];

  protected readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  protected readonly view = signal<SettingsView | null>(null);
  protected readonly busy = signal(false);
  protected readonly outdated = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly failure = signal<string | null>(null);
  private readonly problems = signal<Record<string, string>>({});

  protected readonly form = inject(FormBuilder).nonNullable.group({
    serviceEnabled: true,
    servicePercent: '',
    serviceBase: 'APOS_DESCONTO' as ServiceBase,
    serviceIncludesCouvert: false,
    couvertEnabled: false,
    couvertMode: 'AUTOMATICO' as CouvertMode,
    couvertAmount: '',
    discountFixedAllowed: true,
    discountPercentAllowed: true,
    attendanceMode: 'GARCOM_RESPONSAVEL' as AttendanceMode,
    exitMode: 'AUTOMATICA' as ExitMode,
    timeZone: 'America/Sao_Paulo',
    cutoff: '06:00',
  });

  constructor() {
    this.load();
  }

  protected load(): void {
    this.state.set('loading');
    this.outdated.set(false);
    this.failure.set(null);
    this.service
      .load()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (view) => this.show(view),
        error: () => this.state.set('error'),
      });
  }

  protected save(): void {
    const current = this.view();
    if (!current || this.busy()) {
      return;
    }
    const value = this.form.getRawValue();
    const fields: SettingsFields = {
      ...value,
      couvertAmount: value.couvertAmount.trim() === '' ? null : value.couvertAmount.trim(),
    };
    this.busy.set(true);
    this.notice.set(null);
    this.failure.set(null);
    this.problems.set({});
    this.service
      .save(current.version, fields)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (view) => {
          this.busy.set(false);
          this.show(view);
          this.notice.set(
            view.version > current.version
              ? `Configuração salva na versão ${view.version}.`
              : 'Configuração salva.',
          );
        },
        error: (error: unknown) => {
          this.busy.set(false);
          this.outdated.set(problemCode(error) === 'VERSAO_DESATUALIZADA');
          this.problems.set(fieldProblems(error));
          this.failure.set(failureMessage(error));
        },
      });
  }

  protected fieldError(field: string): string | undefined {
    return this.problems()[field];
  }

  protected label(changed: string[]): string {
    return changedLabel(changed);
  }

  protected zoneLabel(id: string): string {
    return TIME_ZONES.find((z) => z.id === id)?.label ?? id;
  }

  /** Deslocamento do fuso naquele instante, para o DatePipe mostrar a hora local da unidade. */
  protected offset(instant: string, timeZone: string): string {
    const parts = new Intl.DateTimeFormat('en-US', { timeZone, timeZoneName: 'longOffset' })
      .formatToParts(new Date(instant))
      .find((p) => p.type === 'timeZoneName')?.value;
    const match = /GMT([+-]\d{2}):?(\d{2})?/.exec(parts ?? '');
    return match ? `${match[1]}${match[2] ?? '00'}` : 'UTC';
  }

  private show(view: SettingsView): void {
    this.view.set(view);
    this.state.set('ready');
    this.form.setValue({
      serviceEnabled: view.serviceEnabled,
      servicePercent: view.servicePercent.replace('.', ','),
      serviceBase: view.serviceBase,
      serviceIncludesCouvert: view.serviceIncludesCouvert,
      couvertEnabled: view.couvertEnabled,
      couvertMode: view.couvertMode,
      couvertAmount: view.couvertAmount?.replace('.', ',') ?? '',
      discountFixedAllowed: view.discountFixedAllowed,
      discountPercentAllowed: view.discountPercentAllowed,
      attendanceMode: view.attendanceMode,
      exitMode: view.exitMode,
      timeZone: view.timeZone,
      cutoff: view.cutoff,
    });
    if (view.editable) {
      this.form.enable();
    } else {
      this.form.disable();
    }
  }
}

function fieldProblems(error: unknown): Record<string, string> {
  if (!(error instanceof HttpErrorResponse)) {
    return {};
  }
  const problems = (error.error as { problems?: { field: string; message: string }[] }).problems;
  return Object.fromEntries((problems ?? []).map((p) => [p.field, p.message]));
}
