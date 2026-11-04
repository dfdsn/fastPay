import { Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';

import { BillsService, GuestBill, serviceLabel } from '../bills/bills.service';
import { reais } from '../orders/order-draft';
import { failureMessage } from '../session/unit-context.service';

/**
 * Conta do cliente avulso (H22, PRD §10.2, FR-28): itens com valores, consumo, desconto, couvert, serviço e total,
 * como o servidor calculou. O serviço aparece incluído quando a casa cobra, e o cliente pode retirar (ou devolver) sem
 * pedir autorização, enquanto a conta está aberta.
 */
@Component({
  selector: 'fp-guest-bill',
  imports: [MatButtonModule],
  template: `
    <section aria-labelledby="conta-cliente-titulo">
      <h2 id="conta-cliente-titulo">Sua conta</h2>
      <div role="alert">
        @if (failure(); as message) {
          <p class="error">{{ message }}</p>
        }
      </div>
      <div role="status" aria-live="polite">
        @if (notice(); as message) {
          <p>{{ message }}</p>
        }
      </div>
      @if (bill(); as b) {
        @if (b.lines.length === 0) {
          <p>Nenhum pedido ainda. Os itens aparecem aqui quando o garçom enviar.</p>
        } @else {
          <ul class="lines">
            @for (line of b.lines; track line.orderLineId) {
              <li>
                <span>{{ line.quantity }} × {{ line.productName }}</span>
                <span>{{ money(line.lineTotal) }}</span>
              </li>
            }
          </ul>
        }
        <dl class="totals">
          <dt>Consumo</dt>
          <dd>{{ money(b.consumption) }}</dd>
          @if (b.discount) {
            <dt>Desconto</dt>
            <dd>− {{ money(b.discount) }}</dd>
          }
          @if (b.couvert !== '0.00') {
            <dt>Couvert</dt>
            <dd>{{ money(b.couvert) }}</dd>
          }
          @if (b.service.enabled) {
            <dt>{{ serviceText(b) }}</dt>
            <dd>{{ b.service.waived ? 'retirado' : money(b.service.amount) }}</dd>
          }
          <dt class="total">Total</dt>
          <dd class="total">{{ money(b.total) }}</dd>
        </dl>
        @if (b.open && b.service.enabled) {
          <p class="muted">O serviço é opcional: você pode retirar ou devolver quando quiser, antes de pagar.</p>
          <button mat-stroked-button type="button" [disabled]="busy()" (click)="toggleService(b)">
            {{ b.service.waived ? 'Incluir o serviço' : 'Retirar o serviço' }}
          </button>
        }
      }
    </section>
  `,
  styles: `
    h2 {
      font: inherit;
      font-weight: 500;
    }
    .lines {
      list-style: none;
      padding: 0;
    }
    .lines li {
      display: flex;
      justify-content: space-between;
      gap: 8px;
    }
    .lines li {
      padding: 4px 0;
      border-bottom: 1px solid rgba(0, 0, 0, 0.12);
    }
    .totals {
      display: grid;
      grid-template-columns: 1fr auto;
      gap: 4px 16px;
    }
    .totals dd {
      margin: 0;
      text-align: right;
    }
    .total {
      font-weight: 500;
    }
    .error {
      color: #b3261e;
    }
    .muted {
      color: rgba(0, 0, 0, 0.6);
    }
  `,
})
export class GuestBillPanel {
  private readonly service = inject(BillsService);
  private readonly destroyRef = inject(DestroyRef);

  readonly tabId = input.required<string>();
  /** Muda a cada aviso da comanda: relê a conta. */
  readonly refresh = input(0);

  protected readonly bill = signal<GuestBill | null>(null);
  protected readonly busy = signal(false);
  protected readonly failure = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);

  constructor() {
    effect(() => {
      this.refresh();
      const tabId = this.tabId();
      untracked(() => this.load(tabId));
    });
  }

  protected money(value: string): string {
    return reais(value);
  }

  protected serviceText(bill: GuestBill): string {
    return serviceLabel(bill.service);
  }

  toggleService(bill: GuestBill): void {
    const waived = !bill.service.waived;
    this.busy.set(true);
    this.failure.set(null);
    this.notice.set(null);
    this.service
      .guestService(this.tabId(), waived)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (updated) => {
          this.busy.set(false);
          this.bill.set(updated);
          this.notice.set(waived ? 'Serviço retirado da sua conta.' : 'Serviço incluído na sua conta.');
        },
        error: (error: unknown) => {
          this.busy.set(false);
          this.failure.set(failureMessage(error));
        },
      });
  }

  private load(tabId: string): void {
    this.service
      .guestBill(tabId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (bill) => this.bill.set(bill),
        error: (error: unknown) => this.failure.set(failureMessage(error)),
      });
  }
}
