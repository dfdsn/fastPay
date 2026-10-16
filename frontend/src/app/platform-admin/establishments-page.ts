import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { problemCode } from '../session/unit-context.service';
import { Establishment, EstablishmentsService, invitationLabel } from './establishments.service';

type ListState = 'loading' | 'ready' | 'forbidden' | 'signed-out' | 'error';

/**
 * Área do admin fastPay (H10, FR-01, FR-02): cadastrar estabelecimento (nome, e-mail do dono e CNPJ opcional)
 * em configuração, acompanhar o convite do dono e reenviá-lo. Cadastrar não ativa a unidade nem cobra
 * mensalidade.
 */
@Component({
  selector: 'fp-establishments-page',
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
    <h1>Estabelecimentos</h1>
    @if (listState() === 'forbidden') {
      <p role="alert">Esta área é exclusiva da equipe fastPay.</p>
    } @else if (listState() === 'signed-out') {
      <p role="alert">Sua sessão não está ativa. Entre novamente.</p>
    } @else {
      <mat-card appearance="outlined">
        <mat-card-header>
          <mat-card-title><h2>Novo estabelecimento</h2></mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <p class="hint">
            O estabelecimento começa em configuração: não opera com dinheiro real nem gera
            mensalidade até ser ativado. O dono recebe um convite por e-mail, válido por 48 horas.
          </p>
          <form [formGroup]="form" (ngSubmit)="create()" novalidate>
            <mat-form-field appearance="outline">
              <mat-label>Nome do estabelecimento</mat-label>
              <input matInput formControlName="name" maxlength="120" />
              @if (form.controls.name.hasError('required')) {
                <mat-error>Informe o nome.</mat-error>
              }
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>E-mail do dono</mat-label>
              <input matInput type="email" formControlName="ownerEmail" autocomplete="off" />
              @if (form.controls.ownerEmail.hasError('required')) {
                <mat-error>Informe o e-mail do dono.</mat-error>
              } @else if (form.controls.ownerEmail.hasError('email')) {
                <mat-error>Confira o e-mail.</mat-error>
              }
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>CNPJ (opcional)</mat-label>
              <input matInput formControlName="document" autocomplete="off" />
              <mat-hint>Pode ficar em branco durante a configuração.</mat-hint>
            </mat-form-field>
            <div role="alert">
              @if (formError(); as message) {
                <p class="error">{{ message }}</p>
              }
            </div>
            <button mat-flat-button type="submit" [disabled]="submitting()">
              Cadastrar e convidar o dono
            </button>
          </form>
        </mat-card-content>
      </mat-card>

      <h2>Cadastrados</h2>
      <div role="status" aria-live="polite">
        @if (listState() === 'loading') {
          <mat-progress-spinner mode="indeterminate" diameter="32" aria-hidden="true" />
          <p>Carregando…</p>
        } @else if (listState() === 'error') {
          <p>Não foi possível carregar a lista. Verifique sua conexão e tente novamente.</p>
        }
        @if (notice(); as message) {
          <p>{{ message }}</p>
        }
      </div>
      @if (listState() === 'error') {
        <button mat-stroked-button type="button" (click)="load()">Tentar novamente</button>
      }
      @if (listState() === 'ready') {
        @if (establishments().length === 0) {
          <p>Nenhum estabelecimento cadastrado ainda.</p>
        } @else {
          <ul class="establishments" aria-label="Estabelecimentos cadastrados">
            @for (item of establishments(); track item.id) {
              <li>
                <mat-card appearance="outlined">
                  <mat-card-content>
                    <p class="name">{{ item.name }}</p>
                    <p>Dono: {{ item.ownerEmail }}</p>
                    @if (item.document) {
                      <p>CNPJ: {{ item.document }}</p>
                    }
                    <p>Situação: em configuração</p>
                    <p>
                      {{ label(item) }}
                      @if (item.ownerInvitation; as invite) {
                        @if (invite.status === 'ENVIADO') {
                          (vale até {{ invite.expiresAt | date: 'dd/MM/yyyy HH:mm' }})
                        }
                      }
                    </p>
                  </mat-card-content>
                  @if (item.ownerInvitation?.status !== 'ACEITO') {
                    <mat-card-actions>
                      <button
                        mat-stroked-button
                        type="button"
                        [disabled]="resending() === item.id"
                        (click)="resend(item)"
                      >
                        Reenviar convite para {{ item.name }}
                      </button>
                    </mat-card-actions>
                  }
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
    .hint {
      color: var(--mat-sys-on-surface-variant);
    }
    .error {
      color: var(--mat-sys-error);
    }
    .establishments {
      list-style: none;
      padding: 0;
      display: grid;
      gap: 8px;
    }
    .name {
      font-weight: 500;
    }
  `,
})
export class EstablishmentsPage {
  private readonly service = inject(EstablishmentsService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    name: ['', Validators.required],
    ownerEmail: ['', [Validators.required, Validators.email]],
    document: [''],
  });
  protected readonly listState = signal<ListState>('loading');
  protected readonly establishments = signal<Establishment[]>([]);
  protected readonly submitting = signal(false);
  protected readonly resending = signal<string | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  constructor() {
    this.load();
  }

  protected label(item: Establishment): string {
    return invitationLabel(item.ownerInvitation);
  }

  protected load(): void {
    this.listState.set('loading');
    this.service
      .list()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (items) => {
          this.establishments.set(items);
          this.listState.set('ready');
        },
        error: (error: unknown) => this.listState.set(failureState(error)),
      });
  }

  protected create(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { name, ownerEmail, document } = this.form.getRawValue();
    this.submitting.set(true);
    this.formError.set(null);
    this.notice.set(null);
    this.service
      .create({
        name: name.trim(),
        ownerEmail: ownerEmail.trim(),
        document: document.trim() || null,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (created) => {
          this.submitting.set(false);
          this.form.reset();
          this.establishments.update((items) => [created, ...items]);
          this.listState.set('ready');
          this.notice.set(
            `${created.name} cadastrado. O convite para ${created.ownerEmail} está a caminho.`,
          );
        },
        error: (error: unknown) => {
          this.submitting.set(false);
          const state = failureState(error);
          if (state === 'forbidden' || state === 'signed-out') {
            this.listState.set(state);
          } else if (
            problemCode(error) === 'DADOS_RECUSADOS' &&
            error instanceof HttpErrorResponse
          ) {
            this.formError.set((error.error as { detail?: string }).detail ?? 'Confira os dados.');
          } else {
            this.formError.set(
              'Não foi possível cadastrar. Verifique sua conexão e tente novamente.',
            );
          }
        },
      });
  }

  protected resend(item: Establishment): void {
    this.resending.set(item.id);
    this.notice.set(null);
    this.service
      .resendOwnerInvitation(item.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (updated) => {
          this.resending.set(null);
          this.establishments.update((items) =>
            items.map((i) => (i.id === updated.id ? updated : i)),
          );
          this.notice.set(
            `Novo convite a caminho de ${updated.ownerEmail}. O anterior deixou de valer.`,
          );
        },
        error: (error: unknown) => {
          this.resending.set(null);
          if (problemCode(error) === 'DONO_JA_ACEITOU') {
            this.notice.set(`O dono de ${item.name} já aceitou o convite.`);
            this.load();
          } else {
            this.notice.set('Não foi possível reenviar o convite. Tente novamente.');
          }
        },
      });
  }
}

function failureState(error: unknown): ListState {
  if (error instanceof HttpErrorResponse && error.status === 401) {
    return 'signed-out';
  }
  return problemCode(error) === 'ACESSO_RESTRITO_PLATAFORMA' ? 'forbidden' : 'error';
}
