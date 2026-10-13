import { Component, DestroyRef, computed, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatToolbarModule } from '@angular/material/toolbar';
import { Router, RouterLink, RouterOutlet } from '@angular/router';

import { AuthService } from './auth/auth.service';
import { UnitContextService, roleLabel } from './session/unit-context.service';

@Component({
  selector: 'fp-root',
  imports: [RouterOutlet, RouterLink, MatToolbarModule, MatButtonModule],
  template: `
    <header>
      <mat-toolbar>
        <span class="brand">fastPay</span>
        @if (unit(); as current) {
          <span class="unit">
            <span class="unit-label">Unidade ativa:</span>&nbsp;
            <span class="unit-name">{{ current.name }}</span
            >&nbsp;·&nbsp;<span class="unit-role">{{ role() }}</span>
          </span>
          <button mat-button type="button" (click)="switchUnit()">Trocar unidade</button>
        }
        @if (session(); as current) {
          <span class="person" [class.alone]="!unit()">{{ current.displayName }}</span>
          @if (current.stage === 'AUTENTICADO') {
            <a mat-button class="security" routerLink="/configurar-verificacao">Segurança</a>
          }
          <button mat-button type="button" class="sign-out" (click)="signOut()">Sair</button>
        }
      </mat-toolbar>
    </header>
    <main>
      <router-outlet />
    </main>
  `,
  styles: `
    .brand {
      font: var(--mat-sys-title-large);
    }
    .unit {
      margin-left: auto;
      margin-right: 8px;
      font: var(--mat-sys-body-medium);
    }
    .unit-name {
      font-weight: 500;
    }
    .person {
      margin-left: 8px;
      font: var(--mat-sys-body-medium);
    }
    .person.alone {
      margin-left: auto;
    }
    main {
      padding: 16px;
    }
  `,
})
export class App {
  private readonly context = inject(UnitContextService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly unit = this.context.current;
  protected readonly session = this.auth.session;
  protected readonly role = computed(() => {
    const current = this.unit();
    return current ? roleLabel(current.role) : '';
  });

  protected switchUnit(): void {
    this.context
      .leave()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => void this.router.navigate(['/unidades']),
        error: () => {
          // Mesmo sem resposta do servidor, nada da unidade anterior fica na tela.
          this.context.clear();
          void this.router.navigate(['/unidades']);
        },
      });
  }

  protected signOut(): void {
    this.auth
      .logout()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => void this.router.navigate(['/entrar']));
  }
}
