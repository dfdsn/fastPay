import { Component, DestroyRef, computed, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { RouterLink } from '@angular/router';

import { UnitContextService, can, roleLabel } from './unit-context.service';

/**
 * Início dentro da unidade escolhida. Ao abrir, confere no servidor o papel e as permissões atuais (H11), que
 * podem ter mudado durante a sessão; os caminhos aparecem conforme elas. As funções de operação chegam nas
 * próximas histórias.
 */
@Component({
  selector: 'fp-unit-home-page',
  imports: [MatButtonModule, MatCardModule, RouterLink],
  template: `
    <mat-card appearance="outlined">
      <mat-card-header>
        <mat-card-title><h1>Início</h1></mat-card-title>
      </mat-card-header>
      <mat-card-content>
        @if (unit(); as current) {
          <p>Você está em {{ current.name }} como {{ role() }}.</p>
          <nav aria-label="Administração da unidade" class="row">
            @if (canSeeTeam()) {
              <a mat-stroked-button routerLink="/equipe">Equipe</a>
            }
            @if (canAuthorizeSupport()) {
              <a mat-stroked-button routerLink="/acesso-de-suporte">Acesso de suporte</a>
            }
          </nav>
        }
      </mat-card-content>
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
    .row {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
  `,
})
export class UnitHomePage {
  private readonly context = inject(UnitContextService);
  protected readonly unit = this.context.current;
  protected readonly role = computed(() => {
    const current = this.unit();
    return current ? roleLabel(current.role) : '';
  });
  protected readonly canSeeTeam = computed(() => can(this.unit(), 'VER_EQUIPE'));
  protected readonly canAuthorizeSupport = computed(() => can(this.unit(), 'AUTORIZAR_SUPORTE'));

  constructor() {
    // Falha aqui é tratada pelo interceptor (unidade perdida volta à seleção); a tela segue com o que tem.
    this.context
      .refresh()
      .pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe({ error: () => undefined });
  }
}
