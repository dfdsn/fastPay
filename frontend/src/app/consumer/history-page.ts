import { DatePipe } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { RouterLink } from '@angular/router';

import { failureMessage } from '../session/unit-context.service';
import { ConsumerService, ConsumerSession, HistoryEntry } from './consumer.service';
import { GoogleResult } from './google-result';

/**
 * Histórico do consumidor com Google (H27, FR-10): só as comandas que a pessoa guardou, de todas as lojas, da mais
 * recente para a mais antiga. Sem itens nem valores; para ver a conta, a comanda precisa do acesso deste aparelho.
 */
@Component({
  selector: 'fp-history-page',
  imports: [DatePipe, MatButtonModule, MatCardModule, MatProgressSpinnerModule, RouterLink, GoogleResult],
  template: `
    <mat-card appearance="outlined">
      <mat-card-header>
        <mat-card-title><h1>Meu histórico</h1></mat-card-title>
      </mat-card-header>
      <mat-card-content>
        <fp-google-result />
        <div role="status" aria-live="polite">
          @if (loading()) {
            <mat-progress-spinner mode="indeterminate" diameter="32" aria-hidden="true" />
            <p>Carregando…</p>
          }
        </div>
        <div role="alert">
          @if (failure(); as message) {
            <p class="error">{{ message }}</p>
          }
        </div>
        @if (session(); as s) {
          @if (s.signedIn) {
            <p>Conta Google: {{ s.email }}</p>
            @if (entries().length === 0 && !loading()) {
              <p>Nenhuma comanda guardada ainda. Abra a sua comanda e escolha "Guardar no meu histórico".</p>
            }
            <ul>
              @for (entry of entries(); track entry.tabId) {
                <li>
                  {{ entry.establishmentName }} · comanda {{ entry.number }} · {{ entry.name }} ·
                  {{ entry.openedAt | date: 'dd/MM/yyyy HH:mm' }} ·
                  {{ entry.closedAt ? 'encerrada' : 'aberta' }}
                </li>
              }
            </ul>
            @if (entries().length < total()) {
              <button mat-stroked-button type="button" [disabled]="loading()" (click)="more()">Ver mais</button>
            }
            <p>
              <button mat-button type="button" (click)="signOut()">Sair da conta Google</button>
            </p>
          } @else {
            <p>Entre com Google para ver as comandas que você guardou. É opcional: sem login, sua comanda continua
              funcionando neste aparelho.</p>
            <button mat-flat-button type="button" (click)="signIn()">Entrar com Google</button>
          }
        }
        <p><a routerLink="/minhas-comandas">Minhas comandas neste aparelho</a></p>
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
    .error {
      color: #b3261e;
    }
  `,
})
export class HistoryPage {
  private readonly service = inject(ConsumerService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly session = signal<ConsumerSession | null>(null);
  protected readonly entries = signal<HistoryEntry[]>([]);
  protected readonly total = signal(0);
  protected readonly loading = signal(true);
  protected readonly failure = signal<string | null>(null);
  private page = 0;

  constructor() {
    this.service
      .session()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (session) => {
          this.session.set(session);
          if (session.signedIn) {
            this.load();
          } else {
            this.loading.set(false);
          }
        },
        error: (error: unknown) => this.fail(error),
      });
  }

  more(): void {
    this.page += 1;
    this.load();
  }

  signIn(): void {
    this.service.signInWithGoogle('/meu-historico');
  }

  signOut(): void {
    this.service
      .signOut()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.entries.set([]);
          this.total.set(0);
          this.session.update((s) => (s ? { ...s, signedIn: false, email: null } : s));
        },
        error: (error: unknown) => this.fail(error),
      });
  }

  private load(): void {
    this.loading.set(true);
    this.service
      .history(this.page)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (page) => {
          this.entries.update((current) => [...current, ...page.items]);
          this.total.set(page.total);
          this.loading.set(false);
        },
        error: (error: unknown) => this.fail(error),
      });
  }

  private fail(error: unknown): void {
    this.loading.set(false);
    this.failure.set(failureMessage(error));
  }
}
