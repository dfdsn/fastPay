import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, InjectionToken, Signal, inject, signal } from '@angular/core';
import { Observable, Subject, Subscription } from 'rxjs';

import { UnitContextService } from '../session/unit-context.service';

/** Do que trata um aviso de mudança (H17). A tela relê pela API só o que mostra. */
export type LiveTopic = 'COMANDA' | 'PEDIDO' | 'PRODUCAO' | 'ATENDIMENTO' | 'PROBLEMA';

/**
 * O que a tela recebe: {@code resync} manda reler tudo (conexão nova, cursor perdido, volta ao foco, rede voltou);
 * {@code change} diz que algo daquele tópico/comanda mudou. Nenhum dos dois traz dados: a leitura é sempre pela API.
 */
export type LiveSignal = { kind: 'resync' } | { kind: 'change'; topic: LiveTopic; tabId: string | null };

/**
 * {@code conectando}: primeira conexão. {@code ao-vivo}: SSE aberto e recebendo sinal de vida. {@code reconectando}:
 * sem SSE; a tela segue por polling e mostra que os dados podem estar desatualizados. {@code encerrada}: o servidor
 * recusou (sessão, unidade ou acesso); quem trata é a navegação (login, seleção de unidade) ou a própria tela.
 */
export type LiveStatus = 'conectando' | 'ao-vivo' | 'reconectando' | 'encerrada';

/** Tempos do cliente (proposta T-03 da H17). O heartbeat do servidor é de 15 s. */
export const LIVE_TIMING = {
  /** Sem nenhum evento por este tempo, a conexão é tratada como morta (2,5 heartbeats). */
  silenceMs: 37_500,
  /** Intervalo do polling enquanto o SSE não conecta. */
  pollMs: 5_000,
  /** Primeira espera para tentar o SSE de novo; dobra a cada falha até o máximo. */
  retryMinMs: 2_000,
  retryMaxMs: 60_000,
  /** Polling com erro de rede também espera mais a cada falha, até o máximo. */
  pollMaxMs: 60_000,
  /** Voltar ao foco várias vezes seguidas não gera várias releituras. */
  focusThrottleMs: 2_000,
};

/** Fonte de eventos do navegador; trocada nos testes. */
export interface EventSourceLike {
  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void;
  onerror: ((event: Event) => void) | null;
  close(): void;
}

export const EVENT_SOURCE_FACTORY = new InjectionToken<(url: string) => EventSourceLike>('EVENT_SOURCE_FACTORY', {
  providedIn: 'root',
  factory: () => (url: string) => new EventSource(url, { withCredentials: true }) as unknown as EventSourceLike,
});

/** Resposta do polling, no mesmo formato dos eventos. */
interface Poll {
  cursor: string;
  resync: boolean;
  changes: { cursor: string; topic: LiveTopic; tabId: string | null }[];
}

/** Recusas que não melhoram tentando de novo: sessão, unidade ou acesso. Os interceptadores levam à tela certa. */
const FINAL_STATUSES = new Set([401, 403, 404, 409]);

/** Endereços de uma conexão: SSE e polling do mesmo contexto. */
export interface LiveEndpoints {
  stream: string;
  poll: string;
}

/**
 * Uma conexão de avisos em tempo real (H17, FR-27, NFR-11): SSE quando possível, polling com espera crescente quando
 * não. Reconecta com o último cursor (o servidor manda o que foi perdido ou pede releitura), ressincroniza ao voltar o
 * foco ou a rede e nunca envia nada por conta própria: rascunhos ficam onde estão até a pessoa confirmar. As requisições
 * de polling vão marcadas como segundo plano e não renovam a sessão.
 */
export class LiveUpdates {
  private readonly signalsSubject = new Subject<LiveSignal>();
  private readonly statusSignal = signal<LiveStatus>('conectando');
  private readonly lastSyncSignal = signal<number | null>(null);

  readonly signals: Observable<LiveSignal> = this.signalsSubject.asObservable();
  readonly status: Signal<LiveStatus> = this.statusSignal.asReadonly();
  /** Último contato confirmado com o servidor (epoch ms). */
  readonly lastSyncAt: Signal<number | null> = this.lastSyncSignal.asReadonly();

  private cursor: string | null = null;
  private source: EventSourceLike | null = null;
  private silenceTimer: ReturnType<typeof setTimeout> | null = null;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private pollTimer: ReturnType<typeof setTimeout> | null = null;
  private pollRequest: Subscription | null = null;
  private retryMs = LIVE_TIMING.retryMinMs;
  private pollDelayMs = LIVE_TIMING.pollMs;
  private lastFocusResync = 0;
  private closed = false;
  private readonly cleanup: (() => void)[] = [];

  constructor(
    private readonly endpoints: LiveEndpoints,
    private readonly http: HttpClient,
    private readonly createSource: (url: string) => EventSourceLike,
    private readonly document: Document,
  ) {
    const view = document.defaultView;
    const onVisible = (): void => {
      if (document.visibilityState === 'visible') {
        this.onReturn();
      }
    };
    const onReturn = (): void => this.onReturn();
    document.addEventListener('visibilitychange', onVisible);
    view?.addEventListener('focus', onReturn);
    view?.addEventListener('online', onReturn);
    this.cleanup.push(
      () => document.removeEventListener('visibilitychange', onVisible),
      () => view?.removeEventListener('focus', onReturn),
      () => view?.removeEventListener('online', onReturn),
    );
    this.openStream();
  }

  /** "Atualizar agora": relê a tela e, sem SSE, tenta reconectar já. */
  refreshNow(): void {
    if (this.closed) {
      return;
    }
    this.signalsSubject.next({ kind: 'resync' });
    if (this.statusSignal() !== 'ao-vivo') {
      this.reconnectNow();
    }
  }

  /** Fecha a conexão e para tudo (tela destruída, unidade trocada, saída). */
  close(): void {
    if (this.closed) {
      return;
    }
    this.closed = true;
    this.stopStream();
    this.stopPolling();
    this.clearTimer('retry');
    this.cleanup.forEach((undo) => undo());
    this.signalsSubject.complete();
  }

  private onReturn(): void {
    if (this.closed || this.statusSignal() === 'encerrada') {
      return;
    }
    const now = Date.now();
    if (now - this.lastFocusResync < LIVE_TIMING.focusThrottleMs) {
      return;
    }
    this.lastFocusResync = now;
    this.signalsSubject.next({ kind: 'resync' });
    if (this.statusSignal() !== 'ao-vivo') {
      this.reconnectNow();
    }
  }

  private reconnectNow(): void {
    this.retryMs = LIVE_TIMING.retryMinMs;
    this.clearTimer('retry');
    this.openStream();
  }

  private openStream(): void {
    this.stopStream();
    const url = this.cursor
      ? `${this.endpoints.stream}?after=${encodeURIComponent(this.cursor)}`
      : this.endpoints.stream;
    const source = this.createSource(url);
    this.source = source;
    const on = (type: string, handle: (data: Record<string, unknown>) => void): void =>
      source.addEventListener(type, (event) => {
        if (this.source !== source) {
          return;
        }
        this.touch();
        handle(parse(event.data));
      });
    on('ready', (data) => {
      this.cursor = text(data['cursor']) ?? this.cursor;
      this.statusSignal.set('ao-vivo');
      this.retryMs = LIVE_TIMING.retryMinMs;
      this.stopPolling();
      if (data['resync'] === true) {
        this.signalsSubject.next({ kind: 'resync' });
      }
    });
    on('change', (data) => {
      this.cursor = text(data['cursor']) ?? this.cursor;
      this.signalsSubject.next({
        kind: 'change',
        topic: data['topic'] as LiveTopic,
        tabId: text(data['tabId']),
      });
    });
    on('resync', (data) => {
      this.cursor = text(data['cursor']) ?? this.cursor;
      this.signalsSubject.next({ kind: 'resync' });
    });
    on('heartbeat', (data) => {
      this.cursor = text(data['cursor']) ?? this.cursor;
    });
    on('closed', (data) => {
      this.stopStream();
      if (data['reason'] === 'RECONECTAR') {
        // Fim da duração máxima ou servidor reiniciando: reconecta já, com o mesmo cursor.
        this.openStream();
      } else {
        // Sessão, unidade ou acesso não valem mais: o polling recebe a recusa e os interceptadores agem.
        this.fallback();
      }
    });
    source.onerror = () => {
      if (this.source === source) {
        this.fallback();
      }
    };
  }

  /** Sem SSE: mostra que pode estar desatualizado, busca já pelo polling e agenda nova tentativa de SSE. */
  private fallback(): void {
    if (this.closed) {
      return;
    }
    this.stopStream();
    if (this.statusSignal() !== 'encerrada') {
      this.statusSignal.set('reconectando');
    }
    this.pollNow();
    this.clearTimer('retry');
    const wait = this.retryMs;
    this.retryMs = Math.min(this.retryMs * 2, LIVE_TIMING.retryMaxMs);
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      if (this.statusSignal() !== 'encerrada') {
        this.openStream();
      }
    }, wait);
  }

  private pollNow(): void {
    this.clearTimer('poll');
    this.pollRequest?.unsubscribe();
    const params: Record<string, string> = this.cursor ? { after: this.cursor } : {};
    this.pollRequest = this.http
      .get<Poll>(this.endpoints.poll, { params, headers: new HttpHeaders({ 'X-Fastpay-Background': '1' }) })
      .subscribe({
        next: (poll) => {
          this.touch(false);
          this.pollDelayMs = LIVE_TIMING.pollMs;
          this.cursor = poll.cursor;
          if (poll.resync) {
            this.signalsSubject.next({ kind: 'resync' });
          } else {
            for (const change of poll.changes) {
              this.signalsSubject.next({ kind: 'change', topic: change.topic, tabId: change.tabId });
            }
          }
          this.schedulePoll();
        },
        error: (error: unknown) => {
          if (error instanceof HttpErrorResponse && FINAL_STATUSES.has(error.status)) {
            this.statusSignal.set('encerrada');
            this.stopStream();
            this.clearTimer('retry');
            return;
          }
          this.pollDelayMs = Math.min(this.pollDelayMs * 2, LIVE_TIMING.pollMaxMs);
          this.schedulePoll();
        },
      });
  }

  private schedulePoll(): void {
    if (this.closed || this.statusSignal() === 'ao-vivo' || this.statusSignal() === 'encerrada') {
      return;
    }
    this.clearTimer('poll');
    this.pollTimer = setTimeout(() => {
      this.pollTimer = null;
      this.pollNow();
    }, this.pollDelayMs);
  }

  /** Sinal de vida: registra o contato e, no SSE, rearma o vigia de silêncio. */
  private touch(stream = true): void {
    this.lastSyncSignal.set(Date.now());
    if (stream) {
      this.clearTimer('silence');
      this.silenceTimer = setTimeout(() => this.fallback(), LIVE_TIMING.silenceMs);
    }
  }

  private stopStream(): void {
    this.clearTimer('silence');
    if (this.source) {
      this.source.onerror = null;
      this.source.close();
      this.source = null;
    }
  }

  private stopPolling(): void {
    this.clearTimer('poll');
    this.pollRequest?.unsubscribe();
    this.pollRequest = null;
    this.pollDelayMs = LIVE_TIMING.pollMs;
  }

  private clearTimer(which: 'silence' | 'retry' | 'poll'): void {
    const timer = which === 'silence' ? this.silenceTimer : which === 'retry' ? this.retryTimer : this.pollTimer;
    if (timer !== null) {
      clearTimeout(timer);
    }
    if (which === 'silence') {
      this.silenceTimer = null;
    } else if (which === 'retry') {
      this.retryTimer = null;
    } else {
      this.pollTimer = null;
    }
  }
}

function parse(data: string): Record<string, unknown> {
  try {
    const value: unknown = JSON.parse(data);
    return value && typeof value === 'object' ? (value as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

function text(value: unknown): string | null {
  return typeof value === 'string' ? value : null;
}

/** Cria conexões de avisos para as telas; cada tela fecha a sua ao sair. */
@Injectable({ providedIn: 'root' })
export class LiveUpdatesService {
  private readonly http = inject(HttpClient);
  private readonly createSource = inject(EVENT_SOURCE_FACTORY);
  private readonly document = inject(DOCUMENT);
  private readonly units = inject(UnitContextService);

  /** Avisos da unidade escolhida, para a equipe. Trocar ou perder a unidade (ou sair) fecha a conexão. */
  forUnit(destroyRef: DestroyRef): LiveUpdates {
    const live = this.open({ stream: '/api/v1/sync/stream', poll: '/api/v1/sync/changes' }, destroyRef);
    const cancel = this.units.onUnitCleared(() => live.close());
    destroyRef.onDestroy(cancel);
    return live;
  }

  /** Avisos da comanda do consumidor, pelo acesso guardado neste navegador. */
  forGuestTab(tabId: string, destroyRef: DestroyRef): LiveUpdates {
    const base = `/api/v1/guest/tabs/${encodeURIComponent(tabId)}`;
    return this.open({ stream: `${base}/stream`, poll: `${base}/changes` }, destroyRef);
  }

  private open(endpoints: LiveEndpoints, destroyRef: DestroyRef): LiveUpdates {
    const live = new LiveUpdates(endpoints, this.http, this.createSource, this.document);
    destroyRef.onDestroy(() => live.close());
    return live;
  }
}
