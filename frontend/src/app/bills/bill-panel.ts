import { DatePipe } from '@angular/common';
import { Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Observable } from 'rxjs';

import { reais } from '../orders/order-draft';
import { failureMessage } from '../session/unit-context.service';
import {
  Bill,
  BillsService,
  COUVERT_REASONS,
  CouvertReason,
  DiscountKind,
  couvertNote,
  percentText,
  serviceLabel,
} from './bills.service';

/**
 * Conta da comanda para a equipe (H22, PRD §10, FR-28–FR-30, FR-68): consumo, desconto, couvert, serviço e total
 * separados, como o servidor calculou. Quem tem "Conceder desconto" concede em reais ou percentual, com motivo, dentro do
 * próprio limite; conceder de novo substitui. Desconto em revisão (o consumo caiu depois da concessão) bloqueia a
 * cobrança até alguém com limite revisar, trocar ou retirar. O serviço sai ou volta a pedido do cliente.
 *
 * Couvert (H26, FR-12, FR-29): um por visita. A tela mostra onde ele está cobrado, a dispensa ou a correção pendente;
 * inclui o manual com o valor vigente; quem pode cancela com motivo e, depois de uma correção, inclui o valor correto.
 */
@Component({
  selector: 'fp-bill-panel',
  imports: [DatePipe, ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <section aria-labelledby="conta-titulo">
      <h2 id="conta-titulo">Conta</h2>
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
        <dl class="totals">
          <dt>Consumo</dt>
          <dd>{{ money(b.consumption) }}</dd>
          @if (b.discount; as d) {
            <dt>Desconto ({{ d.kind === 'FIXO' ? money(d.value) : percent(d.value) }})</dt>
            <dd>− {{ money(d.amount) }}</dd>
          }
          @if (b.couvert !== '0.00') {
            <dt>Couvert</dt>
            <dd>{{ money(b.couvert) }}</dd>
          }
          @if (b.service.enabled) {
            <dt>{{ serviceText(b) }}{{ b.service.waived ? ' · retirado pelo cliente' : '' }}</dt>
            <dd>{{ money(b.service.amount) }}</dd>
          }
          <dt class="total">Total</dt>
          <dd class="total">{{ money(b.total) }}</dd>
        </dl>

        @if (b.discount; as d) {
          <div class="discount" [class.flagged]="d.needsReview">
            <p>
              Desconto de {{ d.grantedBy.name ?? 'alguém da equipe' }} às {{ d.grantedAt | date: 'HH:mm' }}: "{{
                d.reason
              }}". Hoje vale {{ percent(d.effectivePercent) }} do consumo.
              @if (d.reviewedBy) {
                Revisado por {{ d.reviewedBy.name ?? 'alguém da equipe' }}.
              }
            </p>
            @if (d.needsReview) {
              <p class="error">
                O consumo caiu e o desconto passou do limite de quem concedeu ({{ percent(d.authorityPercent) }}). A
                cobrança fica bloqueada até alguém com limite de pelo menos {{ percent(d.effectivePercent) }} revisar,
                trocar ou retirar o desconto.
              </p>
            }
            @if (b.policy.canGrant) {
              <div class="row">
                @if (d.needsReview) {
                  <button mat-flat-button type="button" [disabled]="busy()" (click)="review()">
                    Revisar e manter desconto
                  </button>
                }
                <mat-form-field appearance="outline" class="grow">
                  <mat-label>Motivo para retirar</mat-label>
                  <input matInput [formControl]="removeReason" maxlength="300" />
                </mat-form-field>
                <button mat-stroked-button type="button" [disabled]="busy()" (click)="remove()">
                  Retirar desconto
                </button>
              </div>
            }
          </div>
        }

        @if (showCouvert(b)) {
        <div class="couvert" aria-labelledby="couvert-titulo">
          <h3 id="couvert-titulo">Couvert</h3>
          @if (note(b); as text) {
            <p>{{ text }}</p>
          }
          @if (b.couvertDetail.state === 'COBRADO') {
            <p>{{ money(b.couvertDetail.amount) }} {{ originText(b) }}.</p>
          } @else if (b.couvertDetail.state === 'SEM_COUVERT') {
            <p class="muted">
              {{ b.couvertDetail.mode === 'MANUAL' ? 'Couvert lançado pela equipe: ainda não incluído.' : 'Sem couvert.' }}
            </p>
          }
          @for (h of b.couvertDetail.history; track h.id) {
            <p class="muted">{{ historyText(h) }}</p>
          }
          @if (b.couvertDetail.canInclude) {
            <button mat-stroked-button type="button" [disabled]="busy()" (click)="includeCouvert()">
              Incluir couvert ({{ money(b.couvertDetail.includeAmount ?? '0.00') }})
            </button>
          }
          @if (b.couvertDetail.canCancel) {
            <form [formGroup]="cancelForm" (ngSubmit)="cancelCouvert()" aria-label="Cancelar couvert">
              <div class="row">
                <mat-form-field appearance="outline">
                  <mat-label>Motivo do cancelamento</mat-label>
                  <select matNativeControl formControlName="reasonKind">
                    @for (r of reasons; track r.value) {
                      <option [value]="r.value">{{ r.label }}</option>
                    }
                  </select>
                  <mat-hint>{{ reasonHint() }}</mat-hint>
                </mat-form-field>
                <mat-form-field appearance="outline" class="grow">
                  <mat-label>Explique</mat-label>
                  <input matInput formControlName="reason" maxlength="300" />
                </mat-form-field>
                <button mat-stroked-button type="submit" [disabled]="busy()">Cancelar couvert</button>
              </div>
            </form>
          }
          @if (b.couvertDetail.canCorrect) {
            <form [formGroup]="correctionForm" (ngSubmit)="correctCouvert()" aria-label="Incluir correção do couvert">
              <div class="row">
                <mat-form-field appearance="outline">
                  <mat-label>Valor correto (R$)</mat-label>
                  <input matInput formControlName="amount" inputmode="decimal" />
                </mat-form-field>
                <mat-form-field appearance="outline" class="grow">
                  <mat-label>Motivo da correção</mat-label>
                  <input matInput formControlName="reason" maxlength="300" />
                </mat-form-field>
                <button mat-flat-button type="submit" [disabled]="busy()">Incluir correção</button>
              </div>
            </form>
          }
        </div>
        }

        @if (b.open && b.service.enabled) {
          <button mat-stroked-button type="button" [disabled]="busy()" (click)="toggleService(b)">
            {{ b.service.waived ? 'Devolver o serviço à conta' : 'Retirar o serviço a pedido do cliente' }}
          </button>
        }

        @if (b.policy.canGrant && (b.policy.fixedAllowed || b.policy.percentAllowed)) {
          <form [formGroup]="form" (ngSubmit)="grant()" aria-labelledby="desconto-titulo">
            <h3 id="desconto-titulo">{{ b.discount ? 'Trocar desconto' : 'Conceder desconto' }}</h3>
            <p class="muted">Seu limite: {{ percent(b.policy.myLimitPercent) }} do consumo. Nunca sobre o couvert.</p>
            <div class="row">
              <mat-form-field appearance="outline">
                <mat-label>Formato</mat-label>
                <select matNativeControl formControlName="kind">
                  @if (b.policy.fixedAllowed) {
                    <option value="FIXO">Em reais</option>
                  }
                  @if (b.policy.percentAllowed) {
                    <option value="PERCENTUAL">Percentual</option>
                  }
                </select>
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>{{ form.controls.kind.value === 'FIXO' ? 'Valor (R$)' : 'Percentual (%)' }}</mat-label>
                <input matInput formControlName="value" inputmode="decimal" />
              </mat-form-field>
            </div>
            <mat-form-field appearance="outline" class="wide">
              <mat-label>Motivo</mat-label>
              <input matInput formControlName="reason" maxlength="300" />
              <mat-hint>De 5 a 300 caracteres.</mat-hint>
            </mat-form-field>
            <button mat-flat-button type="submit" [disabled]="busy()">
              {{ b.discount ? 'Trocar desconto' : 'Conceder desconto' }}
            </button>
          </form>
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
    .row {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      align-items: center;
    }
    .grow {
      flex: 1 1 200px;
    }
    .wide {
      width: 100%;
    }
    .couvert,
    .discount {
      padding: 8px;
      border: 1px solid rgba(0, 0, 0, 0.12);
      border-radius: 4px;
      margin: 8px 0;
    }
    .flagged {
      background: rgba(179, 38, 30, 0.06);
    }
    .error {
      color: #b3261e;
    }
    .muted {
      color: rgba(0, 0, 0, 0.6);
    }
  `,
})
export class BillPanel {
  private readonly service = inject(BillsService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly forms = inject(FormBuilder).nonNullable;

  readonly tabId = input.required<string>();
  /** Muda a cada aviso de pedido ou de conta desta comanda: relê a conta. */
  readonly refresh = input(0);

  protected readonly bill = signal<Bill | null>(null);
  protected readonly busy = signal(false);
  protected readonly failure = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly form = this.forms.group({
    kind: this.forms.control<DiscountKind>('FIXO'),
    value: this.forms.control('', Validators.required),
    reason: this.forms.control('', [Validators.required, Validators.minLength(5), Validators.maxLength(300)]),
  });
  protected readonly removeReason = this.forms.control('');
  protected readonly reasons = COUVERT_REASONS;
  protected readonly cancelForm = this.forms.group({
    reasonKind: this.forms.control<CouvertReason>('INDEVIDO'),
    reason: this.forms.control(''),
  });
  protected readonly correctionForm = this.forms.group({
    amount: this.forms.control(''),
    reason: this.forms.control(''),
  });

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

  protected percent(value: string): string {
    return percentText(value);
  }

  protected serviceText(bill: Bill): string {
    return serviceLabel(bill.service);
  }

  /** Comanda aberta sem couvert e sem nada na visita: a seção não aparece. */
  protected showCouvert(bill: Bill): boolean {
    const c = bill.couvertDetail;
    return c.mode !== null || c.state !== 'SEM_COUVERT' || c.history.length > 0;
  }

  protected note(bill: Bill): string | null {
    return couvertNote(bill.couvertDetail.state, bill.couvertDetail.chargedOnTab);
  }

  protected originText(bill: Bill): string {
    switch (bill.couvertDetail.origin) {
      case 'MANUAL':
        return 'incluído pela equipe';
      case 'CORRECAO':
        return 'incluído como correção';
      default:
        return 'automático, da configuração na abertura';
    }
  }

  protected historyText(h: Bill['couvertDetail']['history'][number]): string {
    const who = h.endedBy.name ?? 'alguém da equipe';
    if (h.endKind === 'NAO_REPETIDO') {
      return `Couvert de ${reais(h.amount)} não repetido: a comanda continua uma visita (${who}).`;
    }
    const reason = COUVERT_REASONS.find((r) => r.value === h.reasonKind)?.label ?? '';
    const verb = h.endKind === 'DEVOLVIDO' ? 'devolvido' : 'cancelado';
    return `Couvert de ${reais(h.amount)} ${verb} por ${who}: ${reason}${h.reason ? ` ("${h.reason}")` : ''}.`;
  }

  protected reasonHint(): string {
    return COUVERT_REASONS.find((r) => r.value === this.cancelForm.controls.reasonKind.value)?.hint ?? '';
  }

  includeCouvert(): void {
    this.run(this.service.includeCouvert(this.tabId()), 'Couvert incluído.');
  }

  cancelCouvert(): void {
    const chargeId = this.bill()?.couvertDetail.chargeId;
    const value = this.cancelForm.getRawValue();
    const reason = value.reason.trim();
    if (!chargeId) {
      return;
    }
    if (reason.length < 5) {
      this.failure.set('Explique por que o couvert sai, em 5 a 300 caracteres.');
      return;
    }
    this.run(this.service.cancelCouvert(this.tabId(), chargeId, value.reasonKind, reason), 'Couvert cancelado.', () =>
      this.cancelForm.reset(),
    );
  }

  correctCouvert(): void {
    const value = this.correctionForm.getRawValue();
    const amount = value.amount.trim().replace(',', '.');
    const reason = value.reason.trim();
    if (amount === '') {
      this.failure.set('Informe o valor correto do couvert, de R$ 0,01 a R$ 200,00.');
      return;
    }
    if (reason.length < 5) {
      this.failure.set('Explique a correção em 5 a 300 caracteres.');
      return;
    }
    this.run(this.service.correctCouvert(this.tabId(), amount, reason), 'Couvert corrigido.', () =>
      this.correctionForm.reset(),
    );
  }

  grant(): void {
    const value = this.form.getRawValue();
    const reason = value.reason.trim();
    if (reason.length < 5) {
      this.failure.set('Explique o motivo do desconto em 5 a 300 caracteres.');
      return;
    }
    if (value.value.trim() === '') {
      this.failure.set('Informe o valor do desconto.');
      return;
    }
    const kind = this.allowedKind(value.kind);
    this.run(
      this.service.grant(this.tabId(), this.bill()?.discount?.id ?? null, kind, value.value.trim(), reason),
      'Desconto concedido.',
      () => this.form.reset({ kind }),
    );
  }

  remove(): void {
    const discount = this.bill()?.discount;
    const reason = this.removeReason.value.trim();
    if (!discount) {
      return;
    }
    if (reason.length < 5) {
      this.failure.set('Explique por que o desconto sai, em 5 a 300 caracteres.');
      return;
    }
    this.run(this.service.remove(this.tabId(), discount.id, reason), 'Desconto retirado.', () =>
      this.removeReason.reset(),
    );
  }

  review(): void {
    const discount = this.bill()?.discount;
    if (discount) {
      this.run(this.service.review(this.tabId(), discount.id), 'Desconto revisado: a cobrança está liberada.');
    }
  }

  toggleService(bill: Bill): void {
    const waived = !bill.service.waived;
    this.run(
      this.service.service(this.tabId(), waived),
      waived ? 'Serviço retirado da conta.' : 'Serviço devolvido à conta.',
    );
  }

  /** O formato escolhido, ou o único aceito pela configuração quando o outro não aparece no seletor. */
  private allowedKind(kind: DiscountKind): DiscountKind {
    const policy = this.bill()?.policy;
    if (kind === 'FIXO' && policy && !policy.fixedAllowed) {
      return 'PERCENTUAL';
    }
    if (kind === 'PERCENTUAL' && policy && !policy.percentAllowed) {
      return 'FIXO';
    }
    return kind;
  }

  private run(request: Observable<Bill>, done: string, after?: () => void): void {
    this.busy.set(true);
    this.failure.set(null);
    this.notice.set(null);
    request.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (bill) => {
        this.busy.set(false);
        this.bill.set(bill);
        this.notice.set(done);
        after?.();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.failure.set(failureMessage(error));
        // A conta pode ter mudado (desconto de outra pessoa, consumo): relê para a tela mostrar o atual.
        this.load(this.tabId());
      },
    });
  }

  private load(tabId: string): void {
    this.service
      .bill(tabId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (bill) => this.bill.set(bill),
        error: (error: unknown) => this.failure.set(failureMessage(error)),
      });
  }
}
