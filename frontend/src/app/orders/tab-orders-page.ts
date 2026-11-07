import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { EMPTY, Observable, expand, reduce, timeout } from 'rxjs';

import { CatalogService, Product } from '../catalog/catalog.service';
import { BillPanel } from '../bills/bill-panel';
import { LiveStatus } from '../live/live-status';
import { LiveUpdatesService } from '../live/live-updates';
import { UnitContextService, failureMessage, problemCode } from '../session/unit-context.service';
import { Tab, TabsService } from '../tabs/tabs.service';
import {
  Draft,
  DraftLine,
  MAX_LINES,
  MAX_QUANTITY,
  NOTE_MAX,
  applyReview,
  draftStorageKey,
  draftTotalCents,
  emptyDraft,
  loadDraft,
  newKey,
  priceText,
  reais,
  saveDraft,
} from './order-draft';
import { Forwarding, OrdersService, ReceivedLine, ReviewProblem, SentOrder } from './orders.service';

/** Motivo do cancelamento (H23): de 5 a 300 caracteres, conferido também no servidor. */
export const CANCEL_REASON_MIN = 5;
export const CANCEL_REASON_MAX = 300;

/** Item com unidades ativas nesta comanda, como as correções o veem (H23, H25). */
export interface TabItem {
  id: string;
  productName: string;
  /** Unidades ativas nesta comanda. */
  inTab: number;
}

/** Cancelamento em edição: o item, quantas unidades e o motivo. */
interface Cancelling {
  line: TabItem;
  quantity: number;
  reason: string;
}

/** Transferência em edição (H25): o item, a comanda de destino, quantas unidades e o motivo. */
interface Transferring {
  line: TabItem;
  toTabId: string;
  quantity: number;
  reason: string;
}

type State = 'loading' | 'ready' | 'forbidden' | 'missing' | 'error';

/** Tempo máximo de espera do envio; depois disso o resultado é conferido antes de qualquer repetição. */
export const SEND_TIMEOUT_MS = 20_000;

/** Recusas definitivas do servidor: nada foi gravado e a chave do envio pode ser descartada. */
const DEFINITE_REFUSALS = new Set([
  'COMANDA_ENCERRADA',
  'COMANDA_EM_FECHAMENTO',
  'COMANDA_NAO_ENCONTRADA',
  'CHAVE_REUTILIZADA',
  'DADOS_INVALIDOS',
  'PERMISSAO_NEGADA',
]);

const FORWARDING_LABELS: Record<Forwarding, string> = {
  PENDENTE: 'encaminhando à produção',
  CONCLUIDO: 'na produção',
  FALHOU: 'falha ao encaminhar à produção',
};

/**
 * Pedido da comanda (H16, FR-14–FR-16, CA-05–CA-07). O garçom monta o rascunho neste aparelho (guardado por pessoa,
 * unidade e comanda, sem nada no servidor), ajusta quantidades e observações e confirma "Enviar pedido". Item esgotado
 * ou preço mudado voltam marcados no rascunho, sem envio parcial; preço novo exige reconfirmação. Sem resposta do
 * servidor, a tela confere a operação antes de repetir e o reenvio usa a mesma chave, que não duplica. Abaixo, os
 * pedidos enviados com o preço de cada envio e a situação do encaminhamento à produção.
 */
@Component({
  selector: 'fp-tab-orders-page',
  imports: [
    DatePipe,
    NgTemplateOutlet,
    RouterLink,
    MatButtonModule,
    MatCardModule,
    MatProgressSpinnerModule,
    LiveStatus,
    BillPanel,
  ],
  template: `
    <p><a routerLink="/comandas">Voltar às comandas</a></p>
    <fp-live-status [live]="live" />
    @if (tab(); as t) {
      <h1>Comanda {{ t.number }} · {{ t.name }}</h1>
      <p>{{ t.table ? 'Mesa ' + t.table : 'Sem mesa' }} · aberta às {{ t.openedAt | date: 'HH:mm' }}</p>
    } @else {
      <h1>Pedido da comanda</h1>
    }

    <div role="status" aria-live="polite">
      @if (state() === 'loading') {
        <mat-progress-spinner mode="indeterminate" diameter="32" aria-hidden="true" />
        <p>Carregando…</p>
      }
      @if (notice(); as message) {
        <p>{{ message }}</p>
      }
    </div>
    <div role="alert">
      @if (state() === 'forbidden') {
        <p>Seu papel nesta unidade não envia pedidos.</p>
      } @else if (state() === 'missing') {
        <p>Comanda não encontrada entre as desta unidade.</p>
      } @else if (state() === 'error') {
        <p>Não foi possível carregar. Verifique sua conexão e tente novamente.</p>
      }
      @if (failure(); as message) {
        <p class="error">{{ message }}</p>
      }
    </div>
    @if (state() === 'error') {
      <button mat-stroked-button type="button" (click)="load()">Tentar novamente</button>
    }

    @if (state() === 'ready') {
      <mat-card appearance="outlined">
        <mat-card-header>
          <mat-card-title><h2>Rascunho ({{ draft().lines.length }} de {{ maxLines }} linhas)</h2></mat-card-title>
        </mat-card-header>
        <mat-card-content>
          <p class="hint">Fica só neste aparelho até você enviar. Nada é cobrado nem vai para a produção antes.</p>
          @if (draft().pendingKey) {
            <p class="warning">
              O último envio ficou sem resposta. Confira se ele chegou antes de mudar o rascunho; reenviar não duplica o
              pedido.
            </p>
          }
          @if (draft().lines.length === 0) {
            <p>Nenhum item. Escolha no catálogo abaixo.</p>
          }
          <ul class="lines">
            @for (line of draft().lines; track line.key; let i = $index) {
              <li [class.flagged]="line.unavailable || line.previousPrice">
                <div>
                  <strong>{{ line.name }}</strong> · {{ money(line.price) }} cada
                  @if (line.unavailable) {
                    <span class="error"> · esgotado ou fora do catálogo: remova para enviar</span>
                  }
                  @if (line.previousPrice) {
                    <span class="warning"> · preço mudou de {{ money(line.previousPrice) }} para {{ money(line.price) }}</span>
                  }
                </div>
                <div class="row">
                  <label>
                    Quantidade
                    <input
                      type="number"
                      min="1"
                      [max]="maxQuantity"
                      [value]="line.quantity"
                      [disabled]="locked()"
                      [attr.aria-label]="'Quantidade de ' + line.name"
                      (change)="setQuantity(line, $any($event.target).value)"
                    />
                  </label>
                  <label class="grow">
                    Observação
                    <input
                      type="text"
                      [maxLength]="noteMax"
                      [value]="line.note"
                      [disabled]="locked()"
                      [attr.aria-label]="'Observação de ' + line.name"
                      (change)="setNote(line, $any($event.target).value)"
                    />
                  </label>
                  <button mat-button type="button" [disabled]="locked()" (click)="remove(line)">
                    Remover {{ line.name }}
                  </button>
                </div>
              </li>
            }
          </ul>
          @if (draft().lines.length > 0) {
            <p><strong>Total do rascunho: {{ money(totalCents() / 100) }}</strong> (sem serviço e couvert)</p>
          }
        </mat-card-content>
        <mat-card-actions class="row">
          @if (draft().pendingKey) {
            <button mat-flat-button type="button" [disabled]="busy()" (click)="verify()">Conferir envio</button>
            <button mat-stroked-button type="button" [disabled]="busy()" (click)="send()">Enviar de novo</button>
          } @else {
            <button
              mat-flat-button
              type="button"
              [disabled]="busy() || draft().lines.length === 0"
              (click)="send()"
            >
              {{ needsReconfirm() ? 'Confirmar novos preços e enviar' : 'Enviar pedido' }}
            </button>
          }
          @if (draft().lines.length > 0) {
            <button mat-button type="button" [disabled]="busy()" (click)="discard()">Descartar rascunho</button>
          }
        </mat-card-actions>
      </mat-card>

      <h2>Catálogo</h2>
      @if (products().length === 0) {
        <p>Nenhum produto no catálogo desta unidade.</p>
      }
      @for (category of categories(); track category) {
        <h3>{{ category }}</h3>
        <ul class="catalog">
          @for (product of byCategory(category); track product.id) {
            <li>
              <span>{{ product.name }} · {{ money(product.price ?? 0) }}</span>
              @if (product.available) {
                <button mat-stroked-button type="button" [disabled]="locked()" (click)="add(product)">
                  Adicionar {{ product.name }}
                </button>
              } @else {
                <span class="muted">esgotado</span>
              }
            </li>
          }
        </ul>
      }

      <h2>Pedidos enviados ({{ orders().length }})</h2>
      @if (orders().length === 0) {
        <p>Nenhum pedido enviado nesta comanda.</p>
      }
      @for (order of orders(); track order.id) {
        <mat-card appearance="outlined">
          <mat-card-content>
            <p>
              Enviado às {{ order.sentAt | date: 'HH:mm' }} ·
              <span [class.error]="order.forwarding === 'FALHOU'">{{ forwardingLabel(order.forwarding) }}</span>
            </p>
            <ul class="sent">
              @for (line of order.lines; track line.id) {
                <li>
                  <span [class.cancelled]="line.inTabQuantity === 0">
                    {{ line.inTabQuantity }}× {{ line.productName }} · {{ money(line.unitPrice) }} cada ·
                    {{ money(activeTotal(line.unitPrice, line.inTabQuantity)) }}
                  </span>
                  @if (line.note) {
                    <span class="muted"> · {{ line.note }}</span>
                  }
                  @if (line.cancelledQuantity > 0) {
                    <span class="warning">
                      · enviado {{ line.quantity }}, cancelado {{ line.cancelledQuantity }}, restante
                      {{ line.activeQuantity }}
                    </span>
                  }
                  @if (line.transferredQuantity > 0) {
                    <span class="warning"> · {{ line.transferredQuantity }} transferida(s) para outras comandas</span>
                  }
                  <ng-container
                    *ngTemplateOutlet="corrections; context: { $implicit: item(line.id, line.productName, line.inTabQuantity) }"
                  />
                </li>
              }
            </ul>
            @if (order.forwarding === 'FALHOU') {
              <button mat-stroked-button type="button" [disabled]="busy()" (click)="retry(order)">
                Reenviar à produção
              </button>
            }
          </mat-card-content>
        </mat-card>
      }
      @if (received().length > 0) {
        <h2>Recebidos de outras comandas ({{ received().length }})</h2>
        <ul class="sent">
          @for (line of received(); track line.id) {
            <li>
              <span>
                {{ line.quantity }}× {{ line.productName }} · {{ money(line.unitPrice) }} cada ·
                {{ money(line.lineTotal) }}
              </span>
              @if (line.note) {
                <span class="muted"> · {{ line.note }}</span>
              }
              @if (line.origin; as o) {
                <span class="muted"> · de Comanda {{ o.number }} · {{ o.name }}</span>
              }
              <ng-container
                *ngTemplateOutlet="corrections; context: { $implicit: item(line.id, line.productName, line.quantity) }"
              />
            </li>
          }
        </ul>
      }
      <button mat-button type="button" (click)="loadOrders()">Atualizar pedidos</button>

      <ng-template #corrections let-line>
        @if (cancelling()?.line?.id === line.id) {
          <form class="row cancel" (submit)="$event.preventDefault(); confirmCancel()">
            <label>
              Cancelar quantas
              <input
                type="number"
                min="1"
                [max]="line.inTab"
                [value]="cancelling()!.quantity"
                [attr.aria-label]="'Unidades de ' + line.productName + ' a cancelar'"
                (change)="setCancelQuantity($any($event.target).value)"
              />
            </label>
            <label class="grow">
              Motivo
              <input
                type="text"
                [maxLength]="reasonMax"
                [value]="cancelling()!.reason"
                [attr.aria-label]="'Motivo do cancelamento de ' + line.productName"
                (input)="setCancelReason($any($event.target).value)"
              />
            </label>
            <button mat-flat-button type="submit" [disabled]="busy()">Confirmar cancelamento</button>
            <button mat-button type="button" [disabled]="busy()" (click)="cancelling.set(null)">Voltar</button>
          </form>
        } @else if (transferring()?.line?.id === line.id) {
          <form class="row cancel" (submit)="$event.preventDefault(); confirmTransfer()">
            <label>
              Para
              <select
                [value]="transferring()!.toTabId"
                [attr.aria-label]="'Comanda de destino de ' + line.productName"
                (change)="setTransferTarget($any($event.target).value)"
              >
                <option value="">Escolha a comanda</option>
                @for (t of destinations(); track t.id) {
                  <option [value]="t.id">Comanda {{ t.number }} · {{ t.name }}{{ t.table ? ' · mesa ' + t.table : '' }}</option>
                }
              </select>
            </label>
            <label>
              Quantas
              <input
                type="number"
                min="1"
                [max]="line.inTab"
                [value]="transferring()!.quantity"
                [attr.aria-label]="'Unidades de ' + line.productName + ' a transferir'"
                (change)="setTransferQuantity($any($event.target).value)"
              />
            </label>
            <label class="grow">
              Motivo
              <input
                type="text"
                [maxLength]="reasonMax"
                [value]="transferring()!.reason"
                [attr.aria-label]="'Motivo da transferência de ' + line.productName"
                (input)="setTransferReason($any($event.target).value)"
              />
            </label>
            <button mat-flat-button type="submit" [disabled]="busy()">Confirmar transferência</button>
            <button mat-button type="button" [disabled]="busy()" (click)="transferring.set(null)">Voltar</button>
          </form>
        } @else if (line.inTab > 0) {
          @if (canCancel()) {
            <button mat-button type="button" [disabled]="busy()" (click)="startCancel(line)">
              Cancelar {{ line.productName }}
            </button>
          }
          @if (canTransfer()) {
            <button mat-button type="button" [disabled]="busy()" (click)="startTransfer(line)">
              Transferir {{ line.productName }}
            </button>
          }
        }
      </ng-template>

      <fp-bill-panel [tabId]="tabId" [refresh]="billRefreshes()" />
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
    .grow {
      flex: 1 1 200px;
    }
    .grow input {
      width: 100%;
    }
    input[type='number'] {
      width: 4.5em;
    }
    ul {
      list-style: none;
      padding: 0;
    }
    .lines li,
    .catalog li {
      padding: 8px 0;
      border-bottom: 1px solid rgba(0, 0, 0, 0.12);
    }
    .catalog li {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      gap: 8px;
      align-items: center;
    }
    .cancelled {
      text-decoration: line-through;
    }
    form.cancel {
      margin: 4px 0 8px;
    }
    .flagged {
      background: rgba(179, 38, 30, 0.06);
    }
    .error {
      color: #b3261e;
    }
    .warning {
      color: #7a4f00;
    }
    .hint,
    .muted {
      color: rgba(0, 0, 0, 0.6);
    }
    mat-card {
      margin-bottom: 16px;
    }
    h2,
    h3 {
      font: inherit;
      font-weight: 500;
    }
  `,
})
export class TabOrdersPage {
  private readonly tabs = inject(TabsService);
  private readonly service = inject(OrdersService);
  private readonly catalog = inject(CatalogService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly unit = inject(UnitContextService);
  protected readonly tabId = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';
  /** Conta as releituras da conta (H22): pedido enviado, aviso de pedido ou de conta, reconexão. */
  protected readonly billRefreshes = signal(0);
  private storageKey: string | null = null;
  /**
   * Avisos da unidade (H17): pedidos e produção desta comanda chegam sem recarregar. Reconectar ou voltar à tela
   * relê comanda, catálogo e pedidos e confere um envio sem resposta; o rascunho nunca é enviado sozinho.
   */
  protected readonly live = inject(LiveUpdatesService).forUnit(this.destroyRef);

  protected readonly maxLines = MAX_LINES;
  protected readonly maxQuantity = MAX_QUANTITY;
  protected readonly noteMax = NOTE_MAX;
  protected readonly reasonMax = CANCEL_REASON_MAX;
  /** Cancelamento aberto num item enviado (H23). */
  protected readonly cancelling = signal<Cancelling | null>(null);
  /** Só quem tem a permissão de cancelar vê o botão; o servidor confere de novo (CA-04). */
  protected readonly canCancel = computed(
    () => this.unit.current()?.permissions?.includes('CANCELAR_ITEM') ?? false,
  );
  /** Transferência aberta num item (H25) e as comandas abertas para onde pode ir (menos esta). */
  protected readonly transferring = signal<Transferring | null>(null);
  protected readonly destinations = signal<Tab[]>([]);
  protected readonly canTransfer = computed(
    () => this.unit.current()?.permissions?.includes('TRANSFERIR_UNIDADES') ?? false,
  );

  protected readonly state = signal<State>('loading');
  protected readonly tab = signal<Tab | null>(null);
  protected readonly products = signal<Product[]>([]);
  protected readonly orders = signal<SentOrder[]>([]);
  /** Itens de outras comandas recebidos por transferência (H25). */
  protected readonly received = signal<ReceivedLine[]>([]);
  protected readonly draft = signal<Draft>(emptyDraft());
  protected readonly busy = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly failure = signal<string | null>(null);

  protected readonly locked = computed(() => this.busy() || this.draft().pendingKey !== null);
  protected readonly needsReconfirm = computed(() => this.draft().lines.some((l) => l.previousPrice));
  protected readonly totalCents = computed(() => draftTotalCents(this.draft().lines));
  protected readonly categories = computed(() => [...new Set(this.products().map((p) => p.category))]);

  constructor() {
    // Trocar ou perder a unidade tira o rascunho da tela (continua guardado para a mesma pessoa e unidade).
    const cancel = this.unit.onUnitCleared(() => {
      this.storageKey = null;
      this.draft.set(emptyDraft());
      this.orders.set([]);
      this.received.set([]);
      this.products.set([]);
    });
    this.destroyRef.onDestroy(cancel);
    this.live.signals.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((signal) => {
      if (this.state() !== 'ready') {
        return;
      }
      if (signal.kind === 'resync') {
        this.refreshTab();
        this.loadCatalog();
        this.loadOrders();
        if (this.draft().pendingKey && !this.busy()) {
          this.verify();
        }
      } else if (signal.tabId === this.tabId) {
        if (signal.topic === 'COMANDA') {
          this.refreshTab();
        } else if (signal.topic === 'CONTA') {
          this.billRefreshes.update((n) => n + 1);
        } else {
          this.loadOrders();
        }
      }
    });
    this.load();
  }

  load(): void {
    this.state.set('loading');
    const unit = this.unit.current();
    this.storageKey = unit?.membershipId ? draftStorageKey(unit.membershipId, this.tabId) : null;
    this.draft.set(this.storageKey ? loadDraft(this.storageKey) : emptyDraft());
    this.tabs
      .find(this.tabId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (tab) => {
          this.tab.set(tab);
          this.loadCatalog();
          this.loadOrders();
          this.state.set('ready');
          if (this.draft().pendingKey) {
            // Envio interrompido em outra visita à tela: conferir antes de qualquer repetição (nunca reenviar sozinho).
            this.verify();
          } else if (this.draft().lines.length > 0) {
            // Rascunho de antes (outra visita, queda ou novo login): confere antes de enviar (especificação §5).
            this.notice.set(
              'Rascunho guardado neste aparelho. Confira itens e quantidades antes de enviar: nada é enviado sem sua confirmação.',
            );
          }
        },
        error: (error: unknown) => {
          const code = problemCode(error);
          this.state.set(
            code === 'PERMISSAO_NEGADA' ? 'forbidden' : code === 'NAO_ENCONTRADA' ? 'missing' : 'error',
          );
        },
      });
  }

  loadOrders(): void {
    this.service
      .ofTab(this.tabId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (orders) => {
          this.orders.set(orders);
          this.billRefreshes.update((n) => n + 1);
        },
        error: (error: unknown) => this.failure.set(failureMessage(error)),
      });
    this.service
      .receivedOfTab(this.tabId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (received) => this.received.set(received),
        error: (error: unknown) => this.failure.set(failureMessage(error)),
      });
  }

  /** Cabeçalho da comanda (mesa trocada por outra pessoa), sem tirar a tela do lugar. */
  private refreshTab(): void {
    this.tabs
      .find(this.tabId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (tab) => this.tab.set(tab), error: () => undefined });
  }

  protected byCategory(category: string): Product[] {
    return this.products().filter((p) => p.category === category);
  }

  protected money(value: string | number): string {
    return reais(value);
  }

  protected forwardingLabel(forwarding: Forwarding): string {
    return FORWARDING_LABELS[forwarding];
  }

  add(product: Product): void {
    if (this.locked() || product.price === null) {
      return;
    }
    const d = this.draft();
    if (d.lines.length >= MAX_LINES) {
      this.failure.set(`Um envio tem no máximo ${MAX_LINES} linhas. Envie este e monte outro.`);
      return;
    }
    this.failure.set(null);
    this.notice.set(`${product.name} incluído no rascunho.`);
    this.update({
      ...d,
      lines: [
        ...d.lines,
        {
          key: newKey(),
          productId: product.id,
          name: product.name,
          price: priceText(product.price),
          quantity: 1,
          note: '',
        },
      ],
    });
  }

  setQuantity(line: DraftLine, value: string): void {
    const quantity = Math.trunc(Number(value));
    if (!Number.isFinite(quantity) || quantity < 1 || quantity > MAX_QUANTITY) {
      this.failure.set(`A quantidade vai de 1 a ${MAX_QUANTITY}.`);
      this.update({ ...this.draft() });
      return;
    }
    this.failure.set(null);
    this.replace(line, { quantity });
  }

  setNote(line: DraftLine, value: string): void {
    this.replace(line, { note: value.slice(0, NOTE_MAX) });
  }

  remove(line: DraftLine): void {
    const d = this.draft();
    this.update({ ...d, lines: d.lines.filter((l) => l.key !== line.key) });
  }

  discard(): void {
    if (!confirm('Descartar o rascunho desta comanda? Os itens não enviados serão perdidos.')) {
      return;
    }
    this.update(emptyDraft());
    this.notice.set('Rascunho descartado.');
  }

  /** Envia o pedido inteiro; sem resposta, guarda a chave para conferir e reenviar sem duplicar. */
  send(): void {
    const d = this.draft();
    if (d.lines.length === 0) {
      return;
    }
    const key = d.pendingKey ?? newKey();
    this.update({ ...d, pendingKey: key });
    const lines = d.lines.map((l) => ({
      productId: l.productId,
      quantity: l.quantity,
      note: l.note.trim() || null,
      expectedPrice: l.price,
    }));
    this.run(this.service.send(this.tabId, key, lines).pipe(timeout(SEND_TIMEOUT_MS)), {
      next: (sent) => {
        this.update(emptyDraft());
        this.notice.set(
          sent.replayed
            ? 'Este pedido já tinha chegado; nada foi duplicado.'
            : `Pedido enviado: ${sent.order.lines.length} ${sent.order.lines.length === 1 ? 'linha' : 'linhas'}.`,
        );
        this.loadOrders();
      },
      error: (error) => {
        const code = problemCode(error);
        if (code === 'PEDIDO_PRECISA_REVISAO') {
          const review = (error as { error: ReviewProblem }).error;
          this.update({
            lines: applyReview(this.draft().lines, review.unavailable ?? [], review.priceChanges ?? []),
            pendingKey: null,
          });
          this.failure.set(failureMessage(error));
          this.loadCatalog();
        } else if (code && DEFINITE_REFUSALS.has(code)) {
          this.update({ ...this.draft(), pendingKey: null });
          this.failure.set(failureMessage(error));
        } else {
          this.failure.set(
            'Sem resposta do servidor. O pedido pode ter chegado: confira antes de mudar o rascunho.',
          );
        }
      },
    });
  }

  /** Confere pela chave se o envio sem resposta chegou (especificação §4). */
  verify(): void {
    const key = this.draft().pendingKey;
    if (!key) {
      return;
    }
    this.run(this.service.operation(key), {
      next: () => {
        this.update(emptyDraft());
        this.notice.set('O pedido tinha chegado. Nada foi duplicado.');
        this.loadOrders();
      },
      error: (error) => {
        if (problemCode(error) === 'PEDIDO_NAO_ENCONTRADO') {
          this.failure.set(
            'O pedido não chegou. O rascunho continua aqui: use "Enviar de novo" (não duplica) ou descarte.',
          );
        } else {
          this.failure.set('Ainda sem resposta do servidor. Confira de novo quando a conexão voltar.');
        }
      },
    });
  }

  /** Total das unidades que contam nesta comanda (preço do envio × unidades), em reais. */
  protected activeTotal(unitPrice: string, units: number): string {
    return ((Math.round(Number(unitPrice) * 100) * units) / 100).toFixed(2);
  }

  /** O item como as correções o veem (enviado daqui ou recebido de outra comanda). */
  protected item(id: string, productName: string, inTab: number): TabItem {
    return { id, productName, inTab };
  }

  startCancel(line: TabItem): void {
    this.failure.set(null);
    this.transferring.set(null);
    this.cancelling.set({ line, quantity: 1, reason: '' });
  }

  setCancelQuantity(value: string): void {
    const c = this.cancelling();
    if (c) {
      this.cancelling.set({ ...c, quantity: Math.trunc(Number(value)) });
    }
  }

  setCancelReason(value: string): void {
    const c = this.cancelling();
    if (c) {
      this.cancelling.set({ ...c, reason: value });
    }
  }

  /**
   * Cancela unidades inteiras do item (H23, FR-20): a conta muda na hora; em preparo ou pronto, a produção é avisada e
   * dá ciência sem que a correção espere por ela.
   */
  confirmCancel(): void {
    const c = this.cancelling();
    if (!c) {
      return;
    }
    const reason = c.reason.trim();
    if (!Number.isInteger(c.quantity) || c.quantity < 1 || c.quantity > c.line.inTab) {
      this.failure.set(`Cancele de 1 a ${c.line.inTab} unidade(s).`);
      return;
    }
    if (reason.length < CANCEL_REASON_MIN || reason.length > CANCEL_REASON_MAX) {
      this.failure.set(`Informe o motivo, com ${CANCEL_REASON_MIN} a ${CANCEL_REASON_MAX} caracteres.`);
      return;
    }
    this.run(this.service.cancel(this.tabId, c.line.id, c.quantity, c.line.inTab, reason), {
      next: (done) => {
        this.cancelling.set(null);
        this.notice.set(
          `${c.quantity} × ${done.productName} cancelado(s); restam ${done.remainingInTab}.` +
            (done.requiresAck ? ' A produção foi avisada.' : ''),
        );
        this.loadOrders();
      },
      error: (error) => {
        this.failure.set(failureMessage(error));
        if (problemCode(error) !== 'DADOS_INVALIDOS') {
          this.cancelling.set(null);
        }
        this.loadOrders();
      },
    });
  }

  /** Abre a transferência do item e carrega as comandas abertas da unidade para escolher o destino. */
  startTransfer(line: TabItem): void {
    this.failure.set(null);
    this.cancelling.set(null);
    this.transferring.set({ line, toTabId: '', quantity: 1, reason: '' });
    this.tabs
      .active()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (tabs) => this.destinations.set(tabs.filter((t) => t.id !== this.tabId)),
        error: (error: unknown) => this.failure.set(failureMessage(error)),
      });
  }

  setTransferTarget(value: string): void {
    const t = this.transferring();
    if (t) {
      this.transferring.set({ ...t, toTabId: value });
    }
  }

  setTransferQuantity(value: string): void {
    const t = this.transferring();
    if (t) {
      this.transferring.set({ ...t, quantity: Math.trunc(Number(value)) });
    }
  }

  setTransferReason(value: string): void {
    const t = this.transferring();
    if (t) {
      this.transferring.set({ ...t, reason: value });
    }
  }

  /**
   * Transfere unidades inteiras do item para outra comanda aberta (H25, FR-17): as duas contas mudam juntas e a
   * produção continua a mesma, agora com os dois destinos.
   */
  confirmTransfer(): void {
    const t = this.transferring();
    if (!t) {
      return;
    }
    const reason = t.reason.trim();
    if (!t.toTabId) {
      this.failure.set('Escolha a comanda de destino.');
      return;
    }
    if (!Number.isInteger(t.quantity) || t.quantity < 1 || t.quantity > t.line.inTab) {
      this.failure.set(`Transfira de 1 a ${t.line.inTab} unidade(s).`);
      return;
    }
    if (reason.length < CANCEL_REASON_MIN || reason.length > CANCEL_REASON_MAX) {
      this.failure.set(`Informe o motivo, com ${CANCEL_REASON_MIN} a ${CANCEL_REASON_MAX} caracteres.`);
      return;
    }
    const target = this.destinations().find((d) => d.id === t.toTabId);
    this.run(this.service.transfer(this.tabId, t.line.id, t.toTabId, t.quantity, t.line.inTab, reason), {
      next: (done) => {
        this.transferring.set(null);
        this.notice.set(
          `${done.quantity} × ${done.productName} transferido(s)` +
            (target ? ` para a comanda ${target.number} · ${target.name}` : '') +
            `; restam ${done.remainingInFrom} nesta.`,
        );
        this.loadOrders();
      },
      error: (error) => {
        this.failure.set(failureMessage(error));
        if (problemCode(error) !== 'DADOS_INVALIDOS') {
          this.transferring.set(null);
        }
        this.loadOrders();
      },
    });
  }

  retry(order: SentOrder): void {
    this.run(this.service.retryForwarding(order.id), {
      next: () => {
        this.notice.set('Encaminhamento à produção reenviado.');
        this.loadOrders();
      },
      error: (error) => {
        this.failure.set(failureMessage(error));
        this.loadOrders();
      },
    });
  }

  private loadCatalog(): void {
    this.catalog
      .load(0)
      .pipe(
        expand((page) =>
          (page.page + 1) * page.pageSize < page.totalProducts ? this.catalog.load(page.page + 1) : EMPTY,
        ),
        reduce((all, page) => [...all, ...page.products], [] as Product[]),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (products) => this.products.set(products),
        error: (error: unknown) => this.failure.set(failureMessage(error)),
      });
  }

  private replace(line: DraftLine, change: Partial<DraftLine>): void {
    const d = this.draft();
    this.update({ ...d, lines: d.lines.map((l) => (l.key === line.key ? { ...l, ...change } : l)) });
  }

  private update(draft: Draft): void {
    this.draft.set(draft);
    if (this.storageKey) {
      saveDraft(this.storageKey, draft);
    }
  }

  private run<T>(request: Observable<T>, handlers: { next: (value: T) => void; error: (e: unknown) => void }): void {
    this.busy.set(true);
    this.failure.set(null);
    this.notice.set(null);
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (value) => {
        this.busy.set(false);
        handlers.next(value);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        handlers.error(error);
      },
    });
  }
}
