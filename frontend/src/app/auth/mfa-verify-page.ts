import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Router } from '@angular/router';

import { problemCode } from '../session/unit-context.service';
import { AUTH_MESSAGES, AuthService, stageRoute } from './auth.service';

/**
 * Segundo passo do login (H09, FR-05): código de 6 dígitos do aplicativo autenticador ou um código de
 * recuperação. O código de recuperação não entra direto: leva a configurar um autenticador novo.
 */
@Component({
  selector: 'fp-mfa-verify-page',
  imports: [ReactiveFormsModule, MatButtonModule, MatCardModule, MatFormFieldModule, MatInputModule],
  template: `
    <mat-card appearance="outlined">
      <mat-card-header>
        <mat-card-title><h1>Verificação em duas etapas</h1></mat-card-title>
      </mat-card-header>
      <mat-card-content>
        <p>Abra o aplicativo autenticador e digite o código de 6 dígitos do fastPay.</p>
        <form [formGroup]="form" (ngSubmit)="submit()" novalidate>
          <mat-form-field appearance="outline">
            <mat-label>Código</mat-label>
            <input
              matInput
              formControlName="code"
              autocomplete="one-time-code"
              autocapitalize="off"
              spellcheck="false"
            />
            <mat-hint>Sem o celular? Use um dos seus códigos de recuperação.</mat-hint>
            @if (form.controls.code.hasError('required')) {
              <mat-error>Informe o código.</mat-error>
            }
          </mat-form-field>
          <div role="alert">
            @if (error(); as message) {
              <p class="error">{{ message }}</p>
            }
          </div>
          <button mat-flat-button type="submit" [disabled]="submitting()">Confirmar</button>
        </form>
        <p class="links">
          <button mat-button type="button" (click)="cancel()">Entrar com outra conta</button>
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
export class MfaVerifyPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly form = inject(FormBuilder).nonNullable.group({
    code: ['', Validators.required],
  });
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    // Sem a etapa pendente (recarregou, venceu ou já entrou), vai para onde a sessão está.
    this.auth
      .refresh()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (session) => {
          if (session.stage !== 'MFA_PENDENTE') {
            void this.router.navigate([stageRoute(session.stage)]);
          }
        },
        error: () => void this.router.navigate(['/entrar']),
      });
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    this.auth
      .verifyMfa(this.form.getRawValue().code.trim())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (session) => {
          this.submitting.set(false);
          void this.router.navigate([stageRoute(session.stage)]);
        },
        error: (error: unknown) => {
          this.submitting.set(false);
          this.form.controls.code.reset();
          const code = problemCode(error);
          if (code === 'SESSAO_AUSENTE') {
            this.auth.forget();
            void this.router.navigate(['/entrar']);
            return;
          }
          this.error.set(
            (code && AUTH_MESSAGES[code]) ??
              'Não foi possível confirmar o código. Verifique sua conexão e tente novamente.',
          );
        },
      });
  }

  protected cancel(): void {
    this.auth
      .logout()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => void this.router.navigate(['/entrar']));
  }
}
