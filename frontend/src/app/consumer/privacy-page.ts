import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { failureMessage } from '../session/unit-context.service';
import { ConsumerService, ConsumerSession, safeReturn } from './consumer.service';

/**
 * Aviso de privacidade antes de criar a conta do consumidor com Google (H27, P-13 aprovada em 07/11/2026). A conta só
 * existe depois do aceite; recusar não guarda nada e a pessoa segue como avulsa. Texto provisório até a validação
 * profissional (P-03/P-08).
 */
@Component({
  selector: 'fp-privacy-page',
  imports: [MatButtonModule, MatCardModule, MatProgressSpinnerModule, RouterLink],
  template: `
    <mat-card appearance="outlined">
      <mat-card-header>
        <mat-card-title><h1>Antes de criar sua conta</h1></mat-card-title>
      </mat-card-header>
      <mat-card-content>
        <div role="status" aria-live="polite">
          @if (loading()) {
            <mat-progress-spinner mode="indeterminate" diameter="32" aria-hidden="true" />
            <p>Carregando…</p>
          }
        </div>
        <div role="alert">
          @if (failure(); as message) {
            <p class="error">{{ message }}</p>
          }
        </div>
        @if (session(); as s) {
          @if (s.pendingEmail) {
            <p>Você entrou com a conta Google <strong>{{ s.pendingEmail }}</strong>.</p>
            <h2>O que o fastPay guarda</h2>
            <ul>
              <li>Um identificador da sua conta Google e o seu e-mail. Não guardamos nome, foto nem telefone.</li>
              <li>Só as comandas que você mesmo escolher guardar no histórico, com o nome da loja, o número, o apelido e as datas.</li>
              <li>Quando você aceitou este aviso (versão {{ s.noticeVersion }}).</li>
            </ul>
            <h2>Quem vê</h2>
            <ul>
              <li>Você vê o seu histórico em todas as lojas que usam o fastPay.</li>
              <li>Cada loja vê só as comandas dela e apenas que o cliente é cadastrado, sem o seu e-mail nem as outras lojas.</li>
              <li>A conta do cliente é separada de qualquer acesso de funcionário, mesmo com o mesmo e-mail.</li>
            </ul>
            <h2>Seus direitos</h2>
            <p>
              Você pode sair da conta a qualquer momento. Para pedir a exclusão dos seus dados, fale com o suporte do
              fastPay: o pedido é registrado mesmo com pendências, e só fica o que for necessário para resolvê-las.
            </p>
            <p class="hint">Sem conta, sua comanda continua funcionando normalmente neste aparelho.</p>
            <div class="actions">
              <button mat-flat-button type="button" [disabled]="busy()" (click)="accept(s.noticeVersion)">
                Aceitar e criar conta
              </button>
              <button mat-stroked-button type="button" [disabled]="busy()" (click)="decline()">
                Não, continuar sem conta
              </button>
            </div>
          } @else {
            <p>O login com Google expirou ou já foi concluído.</p>
            <p><a [routerLink]="returnTo">Voltar</a></p>
          }
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
      font-size: 1rem;
      margin: 16px 0 4px;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .hint {
      color: #49454f;
    }
    .error {
      color: #b3261e;
    }
  `,
})
export class PrivacyPage {
  private readonly service = inject(ConsumerService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly returnTo = safeReturn(inject(ActivatedRoute).snapshot.queryParamMap.get('returnTo'));
  protected readonly session = signal<ConsumerSession | null>(null);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly failure = signal<string | null>(null);

  constructor() {
    this.service
      .session()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (session) => {
          this.session.set(session);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.loading.set(false);
          this.failure.set(failureMessage(error));
        },
      });
  }

  accept(noticeVersion: string): void {
    this.busy.set(true);
    this.failure.set(null);
    this.service
      .acceptPrivacy(noticeVersion)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => void this.router.navigateByUrl(`${safeReturn(result.returnTo)}?google=conectado`),
        error: (error: unknown) => {
          this.busy.set(false);
          this.failure.set(failureMessage(error));
        },
      });
  }

  decline(): void {
    this.busy.set(true);
    this.service
      .declinePrivacy()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => void this.router.navigateByUrl(this.returnTo),
        error: () => void this.router.navigateByUrl(this.returnTo),
      });
  }
}
