import { NgTemplateOutlet } from '@angular/common';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { RouterLink } from '@angular/router';
import { Observable, interval, switchMap } from 'rxjs';

import { ChimeService, SoundToggle } from '../live/chime';
import { LiveStatus } from '../live/live-status';
import { LiveUpdatesService } from '../live/live-updates';
import { ProductionService, elapsed } from '../production/production.service';
import {
  UnitContextService,
  can,
  failureMessage,
  problemCode,
} from '../session/unit-context.service';
import { TabsService } from '../tabs/tabs.service';
import {
  AttendanceCall,
  AttendanceService,
  Attendant,
  KIND_LABEL,
} from './attendance.service';

type State = 'loading' | 'ready' | 'forbidden' | 'error';

/** Recusas em que a tela relê a fila: outra pessoa assumiu, entregou ou mudou o item. */
const STALE = new Set([
  'CHAMADO_JA_ASSUMIDO',
  'CHAMADO_CONCLUIDO',
  'CHAMADO_NAO_ENCONTRADO',
  'VERSAO_DESATUALIZADA',
  'ETAPA_INVALIDA',
  'LINHA_NAO_ENCONTRADA',
  'LINHA_IMPOSSIVEL',
]);

/**
 * Atendimento (H20, FR-23, FR-26, FR-27): os avisos de item pronto e de "não é possível atender", separados entre os
 * que são seus, os da central ainda sem dono e os de outras pessoas, que você pode ajudar a entregar. Na central,
 * "Assumir" fica com o chamado de forma exclusiva. O gerente reatribui: na central o chamado, no garçom responsável a
 * comanda inteira. Ler não conclui nada: a entrega conclui o aviso de pronto. Não há botão de chamar atendimento.
 */
@Component({
  selector: 'fp-attendance-page',
  imports: [NgTemplateOutlet, RouterLink, MatButtonModule, MatProgressSpinnerModule, LiveStatus, SoundToggle],
  template: `
    <h1>Atendimento</h1>
    <p><a routerLink="/inicio">Voltar ao início</a></p>
    <fp-live-status [live]="live" />
    @if (state() === 'forbidden') {
      <p role="alert">Seu papel nesta unidade não atende comandas.</p>
    } @else {
      <fp-sound-toggle />
      <div role="status" aria-live="polite">
        @if (state() === 'loading') {
          <mat-progress-spinner mode="indeterminate" diameter="32" aria-hidden="true" />
          <p>Carregando…</p>
        } @else if (state() === 'error') {
          <p>Não foi possível carregar. Verifique sua conexão e tente novamente.</p>
        } @else if (state() === 'ready') {
          <p>
            Com você: {{ mine().length }} · Na central, sem dono: {{ unowned().length }} · Com outras
            pessoas: {{ others().length }}
          </p>
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
        <h2>Com você ({{ mine().length }})</h2>
        @if (mine().length === 0) {
          <p>Nada com você agora.</p>
        }
        <ul class="calls" aria-label="Chamados com você">
          @for (call of mine(); track call.id) {
            <ng-container *ngTemplateOutlet="item; context: { $implicit: call }" />
          }
        </ul>

        @if (unowned().length > 0) {
          <h2>Na central, sem dono ({{ unowned().length }})</h2>
          <ul class="calls" aria-label="Chamados da central sem dono">
            @for (call of unowned(); track call.id) {
              <ng-container *ngTemplateOutlet="item; context: { $implicit: call }" />
            }
          </ul>
        }

        <h2>Com outras pessoas ({{ others().length }})</h2>
        @if (others().length === 0) {
          <p>Nenhum chamado com outras pessoas.</p>
        }
        <ul class="calls" aria-label="Chamados com outras pessoas">
          @for (call of others(); track call.id) {
            <ng-container *ngTemplateOutlet="item; context: { $implicit: call }" />
          }
        </ul>
        <button mat-button type="button" (click)="load()">Atualizar</button>
      }
    }

    <ng-template #item let-call>
      <li [class.blocked]="call.kind === 'IMPOSSIBILIDADE'" [class.fresh]="fresh().has(call.id)">
        <div>
          <strong>{{ kindText(call) }}</strong>
          @if (fresh().has(call.id)) {
            · <strong>novo</strong>
          }
          · Comanda {{ call.tab.number }} · {{ call.tab.name }} ·
          {{ call.tab.table ? 'mesa ' + call.tab.table : 'sem mesa' }}
        </div>
        @if (call.line; as line) {
          <div>
            {{ call.quantity }} × {{ line.productName }}
            @if (line.sectorName) {
              · {{ line.sectorName }}
            }
          </div>
          @if (line.note) {
            <div class="note">Obs.: {{ line.note }}</div>
          }
          @if (call.kind === 'IMPOSSIBILIDADE' && line.impossible) {
            <div class="problem">Motivo: {{ line.impossible.reason }}</div>
            <div>Fale com o cliente. O cancelamento do item é feito por quem tem a permissão.</div>
          }
        }
        <div>
          Há {{ since(call.createdAt) }} ·
          @if (call.owner) {
            {{ call.mode === 'CENTRAL' ? 'Assumido por' : 'Responsável:' }} {{ call.owner.name }}
          } @else {
            Central, sem dono
          }
        </div>
        <div class="row">
          @if (deliverable(call)) {
            <button mat-flat-button type="button" [disabled]="busy()" (click)="deliver(call)">
              Entregar {{ call.line?.quantity }} × {{ call.line?.productName }}
            </button>
          }
          @if (call.mode === 'CENTRAL' && !call.owner) {
            <button mat-stroked-button type="button" [disabled]="busy()" (click)="claim(call)">
              Assumir
            </button>
          }
          @if (canReassign()) {
            <button mat-button type="button" [disabled]="busy()" (click)="startReassign(call)">
              Reatribuir
            </button>
          }
        </div>
        @if (reassigning()?.id === call.id) {
          <div class="row" role="group" [attr.aria-label]="'Reatribuir chamado da comanda ' + call.tab.number">
            @if (call.mode === 'GARCOM_RESPONSAVEL') {
              <p>Muda o responsável da comanda {{ call.tab.number }} inteira, com os chamados dela.</p>
            }
            @for (person of attendants(); track person.id) {
              @if (person.id !== call.owner?.id) {
                <button mat-stroked-button type="button" [disabled]="busy()" (click)="reassign(call, person)">
                  Passar para {{ person.displayName }}
                </button>
              }
            }
            <button mat-button type="button" (click)="reassigning.set(null)">Cancelar</button>
          </div>
        }
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
      align-items: center;
    }
    .calls {
      list-style: none;
      padding: 0;
      display: grid;
      gap: 8px;
    }
    .calls li {
      padding: 12px;
      border: 1px solid rgba(0, 0, 0, 0.12);
      border-radius: 4px;
    }
    .calls li.fresh {
      border: 2px solid #1a73e8;
    }
    .calls li.blocked {
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
export class AttendancePage {
  private readonly service = inject(AttendanceService);
  private readonly production = inject(ProductionService);
  private readonly tabs = inject(TabsService);
  private readonly chime = inject(ChimeService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly unit = inject(UnitContextService).current;
  private offset = 0;
  /** Chamados já vistos nesta tela: os que chegam depois ganham o sinal de novo (e o som, se ligado). */
  private seen: Set<string> | null = null;
  private readonly now = signal(Date.now());
  /** Avisos da unidade (H17): chamado novo, assumido, reatribuído ou concluído; item pronto ou entregue. */
  protected readonly live = inject(LiveUpdatesService).forUnit(this.destroyRef);

  protected readonly state = signal<State>('loading');
  protected readonly calls = signal<AttendanceCall[]>([]);
  protected readonly fresh = signal<ReadonlySet<string>>(new Set());
  protected readonly busy = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly failure = signal<string | null>(null);
  protected readonly reassigning = signal<AttendanceCall | null>(null);
  protected readonly attendants = signal<Attendant[]>([]);
  protected readonly canReassign = computed(() => can(this.unit(), 'REATRIBUIR_ATENDIMENTO'));
  protected readonly mine = computed(() => this.calls().filter((c) => c.mine));
  protected readonly unowned = computed(() =>
    this.calls().filter((c) => c.mode === 'CENTRAL' && !c.owner),
  );
  protected readonly others = computed(() => this.calls().filter((c) => !c.mine && c.owner));

  constructor() {
    interval(1000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.now.set(Date.now() + this.offset));
    this.live.signals.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((signal) => {
      if (
        signal.kind === 'resync' ||
        signal.topic === 'ATENDIMENTO' ||
        signal.topic === 'PRODUCAO'
      ) {
        this.refresh();
      }
    });
    this.load();
  }

  load(): void {
    this.state.set('loading');
    this.fetch();
  }

  protected kindText(call: AttendanceCall): string {
    return KIND_LABEL[call.kind];
  }

  protected since(instant: string): string {
    return elapsed(instant, this.now());
  }

  /** Qualquer pessoa que atende pode entregar (ajuda): o servidor confere a versão do item. */
  protected deliverable(call: AttendanceCall): boolean {
    return (
      call.kind === 'ITEM_PRONTO' &&
      call.line !== null &&
      call.line.status === 'PRONTO' &&
      !call.line.impossible
    );
  }

  deliver(call: AttendanceCall): void {
    const line = call.line!;
    this.run(
      this.production.act(line, 'deliver'),
      `${line.quantity} × ${line.productName} entregue.`,
    );
  }

  claim(call: AttendanceCall): void {
    this.run(this.service.claim(call), `Chamado da comanda ${call.tab.number} assumido por você.`);
  }

  startReassign(call: AttendanceCall): void {
    this.reassigning.set(call);
    this.service
      .attendants()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (people) => this.attendants.set(people),
        error: (error: unknown) => this.failure.set(failureMessage(error)),
      });
  }

  reassign(call: AttendanceCall, person: Attendant): void {
    const done = `Comanda ${call.tab.number}: agora com ${person.displayName}.`;
    if (call.mode === 'CENTRAL') {
      this.run(this.service.assign(call, person.id), done);
      return;
    }
    // Garçom responsável: o chamado segue o responsável da comanda, lido agora com a versão atual.
    this.run(
      this.tabs.find(call.tab.id).pipe(switchMap((tab) => this.tabs.reassign(tab, person.id))),
      done,
    );
  }

  private run(action: Observable<unknown>, done: string): void {
    this.busy.set(true);
    this.failure.set(null);
    this.notice.set(null);
    action.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.busy.set(false);
        this.reassigning.set(null);
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
      .queue()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (queue) => {
          this.offset = Date.parse(queue.now) - Date.now();
          this.now.set(Date.now() + this.offset);
          this.calls.set(queue.calls);
          this.markFresh(queue.calls);
          this.state.set('ready');
        },
        error: (error: unknown) =>
          this.state.set(problemCode(error) === 'PERMISSAO_NEGADA' ? 'forbidden' : 'error'),
      });
  }

  /** Sinal de novo (FR-27) para o que chegou depois da primeira leitura; som só para o seu ou o da central. */
  private markFresh(calls: AttendanceCall[]): void {
    const seen = this.seen;
    const arrived = seen ? calls.filter((c) => !seen.has(c.id)) : [];
    this.seen = new Set([...(seen ?? []), ...calls.map((c) => c.id)]);
    const present = new Set(calls.map((c) => c.id));
    this.fresh.set(
      new Set([...this.fresh(), ...arrived.map((c) => c.id)].filter((id) => present.has(id))),
    );
    if (arrived.some((c) => c.mine || (c.mode === 'CENTRAL' && !c.owner))) {
      this.chime.play();
    }
  }
}
