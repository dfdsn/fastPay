import { Component, computed, inject } from '@angular/core';
import { MatCardModule } from '@angular/material/card';

import { UnitContextService, roleLabel } from './unit-context.service';

/** Início dentro da unidade escolhida. As funções de cada perfil chegam nas próximas histórias. */
@Component({
  selector: 'fp-unit-home-page',
  imports: [MatCardModule],
  template: `
    <mat-card appearance="outlined">
      <mat-card-header>
        <mat-card-title><h1>Início</h1></mat-card-title>
      </mat-card-header>
      <mat-card-content>
        @if (unit(); as current) {
          <p>Você está em {{ current.name }} como {{ role() }}.</p>
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
  `,
})
export class UnitHomePage {
  protected readonly unit = inject(UnitContextService).current;
  protected readonly role = computed(() => {
    const current = this.unit();
    return current ? roleLabel(current.role) : '';
  });
}
