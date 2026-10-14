import { DatePipe, Location } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { problemCode } from '../session/unit-context.service';
import { INVITATION_MESSAGES, InvitationPreview, InvitationService } from './invitation.service';

type PageState =
  | { kind: 'loading' }
  | { kind: 'missing-link' }
  | { kind: 'failed'; message: string }
  | { kind: 'ready'; preview: InvitationPreview }
  | { kind: 'done'; signedIn: boolean; establishmentName: string };

function sameAsPassword(control: AbstractControl): ValidationErrors | null {
  const password = control.parent?.get('password')?.value as string | undefined;
  return control.value && password !== control.value ? { mismatch: true } : null;
}

/**
 * Aceite de convite (H10, FR-02). O token vem no fragmento do link ({@code #token=}) e sai da barra de endereço
 * assim que é lido. Quem não tem conta cria a sua aqui (nome e senha; a política é conferida no servidor); quem
 * já tem conta com o e-mail do convite entra com ela e aceita.
 */
@Component({
  selector: 'fp-invitation-page',
  imports: [
    DatePipe,
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
  ],
  template: `
    <mat-card appearance="outlined">
      <mat-card-header>
        <mat-card-title><h1>Convite para o fastPay</h1></mat-card-title>
      </mat-card-header>
      <mat-card-content>
        <div role="status" aria-live="polite">
          @switch (state().kind) {
            @case ('loading') {
              <mat-progress-spinner mode="indeterminate" diameter="32" aria-hidden="true" />
              <p>Conferindo o convite…</p>
            }
            @case ('missing-link') {
              <p>O link está incompleto. Abra de novo o link do e-mail.</p>
            }
          }
          @if (failure(); as message) {
            <p>{{ message }}</p>
          }
          @if (done(); as result) {
            <p>Pronto! Agora você participa de {{ result.establishmentName }}.</p>
            @if (!result.signedIn) {
              <p>
                Entre com o seu e-mail e a senha que você acabou de criar. No primeiro acesso, você
                vai configurar a verificação em duas etapas, se o seu papel exigir.
              </p>
            }
          }
        </div>

        @if (preview(); as invite) {
          <p>
            Você recebeu um convite para participar de
            <strong>{{ invite.establishmentName }}</strong> como {{ invite.roleLabel }}.
          </p>
          <p class="hint">
            Convite para {{ invite.email }}, válido até
            {{ invite.expiresAt | date: 'dd/MM/yyyy HH:mm' }}.
          </p>

          @if (invite.signedIn) {
            <button
              mat-flat-button
              type="button"
              [disabled]="submitting()"
              (click)="acceptSignedIn()"
            >
              Aceitar convite
            </button>
          } @else if (invite.accountExists) {
            <p>
              Este e-mail já tem conta no fastPay. Entre com ela e depois abra de novo o link do
              convite para aceitar.
            </p>
          } @else {
            <form [formGroup]="form" (ngSubmit)="createAccount()" novalidate>
              <mat-form-field appearance="outline">
                <mat-label>Seu nome</mat-label>
                <input matInput formControlName="displayName" autocomplete="name" maxlength="120" />
                @if (form.controls.displayName.hasError('required')) {
                  <mat-error>Informe o seu nome.</mat-error>
                }
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Crie uma senha</mat-label>
                <input
                  matInput
                  type="password"
                  formControlName="password"
                  autocomplete="new-password"
                />
                <mat-hint
                  >Pelo menos 12 caracteres. Uma frase fácil de lembrar funciona bem.</mat-hint
                >
                @if (form.controls.password.hasError('required')) {
                  <mat-error>Informe a senha.</mat-error>
                }
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Repita a senha</mat-label>
                <input
                  matInput
                  type="password"
                  formControlName="confirmation"
                  autocomplete="new-password"
                />
                @if (form.controls.confirmation.hasError('required')) {
                  <mat-error>Repita a senha.</mat-error>
                } @else if (form.controls.confirmation.hasError('mismatch')) {
                  <mat-error>As senhas não são iguais.</mat-error>
                }
              </mat-form-field>
              <button mat-flat-button type="submit" [disabled]="submitting()">
                Criar conta e aceitar
              </button>
            </form>
          }
          <div role="alert">
            @if (error(); as message) {
              <p class="error">{{ message }}</p>
            }
          </div>
        }

        <p class="links">
          @if (done(); as result) {
            @if (result.signedIn) {
              <a routerLink="/unidades">Escolher a unidade</a>
            } @else {
              <a routerLink="/entrar">Ir para o login</a>
            }
          } @else if (preview()?.accountExists && !preview()?.signedIn) {
            <a routerLink="/entrar">Entrar com a minha conta</a>
          }
        </p>
      </mat-card-content>
    </mat-card>
  `,
  styles: `
    mat-card {
      max-width: 460px;
      margin: 0 auto;
    }
    h1 {
      font: inherit;
      margin: 0;
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
    .links {
      margin-top: 16px;
    }
  `,
})
export class InvitationPage {
  private readonly invitations = inject(InvitationService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly token: string | null;

  protected readonly form = inject(FormBuilder).nonNullable.group({
    displayName: ['', Validators.required],
    password: ['', Validators.required],
    confirmation: ['', [Validators.required, sameAsPassword]],
  });
  protected readonly state = signal<PageState>({ kind: 'loading' });
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    const fragment = inject(ActivatedRoute).snapshot.fragment ?? '';
    this.token = new URLSearchParams(fragment).get('token');
    inject(Location).replaceState('/convite');
    this.form.controls.password.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.form.controls.confirmation.updateValueAndValidity());
    if (!this.token) {
      this.state.set({ kind: 'missing-link' });
      return;
    }
    this.invitations
      .preview(this.token)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (preview) => this.state.set({ kind: 'ready', preview }),
        error: (error: unknown) => this.state.set({ kind: 'failed', message: messageFor(error) }),
      });
  }

  protected preview(): InvitationPreview | null {
    const current = this.state();
    return current.kind === 'ready' ? current.preview : null;
  }

  protected failure(): string | null {
    const current = this.state();
    return current.kind === 'failed' ? current.message : null;
  }

  protected done(): { signedIn: boolean; establishmentName: string } | null {
    const current = this.state();
    return current.kind === 'done' ? current : null;
  }

  protected createAccount(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { displayName, password } = this.form.getRawValue();
    this.accept({ displayName: displayName.trim(), password });
  }

  protected acceptSignedIn(): void {
    this.accept();
  }

  private accept(account?: { displayName: string; password: string }): void {
    const preview = this.preview();
    if (!this.token || !preview) {
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    this.invitations
      .accept(this.token, account)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (accepted) => {
          this.submitting.set(false);
          this.state.set({
            kind: 'done',
            signedIn: accepted.signedIn,
            establishmentName: preview.establishmentName,
          });
        },
        error: (error: unknown) => {
          this.submitting.set(false);
          const code = problemCode(error);
          if (code === 'CONVITE_INVALIDO') {
            this.state.set({ kind: 'failed', message: messageFor(error) });
          } else if (code === 'DADOS_RECUSADOS' && error instanceof HttpErrorResponse) {
            this.error.set(
              (error.error as { detail?: string }).detail ?? 'Confira o nome e a senha.',
            );
          } else {
            this.error.set(messageFor(error));
          }
        },
      });
  }
}

function messageFor(error: unknown): string {
  const code = problemCode(error);
  return (
    (code && INVITATION_MESSAGES[code]) ??
    'Não foi possível concluir. Verifique sua conexão e tente novamente.'
  );
}
