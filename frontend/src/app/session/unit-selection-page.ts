import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Router } from '@angular/router';

import {
  UnitContextService,
  UnitView,
  UnitsResponse,
  problemCode,
  roleLabel,
} from './unit-context.service';

type PageState =
  | { kind: 'loading' }
  | { kind: 'signed-out' }
  | { kind: 'error' }
  | { kind: 'ready'; data: UnitsResponse };

/** Escolha da unidade (H07, FR-03). Com um único vínculo, entra direto nele. */
@Component({
  selector: 'fp-unit-selection-page',
  imports: [MatCardModule, MatButtonModule, MatProgressSpinnerModule],
  template: `
    <mat-card appearance="outlined">
      <mat-card-header>
        <mat-card-title><h1>Escolha a unidade</h1></mat-card-title>
      </mat-card-header>
      <mat-card-content>
        <div role="status" aria-live="polite">
          @switch (state().kind) {
            @case ('loading') {
              <mat-progress-spinner mode="indeterminate" diameter="32" aria-hidden="true" />
              <p>Carregando suas unidades…</p>
            }
            @case ('signed-out') {
              <p>Sua sessão não está ativa. Entre novamente.</p>
            }
            @case ('error') {
              <p>
                Não foi possível carregar suas unidades. Verifique sua conexão e tente novamente.
              </p>
            }
          }
          @if (message(); as text) {
            <p class="message">{{ text }}</p>
          }
        </div>
        @if (ready(); as data) {
          @if (data.units.length === 0) {
            <p>Olá, {{ data.displayName }}. Você ainda não tem acesso a nenhuma unidade.</p>
          } @else {
            <p>Olá, {{ data.displayName }}. Em qual unidade você vai trabalhar?</p>
            <ul class="units" aria-label="Unidades disponíveis">
              @for (unit of data.units; track unit.establishmentId) {
                <li>
                  <button
                    mat-stroked-button
                    type="button"
                    [disabled]="selecting()"
                    (click)="choose(unit)"
                  >
                    <span class="name">{{ unit.name }}</span
                    >&nbsp;·&nbsp;<span class="role">{{ label(unit) }}</span>
                    @if (unit.establishmentId === data.currentEstablishmentId) {
                      <span class="current">(unidade atual)</span>
                    }
                  </button>
                </li>
              }
            </ul>
          }
        }
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
      max-width: 520px;
      margin: 0 auto;
    }
    h1 {
      font: inherit;
      margin: 0;
    }
    .units {
      list-style: none;
      padding: 0;
      margin: 0;
      display: grid;
      gap: 8px;
    }
    .units button {
      width: 100%;
      justify-content: flex-start;
      text-align: left;
    }
    .name {
      font-weight: 500;
    }
    .current {
      margin-left: 8px;
    }
  `,
})
export class UnitSelectionPage {
  private readonly context = inject(UnitContextService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly state = signal<PageState>({ kind: 'loading' });
  protected readonly selecting = signal(false);
  protected readonly message = signal<string | null>(null);

  constructor() {
    this.load();
  }

  protected readonly ready = computed(() => {
    const current = this.state();
    return current.kind === 'ready' ? current.data : null;
  });

  protected label(unit: UnitView): string {
    return roleLabel(unit.role);
  }

  /** Com {@code autoEnter}, um único vínculo entra direto; após uma recusa, a pessoa escolhe. */
  protected load(autoEnter = true): void {
    this.state.set({ kind: 'loading' });
    this.context
      .units()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => {
          this.state.set({ kind: 'ready', data });
          if (autoEnter && data.units.length === 1 && !data.currentEstablishmentId) {
            this.choose(data.units[0]);
          }
        },
        error: (error: unknown) =>
          this.state.set({
            kind:
              error instanceof HttpErrorResponse && error.status === 401 ? 'signed-out' : 'error',
          }),
      });
  }

  protected choose(unit: UnitView): void {
    this.selecting.set(true);
    this.message.set(null);
    this.context
      .select(unit.establishmentId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.selecting.set(false);
          void this.router.navigate(['/inicio']);
        },
        error: (error: unknown) => {
          this.selecting.set(false);
          if (problemCode(error) === 'UNIDADE_NAO_AUTORIZADA') {
            this.message.set('Você não tem mais acesso a esta unidade. Escolha outra.');
            this.load(false);
          } else if (error instanceof HttpErrorResponse && error.status === 401) {
            this.state.set({ kind: 'signed-out' });
          } else {
            this.message.set('Não foi possível entrar na unidade. Tente novamente.');
          }
        },
      });
  }
}
