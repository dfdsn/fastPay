import { DatePipe } from '@angular/common';
import { Component, DestroyRef, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { TabHistoryPanel } from '../consumer/tab-history-panel';
import { LiveStatus } from '../live/live-status';
import { LiveUpdatesService } from '../live/live-updates';
import { failureMessage } from '../session/unit-context.service';
import { GuestBillPanel } from './guest-bill';
import { GuestIssuesPanel } from './guest-issues';
import { GuestService, GuestTab } from './guest.service';

/**
 * Comanda do cliente avulso, sem login (H15, FR-09–FR-11). O servidor confere o acesso guardado neste aparelho; os
 * itens e a conta, com os valores calculados no servidor, chegam com os pedidos (H22), e o cliente pode retirar o serviço;
 * o cliente fecha a conta quando tudo foi entregue (H28). O cliente informa e acompanha problemas (H21).
 */
@Component({
  selector: 'fp-guest-tab-page',
  imports: [
    DatePipe,
    MatButtonModule,
    MatCardModule,
    MatProgressSpinnerModule,
    RouterLink,
    LiveStatus,
    GuestBillPanel,
    GuestIssuesPanel,
    TabHistoryPanel,
  ],
  template: `
    <mat-card appearance="outlined">
      <mat-card-header>
        <mat-card-title><h1>Sua comanda</h1></mat-card-title>
      </mat-card-header>
      <mat-card-content>
        <fp-live-status [live]="live" />
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
          <p>{{ t.open ? 'Em atendimento.' : 'Encerrada.' }}</p>
          <button mat-stroked-button type="button" (click)="load()">Atualizar</button>
          <fp-guest-bill [tabId]="t.tabId" [refresh]="refreshes()" />
          <fp-guest-issues [tabId]="t.tabId" [refresh]="refreshes()" />
          <fp-tab-history [tabId]="t.tabId" />
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
  /** Conta as releituras por aviso: a conta (H22) e os problemas (H21) relêem junto. */
  protected readonly refreshes = signal(0);
  /** Avisos só desta comanda (H17); acesso revogado ou vencido encerra a conexão e a tela relê para mostrar isso. */
  protected readonly live = inject(LiveUpdatesService).forGuestTab(this.id, this.destroyRef);

  constructor() {
    this.live.signals.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.refreshes.update((n) => n + 1);
      this.load(true);
    });
    effect(() => {
      if (this.live.status() === 'encerrada') {
        untracked(() => this.load(true));
      }
    });
    this.load();
  }

  /** {@code quiet}: releitura por aviso, sem tirar a comanda da tela enquanto carrega. */
  load(quiet = false): void {
    this.loading.set(!quiet);
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
