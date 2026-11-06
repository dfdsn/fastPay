import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { comCookieCsrf } from '../auth/auth-testing';
import { FakeEventSource, provideFakeEventSource } from '../live/live-testing';
import { Permission, UnitContextService } from '../session/unit-context.service';
import { DeliveriesPage } from './deliveries-page';
import { ProductionLine } from './production.service';
import { linha } from './production-testing';

const AGORA = '2026-11-04T20:20:00Z';

const BATATA = linha({
  status: 'PRONTO',
  readyAt: '2026-11-04T20:18:00Z',
  statusSince: '2026-11-04T20:18:00Z',
});
const CHOPP = linha({
  id: 'l-2',
  sectorId: null,
  sectorName: null,
  delayLimitMinutes: null,
  productName: 'Chopp',
  quantity: 2,
  note: null,
  status: 'PRONTO',
  readyAt: '2026-11-04T20:00:00Z',
  destinations: [
    { tabId: 't-1', number: 12, name: 'Ana', table: '5', quantity: 1 },
    { tabId: 't-2', number: 13, name: 'Bia', table: null, quantity: 1 },
  ],
});
const PASTEL = linha({
  id: 'l-3',
  productName: 'Pastel',
  quantity: 1,
  impossible: { reason: 'Acabou a massa', at: AGORA },
});

describe('DeliveriesPage (H18)', () => {
  let fixture: ComponentFixture<DeliveriesPage>;
  let http: HttpTestingController;
  const element = () => fixture.nativeElement as HTMLElement;
  const texto = () => element().textContent ?? '';
  const botao = (rotulo: string) =>
    Array.from(element().querySelectorAll('button')).find((b) => b.textContent?.includes(rotulo));
  const entregas = () => http.expectOne('/api/v1/production/deliveries');

  function abrir(lines: ProductionLine[], permissions: Permission[] = ['OPERAR_COMANDA']): void {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    vi.setSystemTime(new Date(AGORA));
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [DeliveriesPage],
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
      role: 'GARCOM',
      permissions,
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(DeliveriesPage);
    fixture.detectChanges();
    entregas().flush({ now: AGORA, lines });
    if (permissions.includes('DESFAZER_ENTREGA')) {
      entregues().flush({ now: AGORA, lines: delivered });
    }
    fixture.detectChanges();
  }

  let delivered: ProductionLine[] = [];
  const entregues = () => http.expectOne('/api/v1/production/delivered');
  const ENTREGUE = linha({
    id: 'l-9',
    productName: 'Caipirinha',
    quantity: 2,
    status: 'ENTREGUE',
    readyAt: '2026-11-04T20:10:00Z',
    deliveredAt: '2026-11-04T20:15:00Z',
    statusSince: '2026-11-04T20:15:00Z',
    version: 3,
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  it('separa prontos com preparo, sem preparo e impossibilidades, com todos os destinos', () => {
    abrir([PASTEL, BATATA, CHOPP]);
    const listas = element().querySelectorAll('ul.lines');
    expect(listas[0].getAttribute('aria-label')).toBe('Itens que não é possível atender');
    expect(listas[0].textContent).toContain('Motivo: Acabou a massa');
    expect(listas[1].textContent).toContain('3 × Batata frita');
    expect(listas[1].textContent).toContain('pronto há 2 min');
    expect(listas[1].textContent).toContain('atrasado');
    expect(listas[2].textContent).toContain('2 × Chopp');
    expect(listas[2].textContent).toContain(
      'Comanda 12 · Ana · mesa 5 (1); Comanda 13 · Bia · sem mesa (1)',
    );
    expect(listas[2].textContent).not.toContain('atrasado');
    expect(botao('Entregar 1 × Pastel')).toBeUndefined();
  });

  it('entrega a linha inteira com a versão lida; destinos mudados pedem nova confirmação', () => {
    abrir([BATATA, CHOPP]);
    botao('Entregar 2 × Chopp')!.click();
    const entrega = http.expectOne('/api/v1/production/lines/l-2/deliver');
    expect(entrega.request.body).toEqual({ version: 0 });
    entrega.flush(
      { code: 'VERSAO_DESATUALIZADA', detail: 'Este item mudou desde que a tela foi carregada.' },
      { status: 409, statusText: 'Conflict' },
    );
    const mudado = { ...CHOPP, destinations: [CHOPP.destinations[0]], quantity: 2, version: 1 };
    entregas().flush({ now: AGORA, lines: [BATATA, mudado] });
    fixture.detectChanges();
    expect(texto()).toContain('Este item mudou desde que a tela foi carregada.');
    expect(texto()).not.toContain('Bia');

    botao('Entregar 2 × Chopp')!.click();
    const segunda = http.expectOne('/api/v1/production/lines/l-2/deliver');
    expect(segunda.request.body).toEqual({ version: 1 });
    segunda.flush({ ...mudado, status: 'ENTREGUE', version: 2 });
    entregas().flush({ now: AGORA, lines: [BATATA] });
    fixture.detectChanges();
    expect(texto()).toContain('2 × Chopp entregue para Comanda 12 · Ana · mesa 5.');
    expect(texto()).toContain('Nenhum item sem preparo para entregar.');
  });

  it('aviso da produção relê a lista', () => {
    abrir([]);
    expect(texto()).toContain('Nada pronto da produção.');
    const conexao = FakeEventSource.last();
    conexao.emit('ready', { cursor: '1', resync: false });
    conexao.emit('change', { cursor: '2', topic: 'PRODUCAO', tabId: 't-1' });
    entregas().flush({ now: AGORA, lines: [BATATA] });
    fixture.detectChanges();
    expect(texto()).toContain('Prontos para entregar (1)');
  });

  it('sem a permissão não mostra entregues nem pede a lista (H24)', () => {
    delivered = [ENTREGUE];
    abrir([]);
    expect(texto()).not.toContain('Entregues recentemente');
    http.expectNone('/api/v1/production/delivered');
  });

  it('desfaz a entrega com motivo e a linha volta para pronto (H24)', () => {
    delivered = [ENTREGUE];
    abrir([], ['OPERAR_COMANDA', 'DESFAZER_ENTREGA']);
    expect(texto()).toContain('Entregues recentemente (1)');
    expect(texto()).toContain('Entregue há 5 min');
    botao('Desfazer entrega de Caipirinha')!.click();
    fixture.detectChanges();
    const motivo = element().querySelector<HTMLInputElement>('input[aria-label="Motivo para Caipirinha"]')!;
    motivo.value = 'ops';
    motivo.dispatchEvent(new Event('input'));
    botao('Confirmar desfazer')!.click();
    fixture.detectChanges();
    expect(texto()).toContain('Informe o motivo, com 5 a 300 caracteres.');
    http.expectNone((r) => r.url.endsWith('/undo-delivery'));

    motivo.value = 'Entregou na mesa errada';
    motivo.dispatchEvent(new Event('input'));
    botao('Confirmar desfazer')!.click();
    const req = http.expectOne('/api/v1/production/lines/l-9/undo-delivery');
    expect(req.request.body).toEqual({ version: 3, reason: 'Entregou na mesa errada' });
    const pronta = { ...ENTREGUE, status: 'PRONTO' as const, deliveredAt: null, version: 4 };
    req.flush(pronta);
    entregas().flush({ now: AGORA, lines: [pronta] });
    entregues().flush({ now: AGORA, lines: [] });
    fixture.detectChanges();
    expect(texto()).toContain('Entrega de 2 × Caipirinha desfeita: o item voltou para pronto.');
    expect(botao('Entregar 2 × Caipirinha')).toBeDefined();
    expect(texto()).toContain('Nenhuma entrega recente.');
  });

  it('com comanda encerrada só registra ocorrência; recusa relê a lista (H24)', () => {
    const encerrada = {
      ...ENTREGUE,
      destinations: [
        { tabId: 't-1', number: 12, name: 'Ana', table: '5', quantity: 1, closed: false },
        { tabId: 't-2', number: 13, name: 'Bia', table: null, quantity: 1, closed: true },
      ],
    };
    delivered = [encerrada];
    abrir([], ['OPERAR_COMANDA', 'DESFAZER_ENTREGA']);
    expect(texto()).toContain('Comanda 13 · Bia · sem mesa · encerrada (1)');
    expect(botao('Desfazer entrega de Caipirinha')).toBeUndefined();
    botao('Ocorrência de Caipirinha')!.click();
    fixture.detectChanges();
    expect(texto()).toContain('nada é reaberto nem devolvido');
    const motivo = element().querySelector<HTMLInputElement>('input[aria-label="Motivo para Caipirinha"]')!;
    motivo.value = 'Bia recebeu bebida errada';
    motivo.dispatchEvent(new Event('input'));
    botao('Registrar ocorrência')!.click();
    const req = http.expectOne('/api/v1/production/lines/l-9/delivery-occurrence');
    expect(req.request.body).toEqual({ version: 3, reason: 'Bia recebeu bebida errada' });
    req.flush({ eventId: 'e-1', line: encerrada });
    entregas().flush({ now: AGORA, lines: [] });
    entregues().flush({ now: AGORA, lines: [encerrada] });
    fixture.detectChanges();
    expect(texto()).toContain('Ocorrência registrada para 2 × Caipirinha.');

    // Outra pessoa já desfez ou a situação mudou: a recusa aparece e a tela relê.
    botao('Ocorrência de Caipirinha')!.click();
    fixture.detectChanges();
    const outro = element().querySelector<HTMLInputElement>('input[aria-label="Motivo para Caipirinha"]')!;
    outro.value = 'Bia recebeu bebida errada';
    outro.dispatchEvent(new Event('input'));
    botao('Registrar ocorrência')!.click();
    http.expectOne('/api/v1/production/lines/l-9/delivery-occurrence').flush(
      { code: 'VERSAO_DESATUALIZADA', detail: 'Este item mudou desde que a tela foi carregada.' },
      { status: 409, statusText: 'Conflict' },
    );
    entregas().flush({ now: AGORA, lines: [] });
    entregues().flush({ now: AGORA, lines: [] });
    fixture.detectChanges();
    expect(texto()).toContain('Este item mudou desde que a tela foi carregada.');
    expect(element().querySelector('input[aria-label="Motivo para Caipirinha"]')).toBeNull();
  });
});
