import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { RouterLink } from '@angular/router';

import { BrandingService, BrandingView } from '../branding/branding.service';
import { UnitLogo } from '../branding/unit-logo';

import { UnitContextService, can, roleLabel } from './unit-context.service';

/**
 * Início dentro da unidade escolhida. Ao abrir, confere no servidor o papel e as permissões atuais (H11), que
 * podem ter mudado durante a sessão; os caminhos aparecem conforme elas. Mostra o logo da unidade ou as iniciais
 * (H13). Comandas desde H15; as demais funções de operação chegam nas próximas histórias.
 */
@Component({
  selector: 'fp-unit-home-page',
  imports: [MatButtonModule, MatCardModule, RouterLink, UnitLogo],
  template: `
    <mat-card appearance="outlined">
      <mat-card-header>
        <mat-card-title><h1>Início</h1></mat-card-title>
      </mat-card-header>
      <mat-card-content>
        @if (unit(); as current) {
          <div class="unit">
            <fp-unit-logo [name]="current.name" [logoFileId]="branding()?.logoFileId ?? null" />
            <p>Você está em {{ current.name }} como {{ role() }}.</p>
          </div>
          <nav aria-label="Administração da unidade" class="row">
            @if (canOperateTabs()) {
              <a mat-stroked-button routerLink="/comandas">Comandas</a>
            }
            <a mat-stroked-button routerLink="/catalogo">Catálogo</a>
            @if (canSeeSettings()) {
              <a mat-stroked-button routerLink="/configuracao">Configuração</a>
            }
            @if (canConfigure()) {
              <a mat-stroked-button routerLink="/logo">Logo da unidade</a>
            }
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
    .unit {
      display: flex;
      gap: 12px;
      align-items: center;
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
  protected readonly canOperateTabs = computed(() => can(this.unit(), 'OPERAR_COMANDA'));
  protected readonly canSeeTeam = computed(() => can(this.unit(), 'VER_EQUIPE'));
  protected readonly canAuthorizeSupport = computed(() => can(this.unit(), 'AUTORIZAR_SUPORTE'));
  protected readonly canSeeSettings = computed(() => can(this.unit(), 'VER_CONFIGURACAO'));
  protected readonly canConfigure = computed(() => can(this.unit(), 'CONFIGURAR_ESTABELECIMENTO'));
  protected readonly branding = signal<BrandingView | null>(null);

  constructor() {
    // Sem logo ou sem resposta: as iniciais do nome (FR-67).
    inject(BrandingService)
      .load()
      .pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe({ next: (view) => this.branding.set(view), error: () => undefined });
    // Falha aqui é tratada pelo interceptor (unidade perdida volta à seleção); a tela segue com o que tem.
    this.context
      .refresh()
      .pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe({ error: () => undefined });
  }
}
