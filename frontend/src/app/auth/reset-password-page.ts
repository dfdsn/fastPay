import { Location } from '@angular/common';
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
import { ActivatedRoute, RouterLink } from '@angular/router';

import { problemCode } from '../session/unit-context.service';
import { AUTH_MESSAGES, AuthService } from './auth.service';

type PageState = 'form' | 'missing-link' | 'invalid-link' | 'done';

function sameAsPassword(control: AbstractControl): ValidationErrors | null {
  const password = control.parent?.get('password')?.value as string | undefined;
  return control.value && password !== control.value ? { mismatch: true } : null;
}

/**
 * Nova senha pelo link do e-mail (H08). O token vem no fragmento ({@code #token=}), que não vai ao
 * servidor nem fica nos logs, e sai da barra de endereço assim que é lido. A política de senha é
 * conferida no servidor; a tela mostra o motivo da recusa.
 */
@Component({
  selector: 'fp-reset-password-page',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
  ],
  template: `
    <mat-card appearance="outlined">
      <mat-card-header>
        <mat-card-title><h1>Criar nova senha</h1></mat-card-title>
      </mat-card-header>
      <mat-card-content>
        <div role="status">
          @switch (state()) {
            @case ('done') {
              <p>Senha alterada. Entre com a nova senha.</p>
            }
            @case ('missing-link') {
              <p>O link está incompleto. Abra de novo o link do e-mail ou peça um novo.</p>
            }
            @case ('invalid-link') {
              <p>{{ invalidLink }}</p>
            }
          }
        </div>
        @if (state() === 'form') {
          <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
            <mat-form-field appearance="outline">
              <mat-label>Nova senha</mat-label>
              <input
                matInput
                type="password"
                formControlName="password"
                autocomplete="new-password"
              />
              <mat-hint>Pelo menos 12 caracteres. Uma frase fácil de lembrar funciona bem.</mat-hint>
              @if (form.controls.password.hasError('required')) {
                <mat-error>Informe a nova senha.</mat-error>
              }
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Repita a nova senha</mat-label>
              <input
                matInput
                type="password"
                formControlName="confirmation"
                autocomplete="new-password"
              />
              @if (form.controls.confirmation.hasError('required')) {
                <mat-error>Repita a nova senha.</mat-error>
              } @else if (form.controls.confirmation.hasError('mismatch')) {
                <mat-error>As senhas não são iguais.</mat-error>
              }
            </mat-form-field>
            <div role="alert">
              @if (error(); as message) {
                <p class="error">{{ message }}</p>
              }
            </div>
            <button mat-flat-button type="submit" [disabled]="submitting()">Salvar senha</button>
          </form>
        }
        <p class="links">
          @if (state() === 'done') {
            <a routerLink="/entrar">Ir para o login</a>
          } @else {
            <a routerLink="/esqueci-a-senha">Pedir um novo link</a>
          }
        </p>
      </mat-card-content>
    </mat-card>
  `,
  styles: `
    mat-card {
      max-width: 420px;
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
    .error {
      color: var(--mat-sys-error);
    }
    .links {
      margin-top: 16px;
    }
  `,
})
export class ResetPasswordPage {
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly token: string | null;

  protected readonly invalidLink = AUTH_MESSAGES['LINK_INVALIDO'];
  protected readonly form = inject(FormBuilder).nonNullable.group({
    password: ['', Validators.required],
    confirmation: ['', [Validators.required, sameAsPassword]],
  });
  protected readonly state = signal<PageState>('form');
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    const fragment = inject(ActivatedRoute).snapshot.fragment ?? '';
    this.token = new URLSearchParams(fragment).get('token');
    inject(Location).replaceState('/redefinir-senha');
    if (!this.token) {
      this.state.set('missing-link');
    }
    this.form.controls.password.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.form.controls.confirmation.updateValueAndValidity());
  }

  protected submit(): void {
    if (this.form.invalid || !this.token) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    this.auth
      .confirmPasswordReset(this.token, this.form.getRawValue().password)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.submitting.set(false);
          this.state.set('done');
        },
        error: (error: unknown) => {
          this.submitting.set(false);
          const code = problemCode(error);
          if (code === 'LINK_INVALIDO') {
            this.state.set('invalid-link');
          } else if (code === 'SENHA_RECUSADA' && error instanceof HttpErrorResponse) {
            this.error.set((error.error as { detail?: string }).detail ?? 'Escolha outra senha.');
          } else {
            this.error.set(
              (code && AUTH_MESSAGES[code]) ??
                'Não foi possível salvar a senha. Verifique sua conexão e tente novamente.',
            );
          }
        },
      });
  }
}
