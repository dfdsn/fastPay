import { DatePipe } from '@angular/common';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatRadioModule } from '@angular/material/radio';
import { DomSanitizer, SafeUrl } from '@angular/platform-browser';
import { RouterLink } from '@angular/router';
import { Observable, interval } from 'rxjs';

import { AttendanceService, Attendant } from '../attendance/attendance.service';
import { LiveStatus } from '../live/live-status';
import { LiveUpdatesService } from '../live/live-updates';
import { UnitContextService, can, failureMessage, problemCode } from '../session/unit-context.service';
import { AccountCheck, BindingQr, ContinuationCandidate, Tab, TabsService } from './tabs.service';

type State = 'loading' | 'ready' | 'forbidden' | 'error';

/** QR na tela, com o tempo que falta para vencer (2 minutos). */
interface ShownQr {
  tab: Tab;
  qr: BindingQr;
  image: SafeUrl;
}

/**
 * Comandas da unidade (H15, FR-07–FR-09). Quem opera comanda abre a comanda de uma pessoa (nome ou apelido e mesa
 * opcional; o número é automático), troca a mesa e mostra o QR para o cliente acompanhar a conta no celular. Cliente
 * que perdeu o acesso (outro aparelho, bateria) é recuperado por quem tem a permissão, depois de conferir
 * pessoalmente o nome e um item da conta (ou a hora da abertura, sem pedido). Nome e número nunca dão acesso.
 *
 * Continuação de visita (H26, FR-12): quem tem a permissão escolhe a comanda anterior do mesmo cliente numa lista por
 * hora de abertura (nunca sugerida pelo nome) e confirma pessoalmente que ele não saiu; o couvert não se repete.
 */
@Component({
  selector: 'fp-tabs-page',
  imports: [
    DatePipe,
    ReactiveFormsModule,
    RouterLink,
    MatButtonModule,
    MatCardModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatRadioModule,
    LiveStatus,
  ],
  template: `
    <h1>Comandas</h1>
    <p><a routerLink="/inicio">Voltar ao início</a></p>
    <fp-live-status [live]="live" />
    @if (state() === 'forbidden') {
      <p role="alert">Seu papel nesta unidade não opera comandas.</p>
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

      @if (shown(); as current) {
        <mat-card appearance="outlined" class="qr">
          <mat-card-header>
            <mat-card-title>
              <h2>QR da comanda {{ current.tab.number }} · {{ current.tab.name }}</h2>
            </mat-card-title>
          </mat-card-header>
          <mat-card-content>
            @if (secondsLeft() > 0) {
              <img
                [src]="current.image"
                width="240"
                height="240"
                [alt]="'QR de acesso à comanda ' + current.tab.number"
              />
              <p aria-live="polite">
                Peça para o cliente apontar a câmera. Vale por {{ secondsLeft() }} s e uma única vez.
              </p>
            } @else {
              <p aria-live="polite">Este QR venceu. Gere outro se o cliente ainda não confirmou.</p>
            }
          </mat-card-content>
          <mat-card-actions>
            <button mat-stroked-button type="button" (click)="showQr(current.tab)">Gerar outro QR</button>
            <button mat-button type="button" (click)="closeQr()">Fechar</button>
          </mat-card-actions>
        </mat-card>
      }

      @if (recovering(); as tab) {
        <mat-card appearance="outlined">
          <mat-card-header>
            <mat-card-title>
              <h2>Recuperar acesso · comanda {{ tab.number }}</h2>
            </mat-card-title>
          </mat-card-header>
          <mat-card-content>
            <p>
              Confira pessoalmente, sem dizer a resposta: o cliente precisa falar o nome ou apelido da comanda e um
              item que pediu (ou, sem pedidos, a hora em que a comanda foi aberta:
              {{ tab.openedAt | date: 'HH:mm' }}). O acesso atual será cancelado.
            </p>
            <form [formGroup]="recovery" (ngSubmit)="recover(tab)">
              <mat-checkbox formControlName="nameChecked">
                O cliente disse o nome ou apelido "{{ tab.name }}"
              </mat-checkbox>
              <mat-radio-group
                formControlName="accountCheck"
                aria-label="Como o cliente confirmou a conta"
                class="column"
              >
                <mat-radio-button value="ITEM_DA_CONTA">Disse um item pedido nesta comanda</mat-radio-button>
                <mat-radio-button value="HORA_DA_ABERTURA">
                  Sem pedidos: disse a hora aproximada da abertura
                </mat-radio-button>
              </mat-radio-group>
              <mat-form-field appearance="outline" class="wide">
                <mat-label>Motivo</mat-label>
                <textarea matInput formControlName="reason" maxlength="300" rows="2"></textarea>
                <mat-hint>De 5 a 300 caracteres, ex.: celular sem bateria.</mat-hint>
              </mat-form-field>
              <div class="row">
                <button mat-flat-button type="submit" [disabled]="busy()">Recuperar e mostrar QR</button>
                <button mat-button type="button" (click)="recovering.set(null)">Cancelar</button>
              </div>
            </form>
          </mat-card-content>
        </mat-card>
      }

      @if (linking(); as tab) {
        <mat-card appearance="outlined">
          <mat-card-header>
            <mat-card-title>
              <h2>Continuação de visita · comanda {{ tab.number }}</h2>
            </mat-card-title>
          </mat-card-header>
          <mat-card-content>
            <p>
              Use quando o cliente já tinha outra comanda hoje e continua no estabelecimento. Pergunte qual era a
              comanda anterior: a lista não sugere pelo nome. O couvert já cobrado na visita não se repete.
            </p>
            @if (candidates().length === 0) {
              <p>Nenhuma comanda anterior nas últimas 24 horas pode ser vinculada.</p>
            } @else {
              <form [formGroup]="continuation" (ngSubmit)="linkVisit(tab)">
                <mat-radio-group formControlName="previousTabId" aria-label="Comanda anterior" class="column">
                  @for (c of candidates(); track c.id) {
                    <mat-radio-button [value]="c.id">
                      {{ c.number }} · {{ c.name }} · {{ c.table ? 'mesa ' + c.table : 'sem mesa' }} · aberta às
                      {{ c.openedAt | date: 'HH:mm' }}{{ c.closed ? ' · encerrada' : '' }}
                    </mat-radio-button>
                  }
                </mat-radio-group>
                <mat-checkbox formControlName="stayConfirmed">
                  Confirmei com o cliente que é a mesma pessoa e que ela não saiu do estabelecimento
                </mat-checkbox>
                <div class="row">
                  <button mat-flat-button type="submit" [disabled]="busy()">Vincular à visita</button>
                  <button mat-button type="button" (click)="linking.set(null)">Cancelar</button>
                </div>
              </form>
            }
          </mat-card-content>
        </mat-card>
      }

      @if (state() === 'ready') {
        <mat-card appearance="outlined">
          <mat-card-header>
            <mat-card-title><h2>Abrir comanda</h2></mat-card-title>
          </mat-card-header>
          <mat-card-content>
            <form [formGroup]="opening" (ngSubmit)="open()" class="row">
              <mat-form-field appearance="outline">
                <mat-label>Nome ou apelido</mat-label>
                <input matInput formControlName="name" maxlength="60" autocomplete="off" />
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Mesa (opcional)</mat-label>
                <input matInput formControlName="table" maxlength="20" autocomplete="off" />
              </mat-form-field>
              <button mat-flat-button type="submit" [disabled]="busy()">Abrir</button>
            </form>
          </mat-card-content>
        </mat-card>

        <h2>Abertas ({{ tabs().length }})</h2>
        @if (tabs().length === 0) {
          <p>Nenhuma comanda aberta.</p>
        }
        <ul class="tabs">
          @for (tab of tabs(); track tab.id) {
            <li>
              <div>
                <strong>{{ tab.number }}</strong> · {{ tab.name }}
                · {{ tab.table ? 'mesa ' + tab.table : 'sem mesa' }}
                · aberta às {{ tab.openedAt | date: 'HH:mm' }}
                · {{ tab.guestLinked ? 'cliente com acesso' : 'cliente sem acesso' }}
                ·
                {{
                  tab.attendanceMode === 'CENTRAL'
                    ? 'atendimento pela central'
                    : 'responsável: ' + (tab.responsibleName ?? 'ninguém')
                }}
                @if (tab.continuesTab; as previous) {
                  · continua a visita da comanda {{ previous.number }}
                }
                @if (tab.closingSince) {
                  · <strong>em fechamento</strong> desde {{ tab.closingSince | date: 'HH:mm' }}
                }
              </div>
              @if (editingTable() === tab.id) {
                <form [formGroup]="tableForm" (ngSubmit)="saveTable(tab)" class="row">
                  <mat-form-field appearance="outline">
                    <mat-label>Nova mesa da comanda {{ tab.number }}</mat-label>
                    <input matInput formControlName="table" maxlength="20" autocomplete="off" />
                  </mat-form-field>
                  <button mat-stroked-button type="submit" [disabled]="busy()">Salvar mesa</button>
                  <button mat-button type="button" (click)="editingTable.set(null)">Cancelar</button>
                </form>
              } @else {
                <div class="row">
                  <a mat-flat-button [routerLink]="['/comandas', tab.id]">Pedido</a>
                  @if (!tab.guestLinked) {
                    <button mat-stroked-button type="button" (click)="showQr(tab)">Mostrar QR</button>
                  } @else if (canRecover()) {
                    <button mat-stroked-button type="button" (click)="startRecovery(tab)">
                      Recuperar acesso
                    </button>
                  }
                  <button mat-button type="button" (click)="editTable(tab)">Trocar mesa</button>
                  @if (canLinkVisit() && !tab.continuesTab) {
                    <button mat-button type="button" (click)="startLinking(tab)">Continuação de visita</button>
                  }
                  @if (canReassign() && tab.attendanceMode === 'GARCOM_RESPONSAVEL') {
                    <button mat-button type="button" (click)="startReassign(tab)">
                      Trocar responsável
                    </button>
                  }
                </div>
                @if (reassigning() === tab.id) {
                  <div
                    class="row"
                    role="group"
                    [attr.aria-label]="'Novo responsável da comanda ' + tab.number"
                  >
                    @for (person of attendants(); track person.id) {
                      @if (person.id !== tab.responsibleId) {
                        <button
                          mat-stroked-button
                          type="button"
                          [disabled]="busy()"
                          (click)="reassign(tab, person)"
                        >
                          Passar para {{ person.displayName }}
                        </button>
                      }
                    }
                    <button mat-button type="button" (click)="reassigning.set(null)">Cancelar</button>
                  </div>
                }
              }
            </li>
          }
        </ul>
        <button mat-button type="button" (click)="load()">Atualizar lista</button>
      }
    }
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
      align-items: center;
    }
    .column {
      display: flex;
      flex-direction: column;
      margin: 8px 0;
    }
    .wide {
      width: 100%;
    }
    .tabs {
      list-style: none;
      padding: 0;
    }
    .tabs li {
      padding: 8px 0;
      border-bottom: 1px solid rgba(0, 0, 0, 0.12);
    }
    .qr img {
      display: block;
      background: #fff;
    }
    .error {
      color: #b3261e;
    }
    mat-card {
      margin-bottom: 16px;
    }
    h2 {
      font: inherit;
      font-weight: 500;
    }
  `,
})
export class TabsPage {
  private readonly service = inject(TabsService);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly destroyRef = inject(DestroyRef);
  private readonly unit = inject(UnitContextService).current;
  private readonly forms = inject(FormBuilder).nonNullable;
  private readonly now = signal(Date.now());
  /** Avisos da unidade (H17): comanda aberta ou mudada por outra pessoa, cliente que confirmou o QR. */
  protected readonly live = inject(LiveUpdatesService).forUnit(this.destroyRef);

  protected readonly state = signal<State>('loading');
  protected readonly tabs = signal<Tab[]>([]);
  protected readonly busy = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly failure = signal<string | null>(null);
  protected readonly shown = signal<ShownQr | null>(null);
  protected readonly recovering = signal<Tab | null>(null);
  protected readonly editingTable = signal<string | null>(null);
  protected readonly canRecover = computed(() => can(this.unit(), 'RECUPERAR_ACESSO_CONSUMIDOR'));
  /** Gerente troca o garçom responsável (H20); os chamados em aberto da comanda acompanham. */
  protected readonly canReassign = computed(() => can(this.unit(), 'REATRIBUIR_ATENDIMENTO'));
  protected readonly reassigning = signal<string | null>(null);
  /** Continuação de visita (H26): permissão própria, delegável ao garçom. */
  protected readonly canLinkVisit = computed(() => can(this.unit(), 'VINCULAR_CONTINUACAO_VISITA'));
  protected readonly linking = signal<Tab | null>(null);
  protected readonly candidates = signal<ContinuationCandidate[]>([]);
  protected readonly attendants = signal<Attendant[]>([]);
  private readonly attendance = inject(AttendanceService);
  protected readonly secondsLeft = computed(() => {
    const current = this.shown();
    if (!current) {
      return 0;
    }
    return Math.max(0, Math.ceil((Date.parse(current.qr.expiresAt) - this.now()) / 1000));
  });

  protected readonly opening = this.forms.group({
    name: ['', [Validators.required, Validators.maxLength(60)]],
    table: ['', Validators.maxLength(20)],
  });
  protected readonly tableForm = this.forms.group({ table: ['', Validators.maxLength(20)] });
  protected readonly recovery = this.forms.group({
    nameChecked: [false, Validators.requiredTrue],
    accountCheck: this.forms.control<AccountCheck | ''>('', Validators.required),
    reason: ['', [Validators.required, Validators.minLength(5), Validators.maxLength(300)]],
  });

  protected readonly continuation = this.forms.group({
    previousTabId: ['', Validators.required],
    stayConfirmed: [false, Validators.requiredTrue],
  });

  constructor() {
    interval(1000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.now.set(Date.now()));
    this.live.signals.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((signal) => {
      if (signal.kind === 'resync' || signal.topic === 'COMANDA') {
        this.refresh();
      }
    });
    this.load();
  }

  load(): void {
    this.state.set('loading');
    this.fetch();
  }

  /** Releitura por aviso do servidor: a lista continua na tela enquanto atualiza. */
  private refresh(): void {
    if (this.state() === 'ready') {
      this.fetch();
    } else if (this.state() === 'error') {
      this.load();
    }
  }

  private fetch(): void {
    this.service
      .active()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (tabs) => {
          this.tabs.set(tabs);
          this.state.set('ready');
          const shown = this.shown();
          if (shown && tabs.some((t) => t.id === shown.tab.id && t.guestLinked)) {
            // O cliente confirmou o QR em outro aparelho: o QR já não serve.
            this.shown.set(null);
            this.notice.set(`Cliente da comanda ${shown.tab.number} confirmou o acesso.`);
          }
        },
        error: (error: unknown) =>
          this.state.set(problemCode(error) === 'PERMISSAO_NEGADA' ? 'forbidden' : 'error'),
      });
  }

  open(): void {
    if (this.opening.invalid) {
      this.failure.set('Informe o nome ou apelido do cliente (até 60 caracteres).');
      return;
    }
    const { name, table } = this.opening.getRawValue();
    this.run(this.service.open(name, table.trim() || null), (tab) => {
      this.opening.reset();
      this.notice.set(`Comanda ${tab.number} aberta para ${tab.name}.`);
      this.load();
      this.showQr(tab, true);
    });
  }

  showQr(tab: Tab, keepNotice = false): void {
    this.recovering.set(null);
    this.run(this.service.bindingQr(tab.id), (qr) => this.display(tab, qr), keepNotice);
  }

  closeQr(): void {
    this.shown.set(null);
    this.load();
  }

  startRecovery(tab: Tab): void {
    this.shown.set(null);
    this.recovery.reset();
    this.recovering.set(tab);
  }

  recover(tab: Tab): void {
    if (this.recovery.invalid) {
      this.failure.set(
        'Confirme o nome, como o cliente confirmou a conta e escreva o motivo (5 a 300 caracteres).',
      );
      return;
    }
    const { nameChecked, accountCheck, reason } = this.recovery.getRawValue();
    this.run(
      this.service.recover(tab.id, { nameChecked, accountCheck: accountCheck as AccountCheck, reason }),
      (qr) => {
        this.recovering.set(null);
        this.notice.set(`Acesso anterior da comanda ${tab.number} cancelado. Mostre o novo QR ao cliente.`);
        this.display({ ...tab, guestLinked: false }, qr);
      },
    );
  }

  startLinking(tab: Tab): void {
    this.shown.set(null);
    this.recovering.set(null);
    this.continuation.reset();
    this.candidates.set([]);
    this.run(this.service.continuationCandidates(tab.id), (candidates) => {
      this.candidates.set(candidates);
      this.linking.set(tab);
    });
  }

  linkVisit(tab: Tab): void {
    if (this.continuation.invalid) {
      this.failure.set('Escolha a comanda anterior e confirme com o cliente que ele não saiu do estabelecimento.');
      return;
    }
    const { previousTabId, stayConfirmed } = this.continuation.getRawValue();
    this.run(this.service.linkVisit(tab, previousTabId, stayConfirmed), (saved) => {
      this.linking.set(null);
      this.notice.set(
        `Comanda ${saved.number} continua a visita da comanda ${saved.continuesTab?.number ?? ''}.`.trim(),
      );
      this.load();
    });
  }

  editTable(tab: Tab): void {
    this.tableForm.setValue({ table: tab.table ?? '' });
    this.editingTable.set(tab.id);
  }

  saveTable(tab: Tab): void {
    this.run(this.service.changeTable(tab, this.tableForm.getRawValue().table.trim() || null), (saved) => {
      this.editingTable.set(null);
      this.notice.set(`Comanda ${saved.number}: ${saved.table ? 'mesa ' + saved.table : 'sem mesa'}.`);
      this.load();
    });
  }

  startReassign(tab: Tab): void {
    this.reassigning.set(tab.id);
    this.attendance
      .attendants()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (people) => this.attendants.set(people),
        error: (error: unknown) => this.failure.set(failureMessage(error)),
      });
  }

  reassign(tab: Tab, person: Attendant): void {
    this.run(this.service.reassign(tab, person.id), (saved) => {
      this.reassigning.set(null);
      this.notice.set(`Comanda ${saved.number}: responsável agora é ${saved.responsibleName ?? person.displayName}.`);
      this.load();
    });
  }

  private display(tab: Tab, qr: BindingQr): void {
    // SVG montado pelo servidor só com retângulos (sem texto nem script): seguro como imagem.
    const image = this.sanitizer.bypassSecurityTrustUrl(
      'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(qr.svg),
    );
    this.now.set(Date.now());
    this.shown.set({ tab, qr, image });
  }

  private run<T>(request: Observable<T>, done: (value: T) => void, keepNotice = false): void {
    this.busy.set(true);
    this.failure.set(null);
    if (!keepNotice) {
      this.notice.set(null);
    }
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (value) => {
        this.busy.set(false);
        done(value);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.failure.set(failureMessage(error));
        if (problemCode(error) === 'CONSUMIDOR_JA_VINCULADO' || problemCode(error) === 'VERSAO_DESATUALIZADA') {
          this.load();
        }
      },
    });
  }
}
