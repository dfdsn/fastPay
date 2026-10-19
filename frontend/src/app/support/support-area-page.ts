import { DatePipe } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { failureMessage, roleLabel } from '../session/unit-context.service';
import { ActiveSupportGrant, SupportService, SupportTeam } from './support.service';

type State = 'loading' | 'ready' | 'error';

/**
 * Área de suporte da equipe fastPay (H11, FR-66): unidades que o dono autorizou, com prazo e motivo, e consulta
 * só de leitura. Cada consulta é registrada para o dono; sem autorização vigente, o servidor recusa.
 */
@Component({
  selector: 'fp-support-area-page',
  imports: [DatePipe, MatButtonModule, MatCardModule, MatProgressSpinnerModule],
  template: `
    <h1>Suporte autorizado</h1>
    <div role="status" aria-live="polite">
      @if (state() === 'loading') {
        <mat-progress-spinner mode="indeterminate" diameter="32" aria-hidden="true" />
        <p>Carregando…</p>
      } @else if (state() === 'error') {
        <p>Não foi possível carregar. Verifique sua conexão e tente novamente.</p>
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
      @if (grants().length === 0) {
        <p>Nenhuma unidade autorizou seu acesso no momento.</p>
      } @else {
        <ul class="list" aria-label="Unidades autorizadas">
          @for (grant of grants(); track grant.id) {
            <li>
              <mat-card appearance="outlined">
                <mat-card-content>
                  <p class="name">{{ grant.establishmentName }}</p>
                  <p>Motivo: {{ grant.reason }}</p>
                  <p>Até {{ grant.expiresAt | date: 'dd/MM/yyyy HH:mm' }}</p>
                  <button mat-stroked-button type="button" (click)="consult(grant)">
                    Consultar equipe de {{ grant.establishmentName }}
                  </button>
                </mat-card-content>
              </mat-card>
            </li>
          }
        </ul>
      }
    }
    @if (consulted(); as view) {
      <section aria-labelledby="consulta">
        <h2 id="consulta">Equipe de {{ view.grant.establishmentName }} (somente leitura)</h2>
        <ul class="list">
          @for (member of view.team.members; track member.email) {
            <li>
              {{ member.displayName }} · {{ member.email }} · {{ label(member) }}
              @if (!member.active) {
                · desativado
              }
            </li>
          }
        </ul>
        @if (view.team.pendingInvitations.length > 0) {
          <h3>Convites em aberto</h3>
          <ul class="list">
            @for (invitation of view.team.pendingInvitations; track invitation.email) {
              <li>{{ invitation.email }} · {{ label(invitation) }}</li>
            }
          </ul>
        }
      </section>
    }
  `,
  styles: `
    :host {
      display: block;
      max-width: 720px;
      margin: 0 auto;
    }
    h1,
    h2,
    h3 {
      font: inherit;
      font-weight: 500;
    }
    .list {
      list-style: none;
      padding: 0;
      display: grid;
      gap: 8px;
    }
    .name {
      font-weight: 500;
    }
    .error {
      color: var(--mat-sys-error);
    }
  `,
})
export class SupportAreaPage {
  private readonly service = inject(SupportService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly state = signal<State>('loading');
  protected readonly grants = signal<ActiveSupportGrant[]>([]);
  protected readonly consulted = signal<{ grant: ActiveSupportGrant; team: SupportTeam } | null>(
    null,
  );
  protected readonly failure = signal<string | null>(null);

  constructor() {
    this.load();
  }

  protected label(item: { role: SupportTeam['members'][number]['role'] }): string {
    return roleLabel(item.role);
  }

  protected load(): void {
    this.state.set('loading');
    this.service
      .myGrants()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (grants) => {
          this.grants.set(grants);
          this.state.set('ready');
        },
        error: () => this.state.set('error'),
      });
  }

  protected consult(grant: ActiveSupportGrant): void {
    this.failure.set(null);
    this.consulted.set(null);
    this.service
      .team(grant.establishmentId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (team) => this.consulted.set({ grant, team }),
        error: (error: unknown) => this.failure.set(failureMessage(error)),
      });
  }
}
