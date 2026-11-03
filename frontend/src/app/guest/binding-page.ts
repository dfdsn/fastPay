import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { failureMessage, problemCode } from '../session/unit-context.service';
import { BindingPreview, GuestService } from './guest.service';

type State = 'loading' | 'preview' | 'invalid' | 'error';

/**
 * Link do QR mostrado pelo atendente (H15, FR-08). O token vem no fragmento ({@code /c#token}), que não vai ao
 * servidor nem fica no histórico: a tela o tira da barra de endereço e o envia no corpo. Ver a comanda não consome o
 * QR; só "Sou eu" confirma e guarda o acesso neste aparelho.
 */
@Component({
  selector: 'fp-binding-page',
  imports: [MatButtonModule, MatCardModule, MatProgressSpinnerModule, RouterLink],
  template: `
    <mat-card appearance="outlined">
      <mat-card-header>
        <mat-card-title><h1>Sua comanda</h1></mat-card-title>
      </mat-card-header>
      <mat-card-content>
        <div role="status" aria-live="polite">
          @if (state() === 'loading') {
            <mat-progress-spinner mode="indeterminate" diameter="32" aria-hidden="true" />
            <p>Carregando…</p>
          }
        </div>
        <div role="alert">
          @if (failure(); as message) {
            <p class="error">{{ message }}</p>
          }
        </div>
        @if (preview(); as p) {
          @if (state() === 'preview') {
            <p>
              <strong>{{ p.establishmentName }}</strong>
            </p>
            <p>Comanda {{ p.number }} · {{ p.name }}</p>
            <p>Confirme só se esta comanda é sua. Depois, você acompanha a conta por este aparelho.</p>
            <button mat-flat-button type="button" [disabled]="busy()" (click)="confirm()">
              Sou eu, confirmar
            </button>
          }
        }
        @if (state() === 'invalid' || state() === 'error') {
          <p><a routerLink="/minhas-comandas">Ver comandas já confirmadas neste aparelho</a></p>
        }
      </mat-card-content>
    </mat-card>
  `,
  styles: `
    mat-card {
      max-width: 480px;
      margin: 0 auto;
    }
    h1 {
      font: inherit;
      margin: 0;
    }
    .error {
      color: #b3261e;
    }
  `,
})
export class BindingPage {
  private readonly service = inject(GuestService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly token: string;

  protected readonly state = signal<State>('loading');
  protected readonly preview = signal<BindingPreview | null>(null);
  protected readonly failure = signal<string | null>(null);
  protected readonly busy = signal(false);

  constructor() {
    this.token = inject(ActivatedRoute).snapshot.fragment ?? '';
    // O token não fica na barra de endereço nem no histórico do navegador.
    history.replaceState(history.state, '', location.pathname);
    if (!this.token) {
      this.state.set('invalid');
      this.failure.set('Link incompleto. Aponte a câmera para o QR que o atendente mostrar.');
      return;
    }
    this.service
      .preview(this.token)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (preview) => {
          this.preview.set(preview);
          this.state.set('preview');
        },
        error: (error: unknown) => this.fail(error),
      });
  }

  confirm(): void {
    this.busy.set(true);
    this.failure.set(null);
    this.service
      .confirm(this.token)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (tab) => void this.router.navigate(['/minha-comanda', tab.tabId]),
        error: (error: unknown) => {
          this.busy.set(false);
          this.fail(error);
        },
      });
  }

  private fail(error: unknown): void {
    this.state.set(problemCode(error) === 'QR_INVALIDO' ? 'invalid' : 'error');
    this.failure.set(failureMessage(error));
  }
}
