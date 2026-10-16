import { DatePipe } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Observable } from 'rxjs';

import { failureMessage, problemCode } from '../session/unit-context.service';
import { GrantStatus, SupportGrant, SupportGrants, SupportService } from './support.service';

type State = 'loading' | 'ready' | 'forbidden' | 'error';

const STATUS_LABELS: Record<GrantStatus, string> = {
  ATIVA: 'vigente',
  VENCIDA: 'vencido',
  ENCERRADA: 'encerrado',
};

/**
 * Acesso de suporte à unidade (H11, FR-66, CA-28), só para o dono: concede por prazo e com motivo, acompanha as
 * consultas feitas e encerra quando quiser. O suporte só lê; não entra como ninguém da equipe.
 */
@Component({
  selector: 'fp-support-grants-page',
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
    <h1>Acesso de suporte</h1>
    @if (state() === 'forbidden') {
      <p role="alert">Só o administrador do estabelecimento autoriza o suporte.</p>
    } @else {
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
      @if (state() === 'error') {
        <button mat-stroked-button type="button" (click)="load()">Tentar novamente</button>
      }

      @if (data(); as current) {
        <mat-card appearance="outlined">
          <mat-card-header>
            <mat-card-title><h2>Autorizar suporte</h2></mat-card-title>
          </mat-card-header>
          <mat-card-content>
            <p class="hint">
              A pessoa da equipe fastPay poderá consultar esta unidade, sem alterar nada, até o fim
              do prazo ou até você encerrar. Cada consulta fica registrada.
            </p>
            <form [formGroup]="form" (ngSubmit)="grant()" novalidate>
              <mat-form-field appearance="outline">
                <mat-label>E-mail da pessoa de suporte</mat-label>
                <input matInput type="email" formControlName="supportEmail" autocomplete="off" />
                @if (form.controls.supportEmail.hasError('required')) {
                  <mat-error>Informe o e-mail.</mat-error>
                } @else if (form.controls.supportEmail.hasError('email')) {
                  <mat-error>Confira o e-mail.</mat-error>
                }
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Motivo</mat-label>
                <textarea matInput formControlName="reason" rows="3" maxlength="500"></textarea>
                @if (form.controls.reason.invalid) {
                  <mat-error>Explique o motivo, com 5 a 500 caracteres.</mat-error>
                }
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Prazo em horas</mat-label>
                <input
                  matInput
                  type="number"
                  formControlName="hours"
                  min="1"
                  [max]="current.maxHours"
                />
                @if (form.controls.hours.invalid) {
                  <mat-error>Escolha entre 1 e {{ current.maxHours }} horas.</mat-error>
                }
              </mat-form-field>
              <button mat-flat-button type="submit" [disabled]="busy()">Autorizar acesso</button>
            </form>
          </mat-card-content>
        </mat-card>

        <h2>Acessos concedidos</h2>
        @if (current.grants.length === 0) {
          <p>Nenhum acesso de suporte concedido.</p>
        } @else {
          <ul class="list" aria-label="Acessos concedidos">
            @for (item of current.grants; track item.id) {
              <li>
                <mat-card appearance="outlined">
                  <mat-card-content>
                    <p class="name">{{ item.supportEmail }} · {{ statusLabel(item) }}</p>
                    <p>Motivo: {{ item.reason }}</p>
                    <p>
                      Concedido em {{ item.grantedAt | date: 'dd/MM/yyyy HH:mm' }}, válido até
                      {{ item.expiresAt | date: 'dd/MM/yyyy HH:mm' }}
                      @if (item.revokedAt) {
                        · encerrado em {{ item.revokedAt | date: 'dd/MM/yyyy HH:mm' }}
                      }
                    </p>
                    <p>
                      Consultas: {{ item.consultations }}
                      @if (item.lastConsultationAt) {
                        · última em {{ item.lastConsultationAt | date: 'dd/MM/yyyy HH:mm' }}
                      }
                    </p>
                    @if (item.status === 'ATIVA') {
                      <button
                        mat-stroked-button
                        type="button"
                        [disabled]="busy()"
                        (click)="revoke(item)"
                      >
                        Encerrar acesso de {{ item.supportEmail }}
                      </button>
                    }
                  </mat-card-content>
                </mat-card>
              </li>
            }
          </ul>
        }
      }
    }
  `,
  styles: `
    :host {
      display: block;
      max-width: 720px;
      margin: 0 auto;
    }
    h1,
    h2 {
      font: inherit;
      font-weight: 500;
    }
    form {
      display: grid;
      gap: 12px;
    }
    .list {
      list-style: none;
      padding: 0;
      display: grid;
      gap: 8px;
    }
    .name {
      font-weight: 500;
    }
    .hint {
      color: var(--mat-sys-on-surface-variant);
    }
    .error {
      color: var(--mat-sys-error);
    }
  `,
})
export class SupportGrantsPage {
  private readonly service = inject(SupportService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    supportEmail: ['', [Validators.required, Validators.email]],
    reason: ['', [Validators.required, Validators.minLength(5), Validators.maxLength(500)]],
    hours: [24, [Validators.required, Validators.min(1)]],
  });
  protected readonly state = signal<State>('loading');
  protected readonly data = signal<SupportGrants | null>(null);
  protected readonly busy = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly failure = signal<string | null>(null);

  constructor() {
    this.load();
  }

  protected statusLabel(grant: SupportGrant): string {
    return STATUS_LABELS[grant.status];
  }

  protected load(): void {
    this.state.set('loading');
    this.service
      .grants()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => this.adopt(data),
        error: (error: unknown) =>
          this.state.set(problemCode(error) === 'PERMISSAO_NEGADA' ? 'forbidden' : 'error'),
      });
  }

  protected grant(): void {
    const { supportEmail, reason, hours } = this.form.getRawValue();
    if (hours > (this.data()?.maxHours ?? 0)) {
      this.form.controls.hours.setErrors({ max: true });
    }
    if (reason.trim().length < 5) {
      this.form.controls.reason.setErrors({ minlength: true });
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const email = supportEmail.trim();
    this.run(
      this.service.grant(email, reason.trim(), hours),
      `${email} pode consultar esta unidade por ${hours} h.`,
      () => this.form.reset({ supportEmail: '', reason: '', hours: 24 }),
    );
  }

  protected revoke(grant: SupportGrant): void {
    this.run(
      this.service.revoke(grant.id),
      `Acesso de ${grant.supportEmail} encerrado. A consulta à unidade foi bloqueada.`,
    );
  }

  private run(action: Observable<SupportGrants>, success: string, after?: () => void): void {
    this.busy.set(true);
    this.notice.set(null);
    this.failure.set(null);
    action.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (data) => {
        this.busy.set(false);
        this.adopt(data);
        this.notice.set(success);
        after?.();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.failure.set(failureMessage(error));
      },
    });
  }

  private adopt(data: SupportGrants): void {
    this.data.set(data);
    this.state.set('ready');
  }
}
