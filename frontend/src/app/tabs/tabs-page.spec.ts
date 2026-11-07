import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { comCookieCsrf } from '../auth/auth-testing';
import { Permission, UnitContextService } from '../session/unit-context.service';
import { BindingQr, Tab } from './tabs.service';
import { TabsPage } from './tabs-page';
import { FakeEventSource, provideFakeEventSource } from '../live/live-testing';

const ANA: Tab = {
  id: 't-1',
  visitId: 'v-1',
  number: 1,
  name: 'Ana',
  table: '4',
  settingsVersionId: 's-1',
  openedAt: '2026-11-03T20:00:00Z',
  guestLinked: false,
  attendanceMode: 'GARCOM_RESPONSAVEL',
  responsibleId: 'u-gabi',
  responsibleName: 'Gabi',
  version: 0,
  continuesTab: null,
  closingSince: null,
};

function qr(recovery = false): BindingQr {
  return {
    tabId: 't-1',
    link: 'http://x/c#token',
    svg: '<svg xmlns="http://www.w3.org/2000/svg"><path d="M4,4h1v1h-1z"/></svg>',
    expiresAt: new Date(Date.now() + 120_000).toISOString(),
    recovery,
  };
}

describe('TabsPage', () => {
  let fixture: ComponentFixture<TabsPage>;
  let http: HttpTestingController;
  const element = () => fixture.nativeElement as HTMLElement;
  const texto = () => element().textContent ?? '';
  const botao = (rotulo: string) =>
    Array.from(element().querySelectorAll('button')).find((b) => b.textContent?.includes(rotulo))!;

  function abrir(permissions: Permission[], tabs: Tab[]): void {
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [TabsPage],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]), provideFakeEventSource()],
    });
    TestBed.inject(UnitContextService).current.set({
      establishmentId: 'a',
      name: 'Bar da Praia',
      role: 'GARCOM',
      permissions,
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TabsPage);
    fixture.detectChanges();
    http.expectOne('/api/v1/tabs').flush(tabs);
    fixture.detectChanges();
  }

  function digitar(rotulo: string, valor: string): void {
    const campo = Array.from(element().querySelectorAll<HTMLInputElement>('input, textarea')).find(
      (i) => i.closest('mat-form-field')?.textContent?.includes(rotulo),
    )!;
    campo.value = valor;
    campo.dispatchEvent(new Event('input'));
  }

  afterEach(() => http.verify());

  it('abre a comanda e já mostra o QR com o prazo', () => {
    abrir(['OPERAR_COMANDA'], []);
    expect(texto()).toContain('Nenhuma comanda aberta.');
    digitar('Nome ou apelido', 'Ana');
    digitar('Mesa', ' 4 ');
    botao('Abrir').click();
    const abertura = http.expectOne('/api/v1/tabs');
    expect(abertura.request.method).toBe('POST');
    expect(abertura.request.body).toEqual({ name: 'Ana', table: '4' });
    abertura.flush(ANA);
    http.expectOne('/api/v1/tabs').flush([ANA]);
    const pedidoQr = http.expectOne('/api/v1/tabs/t-1/binding-qr');
    expect(pedidoQr.request.method).toBe('POST');
    pedidoQr.flush(qr());
    fixture.detectChanges();
    expect(texto()).toContain('Comanda 1 aberta para Ana.');
    expect(texto()).toContain('QR da comanda 1 · Ana');
    expect(texto()).toMatch(/Vale por 1[12]\d s e uma única vez/);
    const imagem = element().querySelector<HTMLImageElement>('.qr img')!;
    expect(imagem.getAttribute('src')).toMatch(/^data:image\/svg\+xml/);
    expect(imagem.alt).toBe('QR de acesso à comanda 1');
  });

  it('mostra o responsável; o gerente troca e a central não tem responsável (H20)', () => {
    const central: Tab = {
      ...ANA,
      id: 't-2',
      number: 2,
      name: 'Bia',
      attendanceMode: 'CENTRAL',
      responsibleId: null,
      responsibleName: null,
    };
    abrir(['OPERAR_COMANDA', 'REATRIBUIR_ATENDIMENTO'], [ANA, central]);
    expect(texto()).toContain('responsável: Gabi');
    expect(texto()).toContain('atendimento pela central');
    const trocas = Array.from(element().querySelectorAll('button')).filter((b) =>
      b.textContent?.includes('Trocar responsável'),
    );
    expect(trocas.length).toBe(1);
    trocas[0].click();
    http.expectOne('/api/v1/attendance/attendants').flush([
      { id: 'u-gabi', displayName: 'Gabi' },
      { id: 'u-hugo', displayName: 'Hugo' },
    ]);
    fixture.detectChanges();
    expect(texto()).not.toContain('Passar para Gabi');
    botao('Passar para Hugo').click();
    const troca = http.expectOne('/api/v1/tabs/t-1/responsible');
    expect(troca.request.body).toEqual({ responsibleId: 'u-hugo', version: 0 });
    troca.flush({ ...ANA, responsibleId: 'u-hugo', responsibleName: 'Hugo', version: 1 });
    http.expectOne('/api/v1/tabs').flush([{ ...ANA, responsibleName: 'Hugo' }]);
    fixture.detectChanges();
    expect(texto()).toContain('Comanda 1: responsável agora é Hugo.');
  });

  it('aviso de comanda relê a lista e fecha o QR quando o cliente confirma noutro aparelho', () => {
    abrir(['OPERAR_COMANDA'], [ANA]);
    botao('Mostrar QR').click();
    http.expectOne('/api/v1/tabs/t-1/binding-qr').flush(qr());
    fixture.detectChanges();
    expect(texto()).toContain('QR da comanda 1 · Ana');

    const conexao = FakeEventSource.last();
    conexao.emit('ready', { cursor: '1', resync: false });
    conexao.emit('change', { cursor: '2', topic: 'PRODUCAO', tabId: 't-1' });
    http.expectNone('/api/v1/tabs');
    conexao.emit('change', { cursor: '3', topic: 'COMANDA', tabId: 't-1' });
    http.expectOne('/api/v1/tabs').flush([{ ...ANA, guestLinked: true }]);
    fixture.detectChanges();
    expect(texto()).not.toContain('QR da comanda 1 · Ana');
    expect(texto()).toContain('Cliente da comanda 1 confirmou o acesso.');
    expect(texto()).toContain('cliente com acesso');
  });

  it('sem nome não envia', () => {
    abrir(['OPERAR_COMANDA'], []);
    botao('Abrir').click();
    fixture.detectChanges();
    expect(element().querySelector('[role="alert"]')?.textContent).toContain('Informe o nome');
  });

  it('cliente com acesso: recuperar só com a permissão e a conferência completa', () => {
    abrir(['OPERAR_COMANDA', 'RECUPERAR_ACESSO_CONSUMIDOR'], [{ ...ANA, guestLinked: true }]);
    expect(texto()).toContain('cliente com acesso');
    expect(botao('Mostrar QR')).toBeUndefined();
    botao('Recuperar acesso').click();
    fixture.detectChanges();
    expect(texto()).toContain('O acesso atual será cancelado.');

    botao('Recuperar e mostrar QR').click();
    fixture.detectChanges();
    expect(element().querySelector('[role="alert"]')?.textContent).toContain('Confirme o nome');

    element().querySelector<HTMLInputElement>('mat-checkbox input')!.click();
    element().querySelector<HTMLInputElement>('mat-radio-button[value="ITEM_DA_CONTA"] input')!.click();
    digitar('Motivo', 'Celular sem bateria');
    botao('Recuperar e mostrar QR').click();
    const pedido = http.expectOne('/api/v1/tabs/t-1/access-recovery');
    expect(pedido.request.body).toEqual({
      nameChecked: true,
      accountCheck: 'ITEM_DA_CONTA',
      reason: 'Celular sem bateria',
    });
    pedido.flush(qr(true));
    fixture.detectChanges();
    expect(texto()).toContain('Acesso anterior da comanda 1 cancelado.');
    expect(texto()).toContain('QR da comanda 1');
  });

  it('garçom sem a permissão não vê recuperar', () => {
    abrir(['OPERAR_COMANDA'], [{ ...ANA, guestLinked: true }]);
    expect(botao('Recuperar acesso')).toBeUndefined();
    expect(botao('Mostrar QR')).toBeUndefined();
  });

  it('troca a mesa com a versão lida e mostra recusa do servidor', () => {
    abrir(['OPERAR_COMANDA'], [ANA]);
    botao('Trocar mesa').click();
    fixture.detectChanges();
    digitar('Nova mesa', '9');
    botao('Salvar mesa').click();
    const pedido = http.expectOne('/api/v1/tabs/t-1/table');
    expect(pedido.request.body).toEqual({ table: '9', version: 0 });
    pedido.flush(
      { code: 'VERSAO_DESATUALIZADA', detail: 'Outra pessoa alterou esta comanda.' },
      { status: 409, statusText: 'Conflict' },
    );
    http.expectOne('/api/v1/tabs').flush([{ ...ANA, version: 1 }]);
    fixture.detectChanges();
    expect(element().querySelector('[role="alert"]')?.textContent).toContain('Outra pessoa alterou');
  });

  it('continuação de visita: lista por abertura, exige escolha e confirmação, e mostra o vínculo (H26)', () => {
    const nova: Tab = { ...ANA, id: 't-2', number: 2, version: 3, openedAt: '2026-11-03T22:00:00Z' };
    abrir(['OPERAR_COMANDA', 'VINCULAR_CONTINUACAO_VISITA'], [ANA, nova]);
    const botoes = Array.from(element().querySelectorAll('button')).filter((b) =>
      b.textContent?.includes('Continuação de visita'),
    );
    expect(botoes.length).toBe(2);
    botoes[1].click();
    http.expectOne('/api/v1/tabs/t-2/visit-continuation/candidates').flush([
      { id: 't-1', number: 1, name: 'Ana', table: '4', openedAt: '2026-11-03T20:00:00Z', closed: false },
    ]);
    fixture.detectChanges();
    expect(texto()).toContain('Continuação de visita · comanda 2');
    expect(texto()).toContain('a lista não sugere pelo nome');

    botao('Vincular à visita').click();
    fixture.detectChanges();
    expect(element().querySelector('[role="alert"]')?.textContent).toContain('confirme com o cliente');

    (element().querySelector('mat-radio-button input') as HTMLInputElement).click();
    (element().querySelector('mat-checkbox input') as HTMLInputElement).click();
    fixture.detectChanges();
    botao('Vincular à visita').click();
    const pedido = http.expectOne('/api/v1/tabs/t-2/visit-continuation');
    expect(pedido.request.body).toEqual({ previousTabId: 't-1', stayConfirmed: true, version: 3 });
    const vinculada = { ...nova, version: 4, continuesTab: { id: 't-1', number: 1, name: 'Ana' } };
    pedido.flush(vinculada);
    http.expectOne('/api/v1/tabs').flush([ANA, vinculada]);
    fixture.detectChanges();
    expect(texto()).toContain('Comanda 2 continua a visita da comanda 1.');
    expect(texto()).toContain('continua a visita da comanda 1');
    expect(
      Array.from(element().querySelectorAll('button')).filter((b) => b.textContent?.includes('Continuação de visita'))
        .length,
    ).toBe(1);
  });

  it('sem a permissão não há continuação de visita', () => {
    abrir(['OPERAR_COMANDA'], [ANA]);
    expect(texto()).not.toContain('Continuação de visita');
  });

  it('produção não opera comandas', () => {
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [TabsPage],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]), provideFakeEventSource()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TabsPage);
    fixture.detectChanges();
    http
      .expectOne('/api/v1/tabs')
      .flush({ code: 'PERMISSAO_NEGADA' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();
    expect(texto()).toContain('Seu papel nesta unidade não opera comandas.');
  });
});
