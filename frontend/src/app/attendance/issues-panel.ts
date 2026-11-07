import { Component, DestroyRef, computed, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { Observable } from 'rxjs';

import { elapsed } from '../production/production.service';
import {
  UnitContextService,
  can,
  failureMessage,
  problemCode,
} from '../session/unit-context.service';
import { AttendanceService, Attendant, ConsumerIssue, Correction } from './attendance.service';

/** Recusas em que a fila é relida: outra pessoa atendeu, concluiu ou mudou o problema. */
const STALE = new Set([
  'PROBLEMA_JA_ASSUMIDO',
  'PROBLEMA_TRATADO',
  'PROBLEMA_NAO_ENCONTRADO',
  'VERSAO_DESATUALIZADA',
]);

const STATUS_LABEL: Record<ConsumerIssue['status'], string> = {
  ABERTO: 'Aberto',
  EM_ATENDIMENTO: 'Em atendimento',
  TRATADO: 'Tratado',
};

/**
 * Problemas do consumidor na fila de atendimento (H21, FR-24, FR-25, UJ-03). Quem atende começa o atendimento
 * (exclusivo na central), explica ou faz a correção pelo caminho próprio e conclui com a resolução, se tem a permissão;
 * senão encaminha ao gerente. Concluir não altera valores. O caso que chegou com a conta já paga vai ao gerente.
 */
@Component({
  selector: 'fp-issues-panel',
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, MatRadioModule],
  template: `
    <h2>Problemas do consumidor ({{ issues().length }})</h2>
    <div role="status" aria-live="polite">
      @if (notice(); as message) {
        <p>{{ message }}</p>
      }
    </div>
    <div role="alert">
      @if (failure(); as message) {
        <p class="error">{{ message }}</p>
      }
    </div>
    @if (issues().length === 0) {
      <p>Nenhum problema em aberto.</p>
    }
    <ul class="issues" aria-label="Problemas do consumidor">
      @for (issue of issues(); track issue.id) {
        <li [class.mine]="issue.mine" [class.paid]="issue.afterClosure">
          <div>
            <strong>{{ statusText(issue) }}</strong> · Comanda {{ issue.tab.number }} · {{ issue.tab.name }} ·
            {{ issue.tab.table ? 'mesa ' + issue.tab.table : 'sem mesa' }}
          </div>
          <div>
            {{
              issue.item
                ? 'Item: ' + (issue.item.productName ?? 'item da comanda')
                : issue.aboutCouvert
                  ? 'Contesta o couvert (quem pode cancelar o couvert faz isso na conta da comanda)'
                  : 'Sobre a conta'
            }}
          </div>
          <div class="description">"{{ issue.description }}"</div>
          @if (issue.afterClosure) {
            <div class="problem">
              A conta já estava encerrada: o pagamento continua válido. Só o gerente conclui, e decide se há devolução.
            </div>
          } @else if (issue.forwarded) {
            <div class="problem">Encaminhado ao gerente.</div>
          }
          <div>
            Há {{ since(issue.createdAt) }} ·
            @if (issue.owner) {
              {{ issue.mode === 'CENTRAL' ? 'Com' : 'Responsável:' }} {{ issue.owner.name }}
            } @else {
              Central, sem ninguém
            }
            @if (issue.attendedBy && issue.attendedBy.id !== issue.owner?.id) {
              · Atendimento começado por {{ issue.attendedBy.name }}
            }
          </div>
          <div class="row">
            @if (canAttend() && attendable(issue)) {
              <button mat-flat-button type="button" [disabled]="busy()" (click)="attend(issue)">
                Atender
              </button>
            }
            @if (canAttend() && issue.status === 'EM_ATENDIMENTO' && !issue.forwarded) {
              <button mat-stroked-button type="button" [disabled]="busy()" (click)="forward(issue)">
                Encaminhar ao gerente
              </button>
            }
            @if (canConclude() && issue.status === 'EM_ATENDIMENTO') {
              <button mat-stroked-button type="button" [disabled]="busy()" (click)="startConclude(issue)">
                Concluir como tratado
              </button>
            }
            @if (canReassign() && issue.mode === 'CENTRAL') {
              <button mat-button type="button" [disabled]="busy()" (click)="startReassign(issue)">
                Reatribuir
              </button>
            }
          </div>
          @if (concluding()?.id === issue.id) {
            <form
              [formGroup]="conclusion"
              (ngSubmit)="conclude(issue)"
              [attr.aria-label]="'Concluir problema da comanda ' + issue.tab.number"
            >
              <p>
                Concluir não altera valores. Faça antes a correção necessária (cancelamento, desconto ou
                transferência); se ela exige uma autorização que você não tem, encaminhe ao gerente.
              </p>
              <mat-radio-group formControlName="correction" aria-label="Correção" class="column">
                <mat-radio-button value="SEM_CORRECAO">Explicado, nada a corrigir</mat-radio-button>
                <mat-radio-button value="CORRECAO_REALIZADA">A correção já foi feita</mat-radio-button>
              </mat-radio-group>
              <mat-form-field appearance="outline" class="wide">
                <mat-label>Resolução</mat-label>
                <textarea matInput formControlName="resolution" maxlength="300" rows="2"></textarea>
                <mat-hint>De 5 a 300 caracteres. O cliente vê este texto.</mat-hint>
              </mat-form-field>
              <div class="row">
                <button mat-flat-button type="submit" [disabled]="busy()">Concluir</button>
                <button mat-button type="button" (click)="concluding.set(null)">Cancelar</button>
              </div>
            </form>
          }
          @if (reassigning()?.id === issue.id) {
            <div class="row" role="group" [attr.aria-label]="'Reatribuir problema da comanda ' + issue.tab.number">
              @for (person of attendants(); track person.id) {
                @if (person.id !== issue.owner?.id) {
                  <button mat-stroked-button type="button" [disabled]="busy()" (click)="reassign(issue, person)">
                    Passar para {{ person.displayName }}
                  </button>
                }
              }
              <button mat-button type="button" (click)="reassigning.set(null)">Cancelar</button>
            </div>
          }
        </li>
      }
    </ul>
  `,
  styles: `
    .row {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin-top: 8px;
      align-items: center;
    }
    .column {
      display: flex;
      flex-direction: column;
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
      padding: 12px;
      border: 1px solid rgba(0, 0, 0, 0.12);
      border-radius: 4px;
    }
    .issues li.mine {
      border: 2px solid #1a73e8;
    }
    .issues li.paid {
      background: #fff4e5;
    }
    .description {
      font-style: italic;
    }
    .error,
    .problem {
      color: #b3261e;
    }
    h2 {
      font: inherit;
      font-weight: 500;
    }
  `,
})
export class IssuesPanel {
  private readonly service = inject(AttendanceService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly unit = inject(UnitContextService).current;
  private readonly forms = inject(FormBuilder).nonNullable;

  /** Problemas ainda não tratados, da leitura da fila. */
  readonly issues = input.required<ConsumerIssue[]>();
  /** Instante do servidor, para contar os tempos. */
  readonly now = input.required<number>();
  /** Algo mudou: a página relê a fila. */
  readonly changed = output<void>();

  protected readonly busy = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly failure = signal<string | null>(null);
  protected readonly concluding = signal<ConsumerIssue | null>(null);
  protected readonly reassigning = signal<ConsumerIssue | null>(null);
  protected readonly attendants = signal<Attendant[]>([]);
  protected readonly canAttend = computed(() => can(this.unit(), 'ATENDER_PROBLEMA'));
  protected readonly canConclude = computed(() => can(this.unit(), 'CONCLUIR_PROBLEMA'));
  protected readonly canReassign = computed(() => can(this.unit(), 'REATRIBUIR_ATENDIMENTO'));
  protected readonly conclusion = this.forms.group({
    correction: this.forms.control<Correction | ''>('', Validators.required),
    resolution: this.forms.control('', [Validators.required, Validators.minLength(5), Validators.maxLength(300)]),
  });

  protected statusText(issue: ConsumerIssue): string {
    return STATUS_LABEL[issue.status];
  }

  protected since(instant: string): string {
    return elapsed(instant, this.now());
  }

  /** Aberto; na central, só sem ninguém (o servidor recusa quem chega depois). */
  protected attendable(issue: ConsumerIssue): boolean {
    return issue.status === 'ABERTO' && (issue.mode !== 'CENTRAL' || !issue.owner);
  }

  attend(issue: ConsumerIssue): void {
    this.run(this.service.attendIssue(issue), `Problema da comanda ${issue.tab.number}: em atendimento.`);
  }

  forward(issue: ConsumerIssue): void {
    this.run(this.service.forwardIssue(issue), `Problema da comanda ${issue.tab.number} encaminhado ao gerente.`);
  }

  startConclude(issue: ConsumerIssue): void {
    this.reassigning.set(null);
    this.conclusion.reset();
    this.concluding.set(issue);
  }

  conclude(issue: ConsumerIssue): void {
    const value = this.conclusion.getRawValue();
    const resolution = value.resolution.trim();
    if (!value.correction || resolution.length < 5) {
      this.failure.set('Diga se houve correção e descreva a resolução em 5 a 300 caracteres.');
      return;
    }
    this.run(
      this.service.concludeIssue(issue, resolution, value.correction),
      `Problema da comanda ${issue.tab.number} tratado.`,
    );
  }

  startReassign(issue: ConsumerIssue): void {
    this.concluding.set(null);
    this.reassigning.set(issue);
    this.service
      .issueAttendants()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (people) => this.attendants.set(people),
        error: (error: unknown) => this.failure.set(failureMessage(error)),
      });
  }

  reassign(issue: ConsumerIssue, person: Attendant): void {
    this.run(
      this.service.assignIssue(issue, person.id),
      `Problema da comanda ${issue.tab.number}: agora com ${person.displayName}.`,
    );
  }

  private run(action: Observable<unknown>, done: string): void {
    this.busy.set(true);
    this.failure.set(null);
    this.notice.set(null);
    action.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.busy.set(false);
        this.concluding.set(null);
        this.reassigning.set(null);
        this.notice.set(done);
        this.changed.emit();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.failure.set(failureMessage(error));
        if (STALE.has(problemCode(error) ?? '')) {
          this.changed.emit();
        }
      },
    });
  }
}
