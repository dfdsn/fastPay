import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Observable } from 'rxjs';

import {
  UnitContextService,
  can,
  failureMessage,
  problemCode,
} from '../session/unit-context.service';
import {
  CatalogListing,
  CatalogService,
  Product,
  ProductInput,
  Sector,
  fileUrl,
} from './catalog.service';

type State = 'loading' | 'ready' | 'error';

/** Mesmo limite do servidor, só para avisar antes de enviar; quem decide é o servidor. */
const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
const MONEY = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' });
const PRICE = /^\s*(R\$\s*)?(\d{1,3}(\.\d{3})*|\d+)([,.]\d{1,2})?\s*$/;

interface CategoryGroup {
  category: string;
  products: Product[];
}

/**
 * Catálogo da unidade (H13, FR-13, FR-14, FR-18, FR-67). Toda a equipe consulta (produção sem preços). O
 * administrador cadastra setores e produtos, troca a foto e arquiva; quem tem a permissão marca disponível ou
 * esgotado. Os botões seguem as permissões da sessão, mas é o servidor que autoriza; uma recusa aparece com a
 * mensagem dele.
 */
@Component({
  selector: 'fp-catalog-page',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
  ],
  template: `
    <h1>Catálogo</h1>
    <div role="status" aria-live="polite">
      @if (state() === 'loading') {
        <mat-progress-spinner mode="indeterminate" diameter="32" aria-hidden="true" />
        <p>Carregando…</p>
      } @else if (state() === 'error') {
        <p>Não foi possível carregar o catálogo. Verifique sua conexão e tente novamente.</p>
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
      <button mat-stroked-button type="button" (click)="load(page())">Tentar novamente</button>
    }

    @if (listing(); as data) {
      @if (manage()) {
        <mat-card appearance="outlined">
          <mat-card-header>
            <mat-card-title><h2>Setores de preparo</h2></mat-card-title>
          </mat-card-header>
          <mat-card-content>
            <p class="hint">
              Cada produto vai para um setor (cozinha, bar...) ou é marcado como sem preparo. O
              atraso é o tempo desde o envio a partir do qual a fila de produção destaca o item (de
              1 a 240 minutos).
            </p>
            @if (data.sectors.length === 0) {
              <p>Nenhum setor cadastrado.</p>
            }
            <ul class="list" aria-label="Setores">
              @for (sector of data.sectors; track sector.id) {
                <li class="row">
                  <mat-form-field appearance="outline">
                    <mat-label>Nome do setor {{ sector.name }}</mat-label>
                    <input
                      matInput
                      [value]="sector.name"
                      maxlength="60"
                      (input)="sectorDraft.set({ id: sector.id, name: $any($event.target).value })"
                    />
                  </mat-form-field>
                  <button
                    mat-stroked-button
                    type="button"
                    [disabled]="busy() || !renamed(sector)"
                    (click)="renameSector(sector)"
                  >
                    Renomear {{ sector.name }}
                  </button>
                  <mat-form-field appearance="outline" class="minutes">
                    <mat-label>Atraso de {{ sector.name }} (min)</mat-label>
                    <input
                      matInput
                      type="number"
                      min="1"
                      max="240"
                      [value]="sector.delayLimitMinutes"
                      (input)="
                        delayDraft.set({ id: sector.id, minutes: $any($event.target).value })
                      "
                    />
                  </mat-form-field>
                  <button
                    mat-stroked-button
                    type="button"
                    [disabled]="busy() || !delayChanged(sector)"
                    (click)="changeSectorDelay(sector)"
                  >
                    Salvar atraso de {{ sector.name }}
                  </button>
                  <button
                    mat-stroked-button
                    type="button"
                    [disabled]="busy()"
                    (click)="archiveSector(sector)"
                  >
                    Arquivar {{ sector.name }}
                  </button>
                </li>
              }
            </ul>
            <form [formGroup]="sectorForm" (ngSubmit)="createSector()" novalidate class="row">
              <mat-form-field appearance="outline">
                <mat-label>Novo setor</mat-label>
                <input matInput formControlName="name" maxlength="60" autocomplete="off" />
                @if (sectorForm.controls.name.hasError('required')) {
                  <mat-error>Informe o nome do setor.</mat-error>
                }
              </mat-form-field>
              <button mat-flat-button type="submit" [disabled]="busy()">Adicionar setor</button>
            </form>
          </mat-card-content>
        </mat-card>

        <mat-card appearance="outlined">
          <mat-card-header>
            <mat-card-title>
              <h2>{{ editing() ? 'Editar ' + editing()!.name : 'Novo produto' }}</h2>
            </mat-card-title>
          </mat-card-header>
          <mat-card-content>
            <p class="hint">
              Tamanhos e versões são produtos distintos (ex.: Chopp 300 ml e Chopp 500 ml).
            </p>
            <form [formGroup]="productForm" (ngSubmit)="saveProduct()" novalidate>
              <mat-form-field appearance="outline">
                <mat-label>Nome</mat-label>
                <input matInput formControlName="name" maxlength="120" autocomplete="off" />
                @if (productForm.controls.name.hasError('required')) {
                  <mat-error>Informe o nome do produto.</mat-error>
                }
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Categoria</mat-label>
                <input
                  matInput
                  formControlName="category"
                  maxlength="60"
                  autocomplete="off"
                  list="fp-categorias"
                />
                <datalist id="fp-categorias">
                  @for (category of data.categories; track category) {
                    <option [value]="category"></option>
                  }
                </datalist>
                @if (productForm.controls.category.hasError('required')) {
                  <mat-error>Informe a categoria.</mat-error>
                }
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Preço (R$)</mat-label>
                <input matInput formControlName="price" inputmode="decimal" autocomplete="off" />
                <mat-hint>Ex.: 12,50</mat-hint>
                @if (productForm.controls.price.invalid) {
                  <mat-error
                    >Informe o preço maior que zero, com até duas casas (ex.: 12,50).</mat-error
                  >
                }
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Setor de preparo</mat-label>
                <select matNativeControl formControlName="sectorId">
                  <option value="">Sem preparo (não vai para a produção)</option>
                  @for (sector of data.sectors; track sector.id) {
                    <option [value]="sector.id">{{ sector.name }}</option>
                  }
                </select>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Descrição (opcional)</mat-label>
                <textarea
                  matInput
                  formControlName="description"
                  maxlength="500"
                  rows="2"
                ></textarea>
              </mat-form-field>
              <div class="row">
                <button mat-flat-button type="submit" [disabled]="busy()">
                  {{ editing() ? 'Salvar alterações' : 'Adicionar produto' }}
                </button>
                @if (editing()) {
                  <button mat-stroked-button type="button" (click)="cancelEdit()">
                    Cancelar edição
                  </button>
                }
              </div>
            </form>
          </mat-card-content>
        </mat-card>
      }

      <h2>Produtos</h2>
      @if (data.products.length === 0) {
        <p>
          Nenhum produto cadastrado ainda.
          @if (manage()) {
            Use o formulário acima para adicionar o primeiro.
          }
        </p>
      }
      @for (group of groups(); track group.category) {
        <h3>{{ group.category }}</h3>
        <ul class="list" [attr.aria-label]="'Produtos de ' + group.category">
          @for (product of group.products; track product.id) {
            <li>
              <mat-card appearance="outlined" [class.sold-out]="!product.available">
                <mat-card-content class="product">
                  @if (product.photoFileId) {
                    <img
                      class="photo"
                      [src]="url(product.photoFileId)"
                      [alt]="'Foto de ' + product.name"
                    />
                  }
                  <div class="details">
                    <p class="name">
                      {{ product.name }}
                      @if (!product.available) {
                        <span class="badge">Esgotado</span>
                      }
                    </p>
                    @if (data.pricesVisible && product.price !== null) {
                      <p>{{ brl(product.price) }}</p>
                    }
                    <p class="hint">{{ sectorLabel(product) }}</p>
                    @if (product.description) {
                      <p>{{ product.description }}</p>
                    }
                    <div class="row">
                      @if (changeAvailability()) {
                        <button
                          mat-stroked-button
                          type="button"
                          [disabled]="busy()"
                          (click)="toggleAvailability(product)"
                        >
                          {{
                            product.available ? 'Marcar como esgotado' : 'Marcar como disponível'
                          }}:
                          {{ product.name }}
                        </button>
                      }
                      @if (manage()) {
                        <button
                          mat-stroked-button
                          type="button"
                          [disabled]="busy()"
                          (click)="edit(product)"
                        >
                          Editar {{ product.name }}
                        </button>
                        <label class="file">
                          <span
                            >{{ product.photoFileId ? 'Trocar foto de' : 'Incluir foto de' }}
                            {{ product.name }}</span
                          >
                          <input
                            type="file"
                            accept="image/jpeg,image/png,image/webp"
                            [disabled]="busy()"
                            (change)="uploadPhoto(product, $event)"
                          />
                        </label>
                        @if (product.photoFileId) {
                          <button
                            mat-stroked-button
                            type="button"
                            [disabled]="busy()"
                            (click)="removePhoto(product)"
                          >
                            Remover foto de {{ product.name }}
                          </button>
                        }
                        <button
                          mat-stroked-button
                          type="button"
                          [disabled]="busy()"
                          (click)="archiveProduct(product)"
                        >
                          Arquivar {{ product.name }}
                        </button>
                      }
                    </div>
                  </div>
                </mat-card-content>
              </mat-card>
            </li>
          }
        </ul>
      }
      @if (pages() > 1) {
        <nav class="row" aria-label="Páginas do catálogo">
          <button
            mat-stroked-button
            type="button"
            [disabled]="page() === 0"
            (click)="load(page() - 1)"
          >
            Página anterior
          </button>
          <span>Página {{ page() + 1 }} de {{ pages() }}</span>
          <button
            mat-stroked-button
            type="button"
            [disabled]="page() + 1 >= pages()"
            (click)="load(page() + 1)"
          >
            Próxima página
          </button>
        </nav>
      }
    }
  `,
  styles: `
    :host {
      display: block;
      max-width: 760px;
      margin: 0 auto;
    }
    h1,
    h2,
    h3 {
      font: inherit;
      font-weight: 500;
    }
    mat-card {
      margin-bottom: 12px;
    }
    form {
      display: grid;
      gap: 8px;
    }
    .row {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      align-items: center;
    }
    .minutes {
      width: 160px;
    }
    .list {
      list-style: none;
      padding: 0;
      display: grid;
      gap: 8px;
    }
    .product {
      display: flex;
      gap: 12px;
      align-items: flex-start;
    }
    .photo {
      width: 96px;
      height: 96px;
      object-fit: cover;
      border-radius: 8px;
      flex: none;
    }
    .details {
      flex: 1;
      min-width: 0;
    }
    .name {
      font-weight: 500;
    }
    .badge {
      margin-left: 8px;
      padding: 2px 8px;
      border-radius: 12px;
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
      font-size: 0.85em;
    }
    .sold-out .name {
      text-decoration: none;
    }
    .file {
      display: inline-flex;
      flex-direction: column;
      gap: 4px;
      font-size: 0.9em;
    }
    .file input:focus-visible {
      outline: 2px solid var(--mat-sys-primary);
    }
    .hint {
      color: var(--mat-sys-on-surface-variant);
    }
    .error {
      color: var(--mat-sys-error);
    }
  `,
})
export class CatalogPage {
  private readonly service = inject(CatalogService);
  private readonly unit = inject(UnitContextService).current;
  private readonly destroyRef = inject(DestroyRef);
  private readonly forms = inject(FormBuilder).nonNullable;

  protected readonly state = signal<State>('loading');
  protected readonly listing = signal<CatalogListing | null>(null);
  protected readonly page = signal(0);
  protected readonly busy = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly failure = signal<string | null>(null);
  protected readonly editing = signal<Product | null>(null);
  protected readonly sectorDraft = signal<{ id: string; name: string } | null>(null);
  protected readonly delayDraft = signal<{ id: string; minutes: string } | null>(null);

  protected readonly manage = computed(() => can(this.unit(), 'GERIR_CATALOGO'));
  protected readonly changeAvailability = computed(() =>
    can(this.unit(), 'ALTERAR_DISPONIBILIDADE'),
  );
  protected readonly pages = computed(() => {
    const data = this.listing();
    return data ? Math.max(1, Math.ceil(data.totalProducts / data.pageSize)) : 1;
  });
  protected readonly groups = computed<CategoryGroup[]>(() => {
    const result: CategoryGroup[] = [];
    for (const product of this.listing()?.products ?? []) {
      const last = result.at(-1);
      if (
        last &&
        last.category.toLocaleLowerCase('pt-BR') === product.category.toLocaleLowerCase('pt-BR')
      ) {
        last.products.push(product);
      } else {
        result.push({ category: product.category, products: [product] });
      }
    }
    return result;
  });

  protected readonly sectorForm = this.forms.group({ name: ['', Validators.required] });
  protected readonly productForm = this.forms.group({
    name: ['', Validators.required],
    category: ['', Validators.required],
    price: ['', [Validators.required, Validators.pattern(PRICE)]],
    sectorId: [''],
    description: [''],
  });

  constructor() {
    this.load(0);
  }

  protected brl(value: number): string {
    return MONEY.format(value);
  }

  protected url(fileId: string): string {
    return fileUrl(fileId);
  }

  protected sectorLabel(product: Product): string {
    if (!product.sectorId) {
      return 'Sem preparo';
    }
    const sector = this.listing()?.sectors.find((s) => s.id === product.sectorId);
    return sector ? `Preparo: ${sector.name}` : 'Preparo: setor arquivado';
  }

  protected renamed(sector: Sector): boolean {
    const draft = this.sectorDraft();
    return draft?.id === sector.id && draft.name.trim() !== '' && draft.name.trim() !== sector.name;
  }

  protected load(page: number): void {
    this.state.set('loading');
    this.service
      .load(page)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (listing) => {
          this.listing.set(listing);
          this.page.set(listing.page);
          this.state.set('ready');
        },
        error: () => this.state.set('error'),
      });
  }

  protected createSector(): void {
    if (this.sectorForm.invalid) {
      this.sectorForm.markAllAsTouched();
      return;
    }
    const name = this.sectorForm.getRawValue().name.trim();
    this.run(this.service.createSector(name), `Setor ${name} adicionado.`, () =>
      this.sectorForm.reset({ name: '' }),
    );
  }

  protected renameSector(sector: Sector): void {
    const name = this.sectorDraft()?.name.trim() ?? sector.name;
    this.run(this.service.renameSector(sector, name), `Setor renomeado para ${name}.`, () =>
      this.sectorDraft.set(null),
    );
  }

  protected delayChanged(sector: Sector): boolean {
    const draft = this.delayDraft();
    return (
      draft?.id === sector.id &&
      draft.minutes !== '' &&
      Number(draft.minutes) !== sector.delayLimitMinutes
    );
  }

  protected changeSectorDelay(sector: Sector): void {
    const minutes = Number(this.delayDraft()?.minutes);
    if (!Number.isInteger(minutes) || minutes < 1 || minutes > 240) {
      this.failure.set('Informe o atraso em minutos inteiros, de 1 a 240.');
      return;
    }
    this.run(
      this.service.changeSectorDelay(sector, minutes),
      `Atraso de ${sector.name}: ${minutes} min.`,
      () => this.delayDraft.set(null),
    );
  }

  protected archiveSector(sector: Sector): void {
    this.run(this.service.archiveSector(sector), `Setor ${sector.name} arquivado.`);
  }

  protected saveProduct(): void {
    if (this.productForm.invalid) {
      this.productForm.markAllAsTouched();
      return;
    }
    const raw = this.productForm.getRawValue();
    const input: ProductInput = {
      name: raw.name.trim(),
      category: raw.category.trim(),
      price: raw.price.trim(),
      description: raw.description.trim(),
      sectorId: raw.sectorId || null,
    };
    const editing = this.editing();
    if (editing) {
      this.run(this.service.updateProduct(editing, input), `${input.name} atualizado.`, () =>
        this.cancelEdit(),
      );
    } else {
      this.run(this.service.createProduct(input), `${input.name} adicionado ao catálogo.`, () =>
        this.productForm.reset({
          name: '',
          category: raw.category,
          price: '',
          sectorId: raw.sectorId,
          description: '',
        }),
      );
    }
  }

  protected edit(product: Product): void {
    this.editing.set(product);
    this.productForm.reset({
      name: product.name,
      category: product.category,
      price: product.price === null ? '' : product.price.toFixed(2).replace('.', ','),
      sectorId: product.sectorId ?? '',
      description: product.description ?? '',
    });
  }

  protected cancelEdit(): void {
    this.editing.set(null);
    this.productForm.reset({ name: '', category: '', price: '', sectorId: '', description: '' });
  }

  protected archiveProduct(product: Product): void {
    this.run(this.service.archiveProduct(product), `${product.name} saiu do catálogo.`);
  }

  protected toggleAvailability(product: Product): void {
    const available = !product.available;
    this.run(
      this.service.setAvailability(product, available),
      available ? `${product.name} está disponível.` : `${product.name} marcado como esgotado.`,
    );
  }

  protected uploadPhoto(product: Product, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) {
      return;
    }
    if (file.size > MAX_IMAGE_BYTES) {
      this.notice.set(null);
      this.failure.set('A imagem passa de 5 MB. Envie um arquivo menor.');
      return;
    }
    this.run(this.service.setPhoto(product, file), `Foto de ${product.name} salva.`);
  }

  protected removePhoto(product: Product): void {
    this.run(this.service.removePhoto(product), `Foto de ${product.name} removida.`);
  }

  /** Executa a mudança e recarrega a página atual; versão desatualizada recarrega para conferir. */
  private run(action: Observable<unknown>, success: string, after?: () => void): void {
    this.busy.set(true);
    this.notice.set(null);
    this.failure.set(null);
    action.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.busy.set(false);
        this.notice.set(success);
        after?.();
        this.load(this.page());
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.failure.set(failureMessage(error));
        if (
          problemCode(error) === 'VERSAO_DESATUALIZADA' ||
          problemCode(error) === 'NAO_ENCONTRADO'
        ) {
          this.cancelEdit();
          this.load(this.page());
        }
      },
    });
  }
}
