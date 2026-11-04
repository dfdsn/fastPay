import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { comCookieCsrf } from '../auth/auth-testing';
import { FakeEventSource, provideFakeEventSource } from '../live/live-testing';
import { UnitContextService } from '../session/unit-context.service';
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

  function abrir(lines: ProductionLine[]): void {
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
      permissions: ['OPERAR_COMANDA'],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(DeliveriesPage);
    fixture.detectChanges();
    entregas().flush({ now: AGORA, lines });
    fixture.detectChanges();
  }

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
});
