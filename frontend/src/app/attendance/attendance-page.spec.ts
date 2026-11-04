import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { comCookieCsrf } from '../auth/auth-testing';
import { ChimeService } from '../live/chime';
import { FakeEventSource, provideFakeEventSource } from '../live/live-testing';
import { linha } from '../production/production-testing';
import { Permission, UnitContextService } from '../session/unit-context.service';
import { AttendancePage } from './attendance-page';
import { AttendanceCall } from './attendance.service';

const AGORA = '2026-11-04T20:20:00Z';
const CHAMADOS = '/api/v1/attendance/calls';

function chamado(changes: Partial<AttendanceCall> = {}): AttendanceCall {
  return {
    id: 'c-1',
    kind: 'ITEM_PRONTO',
    mode: 'GARCOM_RESPONSAVEL',
    tab: { id: 't-1', number: 12, name: 'Ana', table: '5' },
    owner: { id: 'u-gabi', name: 'Gabi' },
    mine: true,
    quantity: 3,
    createdAt: '2026-11-04T20:15:00Z',
    claimedAt: null,
    line: linha({ status: 'PRONTO', readyAt: '2026-11-04T20:15:00Z' }),
    version: 0,
    ...changes,
  };
}

const MEU = chamado();
const CENTRAL = chamado({
  id: 'c-2',
  mode: 'CENTRAL',
  owner: null,
  mine: false,
  tab: { id: 't-2', number: 13, name: 'Bia', table: null },
  line: linha({ id: 'l-2', productName: 'Chopp', quantity: 1, status: 'PRONTO', sectorId: null }),
  quantity: 1,
});
const DO_HUGO = chamado({
  id: 'c-3',
  kind: 'IMPOSSIBILIDADE',
  owner: { id: 'u-hugo', name: 'Hugo' },
  mine: false,
  tab: { id: 't-3', number: 14, name: 'Caio', table: '2' },
  line: linha({ id: 'l-3', productName: 'Pastel', impossible: { reason: 'Acabou a massa', at: AGORA } }),
});

describe('AttendancePage (H20)', () => {
  let fixture: ComponentFixture<AttendancePage>;
  let http: HttpTestingController;
  const element = () => fixture.nativeElement as HTMLElement;
  const texto = () => element().textContent ?? '';
  const botao = (rotulo: string) =>
    Array.from(element().querySelectorAll('button')).find((b) => b.textContent?.includes(rotulo));
  const fila = () => http.expectOne(CHAMADOS);

  function abrir(calls: AttendanceCall[], permissions: Permission[] = ['OPERAR_COMANDA']): void {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    vi.setSystemTime(new Date(AGORA));
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [AttendancePage],
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
    fixture = TestBed.createComponent(AttendancePage);
    fixture.detectChanges();
    fila().flush({ now: AGORA, calls });
    fixture.detectChanges();
  }

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  it('separa os seus, os da central sem dono e os de outras pessoas, sem botão de chamar atendimento', () => {
    abrir([MEU, CENTRAL, DO_HUGO]);
    const listas = element().querySelectorAll('ul.calls');
    expect(listas[0].getAttribute('aria-label')).toBe('Chamados com você');
    expect(listas[0].textContent).toContain('Pronto para entregar');
    expect(listas[0].textContent).toContain('Comanda 12 · Ana · mesa 5');
    expect(listas[0].textContent).toContain('3 × Batata frita');
    expect(listas[0].textContent).toContain('Há 5 min');
    expect(listas[0].textContent).toContain('Responsável: Gabi');
    expect(listas[1].getAttribute('aria-label')).toBe('Chamados da central sem dono');
    expect(listas[1].textContent).toContain('Central, sem dono');
    expect(listas[2].textContent).toContain('Não é possível atender');
    expect(listas[2].textContent).toContain('Motivo: Acabou a massa');
    expect(texto()).toContain('Com você: 1 · Na central, sem dono: 1 · Com outras pessoas: 1');
    expect(botao('Chamar atendimento')).toBeUndefined();
    // Garçom não reatribui; impossibilidade não se entrega.
    expect(botao('Reatribuir')).toBeUndefined();
    expect(botao('Entregar 1 × Pastel')).toBeUndefined();
  });

  it('assume o chamado da central; se outra pessoa assumiu antes, mostra quem e relê', () => {
    abrir([CENTRAL]);
    botao('Assumir')!.click();
    http
      .expectOne(`${CHAMADOS}/c-2/claim`)
      .flush(
        { code: 'CHAMADO_JA_ASSUMIDO', detail: 'Hugo já assumiu este chamado.' },
        { status: 409, statusText: 'Conflict' },
      );
    fila().flush({
      now: AGORA,
      calls: [{ ...CENTRAL, owner: { id: 'u-hugo', name: 'Hugo' }, version: 1 }],
    });
    fixture.detectChanges();
    expect(texto()).toContain('Hugo já assumiu este chamado.');
    expect(texto()).toContain('Assumido por Hugo');
    expect(botao('Assumir')).toBeUndefined();
  });

  it('ajuda: entrega o item de outra pessoa pela produção, com a versão lida', () => {
    const doHugoPronto = chamado({ id: 'c-4', owner: { id: 'u-hugo', name: 'Hugo' }, mine: false });
    abrir([doHugoPronto]);
    botao('Entregar 3 × Batata frita')!.click();
    const entrega = http.expectOne('/api/v1/production/lines/l-1/deliver');
    expect(entrega.request.body).toEqual({ version: 0 });
    entrega.flush({ ...doHugoPronto.line, status: 'ENTREGUE', version: 1 });
    fila().flush({ now: AGORA, calls: [] });
    fixture.detectChanges();
    expect(texto()).toContain('3 × Batata frita entregue.');
    expect(texto()).toContain('Nada com você agora.');
  });

  it('gerente reatribui: na central o chamado, no garçom responsável a comanda', () => {
    abrir([MEU, { ...CENTRAL, owner: { id: 'u-gabi', name: 'Gabi' }, mine: true }], [
      'OPERAR_COMANDA',
      'REATRIBUIR_ATENDIMENTO',
    ]);
    const pessoas = [
      { id: 'u-gabi', displayName: 'Gabi' },
      { id: 'u-hugo', displayName: 'Hugo' },
    ];
    element().querySelectorAll<HTMLButtonElement>('button').forEach((b) => {
      if (b.textContent?.includes('Reatribuir') && b.closest('li')?.textContent?.includes('Bia')) {
        b.click();
      }
    });
    http.expectOne('/api/v1/attendance/attendants').flush(pessoas);
    fixture.detectChanges();
    expect(botao('Passar para Gabi')).toBeUndefined();
    botao('Passar para Hugo')!.click();
    const assign = http.expectOne(`${CHAMADOS}/c-2/assign`);
    expect(assign.request.body).toEqual({ assigneeId: 'u-hugo', version: 0 });
    assign.flush({ ...CENTRAL, owner: { id: 'u-hugo', name: 'Hugo' } });
    fila().flush({ now: AGORA, calls: [MEU] });
    fixture.detectChanges();
    expect(texto()).toContain('Comanda 13: agora com Hugo.');

    botao('Reatribuir')!.click();
    http.expectOne('/api/v1/attendance/attendants').flush(pessoas);
    fixture.detectChanges();
    expect(texto()).toContain('Muda o responsável da comanda 12 inteira');
    botao('Passar para Hugo')!.click();
    http.expectOne('/api/v1/tabs/t-1').flush({ id: 't-1', number: 12, version: 4 });
    const troca = http.expectOne('/api/v1/tabs/t-1/responsible');
    expect(troca.request.body).toEqual({ responsibleId: 'u-hugo', version: 4 });
    troca.flush({ id: 't-1', number: 12, version: 5, responsibleName: 'Hugo' });
    fila().flush({ now: AGORA, calls: [] });
  });

  it('aviso do atendimento relê; chamado novo ganha sinal e o som, se ligado', () => {
    const chime = { play: vi.fn() };
    abrir([]);
    (TestBed.inject(ChimeService) as unknown as { play: () => void }).play = chime.play;
    const conexao = FakeEventSource.last();
    conexao.emit('ready', { cursor: '1', resync: false });
    conexao.emit('change', { cursor: '2', topic: 'ATENDIMENTO', tabId: 't-1' });
    fila().flush({ now: AGORA, calls: [MEU] });
    fixture.detectChanges();
    expect(texto()).toContain('novo');
    expect(chime.play).toHaveBeenCalledTimes(1);
  });

  it('produção não atende', () => {
    vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [AttendancePage],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]), provideFakeEventSource()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AttendancePage);
    fixture.detectChanges();
    fila().flush({ code: 'PERMISSAO_NEGADA' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();
    expect(texto()).toContain('Seu papel nesta unidade não atende comandas.');
  });
});
