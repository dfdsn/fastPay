import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { DomSanitizer, SafeUrl } from '@angular/platform-browser';
import { Router } from '@angular/router';

import { problemCode } from '../session/unit-context.service';
import { AUTH_MESSAGES, AuthService, AuthStage, MfaSetup, stageRoute } from './auth.service';

type PageState =
  | { kind: 'loading' }
  | { kind: 'already-active' }
  | { kind: 'error' }
  | { kind: 'scan'; setup: MfaSetup }
  | { kind: 'codes'; codes: string[] };

/**
 * Cadastro do aplicativo autenticador (H09, FR-05). Obrigatório para gerente, admin do estabelecimento
 * e admin fastPay antes de qualquer função; opcional para garçom e produção; também é o recadastro
 * depois de entrar com um código de recuperação. Os dez códigos de recuperação aparecem uma única vez.
 */
@Component({
  selector: 'fp-mfa-setup-page',
  imports: [ReactiveFormsModule, MatButtonModule, MatCardModule, MatFormFieldModule, MatInputModule],
  template: `
    <mat-card appearance="outlined">
      <mat-card-header>
        <mat-card-title><h1>Configurar verificação em duas etapas</h1></mat-card-title>
      </mat-card-header>
      <mat-card-content>
        <div role="status" aria-live="polite">
          @switch (state().kind) {
            @case ('loading') {
              <p>Preparando a configuração…</p>
            }
            @case ('already-active') {
              <p>{{ alreadyActive }}</p>
            }
            @case ('error') {
              <p>
                Não foi possível preparar a configuração. Verifique sua conexão e tente novamente.
              </p>
            }
          }
        </div>
        @if (scan(); as setup) {
          <p class="intro">{{ intro() }}</p>
          <ol>
            <li>
              Instale um aplicativo autenticador no celular (por exemplo, Google Authenticator ou
              Microsoft Authenticator).
            </li>
            <li>
              No celular, <a [href]="otpauthLink()">abra o fastPay no aplicativo</a>. Ou adicione
              uma conta manualmente com esta chave:
              <code class="secret" aria-label="Chave para o aplicativo autenticador">{{
                setup.secret
              }}</code>
            </li>
            <li>Digite abaixo o código de 6 dígitos que o aplicativo mostrar.</li>
          </ol>
          <form [formGroup]="form" (ngSubmit)="confirm()" novalidate>
            <mat-form-field appearance="outline">
              <mat-label>Código do aplicativo</mat-label>
              <input
                matInput
                formControlName="code"
                inputmode="numeric"
                autocomplete="one-time-code"
              />
              @if (form.controls.code.hasError('required')) {
                <mat-error>Informe o código.</mat-error>
              }
            </mat-form-field>
            <div role="alert">
              @if (error(); as message) {
                <p class="error">{{ message }}</p>
              }
            </div>
            <button mat-flat-button type="submit" [disabled]="submitting()">Ativar</button>
          </form>
        }
        @if (codes(); as list) {
          <h2 tabindex="-1" id="codes-title">Guarde seus códigos de recuperação</h2>
          <p>
            Cada código vale uma única vez, se você ficar sem o celular. Eles não serão mostrados de
            novo: anote ou imprima e guarde em lugar seguro.
          </p>
          <ul class="codes" aria-labelledby="codes-title">
            @for (code of list; track code) {
              <li><code>{{ code }}</code></li>
            }
          </ul>
          <button mat-flat-button type="button" (click)="finish()">Já guardei, continuar</button>
        }
        @if (state().kind === 'already-active' || state().kind === 'error') {
          <p class="links">
            <button mat-button type="button" (click)="finish()">Voltar</button>
          </p>
        }
      </mat-card-content>
    </mat-card>
  `,
  styles: `
    mat-card {
      max-width: 520px;
      margin: 0 auto;
    }
    h1 {
      font: inherit;
      margin: 0;
    }
    h2 {
      font: var(--mat-sys-title-medium);
    }
    form {
      display: grid;
      gap: 12px;
    }
    .secret {
      display: block;
      margin-top: 8px;
      font-size: 1.1em;
      letter-spacing: 0.05em;
      word-break: break-all;
    }
    .codes {
      columns: 2;
      padding-left: 20px;
      font-size: 1.05em;
    }
    .error {
      color: var(--mat-sys-error);
    }
    .links {
      margin-top: 16px;
    }
  `,
})
export class MfaSetupPage {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly alreadyActive = AUTH_MESSAGES['MFA_JA_ATIVO'];
  protected readonly form = inject(FormBuilder).nonNullable.group({
    code: ['', Validators.required],
  });
  protected readonly state = signal<PageState>({ kind: 'loading' });
  protected readonly stage = signal<AuthStage>('AUTENTICADO');
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly scan = computed(() => {
    const current = this.state();
    return current.kind === 'scan' ? current.setup : null;
  });
  protected readonly codes = computed(() => {
    const current = this.state();
    return current.kind === 'codes' ? current.codes : null;
  });
  /** {@code otpauth:} não está na lista de esquemas seguros do Angular; o endereço vem do servidor. */
  protected readonly otpauthLink = computed<SafeUrl>(() =>
    this.sanitizer.bypassSecurityTrustUrl(this.scan()?.otpauthUri ?? ''),
  );
  protected readonly intro = computed(() => {
    switch (this.stage()) {
      case 'MFA_CADASTRO_OBRIGATORIO':
        return 'Seu perfil exige a verificação em duas etapas. Configure agora para continuar.';
      case 'MFA_RECADASTRO':
        return 'Você entrou com um código de recuperação. Configure um aplicativo autenticador novo: o anterior e os códigos antigos deixarão de valer e as outras sessões serão encerradas.';
      default:
        return 'Proteja sua conta: além da senha, o fastPay vai pedir um código do seu celular.';
    }
  });

  constructor() {
    this.auth
      .refresh()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (session) => {
          if (session.stage === 'MFA_PENDENTE') {
            void this.router.navigate([stageRoute(session.stage)]);
            return;
          }
          this.stage.set(session.stage);
          this.start();
        },
        error: () => void this.router.navigate(['/entrar']),
      });
  }

  protected confirm(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    this.auth
      .confirmMfaSetup(this.form.getRawValue().code.trim())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (enrolled) => {
          this.submitting.set(false);
          this.state.set({ kind: 'codes', codes: enrolled.recoveryCodes });
          setTimeout(() => document.getElementById('codes-title')?.focus());
        },
        error: (error: unknown) => {
          this.submitting.set(false);
          this.form.controls.code.reset();
          const code = problemCode(error);
          if (code === 'CADASTRO_NAO_INICIADO') {
            this.start();
          }
          this.error.set(
            (code && AUTH_MESSAGES[code]) ??
              'Não foi possível ativar. Verifique sua conexão e tente novamente.',
          );
        },
      });
  }

  protected finish(): void {
    void this.router.navigate(['/unidades']);
  }

  private start(): void {
    this.auth
      .startMfaSetup()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (setup) => this.state.set({ kind: 'scan', setup }),
        error: (error: unknown) =>
          this.state.set({
            kind: problemCode(error) === 'MFA_JA_ATIVO' ? 'already-active' : 'error',
          }),
      });
  }
}
