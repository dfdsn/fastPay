import { DatePipe } from '@angular/common';
import { Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';

import { failureMessage } from '../session/unit-context.service';
import { GuestIssue, GuestIssues, GuestService, ISSUE_STATUS_LABEL } from './guest.service';

/** Valor do seletor para o problema da conta (sem item). */
const ACCOUNT = 'conta';
/** Valor do seletor para contestar o couvert (H26): problema da conta marcado. */
const COUVERT = 'couvert';

/**
 * Problemas do cliente avulso (H21, FR-24, FR-25, UJ-03): aponta um item da comanda ou a conta e conta o que aconteceu;
 * acompanha aberto, em atendimento e tratado, com a resolução da equipe. Com a conta já encerrada, o problema não
 * desfaz o pagamento: vai ao gerente. Não há botão de chamar atendimento (FR-26): ajuda geral é pessoalmente.
 * O couvert é contestado aqui (H26, FR-29), como problema da conta.
 */
@Component({
  selector: 'fp-guest-issues',
  imports: [DatePipe, ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatSelectModule],
  template: `
    <section aria-labelledby="problemas-titulo">
      <h2 id="problemas-titulo">Algum problema?</h2>
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
      @if (data(); as d) {
        @if (d.closed) {
          <p>
            Sua conta já foi encerrada. Se algo deu errado, conte aqui: o pagamento continua válido e o gerente
            decide se há devolução.
          </p>
        }
        <form [formGroup]="form" (ngSubmit)="report()">
          <mat-form-field appearance="outline" class="wide">
            <mat-label>Sobre o quê</mat-label>
            <select matNativeControl formControlName="target">
              <option value="conta">A conta</option>
              <option value="couvert">O couvert</option>
              @for (item of d.items; track item.orderLineId) {
                <option [value]="item.orderLineId">{{ item.quantity }} × {{ item.productName }}</option>
              }
            </select>
          </mat-form-field>
          <mat-form-field appearance="outline" class="wide">
            <mat-label>O que aconteceu</mat-label>
            <textarea matInput formControlName="description" maxlength="300" rows="2"></textarea>
            <mat-hint>De 5 a 300 caracteres.</mat-hint>
          </mat-form-field>
          <button mat-flat-button type="submit" [disabled]="busy()">Informar problema</button>
        </form>
        @if (d.issues.length > 0) {
          <h3>Seus problemas</h3>
          <ul class="issues">
            @for (issue of d.issues; track issue.id) {
              <li>
                <div>
                  <strong>{{ statusText(issue) }}</strong> ·
                  {{ issue.item ? (issue.item.productName ?? 'Item') : issue.aboutCouvert ? 'O couvert' : 'A conta' }} · às
                  {{ issue.createdAt | date: 'HH:mm' }}
                </div>
                <div class="description">"{{ issue.description }}"</div>
                @if (issue.status === 'TRATADO' && issue.resolution) {
                  <div>Resolução: {{ issue.resolution }}</div>
                } @else if (issue.afterClosure) {
                  <div>Encaminhado ao gerente.</div>
                }
              </li>
            }
          </ul>
        }
      }
    </section>
  `,
  styles: `
    h2,
    h3 {
      font: inherit;
      font-weight: 500;
    }
    .wide {
      width: 100%;
    }
    .issues {
      list-style: none;
      padding: 0;
      display: grid;
      gap: 8px;
    }
    .issues li {
      padding: 8px;
      border: 1px solid rgba(0, 0, 0, 0.12);
      border-radius: 4px;
    }
    .description {
      font-style: italic;
    }
    .error {
      color: #b3261e;
    }
  `,
})
export class GuestIssuesPanel {
  private readonly service = inject(GuestService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly forms = inject(FormBuilder).nonNullable;

  readonly tabId = input.required<string>();
  /** Muda a cada aviso da comanda: relê os problemas. */
  readonly refresh = input(0);

  protected readonly data = signal<GuestIssues | null>(null);
  protected readonly busy = signal(false);
  protected readonly failure = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly form = this.forms.group({
    target: this.forms.control(ACCOUNT),
    description: this.forms.control('', [Validators.required, Validators.minLength(5), Validators.maxLength(300)]),
  });

  constructor() {
    effect(() => {
      this.refresh();
      const tabId = this.tabId();
      untracked(() => this.load(tabId));
    });
  }

  protected statusText(issue: GuestIssue): string {
    return ISSUE_STATUS_LABEL[issue.status];
  }

  report(): void {
    const value = this.form.getRawValue();
    const description = value.description.trim();
    if (description.length < 5) {
      this.failure.set('Conte o que aconteceu em 5 a 300 caracteres.');
      return;
    }
    this.busy.set(true);
    this.failure.set(null);
    this.notice.set(null);
    this.service
      .report(
        this.tabId(),
        value.target === ACCOUNT || value.target === COUVERT ? null : value.target,
        description,
        value.target === COUVERT,
      )
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.form.reset();
          this.notice.set('Problema informado. A equipe vai até você.');
          this.load(this.tabId());
        },
        error: (error: unknown) => {
          this.busy.set(false);
          this.failure.set(failureMessage(error));
        },
      });
  }

  private load(tabId: string): void {
    this.service
      .issues(tabId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (data) => this.data.set(data),
        error: (error: unknown) => this.failure.set(failureMessage(error)),
      });
  }
}
