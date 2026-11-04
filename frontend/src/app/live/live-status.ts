import { DatePipe } from '@angular/common';
import { Component, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';

import { LiveUpdates } from './live-updates';

/**
 * Aviso de dados possivelmente desatualizados (H17, especificação §11): aparece enquanto a conexão em tempo real está
 * fora, com a hora do último contato com o servidor e um botão para reler agora. Ao vivo, não mostra nada.
 */
@Component({
  selector: 'fp-live-status',
  imports: [DatePipe, MatButtonModule],
  template: `
    <div role="status" aria-live="polite">
      @if (live().status() === 'reconectando') {
        <div class="stale">
          <p>
            Sem conexão em tempo real: os dados desta tela podem estar desatualizados.
            @if (live().lastSyncAt(); as at) {
              Última atualização às {{ at | date: 'HH:mm:ss' }}.
            }
            Tentando reconectar…
          </p>
          <button mat-stroked-button type="button" (click)="live().refreshNow()">Atualizar agora</button>
        </div>
      }
    </div>
  `,
  styles: `
    .stale {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      align-items: center;
      padding: 8px 12px;
      margin-bottom: 12px;
      border-radius: 4px;
      background: #fff4e5;
      color: #5f370e;
    }
    .stale p {
      margin: 0;
      flex: 1 1 240px;
    }
  `,
})
export class LiveStatus {
  readonly live = input.required<LiveUpdates>();
}
