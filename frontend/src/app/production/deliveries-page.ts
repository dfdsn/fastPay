import { NgTemplateOutlet } from '@angular/common';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { RouterLink } from '@angular/router';
import { interval } from 'rxjs';

import { LiveStatus } from '../live/live-status';
import { LiveUpdatesService } from '../live/live-updates';
import { failureMessage, problemCode } from '../session/unit-context.service';
import {
  ProductionLine,
  ProductionService,
  STATUS_LABEL,
  destinationLabel,
  elapsed,
  isLate,
} from './production.service';

type State = 'loading' | 'ready' | 'forbidden' | 'error';

const STALE = new Set([
  'VERSAO_DESATUALIZADA',
  'ETAPA_INVALIDA',
  'LINHA_NAO_ENCONTRADA',
  'LINHA_IMPOSSIVEL',
]);

/**
 * Entregas (H18, FR-19, FR-21, D-06): o que está pronto, em duas classes (com preparo e sem preparo), e o que a
 * produção sinalizou como impossível de atender. Entregar confirma a linha inteira para todos os destinos mostrados,
 * sem entrega parcial; se os destinos mudaram desde a leitura (transferência, cancelamento), o servidor recusa e a
 * tela mostra os novos para confirmar de novo.
 */
@Component({
  selector: 'fp-deliveries-page',
  imports: [NgTemplateOutlet, RouterLink, MatButtonModule, MatProgressSpinnerModule, LiveStatus],
  template: `
    <h1>Entregas</h1>
    <p><a routerLink="/inicio">Voltar ao início</a></p>
    <fp-live-status [live]="live" />
    @if (state() === 'forbidden') {
      <p role="alert">Seu papel nesta unidade não faz entregas.</p>
    } @else {
      <div role="status" aria-live="polite">
        @if (state() === 'loading') {
          <mat-progress-spinner mode="indeterminate" diameter="32" aria-hidden="true" />
          <p>Carregando…</p>
        } @else if (state() === 'error') {
          <p>Não foi possível carregar. Verifique sua conexão e tente novamente.</p>
        }
        @if (notice(); as message) {
          <p>{{ message }}</p>
        }
      </div>
      <div role="alert">
        @if (failure(); as message) {
          <p class="error">{{ message }}</p>
        }
      </div>
      @if (state() === 'error') {
        <button mat-stroked-button type="button" (click)="load()">Tentar novamente</button>
      }
      @if (state() === 'ready') {
        @if (problems().length > 0) {
          <h2>Não é possível atender ({{ problems().length }})</h2>
          <ul class="lines" aria-label="Itens que não é possível atender">
            @for (line of problems(); track line.id) {
              <li class="blocked">
                <div>
                  <strong>{{ line.quantity }} × {{ line.productName }}</strong> ·
                  {{ statusLabel[line.status] }}
                  @if (line.sectorName) {
                    · {{ line.sectorName }}
                  }
                </div>
                <div>Para: {{ destinations(line) }}</div>
                <div class="problem">Motivo: {{ line.impossible?.reason }}</div>
                <div>
                  Fale com o cliente. O cancelamento do item é feito por quem tem a permissão.
                </div>
              </li>
            }
          </ul>
        }

        <h2>Prontos para entregar ({{ prepared().length }})</h2>
        @if (prepared().length === 0) {
          <p>Nada pronto da produção.</p>
        }
        <ul class="lines" aria-label="Prontos da produção">
          @for (line of prepared(); track line.id) {
            <ng-container *ngTemplateOutlet="item; context: { $implicit: line }" />
          }
        </ul>

        <h2>Sem preparo ({{ unprepared().length }})</h2>
        @if (unprepared().length === 0) {
          <p>Nenhum item sem preparo para entregar.</p>
        }
        <ul class="lines" aria-label="Itens sem preparo">
          @for (line of unprepared(); track line.id) {
            <ng-container *ngTemplateOutlet="item; context: { $implicit: line }" />
          }
        </ul>
        <button mat-button type="button" (click)="load()">Atualizar</button>
      }
    }

    <ng-template #item let-line>
      <li [class.late]="late(line)">
        <div>
          <strong>{{ line.quantity }} × {{ line.productName }}</strong>
          @if (line.sectorName) {
            · {{ line.sectorName }}
          }
        </div>
        @if (line.note) {
          <div class="note">Obs.: {{ line.note }}</div>
        }
        <div>Para: {{ destinations(line) }}</div>
        <div>
          Enviado há {{ since(line.sentAt) }} · pronto há
          {{ since(line.readyAt ?? line.statusSince) }}
          @if (late(line)) {
            · <strong>atrasado</strong>
          }
        </div>
        <div class="row">
          <button mat-flat-button type="button" [disabled]="busy()" (click)="deliver(line)">
            Entregar {{ line.quantity }} × {{ line.productName }}
          </button>
        </div>
      </li>
    </ng-template>
  `,
  styles: `
    :host {
      display: block;
      max-width: 720px;
      margin: 0 auto;
    }
    .row {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin-top: 8px;
    }
    .lines {
      list-style: none;
      padding: 0;
      display: grid;
      gap: 8px;
    }
    .lines li {
      padding: 12px;
      border: 1px solid rgba(0, 0, 0, 0.12);
      border-radius: 4px;
    }
    .lines li.late {
      border: 2px solid #b3261e;
    }
    .lines li.blocked {
      background: #fdecea;
    }
    .note,
    .problem {
      font-weight: 500;
    }
    .error,
    .problem {
      color: #b3261e;
    }
    h2 {
      font: inherit;
      font-weight: 500;
    }
  `,
})
export class DeliveriesPage {
  private readonly service = inject(ProductionService);
  private readonly destroyRef = inject(DestroyRef);
  private offset = 0;
  private readonly now = signal(Date.now());
  /** Avisos da unidade (H17): item pronto, entregue em outro aparelho, impossibilidade sinalizada. */
  protected readonly live = inject(LiveUpdatesService).forUnit(this.destroyRef);

  protected readonly statusLabel = STATUS_LABEL;
  protected readonly state = signal<State>('loading');
  protected readonly lines = signal<ProductionLine[]>([]);
  protected readonly busy = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly failure = signal<string | null>(null);
  protected readonly problems = computed(() => this.lines().filter((l) => l.impossible));
  protected readonly prepared = computed(() =>
    this.lines().filter((l) => !l.impossible && l.status === 'PRONTO' && l.sectorId !== null),
  );
  protected readonly unprepared = computed(() =>
    this.lines().filter((l) => !l.impossible && l.status === 'PRONTO' && l.sectorId === null),
  );

  constructor() {
    interval(1000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.now.set(Date.now() + this.offset));
    this.live.signals.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((signal) => {
      if (signal.kind === 'resync' || signal.topic === 'PRODUCAO') {
        this.refresh();
      }
    });
    this.load();
  }

  load(): void {
    this.state.set('loading');
    this.fetch();
  }

  protected destinations(line: ProductionLine): string {
    const many = line.destinations.length > 1;
    return line.destinations.map((d) => destinationLabel(d, many)).join('; ');
  }

  protected since(instant: string): string {
    return elapsed(instant, this.now());
  }

  protected late(line: ProductionLine): boolean {
    return isLate(line, this.now());
  }

  deliver(line: ProductionLine): void {
    this.busy.set(true);
    this.failure.set(null);
    this.notice.set(null);
    this.service
      .act(line, 'deliver')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.notice.set(
            `${line.quantity} × ${line.productName} entregue para ${this.destinations(line)}.`,
          );
          this.fetch();
        },
        error: (error: unknown) => {
          this.busy.set(false);
          this.failure.set(failureMessage(error));
          if (STALE.has(problemCode(error) ?? '')) {
            this.fetch();
          }
        },
      });
  }

  private refresh(): void {
    if (this.state() === 'ready') {
      this.fetch();
    } else if (this.state() === 'error') {
      this.load();
    }
  }

  private fetch(): void {
    this.service
      .deliveries()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (board) => {
          this.offset = Date.parse(board.now) - Date.now();
          this.now.set(Date.now() + this.offset);
          this.lines.set(board.lines);
          this.state.set('ready');
        },
        error: (error: unknown) =>
          this.state.set(problemCode(error) === 'PERMISSAO_NEGADA' ? 'forbidden' : 'error'),
      });
  }
}
