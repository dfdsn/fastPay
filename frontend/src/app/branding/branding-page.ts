import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Observable } from 'rxjs';

import { UnitContextService, can, failureMessage } from '../session/unit-context.service';
import { BrandingService, BrandingView } from './branding.service';
import { UnitLogo } from './unit-logo';

const MAX_IMAGE_BYTES = 5 * 1024 * 1024;

/**
 * Logo da unidade (H13, FR-67). O administrador inclui, substitui ou remove; sem logo, as telas mostram as iniciais
 * do nome. JPEG, PNG ou WebP até 5 MB; o servidor confere o conteúdo e regrava a imagem.
 */
@Component({
  selector: 'fp-branding-page',
  imports: [MatButtonModule, MatCardModule, MatProgressSpinnerModule, UnitLogo],
  template: `
    <h1>Logo da unidade</h1>
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
    @if (view(); as current) {
      <mat-card appearance="outlined">
        <mat-card-content>
          <div class="preview">
            <fp-unit-logo [name]="current.name" [logoFileId]="current.logoFileId" />
            <p>
              {{ current.name }}
              @if (!current.logoFileId) {
                <br /><span class="hint">Sem logo: as telas mostram as iniciais.</span>
              }
            </p>
          </div>
          @if (manage()) {
            <p class="hint">
              JPEG, PNG ou WebP de até 5 MB. Fundo transparente é preservado em PNG.
            </p>
            <div class="row">
              <label class="file">
                <span>{{ current.logoFileId ? 'Substituir logo' : 'Incluir logo' }}</span>
                <input
                  type="file"
                  accept="image/jpeg,image/png,image/webp"
                  [disabled]="busy()"
                  (change)="upload($event)"
                />
              </label>
              @if (current.logoFileId) {
                <button mat-stroked-button type="button" [disabled]="busy()" (click)="remove()">
                  Remover logo
                </button>
              }
            </div>
          } @else {
            <p class="hint">Só o administrador do estabelecimento altera o logo.</p>
          }
        </mat-card-content>
      </mat-card>
    }
  `,
  styles: `
    :host {
      display: block;
      max-width: 520px;
      margin: 0 auto;
      --fp-logo-size: 96px;
    }
    h1 {
      font: inherit;
      font-weight: 500;
    }
    .preview,
    .row {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;
      align-items: center;
    }
    .file {
      display: inline-flex;
      flex-direction: column;
      gap: 4px;
    }
    .hint {
      color: var(--mat-sys-on-surface-variant);
    }
    .error {
      color: var(--mat-sys-error);
    }
  `,
})
export class BrandingPage {
  private readonly service = inject(BrandingService);
  private readonly unit = inject(UnitContextService).current;
  private readonly destroyRef = inject(DestroyRef);

  protected readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  protected readonly view = signal<BrandingView | null>(null);
  protected readonly busy = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly failure = signal<string | null>(null);
  protected readonly manage = computed(() => can(this.unit(), 'CONFIGURAR_ESTABELECIMENTO'));

  constructor() {
    this.load();
  }

  protected load(): void {
    this.state.set('loading');
    this.service
      .load()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (view) => {
          this.view.set(view);
          this.state.set('ready');
        },
        error: () => this.state.set('error'),
      });
  }

  protected upload(event: Event): void {
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
    this.run(this.service.setLogo(file), 'Logo salvo.');
  }

  protected remove(): void {
    this.run(this.service.removeLogo(), 'Logo removido. As telas mostram as iniciais da unidade.');
  }

  private run(action: Observable<BrandingView>, success: string): void {
    this.busy.set(true);
    this.notice.set(null);
    this.failure.set(null);
    action.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (view) => {
        this.busy.set(false);
        this.view.set(view);
        this.notice.set(success);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.failure.set(failureMessage(error));
      },
    });
  }
}
