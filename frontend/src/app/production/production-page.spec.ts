import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { comCookieCsrf } from '../auth/auth-testing';
import { FakeEventSource, provideFakeEventSource } from '../live/live-testing';
import { UnitContextService } from '../session/unit-context.service';
import { ProductionPage } from './production-page';
import { CancellationNotice, ProductionLine } from './production.service';
import { cancelamento, linha } from './production-testing';

const AGORA = '2026-11-04T20:05:00Z';

describe('ProductionPage (H18)', () => {
  let fixture: ComponentFixture<ProductionPage>;
  let http: HttpTestingController;
  const element = () => fixture.nativeElement as HTMLElement;
  const texto = () => element().textContent ?? '';
  const botao = (rotulo: string) =>
    Array.from(element().querySelectorAll('button')).find((b) => b.textContent?.includes(rotulo));
  /** Toda leitura da fila lê junto os cancelamentos que esperam ciência (H23). */
  const fila = () => {
    const req = http.expectOne((r) => r.url === '/api/v1/production/queue');
    const avisos = http.expectOne((r) => r.url === '/api/v1/production/cancellations');
    return {
      request: req.request,
      avisos: avisos.request,
      flush: (body: { now: string; lines: ProductionLine[] }, cancellations: CancellationNotice[] = []) => {
        req.flush(body);
        avisos.flush({ now: body.now, cancellations });
      },
    };
  };

  function abrir(lines: ProductionLine[], now = AGORA, cancellations: CancellationNotice[] = []): void {
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
    fila().flush({ now, lines }, cancellations);
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
    const releitura = fila();
    expect(releitura.request.params.get('sectorId')).toBe('s-bar');
    expect(releitura.avisos.params.get('sectorId')).toBe('s-bar');
    releitura.flush({ now: AGORA, lines: [] });
  });

  it('mostra o cancelamento em preparo para dar ciência e a linha com enviado e cancelado (H23)', () => {
    abrir(
      [linha({ status: 'EM_PREPARO', quantity: 2, cancelledQuantity: 1 })],
      AGORA,
      [cancelamento()],
    );
    expect(texto()).toContain('Cancelamentos para dar ciência (1)');
    expect(texto()).toContain('Cancelado: 1 × Batata frita');
    expect(texto()).toContain('estava em preparo');
    expect(texto()).toContain('Agora são 2 de 3.');
    expect(texto()).toContain('Motivo: Cliente desistiu · por Gui Gerente');
    expect(texto()).toContain('2 × Batata frita');
    expect(texto()).toContain('enviado 3, cancelado 1');

    botao('Ciente do cancelamento de Batata frita')!.click();
    const ciencia = http.expectOne('/api/v1/production/cancellations/c-1/acknowledge');
    expect(ciencia.request.method).toBe('POST');
    ciencia.flush({
      cancellation: cancelamento({ acknowledgedBy: 'Paulo Produção', acknowledgedAt: AGORA }),
      alreadyAcknowledged: false,
    });
    fila().flush({ now: AGORA, lines: [linha({ status: 'EM_PREPARO', quantity: 2, cancelledQuantity: 1 })] });
    fixture.detectChanges();
    expect(texto()).toContain('Ciência registrada: cancelamento de Batata frita.');
    expect(texto()).not.toContain('Cancelamentos para dar ciência');
  });

  it('ciência repetida diz quem já tinha visto e cancelamento total pede parar o preparo (H23)', () => {
    abrir([], AGORA, [cancelamento({ status: 'CANCELADA', remaining: 0, stage: 'PRONTO' })]);
    expect(texto()).toContain('Não sobrou nada desta linha: pare o preparo.');
    expect(texto()).toContain('estava pronto');
    botao('Ciente do cancelamento')!.click();
    http.expectOne('/api/v1/production/cancellations/c-1/acknowledge').flush({
      cancellation: cancelamento({ acknowledgedBy: 'Bia Bar', acknowledgedAt: AGORA }),
      alreadyAcknowledged: true,
    });
    fila().flush({ now: AGORA, lines: [] });
    fixture.detectChanges();
    expect(texto()).toContain('já tinha ciência de Bia Bar');
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

  it('lote (H19): marca, inicia só os da etapa e mostra o resultado de cada item', () => {
    const pastel = linha({ id: 'l-2', productName: 'Pastel', quantity: 2 });
    const salada = linha({ id: 'l-3', productName: 'Salada', quantity: 1, version: 4 });
    const preparo = linha({ id: 'l-4', productName: 'Caldo', status: 'EM_PREPARO', version: 1 });
    const travada = linha({
      id: 'l-5',
      productName: 'Torta',
      impossible: { reason: 'Acabou', at: AGORA },
      version: 1,
    });
    abrir([linha(), pastel, salada, preparo, travada]);
    expect(botao('Iniciar marcados (0)')!.disabled).toBe(true);
    const marcar = (rotulo: string) =>
      element().querySelector<HTMLInputElement>(`input[aria-label="${rotulo}"]`)!.click();
    marcar('Marcar todos');
    fixture.detectChanges();
    // A travada e a que já está em preparo não entram em "Iniciar".
    expect(botao('Iniciar marcados (3)')).toBeDefined();
    expect(botao('Marcar prontos os marcados (1)')).toBeDefined();
    marcar('Marcar 3 × Batata frita');
    fixture.detectChanges();
    botao('Iniciar marcados (2)')!.click();
    const lote = http.expectOne('/api/v1/production/batch');
    expect(lote.request.body).toEqual({
      action: 'INICIAR',
      lines: [
        { id: 'l-2', version: 0 },
        { id: 'l-3', version: 4 },
      ],
    });
    lote.flush({
      concluded: 1,
      ignored: 0,
      failed: 1,
      lines: [
        { lineId: 'l-2', status: 'CONCLUIDA', code: null, message: null },
        {
          lineId: 'l-3',
          status: 'FALHA',
          code: 'VERSAO_DESATUALIZADA',
          message: 'Este item mudou desde que a tela foi carregada.',
        },
      ],
    });
    fila().flush({
      now: AGORA,
      lines: [linha(), { ...pastel, status: 'EM_PREPARO', version: 1 }, salada, preparo, travada],
    });
    fixture.detectChanges();
    expect(texto()).toContain('Lote: 1 iniciado, 1 não avançou.');
    expect(texto()).toContain('1 × Salada: Este item mudou desde que a tela foi carregada.');
    // O que avançou sai da marcação; o que falhou continua marcado para tentar de novo.
    expect(botao('Iniciar marcados (1)')).toBeDefined();
    expect(botao('Marcar prontos os marcados (1)')).toBeDefined();
  });

  it('lote repetido mostra o que já estava feito; acima de 50 nem envia', () => {
    const muitas = Array.from({ length: 51 }, (_, i) => linha({ id: `l-${i}` }));
    abrir(muitas);
    element().querySelector<HTMLInputElement>('input[aria-label="Marcar todos"]')!.click();
    fixture.detectChanges();
    botao('Iniciar marcados (51)')!.click();
    http.expectNone('/api/v1/production/batch');
    fixture.detectChanges();
    expect(texto()).toContain('Marque no máximo 50 itens por vez.');

    element().querySelector<HTMLInputElement>('input[aria-label="Marcar 3 × Batata frita"]')!.click();
    fixture.detectChanges();
    botao('Iniciar marcados (50)')!.click();
    http.expectOne('/api/v1/production/batch').flush({
      concluded: 0,
      ignored: 50,
      failed: 0,
      lines: muitas.slice(1).map((l) => ({
        lineId: l.id,
        status: 'IGNORADA',
        code: 'JA_APLICADA',
        message: 'Já estava feito; nada mudou.',
      })),
    });
    fila().flush({ now: AGORA, lines: [] });
    fixture.detectChanges();
    expect(texto()).toContain('Lote: 0 iniciados, 50 já estavam feitos.');
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
    http
      .expectOne((r) => r.url === '/api/v1/production/queue')
      .flush({ code: 'PERMISSAO_NEGADA' }, { status: 403, statusText: 'Forbidden' });
    http
      .expectOne((r) => r.url === '/api/v1/production/cancellations')
      .flush({ code: 'PERMISSAO_NEGADA' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();
    expect(texto()).toContain('Seu papel nesta unidade não opera a produção.');
  });
});
