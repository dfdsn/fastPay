import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';
import { Observable, interval } from 'rxjs';

import { CatalogService, Sector } from '../catalog/catalog.service';
import { ChimeService, SoundToggle } from '../live/chime';
import { LiveStatus } from '../live/live-status';
import { LiveUpdatesService } from '../live/live-updates';
import { failureMessage, problemCode } from '../session/unit-context.service';
import {
  BATCH_MAX,
  BatchAction,
  BatchResult,
  ProductionLine,
  ProductionService,
  STATUS_LABEL,
  destinationLabel,
  elapsed,
  isLate,
} from './production.service';

type State = 'loading' | 'ready' | 'forbidden' | 'error';

/** Recusas que pedem reler a fila: outra pessoa mexeu na linha. */
const STALE = new Set([
  'VERSAO_DESATUALIZADA',
  'ETAPA_INVALIDA',
  'LINHA_NAO_ENCONTRADA',
  'LINHA_IMPOSSIVEL',
]);

/**
 * Fila de produção (H18, FR-18–FR-22): o que aguarda e está em preparo, de um setor ou de todos, das mais antigas às
 * mais novas. Cada linha é uma sequência só para toda a quantidade, com destinos, responsável, observação e os
 * tempos desde o envio e na etapa; passou do limite do setor, fica destacada (nada muda sozinho). Sem valores.
 * "Não é possível atender" pede motivo e trava a linha até retirar a sinalização ou o item ser cancelado.
 * Em lote (H19, FR-69): marcar itens e iniciar ou marcar pronto até 50 de uma vez; cada item tem o próprio resultado,
 * e os que não avançaram aparecem com o motivo, sem desfazer os que passaram.
 */
@Component({
  selector: 'fp-production-page',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    LiveStatus,
    SoundToggle,
  ],
  template: `
    <h1>Produção</h1>
    <p><a routerLink="/inicio">Voltar ao início</a></p>
    <fp-live-status [live]="live" />
    @if (state() === 'forbidden') {
      <p role="alert">Seu papel nesta unidade não opera a produção.</p>
    } @else {
      <fp-sound-toggle />
      <mat-form-field appearance="outline">
        <mat-label>Setor</mat-label>
        <mat-select [value]="sectorId()" (valueChange)="chooseSector($event)">
          <mat-option [value]="null">Todos os setores</mat-option>
          @for (sector of sectors(); track sector.id) {
            <mat-option [value]="sector.id">{{ sector.name }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
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
        <h2>Na fila ({{ lines().length }})</h2>
        @if (lines().length === 0) {
          <p>Nada aguardando preparo.</p>
        } @else {
          <div class="row batch" role="group" aria-label="Ações em lote">
            <mat-checkbox
              [checked]="allSelected()"
              [indeterminate]="selected().size > 0 && !allSelected()"
              (change)="selectAll($event.checked)"
              aria-label="Marcar todos"
            >
              Marcar todos
            </mat-checkbox>
            <button
              mat-flat-button
              type="button"
              [disabled]="busy() || toStart().length === 0"
              (click)="runBatch('INICIAR')"
            >
              Iniciar marcados ({{ toStart().length }})
            </button>
            <button
              mat-flat-button
              type="button"
              [disabled]="busy() || toFinish().length === 0"
              (click)="runBatch('PRONTO')"
            >
              Marcar prontos os marcados ({{ toFinish().length }})
            </button>
          </div>
        }
        @if (batchFailures().length > 0) {
          <ul class="failures" aria-label="Itens do lote que não avançaram">
            @for (item of batchFailures(); track item.id) {
              <li>{{ item.name }}: {{ item.message }}</li>
            }
          </ul>
        }
        <ul class="lines" aria-label="Fila de produção">
          @for (line of lines(); track line.id) {
            <li
              [class.late]="late(line)"
              [class.blocked]="line.impossible"
              [class.fresh]="fresh().has(line.id)"
            >
              <div>
                <mat-checkbox
                  [checked]="selected().has(line.id)"
                  (change)="toggle(line.id, $event.checked)"
                  [aria-label]="'Marcar ' + line.quantity + ' × ' + line.productName"
                />
                @if (fresh().has(line.id)) {
                  <span class="badge">Novo</span>
                }
                <strong>{{ line.quantity }} × {{ line.productName }}</strong>
                · {{ statusLabel[line.status] }}
                @if (!sectorId() && line.sectorName) {
                  · {{ line.sectorName }}
                }
              </div>
              @if (line.note) {
                <div class="note">Obs.: {{ line.note }}</div>
              }
              <div>
                Para: {{ destinations(line) }}
                @if (line.responsible) {
                  · responsável {{ line.responsible }}
                }
              </div>
              <div>
                Enviado há {{ since(line.sentAt) }} · nesta etapa há {{ since(line.statusSince) }}
                @if (late(line)) {
                  · <strong>atrasado</strong> (limite {{ line.delayLimitMinutes }} min)
                }
              </div>
              @if (line.impossible; as problem) {
                <div class="problem">Não é possível atender: {{ problem.reason }}</div>
              }
              @if (reporting() === line.id) {
                <form [formGroup]="reasonForm" (ngSubmit)="report(line)" class="row">
                  <mat-form-field appearance="outline" class="wide">
                    <mat-label>Motivo para {{ line.productName }}</mat-label>
                    <input matInput formControlName="reason" maxlength="300" autocomplete="off" />
                    <mat-hint>De 5 a 300 caracteres, ex.: acabou o insumo.</mat-hint>
                  </mat-form-field>
                  <button mat-flat-button type="submit" [disabled]="busy()">Sinalizar</button>
                  <button mat-button type="button" (click)="reporting.set(null)">Cancelar</button>
                </form>
              } @else {
                <div class="row">
                  @if (line.impossible) {
                    <button
                      mat-stroked-button
                      type="button"
                      [disabled]="busy()"
                      (click)="act(line, 'impossible/withdraw')"
                    >
                      Retirar sinalização de {{ line.productName }}
                    </button>
                  } @else {
                    @if (line.status === 'AGUARDANDO_PREPARO') {
                      <button
                        mat-flat-button
                        type="button"
                        [disabled]="busy()"
                        (click)="act(line, 'start')"
                      >
                        Iniciar {{ line.productName }}
                      </button>
                    } @else {
                      <button
                        mat-flat-button
                        type="button"
                        [disabled]="busy()"
                        (click)="act(line, 'ready')"
                      >
                        Marcar pronto {{ line.productName }}
                      </button>
                    }
                    <button
                      mat-button
                      type="button"
                      [disabled]="busy()"
                      (click)="startReport(line)"
                    >
                      Não é possível atender
                    </button>
                  }
                </div>
              }
            </li>
          }
        </ul>
        <button mat-button type="button" (click)="load()">Atualizar fila</button>
      }
    }
  `,
  styles: `
    :host {
      display: block;
      max-width: 960px;
      margin: 0 auto;
    }
    .row {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      align-items: center;
      margin-top: 8px;
    }
    .batch {
      margin-bottom: 8px;
    }
    .failures {
      color: #b3261e;
    }
    .wide {
      flex: 1 1 280px;
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
    .lines li.fresh {
      background: #e8f0fe;
    }
    .badge {
      display: inline-block;
      padding: 0 6px;
      margin-right: 6px;
      border-radius: 4px;
      background: #0b57d0;
      color: #fff;
      font-size: 0.85em;
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
export class ProductionPage {
  private readonly service = inject(ProductionService);
  private readonly catalog = inject(CatalogService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly chime = inject(ChimeService);
  private readonly forms = inject(FormBuilder).nonNullable;
  /** Diferença entre o relógio do servidor e o do aparelho, medida a cada leitura. */
  private offset = 0;
  /** Linhas já vistas nesta tela: as que chegam depois ganham o sinal visual de novo (FR-27). */
  private seen: Set<string> | null = null;
  protected readonly fresh = signal<ReadonlySet<string>>(new Set());
  private readonly now = signal(Date.now());
  /** Avisos da unidade (H17): pedido novo na produção, etapa mudada em outro aparelho, entrega. */
  protected readonly live = inject(LiveUpdatesService).forUnit(this.destroyRef);

  protected readonly statusLabel = STATUS_LABEL;
  protected readonly state = signal<State>('loading');
  protected readonly lines = signal<ProductionLine[]>([]);
  protected readonly sectors = signal<Sector[]>([]);
  protected readonly sectorId = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly failure = signal<string | null>(null);
  protected readonly reporting = signal<string | null>(null);
  /** Itens marcados para o lote (H19); some da marcação o que sai da fila. */
  protected readonly selected = signal<ReadonlySet<string>>(new Set());
  protected readonly allSelected = computed(
    () => this.lines().length > 0 && this.lines().every((l) => this.selected().has(l.id)),
  );
  protected readonly toStart = computed(() => this.eligible('INICIAR'));
  protected readonly toFinish = computed(() => this.eligible('PRONTO'));
  /** Itens do último lote que não avançaram, com o motivo dado pelo servidor. */
  protected readonly batchFailures = signal<{ id: string; name: string; message: string }[]>([]);
  protected readonly reasonForm = this.forms.group({
    reason: ['', [Validators.required, Validators.minLength(5), Validators.maxLength(300)]],
  });

  constructor() {
    interval(1000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.now.set(Date.now() + this.offset));
    this.live.signals.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((signal) => {
      if (signal.kind === 'resync' || signal.topic === 'PRODUCAO') {
        this.refresh();
      }
    });
    this.catalog
      .load()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (listing) => this.sectors.set(listing.sectors), error: () => undefined });
    this.load();
  }

  load(): void {
    this.state.set('loading');
    this.fetch();
  }

  chooseSector(sectorId: string | null): void {
    this.sectorId.set(sectorId);
    this.seen = null;
    this.fresh.set(new Set());
    this.load();
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

  act(line: ProductionLine, action: 'start' | 'ready' | 'impossible/withdraw'): void {
    this.fresh.update((ids) => new Set([...ids].filter((id) => id !== line.id)));
    const done =
      action === 'start'
        ? `${line.productName}: em preparo.`
        : action === 'ready'
          ? `${line.productName}: pronto para entregar.`
          : `${line.productName}: sinalização retirada.`;
    this.run(this.service.act(line, action), done);
  }

  toggle(id: string, checked: boolean): void {
    this.selected.update((ids) => {
      const next = new Set(ids);
      if (checked) {
        next.add(id);
      } else {
        next.delete(id);
      }
      return next;
    });
  }

  selectAll(checked: boolean): void {
    this.selected.set(checked ? new Set(this.lines().map((l) => l.id)) : new Set());
  }

  /** Marcados na etapa da ação e sem impossibilidade: só esses vão no lote. */
  private eligible(action: BatchAction): ProductionLine[] {
    const status = action === 'INICIAR' ? 'AGUARDANDO_PREPARO' : 'EM_PREPARO';
    return this.lines().filter(
      (l) => this.selected().has(l.id) && l.status === status && !l.impossible,
    );
  }

  runBatch(action: BatchAction): void {
    const lines = action === 'INICIAR' ? this.toStart() : this.toFinish();
    if (lines.length > BATCH_MAX) {
      this.failure.set(`Marque no máximo ${BATCH_MAX} itens por vez.`);
      return;
    }
    const names = new Map(lines.map((l) => [l.id, `${l.quantity} × ${l.productName}`]));
    this.busy.set(true);
    this.failure.set(null);
    this.notice.set(null);
    this.batchFailures.set([]);
    this.fresh.update((ids) => new Set([...ids].filter((id) => !names.has(id))));
    this.service
      .batch(action, lines)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.busy.set(false);
          this.notice.set(batchSummary(action, result));
          this.batchFailures.set(
            result.lines
              .filter((l) => l.status === 'FALHA')
              .map((l) => ({
                id: l.lineId,
                name: names.get(l.lineId) ?? 'Item',
                message: l.message ?? 'Não avançou.',
              })),
          );
          const done = new Set(result.lines.filter((l) => l.status !== 'FALHA').map((l) => l.lineId));
          this.selected.update((ids) => new Set([...ids].filter((id) => !done.has(id))));
          this.fetch();
        },
        error: (error: unknown) => {
          this.busy.set(false);
          this.failure.set(failureMessage(error));
        },
      });
  }

  startReport(line: ProductionLine): void {
    this.reasonForm.reset();
    this.reporting.set(line.id);
  }

  report(line: ProductionLine): void {
    const reason = this.reasonForm.getRawValue().reason.trim();
    if (this.reasonForm.invalid || reason.length < 5) {
      this.failure.set('Escreva o motivo, com 5 a 300 caracteres.');
      return;
    }
    this.run(
      this.service.impossible(line, reason),
      `${line.productName}: não é possível atender. Quem atende foi avisado.`,
    );
  }

  /** Releitura por aviso do servidor: a fila continua na tela enquanto atualiza. */
  private refresh(): void {
    if (this.state() === 'ready') {
      this.fetch();
    } else if (this.state() === 'error') {
      this.load();
    }
  }

  private fetch(): void {
    this.service
      .queue(this.sectorId())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (board) => {
          this.offset = Date.parse(board.now) - Date.now();
          this.now.set(Date.now() + this.offset);
          this.lines.set(board.lines);
          const present = new Set(board.lines.map((l) => l.id));
          this.selected.update((ids) => new Set([...ids].filter((id) => present.has(id))));
          this.markFresh(board.lines);
          this.state.set('ready');
        },
        error: (error: unknown) =>
          this.state.set(problemCode(error) === 'PERMISSAO_NEGADA' ? 'forbidden' : 'error'),
      });
  }

  /** Sinal visual de item novo na fila (FR-27); trocar de setor ou recarregar não conta como novo. */
  private markFresh(lines: ProductionLine[]): void {
    const seen = this.seen;
    const arrived = seen ? lines.filter((l) => !seen.has(l.id)) : [];
    this.seen = new Set([...(seen ?? []), ...lines.map((l) => l.id)]);
    if (arrived.length > 0) {
      this.chime.play();
      this.fresh.set(new Set([...this.fresh(), ...arrived.map((l) => l.id)]));
      this.notice.set(
        arrived.length === 1
          ? `Novo na fila: ${arrived[0].quantity} × ${arrived[0].productName}.`
          : `${arrived.length} itens novos na fila.`,
      );
    }
  }

  private run(request: Observable<ProductionLine>, done: string): void {
    this.busy.set(true);
    this.failure.set(null);
    this.notice.set(null);
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.busy.set(false);
        this.reporting.set(null);
        this.notice.set(done);
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
}

/** "3 iniciados, 1 já estava feito, 2 não avançaram." */
function batchSummary(action: BatchAction, result: BatchResult): string {
  const parts = [
    `${result.concluded} ${action === 'INICIAR' ? 'iniciado' : 'pronto'}${result.concluded === 1 ? '' : 's'}`,
  ];
  if (result.ignored > 0) {
    parts.push(`${result.ignored} já ${result.ignored === 1 ? 'estava feito' : 'estavam feitos'}`);
  }
  if (result.failed > 0) {
    parts.push(`${result.failed} não ${result.failed === 1 ? 'avançou' : 'avançaram'}`);
  }
  return `Lote: ${parts.join(', ')}.`;
}
