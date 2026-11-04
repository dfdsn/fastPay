import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { DestroyRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { UnitContextService } from '../session/unit-context.service';
import { FakeEventSource, provideFakeEventSource } from './live-testing';
import { LIVE_TIMING, LiveSignal, LiveUpdates, LiveUpdatesService } from './live-updates';

describe('LiveUpdates (H17)', () => {
  let http: HttpTestingController;
  let live: LiveUpdates;
  let signals: LiveSignal[];
  let destroy: () => void;

  const poll = () => http.expectOne((r) => r.url === '/api/v1/sync/changes');

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideFakeEventSource()],
    });
    http = TestBed.inject(HttpTestingController);
    const callbacks: (() => void)[] = [];
    destroy = () => callbacks.forEach((c) => c());
    const destroyRef = { onDestroy: (c: () => void) => callbacks.push(c) } as unknown as DestroyRef;
    live = TestBed.inject(LiveUpdatesService).forUnit(destroyRef);
    signals = [];
    live.signals.subscribe((s) => signals.push(s));
  });

  afterEach(() => {
    destroy();
    http.verify();
    vi.useRealTimers();
  });

  it('conexão nova pede releitura e os avisos seguem com o cursor', () => {
    const conexao = FakeEventSource.last();
    expect(live.status()).toBe('conectando');
    conexao.emit('ready', { cursor: '3', resync: true });
    expect(live.status()).toBe('ao-vivo');
    conexao.emit('change', { cursor: '4', topic: 'PEDIDO', tabId: 't-1' });
    conexao.emit('heartbeat', { cursor: '5' });
    expect(signals).toEqual([{ kind: 'resync' }, { kind: 'change', topic: 'PEDIDO', tabId: 't-1' }]);

    // Queda: polling com o último cursor e reconexão com ele, depois da espera.
    conexao.fail();
    expect(live.status()).toBe('reconectando');
    expect(poll().request.params.get('after')).toBe('5');
    vi.advanceTimersByTime(LIVE_TIMING.retryMinMs);
    expect(FakeEventSource.last().url).toBe('/api/v1/sync/stream?after=5');
  });

  it('sem SSE: polling a cada intervalo e novas tentativas com espera crescente até o máximo', () => {
    FakeEventSource.last().fail();
    poll().flush({ cursor: '2', resync: false, changes: [{ cursor: '2', topic: 'COMANDA', tabId: 't-9' }] });
    expect(signals).toEqual([{ kind: 'change', topic: 'COMANDA', tabId: 't-9' }]);
    expect(live.lastSyncAt()).not.toBeNull();

    const esperas: number[] = [];
    for (let i = 0; i < 7; i++) {
      const antes = FakeEventSource.opened.length;
      let esperado = 0;
      while (FakeEventSource.opened.length === antes) {
        vi.advanceTimersByTime(1_000);
        esperado += 1_000;
        // Enquanto espera, o polling segue no intervalo normal.
        http.match((r) => r.url === '/api/v1/sync/changes').forEach((p) =>
          p.flush({ cursor: '2', resync: false, changes: [] }),
        );
      }
      esperas.push(esperado);
      FakeEventSource.last().fail();
      http.match((r) => r.url === '/api/v1/sync/changes').forEach((p) =>
        p.flush({ cursor: '2', resync: false, changes: [] }),
      );
    }
    expect(esperas).toEqual([2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000]);
  });

  it('silêncio além de 2,5 heartbeats derruba a conexão e passa ao polling', () => {
    const conexao = FakeEventSource.last();
    conexao.emit('ready', { cursor: '1', resync: false });
    vi.advanceTimersByTime(LIVE_TIMING.silenceMs - 1);
    expect(conexao.closed).toBe(false);
    vi.advanceTimersByTime(1);
    expect(conexao.closed).toBe(true);
    expect(live.status()).toBe('reconectando');
    poll().flush({ cursor: '1', resync: false, changes: [] });
  });

  it('servidor pede reconexão: reabre já, sem mostrar dados desatualizados', () => {
    const conexao = FakeEventSource.last();
    conexao.emit('ready', { cursor: '8', resync: false });
    conexao.emit('closed', { reason: 'RECONECTAR' });
    expect(conexao.closed).toBe(true);
    expect(FakeEventSource.last()).not.toBe(conexao);
    expect(FakeEventSource.last().url).toBe('/api/v1/sync/stream?after=8');
    expect(live.status()).toBe('ao-vivo');
    http.expectNone((r) => r.url === '/api/v1/sync/changes');
  });

  it('sessão ou unidade recusada: para de tentar e deixa a navegação com os interceptadores', () => {
    FakeEventSource.last().emit('closed', { reason: 'SESSAO_ENCERRADA' });
    poll().flush({ code: 'SESSAO_AUSENTE' }, { status: 401, statusText: 'Unauthorized' });
    expect(live.status()).toBe('encerrada');
    const abertas = FakeEventSource.opened.length;
    vi.advanceTimersByTime(LIVE_TIMING.retryMaxMs * 2);
    expect(FakeEventSource.opened.length).toBe(abertas);
    http.expectNone((r) => r.url === '/api/v1/sync/changes');
  });

  it('voltar ao foco ou a rede voltar relê a tela, uma vez por vez', () => {
    FakeEventSource.last().emit('ready', { cursor: '1', resync: false });
    window.dispatchEvent(new Event('focus'));
    window.dispatchEvent(new Event('online'));
    expect(signals).toEqual([{ kind: 'resync' }]);
    vi.advanceTimersByTime(LIVE_TIMING.focusThrottleMs);
    FakeEventSource.last().emit('heartbeat', { cursor: '1' });
    window.dispatchEvent(new Event('online'));
    expect(signals).toEqual([{ kind: 'resync' }, { kind: 'resync' }]);
  });

  it('trocar ou perder a unidade fecha a conexão', () => {
    const conexao = FakeEventSource.last();
    TestBed.inject(UnitContextService).clear();
    expect(conexao.closed).toBe(true);
    vi.advanceTimersByTime(LIVE_TIMING.retryMaxMs);
    expect(FakeEventSource.opened).toHaveLength(1);
  });
});
