import { DatePipe } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { failureMessage } from '../session/unit-context.service';
import { GuestService, GuestTab } from './guest.service';

/**
 * Comanda do cliente avulso, sem login (H15, FR-09–FR-11). O servidor confere o acesso guardado neste aparelho; os
 * itens e a conta chegam com os pedidos (H16) e o fechamento (H28).
 */
@Component({
  selector: 'fp-guest-tab-page',
  imports: [DatePipe, MatButtonModule, MatCardModule, MatProgressSpinnerModule, RouterLink],
  template: `
    <mat-card appearance="outlined">
      <mat-card-header>
        <mat-card-title><h1>Sua comanda</h1></mat-card-title>
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
        @if (tab(); as t) {
          <p><strong>{{ t.establishmentName }}</strong></p>
          <p>Comanda {{ t.number }} · {{ t.name }}</p>
          <p>{{ t.table ? 'Mesa ' + t.table : 'Sem mesa' }} · aberta às {{ t.openedAt | date: 'HH:mm' }}</p>
          <p>{{ t.open ? 'Em atendimento.' : 'Encerrada.' }} Os pedidos aparecerão aqui.</p>
          <button mat-stroked-button type="button" (click)="load()">Atualizar</button>
        }
        <p><a routerLink="/minhas-comandas">Minhas comandas</a></p>
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
export class GuestTabPage {
  private readonly service = inject(GuestService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly id = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';

  protected readonly tab = signal<GuestTab | null>(null);
  protected readonly loading = signal(true);
  protected readonly failure = signal<string | null>(null);

  constructor() {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.service
      .tab(this.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (tab) => {
          this.tab.set(tab);
          this.failure.set(null);
          this.loading.set(false);
        },
        error: (error: unknown) => {
          this.tab.set(null);
          this.loading.set(false);
          this.failure.set(failureMessage(error));
        },
      });
  }
}
