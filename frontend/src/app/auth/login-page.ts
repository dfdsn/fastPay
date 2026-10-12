import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Router, RouterLink } from '@angular/router';

import { problemCode } from '../session/unit-context.service';
import { AUTH_MESSAGES, AuthService } from './auth.service';

/** Login da equipe por e-mail e senha (H08, FR-04). */
@Component({
  selector: 'fp-login-page',
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
        <mat-card-title><h1>Entrar no fastPay</h1></mat-card-title>
      </mat-card-header>
      <mat-card-content>
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
          <mat-form-field appearance="outline">
            <mat-label>Senha</mat-label>
            <input
              matInput
              type="password"
              formControlName="password"
              autocomplete="current-password"
            />
            @if (form.controls.password.hasError('required')) {
              <mat-error>Informe sua senha.</mat-error>
            }
          </mat-form-field>
          <div role="alert">
            @if (error(); as message) {
              <p class="error">{{ message }}</p>
            }
          </div>
          <button mat-flat-button type="submit" [disabled]="submitting()">Entrar</button>
        </form>
        <p class="links"><a routerLink="/esqueci-a-senha">Esqueci minha senha</a></p>
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
export class LoginPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    // Quem já está com sessão vai direto para as unidades.
    this.auth
      .refresh()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: () => void this.router.navigate(['/unidades']), error: () => undefined });
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { email, password } = this.form.getRawValue();
    this.submitting.set(true);
    this.error.set(null);
    this.auth
      .login(email, password)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.submitting.set(false);
          void this.router.navigate(['/unidades']);
        },
        error: (error: unknown) => {
          this.submitting.set(false);
          this.form.controls.password.reset();
          const code = problemCode(error);
          this.error.set(
            (code && AUTH_MESSAGES[code]) ??
              'Não foi possível entrar. Verifique sua conexão e tente novamente.',
          );
        },
      });
  }
}
