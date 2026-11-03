import { DatePipe } from '@angular/common';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { EMPTY, Observable, expand, reduce, timeout } from 'rxjs';

import { CatalogService, Product } from '../catalog/catalog.service';
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
import { Forwarding, OrdersService, ReviewProblem, SentOrder } from './orders.service';

type State = 'loading' | 'ready' | 'forbidden' | 'missing' | 'error';

/** Tempo máximo de espera do envio; depois disso o resultado é conferido antes de qualquer repetição. */
export const SEND_TIMEOUT_MS = 20_000;

/** Recusas definitivas do servidor: nada foi gravado e a chave do envio pode ser descartada. */
const DEFINITE_REFUSALS = new Set([
  'COMANDA_ENCERRADA',
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
  imports: [DatePipe, RouterLink, MatButtonModule, MatCardModule, MatProgressSpinnerModule],
  template: `
    <p><a routerLink="/comandas">Voltar às comandas</a></p>
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
                  {{ line.quantity }}× {{ line.productName }} · {{ money(line.unitPrice) }} cada ·
                  {{ money(line.lineTotal) }}
                  @if (line.note) {
                    <span class="muted"> · {{ line.note }}</span>
                  }
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
      <button mat-button type="button" (click)="loadOrders()">Atualizar pedidos</button>
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
  private readonly tabId = inject(ActivatedRoute).snapshot.paramMap.get('id') ?? '';
  private storageKey: string | null = null;

  protected readonly maxLines = MAX_LINES;
  protected readonly maxQuantity = MAX_QUANTITY;
  protected readonly noteMax = NOTE_MAX;

  protected readonly state = signal<State>('loading');
  protected readonly tab = signal<Tab | null>(null);
  protected readonly products = signal<Product[]>([]);
  protected readonly orders = signal<SentOrder[]>([]);
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
      this.products.set([]);
    });
    this.destroyRef.onDestroy(cancel);
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
        next: (orders) => this.orders.set(orders),
        error: (error: unknown) => this.failure.set(failureMessage(error)),
      });
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
