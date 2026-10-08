import { Component } from '@angular/core';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterOutlet } from '@angular/router';

@Component({
  selector: 'fp-root',
  imports: [RouterOutlet, MatToolbarModule],
  template: `
    <header>
      <mat-toolbar>
        <span class="brand">fastPay</span>
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
    main {
      padding: 16px;
    }
  `,
})
export class App {}
