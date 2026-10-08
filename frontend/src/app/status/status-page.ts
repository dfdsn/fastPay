import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { ApplicationInfo, PlatformInfoService } from '../platform/platform-info.service';

type ConnectionState =
  | { kind: 'loading' }
  | { kind: 'ready'; info: ApplicationInfo }
  | { kind: 'error' };

@Component({
  selector: 'fp-status-page',
  imports: [MatCardModule, MatButtonModule, MatProgressSpinnerModule],
  template: `
    <mat-card appearance="outlined">
      <mat-card-header>
        <mat-card-title><h1>Situação do sistema</h1></mat-card-title>
      </mat-card-header>
      <mat-card-content>
        <div role="status" aria-live="polite">
          @switch (state().kind) {
            @case ('loading') {
              <mat-progress-spinner mode="indeterminate" diameter="32" aria-hidden="true" />
              <p>Verificando o servidor…</p>
            }
            @case ('ready') {
              @if (info(); as current) {
                <p>Servidor disponível.</p>
                <p>Versão {{ current.version }} · ambiente {{ current.environment }}</p>
              }
            }
            @case ('error') {
              <p>Não foi possível contatar o servidor. Verifique sua conexão e tente novamente.</p>
            }
          }
        </div>
      </mat-card-content>
      @if (state().kind === 'error') {
        <mat-card-actions>
          <button mat-flat-button type="button" (click)="load()">Tentar novamente</button>
        </mat-card-actions>
      }
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
export class StatusPage {
  private readonly platform = inject(PlatformInfoService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly state = signal<ConnectionState>({ kind: 'loading' });
  protected readonly info = computed(() => {
    const current = this.state();
    return current.kind === 'ready' ? current.info : null;
  });

  constructor() {
    this.load();
  }

  protected load(): void {
    this.state.set({ kind: 'loading' });
    this.platform
      .info()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (info) => this.state.set({ kind: 'ready', info }),
        error: () => this.state.set({ kind: 'error' }),
      });
  }
}
