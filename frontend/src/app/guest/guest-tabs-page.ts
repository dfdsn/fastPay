import { DatePipe } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { RouterLink } from '@angular/router';

import { GoogleResult } from '../consumer/google-result';
import { GuestService, GuestTab } from './guest.service';

/** Comandas confirmadas neste aparelho que ainda valem (H15, FR-11). Sem login; o histórico com Google é da H27. */
@Component({
  selector: 'fp-guest-tabs-page',
  imports: [DatePipe, MatCardModule, MatProgressSpinnerModule, RouterLink, GoogleResult],
  template: `
    <mat-card appearance="outlined">
      <mat-card-header>
        <mat-card-title><h1>Minhas comandas</h1></mat-card-title>
      </mat-card-header>
      <mat-card-content>
        <fp-google-result />
        <div role="status" aria-live="polite">
          @if (loading()) {
            <mat-progress-spinner mode="indeterminate" diameter="32" aria-hidden="true" />
            <p>Carregando…</p>
          } @else if (failed()) {
            <p>Não foi possível carregar. Verifique sua conexão e tente novamente.</p>
          } @else if (tabs().length === 0) {
            <p>Nenhuma comanda neste aparelho. Peça ao atendente para mostrar o QR da sua comanda.</p>
          }
        </div>
        <ul>
          @for (tab of tabs(); track tab.tabId) {
            <li>
              <a [routerLink]="['/minha-comanda', tab.tabId]">
                {{ tab.establishmentName }} · comanda {{ tab.number }} · {{ tab.name }}
              </a>
              · {{ tab.openedAt | date: 'dd/MM HH:mm' }} · {{ tab.open ? 'aberta' : 'encerrada' }}
            </li>
          }
        </ul>
        <p>
          <a routerLink="/meu-historico">Meu histórico com Google</a> (opcional: comandas que você guardou, de todas as
          lojas)
        </p>
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
  `,
})
export class GuestTabsPage {
  protected readonly tabs = signal<GuestTab[]>([]);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);

  constructor() {
    inject(GuestService)
      .tabs()
      .pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe({
        next: (tabs) => {
          this.tabs.set(tabs);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.failed.set(true);
        },
      });
  }
}
