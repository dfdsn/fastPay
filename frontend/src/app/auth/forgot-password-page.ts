import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { RouterLink } from '@angular/router';

import { problemCode } from '../session/unit-context.service';
import { AUTH_MESSAGES, AuthService } from './auth.service';

/** Pedido de link para criar nova senha (H08). A resposta é a mesma exista o e-mail ou não. */
@Component({
  selector: 'fp-forgot-password-page',
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
        <mat-card-title><h1>Esqueci minha senha</h1></mat-card-title>
      </mat-card-header>
      <mat-card-content>
        <div role="status">
          @if (sent(); as message) {
            <p>{{ message }}</p>
          }
        </div>
        @if (!sent()) {
          <p>Informe o e-mail da sua conta. Enviaremos um link para criar uma nova senha.</p>
          <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
            <mat-form-field appearance="outline">
              <mat-label>E-mail</mat-label>
              <input matInput type="email" formControlName="email" autocomplete="username" />
              @if (form.controls.email.hasError('required')) {
                <mat-error>Informe seu e-mail.</mat-error>
              } @else if (form.controls.email.hasError('email')) {
                <mat-error>Informe um e-mail válido.</mat-error>
              }
            </mat-form-field>
            <div role="alert">
              @if (error(); as message) {
                <p class="error">{{ message }}</p>
              }
            </div>
            <button mat-flat-button type="submit" [disabled]="submitting()">Enviar link</button>
          </form>
        }
        <p class="links"><a routerLink="/entrar">Voltar para o login</a></p>
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
      gap: 4px;
    }
    .error {
      color: var(--mat-sys-error);
    }
    .links {
      margin-top: 16px;
    }
  `,
})
export class ForgotPasswordPage {
  private readonly auth = inject(AuthService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
  });
  protected readonly submitting = signal(false);
  protected readonly sent = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    this.auth
      .requestPasswordReset(this.form.getRawValue().email)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (message) => {
          this.submitting.set(false);
          this.sent.set(message);
        },
        error: (error: unknown) => {
          this.submitting.set(false);
          const code = problemCode(error);
          this.error.set(
            (code && AUTH_MESSAGES[code]) ??
              'Não foi possível enviar o pedido. Verifique sua conexão e tente novamente.',
          );
        },
      });
  }
}
