import { Component, DestroyRef, inject, input, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { forkJoin } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { RouterLink } from '@angular/router';

import { failureMessage } from '../session/unit-context.service';
import { ConsumerService, ConsumerSession, LinkState } from './consumer.service';
import { GoogleResult } from './google-result';

/**
 * Guardar a comanda no histórico da conta Google (H27, FR-10). Opcional: o acesso deste aparelho continua igual com
 * ou sem login. Só guarda com a pessoa conectada e marcando que a comanda é dela.
 */
@Component({
  selector: 'fp-tab-history',
  imports: [MatButtonModule, MatCheckboxModule, RouterLink, GoogleResult],
  template: `
    <section aria-labelledby="historico-titulo">
      <h2 id="historico-titulo">Histórico</h2>
      <fp-google-result />
      <div role="alert">
        @if (failure(); as message) {
          <p class="error">{{ message }}</p>
        }
      </div>
      @if (state(); as current) {
        @if (current.mine) {
          <p>Esta comanda está no seu <a routerLink="/meu-historico">histórico</a>.</p>
        } @else if (current.linked) {
          <p>Esta comanda está no histórico de outra conta Google.</p>
        } @else if (session()?.signedIn) {
          <mat-checkbox [checked]="confirmed()" (change)="confirmed.set($event.checked)">
            Confirmo que esta comanda é minha
          </mat-checkbox>
          <button mat-stroked-button type="button" [disabled]="!confirmed() || busy()" (click)="link()">
            Guardar no meu histórico
          </button>
        } @else {
          <p>Quer guardar esta comanda num histórico? É opcional; sem login ela continua funcionando aqui.</p>
          <button mat-stroked-button type="button" (click)="signIn()">Entrar com Google</button>
        }
      }
    </section>
  `,
  styles: `
    h2 {
      font-size: 1rem;
      margin: 16px 0 4px;
    }
    .error {
      color: #b3261e;
    }
  `,
})
export class TabHistoryPanel implements OnInit {
  readonly tabId = input.required<string>();

  private readonly service = inject(ConsumerService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly session = signal<ConsumerSession | null>(null);
  protected readonly state = signal<LinkState | null>(null);
  protected readonly confirmed = signal(false);
  protected readonly busy = signal(false);
  protected readonly failure = signal<string | null>(null);

  ngOnInit(): void {
    forkJoin({ session: this.service.session(), state: this.service.linkState(this.tabId()) })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ session, state }) => {
          this.session.set(session);
          this.state.set(state);
        },
        error: (error: unknown) => this.failure.set(failureMessage(error)),
      });
  }

  signIn(): void {
    this.service.signInWithGoogle(`/minha-comanda/${this.tabId()}`);
  }

  link(): void {
    this.busy.set(true);
    this.failure.set(null);
    this.service
      .link(this.tabId())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (state) => {
          this.state.set(state);
          this.busy.set(false);
        },
        error: (error: unknown) => {
          this.busy.set(false);
          this.failure.set(failureMessage(error));
        },
      });
  }
}
