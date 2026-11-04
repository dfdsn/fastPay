import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { comCookieCsrf } from '../auth/auth-testing';
import { FakeEventSource, provideFakeEventSource } from '../live/live-testing';
import { UnitContextService } from '../session/unit-context.service';
import { ProductionPage } from './production-page';
import { ProductionLine } from './production.service';
import { linha } from './production-testing';

const AGORA = '2026-11-04T20:05:00Z';

describe('ProductionPage (H18)', () => {
  let fixture: ComponentFixture<ProductionPage>;
  let http: HttpTestingController;
  const element = () => fixture.nativeElement as HTMLElement;
  const texto = () => element().textContent ?? '';
  const botao = (rotulo: string) =>
    Array.from(element().querySelectorAll('button')).find((b) => b.textContent?.includes(rotulo));
  const fila = () => http.expectOne((r) => r.url === '/api/v1/production/queue');

  function abrir(lines: ProductionLine[], now = AGORA): void {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    vi.setSystemTime(new Date(now));
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [ProductionPage],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideFakeEventSource(),
      ],
    });
    TestBed.inject(UnitContextService).current.set({
      establishmentId: 'a',
      name: 'Bar da Praia',
      role: 'PRODUCAO',
      permissions: ['OPERAR_PRODUCAO'],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ProductionPage);
    fixture.detectChanges();
    http
      .expectOne((r) => r.url === '/api/v1/catalog')
      .flush({
        sectors: [
          { id: 's-cozinha', name: 'Cozinha', delayLimitMinutes: 15, version: 0 },
          { id: 's-bar', name: 'Bar', delayLimitMinutes: 10, version: 0 },
        ],
        products: [],
        categories: [],
        page: 0,
        pageSize: 100,
        total: 0,
        pricesVisible: false,
      });
    fila().flush({ now, lines });
    fixture.detectChanges();
  }

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  it('mostra a linha inteira com destinos, observação, responsável e tempos, sem valores', () => {
    abrir([linha()]);
    expect(texto()).toContain('Na fila (1)');
    expect(texto()).toContain('3 × Batata frita');
    expect(texto()).toContain('Aguardando preparo');
    expect(texto()).toContain('Obs.: sem sal');
    expect(texto()).toContain('Para: Comanda 12 · Ana · mesa 5');
    expect(texto()).toContain('responsável Gabi Garçom');
    expect(texto()).toContain('Enviado há 5 min');
    expect(texto()).not.toContain('atrasado');
    expect(texto()).not.toContain('R$');
  });

  it('passou do limite do setor desde o envio: destaca, sem mudar a etapa', () => {
    abrir(
      [linha({ status: 'EM_PREPARO', statusSince: '2026-11-04T20:10:00Z' })],
      '2026-11-04T20:14:59Z',
    );
    expect(element().querySelector('li.late')).toBeNull();
    vi.advanceTimersByTime(2_000);
    fixture.detectChanges();
    expect(element().querySelector('li.late')).not.toBeNull();
    expect(texto()).toContain('atrasado (limite 15 min)');
    expect(texto()).toContain('Em preparo');
    expect(texto()).toContain('nesta etapa há 5 min');
  });

  it('inicia e marca pronto com a versão lida; conflito mostra a recusa e relê', () => {
    abrir([linha()]);
    botao('Iniciar Batata frita')!.click();
    const inicio = http.expectOne('/api/v1/production/lines/l-1/start');
    expect(inicio.request.body).toEqual({ version: 0 });
    inicio.flush(linha({ status: 'EM_PREPARO', version: 1 }));
    fila().flush({ now: AGORA, lines: [linha({ status: 'EM_PREPARO', version: 1 })] });
    fixture.detectChanges();
    expect(texto()).toContain('Batata frita: em preparo.');

    botao('Marcar pronto Batata frita')!.click();
    http
      .expectOne('/api/v1/production/lines/l-1/ready')
      .flush(
        { code: 'VERSAO_DESATUALIZADA', detail: 'Este item mudou desde que a tela foi carregada.' },
        { status: 409, statusText: 'Conflict' },
      );
    fila().flush({ now: AGORA, lines: [] });
    fixture.detectChanges();
    expect(texto()).toContain('Este item mudou desde que a tela foi carregada.');
    expect(texto()).toContain('Nada aguardando preparo.');
  });

  it('não é possível atender pede motivo; sinalizada só permite retirar', () => {
    abrir([linha()]);
    botao('Não é possível atender')!.click();
    fixture.detectChanges();
    botao('Sinalizar')!.click();
    fixture.detectChanges();
    expect(element().querySelector('[role="alert"]')?.textContent).toContain('Escreva o motivo');
    const campo = element().querySelector<HTMLInputElement>('input[formControlName="reason"]')!;
    campo.value = 'Acabou a batata';
    campo.dispatchEvent(new Event('input'));
    botao('Sinalizar')!.click();
    const pedido = http.expectOne('/api/v1/production/lines/l-1/impossible');
    expect(pedido.request.body).toEqual({ version: 0, reason: 'Acabou a batata' });
    const sinalizada = linha({ impossible: { reason: 'Acabou a batata', at: AGORA }, version: 1 });
    pedido.flush(sinalizada);
    fila().flush({ now: AGORA, lines: [sinalizada] });
    fixture.detectChanges();
    expect(texto()).toContain('Não é possível atender: Acabou a batata');
    expect(botao('Iniciar Batata frita')).toBeUndefined();
    botao('Retirar sinalização de Batata frita')!.click();
    expect(http.expectOne('/api/v1/production/lines/l-1/impossible/withdraw').request.body).toEqual(
      {
        version: 1,
      },
    );
  });

  it('filtra por setor e relê com aviso da produção', () => {
    abrir([linha()]);
    fixture.componentInstance.chooseSector('s-bar');
    const doBar = fila();
    expect(doBar.request.params.get('sectorId')).toBe('s-bar');
    doBar.flush({ now: AGORA, lines: [] });

    const conexao = FakeEventSource.last();
    conexao.emit('ready', { cursor: '1', resync: false });
    conexao.emit('change', { cursor: '2', topic: 'COMANDA', tabId: 't-1' });
    http.expectNone((r) => r.url === '/api/v1/production/queue');
    conexao.emit('change', { cursor: '3', topic: 'PRODUCAO', tabId: 't-1' });
    expect(fila().request.params.get('sectorId')).toBe('s-bar');
  });

  it('item que chega depois ganha o sinal visual de novo até ser iniciado', () => {
    abrir([linha()]);
    expect(element().querySelector('li.fresh')).toBeNull();
    const conexao = FakeEventSource.last();
    conexao.emit('ready', { cursor: '1', resync: false });
    conexao.emit('change', { cursor: '2', topic: 'PRODUCAO', tabId: 't-2' });
    const nova = linha({ id: 'l-2', productName: 'Pastel', quantity: 2 });
    fila().flush({ now: AGORA, lines: [linha(), nova] });
    fixture.detectChanges();
    expect(element().querySelectorAll('li.fresh')).toHaveLength(1);
    expect(element().querySelector('li.fresh')?.textContent).toContain('Novo');
    expect(texto()).toContain('Novo na fila: 2 × Pastel.');

    botao('Iniciar Pastel')!.click();
    http
      .expectOne('/api/v1/production/lines/l-2/start')
      .flush({ ...nova, status: 'EM_PREPARO', version: 1 });
    fila().flush({ now: AGORA, lines: [linha(), { ...nova, status: 'EM_PREPARO', version: 1 }] });
    fixture.detectChanges();
    expect(element().querySelector('li.fresh')).toBeNull();
  });

  it('papel sem produção vê a recusa', () => {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [ProductionPage],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideFakeEventSource(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ProductionPage);
    fixture.detectChanges();
    http
      .expectOne((r) => r.url === '/api/v1/catalog')
      .flush({ sectors: [], products: [], categories: [] });
    fila().flush({ code: 'PERMISSAO_NEGADA' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();
    expect(texto()).toContain('Seu papel nesta unidade não opera a produção.');
  });
});
