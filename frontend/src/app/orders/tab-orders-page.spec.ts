import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';

import { comCookieCsrf } from '../auth/auth-testing';
import { CatalogListing, Product } from '../catalog/catalog.service';
import { Permission, UnitContextService } from '../session/unit-context.service';
import { Tab } from '../tabs/tabs.service';
import { draftStorageKey, loadDraft, saveDraft } from './order-draft';
import { ReceivedLine, SentOrder } from './orders.service';
import { TabOrdersPage } from './tab-orders-page';
import { FakeEventSource, provideFakeEventSource } from '../live/live-testing';

const ANA: Tab = {
  id: 't-1',
  visitId: 'v-1',
  number: 7,
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

function produto(id: string, name: string, price: number, available = true): Product {
  return {
    id,
    name,
    category: 'Bebidas',
    description: null,
    price,
    sectorId: null,
    available,
    photoFileId: null,
    version: 0,
  };
}

const CATALOGO: CatalogListing = {
  sectors: [],
  products: [produto('p-chopp', 'Chopp', 9.9), produto('p-agua', 'Água', 5), produto('p-suco', 'Suco', 8, false)],
  categories: ['Bebidas'],
  page: 0,
  pageSize: 100,
  totalProducts: 3,
  pricesVisible: true,
};

function pedido(forwarding: SentOrder['forwarding'] = 'CONCLUIDO'): SentOrder {
  return {
    id: 'o-1',
    tabId: 't-1',
    operationKey: 'k',
    sentBy: 'g',
    sentAt: '2026-11-03T20:10:00Z',
    forwarding,
    lines: [
      {
        id: 'l-1',
        position: 1,
        productId: 'p-chopp',
        productName: 'Chopp',
        unitPrice: '9.90',
        quantity: 2,
        lineTotal: '19.80',
        note: 'gelado',
        sectorId: null,
        activeQuantity: 2,
        cancelledQuantity: 0,
        inTabQuantity: 2,
        transferredQuantity: 0,
      },
    ],
  };
}

const CHAVE = draftStorageKey('m-1', 't-1');

describe('TabOrdersPage', () => {
  let fixture: ComponentFixture<TabOrdersPage>;
  let http: HttpTestingController;
  const element = () => fixture.nativeElement as HTMLElement;
  const texto = () => (element().textContent ?? '').replace(/\u00a0/g, ' ');
  const botao = (rotulo: string) =>
    Array.from(element().querySelectorAll<HTMLButtonElement>('button')).find((b) =>
      b.textContent?.includes(rotulo),
    )!;

  const recebidos = (r: { url: string }) => r.url === '/api/v1/orders/received';

  function abrir(
    pedidos: SentOrder[] = [],
    permissions: Permission[] = ['OPERAR_COMANDA'],
    recebidosDaComanda: ReceivedLine[] = [],
  ): void {
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [TabOrdersPage],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideFakeEventSource(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: 't-1' }) } } },
      ],
    });
    TestBed.inject(UnitContextService).current.set({
      establishmentId: 'a',
      name: 'Bar',
      role: 'GARCOM',
      permissions,
      membershipId: 'm-1',
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TabOrdersPage);
    fixture.detectChanges();
    http.expectOne('/api/v1/tabs/t-1').flush(ANA);
    http.expectOne((r) => r.url === '/api/v1/catalog').flush(CATALOGO);
    http.expectOne((r) => r.url === '/api/v1/orders' && r.method === 'GET').flush(pedidos);
    http.expectOne(recebidos).flush(recebidosDaComanda);
    fixture.detectChanges();
  }

  function envio() {
    return http.expectOne((r) => r.url === '/api/v1/orders' && r.method === 'POST');
  }

  function recarregaPedidos(pedidos: SentOrder[], recebidosDaComanda: ReceivedLine[] = []): void {
    http.expectOne((r) => r.url === '/api/v1/orders' && r.method === 'GET').flush(pedidos);
    http.expectOne(recebidos).flush(recebidosDaComanda);
    fixture.detectChanges();
  }

  beforeEach(() => localStorage.clear());
  afterEach(() => {
    // A conta (H22) tem testes próprios; aqui basta que a tela a releia junto.
    http.match('/api/v1/tabs/t-1/bill');
    http.match('/api/v1/tabs/t-1/closing');
    http.verify();
  });

  it('monta o rascunho só no aparelho e envia o pedido inteiro com chave e preços vistos', () => {
    abrir();
    expect(texto()).toContain('Comanda 7 · Ana');
    expect(texto()).toContain('esgotado');
    expect(botao('Adicionar Suco')).toBeUndefined();
    botao('Adicionar Chopp').click();
    botao('Adicionar Água').click();
    fixture.detectChanges();
    const quantidade = element().querySelector<HTMLInputElement>('input[aria-label="Quantidade de Chopp"]')!;
    quantidade.value = '2';
    quantidade.dispatchEvent(new Event('change'));
    const obs = element().querySelector<HTMLInputElement>('input[aria-label="Observação de Chopp"]')!;
    obs.value = ' gelado ';
    obs.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    // Nada foi ao servidor; o rascunho está guardado para esta pessoa e comanda.
    expect(loadDraft(CHAVE).lines.map((l) => [l.name, l.quantity, l.price])).toEqual([
      ['Chopp', 2, '9.90'],
      ['Água', 1, '5.00'],
    ]);
    expect(texto()).toContain('R$ 24,80');

    botao('Enviar pedido').click();
    const req = envio();
    expect(req.request.body.tabId).toBe('t-1');
    expect(req.request.body.operationKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(req.request.body.lines).toEqual([
      { productId: 'p-chopp', quantity: 2, note: 'gelado', expectedPrice: '9.90' },
      { productId: 'p-agua', quantity: 1, note: null, expectedPrice: '5.00' },
    ]);
    // Enquanto espera, a chave fica guardada: uma queda não perde o vínculo com o envio.
    expect(loadDraft(CHAVE).pendingKey).toBe(req.request.body.operationKey);
    req.flush({ order: pedido(), replayed: false });
    recarregaPedidos([pedido()]);
    expect(texto()).toContain('Pedido enviado: 1 linha.');
    expect(localStorage.getItem(CHAVE)).toBeNull();
    expect(texto()).toContain('2× Chopp');
    expect(texto()).toContain('gelado');
    expect(texto()).toContain('na produção');
  });

  it('esgotado e preço mudado voltam marcados no rascunho e pedem reconfirmação', () => {
    abrir();
    botao('Adicionar Chopp').click();
    botao('Adicionar Água').click();
    fixture.detectChanges();
    botao('Enviar pedido').click();
    envio().flush(
      {
        code: 'PEDIDO_PRECISA_REVISAO',
        detail: 'Há item esgotado ou fora do catálogo. Nada foi enviado: ajuste o rascunho e envie de novo.',
        unavailable: ['p-agua'],
        priceChanges: [{ productId: 'p-chopp', seenPrice: '9.90', currentPrice: '10.50' }],
      },
      { status: 409, statusText: 'Conflict' },
    );
    http.expectOne((r) => r.url === '/api/v1/catalog').flush(CATALOGO);
    fixture.detectChanges();
    expect(texto()).toContain('Nada foi enviado');
    expect(texto()).toContain('preço mudou de R$ 9,90 para R$ 10,50');
    expect(texto()).toContain('esgotado ou fora do catálogo: remova para enviar');
    expect(loadDraft(CHAVE)).toMatchObject({ pendingKey: null });
    expect(loadDraft(CHAVE).lines).toHaveLength(2);

    botao('Remover Água').click();
    fixture.detectChanges();
    botao('Confirmar novos preços e enviar').click();
    const req = envio();
    expect(req.request.body.lines).toEqual([
      { productId: 'p-chopp', quantity: 1, note: null, expectedPrice: '10.50' },
    ]);
    req.flush({ order: pedido(), replayed: false });
    recarregaPedidos([pedido()]);
  });

  it('sem resposta, confere a operação e reenvia com a mesma chave', () => {
    abrir();
    botao('Adicionar Chopp').click();
    fixture.detectChanges();
    botao('Enviar pedido').click();
    const primeira = envio();
    const chave = primeira.request.body.operationKey;
    primeira.error(new ProgressEvent('error'));
    fixture.detectChanges();
    expect(texto()).toContain('O pedido pode ter chegado');
    expect(botao('Adicionar Chopp').disabled).toBe(true);
    expect(botao('Remover Chopp').disabled).toBe(true);

    botao('Conferir envio').click();
    http.expectOne(`/api/v1/orders/operations/${chave}`).flush(
      { code: 'PEDIDO_NAO_ENCONTRADO', detail: 'x' },
      { status: 404, statusText: 'Not Found' },
    );
    fixture.detectChanges();
    expect(texto()).toContain('O pedido não chegou');

    botao('Enviar de novo').click();
    const segunda = envio();
    expect(segunda.request.body.operationKey).toBe(chave);
    segunda.flush({ order: pedido(), replayed: true });
    recarregaPedidos([pedido()]);
    expect(texto()).toContain('já tinha chegado');
    expect(localStorage.getItem(CHAVE)).toBeNull();
  });

  it('ao voltar à tela com envio pendente, confere antes de qualquer repetição', () => {
    saveDraft(CHAVE, {
      lines: [{ key: 'x', productId: 'p-chopp', name: 'Chopp', price: '9.90', quantity: 2, note: '' }],
      pendingKey: 'k-pendente',
    });
    abrir();
    http.expectOne('/api/v1/orders/operations/k-pendente').flush(pedido());
    recarregaPedidos([pedido()]);
    expect(texto()).toContain('O pedido tinha chegado');
    expect(localStorage.getItem(CHAVE)).toBeNull();
  });

  it('comanda encerrada recusa e preserva o rascunho', () => {
    abrir();
    botao('Adicionar Chopp').click();
    fixture.detectChanges();
    botao('Enviar pedido').click();
    envio().flush(
      { code: 'COMANDA_ENCERRADA', detail: 'Esta comanda não recebe mais pedidos.' },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();
    expect(texto()).toContain('não recebe mais pedidos');
    expect(loadDraft(CHAVE)).toMatchObject({ pendingKey: null });
    expect(loadDraft(CHAVE).lines).toHaveLength(1);
  });

  it('cancela unidades com motivo e mostra enviado, cancelado e restante (H23)', () => {
    abrir([pedido()], ['OPERAR_COMANDA', 'CANCELAR_ITEM']);
    botao('Cancelar Chopp').click();
    fixture.detectChanges();
    const motivo = element().querySelector<HTMLInputElement>('input[aria-label="Motivo do cancelamento de Chopp"]')!;
    motivo.value = 'ops';
    motivo.dispatchEvent(new Event('input'));
    botao('Confirmar cancelamento').click();
    fixture.detectChanges();
    expect(texto()).toContain('Informe o motivo, com 5 a 300 caracteres.');
    http.expectNone((r) => r.url.endsWith('/cancel'));

    motivo.value = 'Cliente desistiu';
    motivo.dispatchEvent(new Event('input'));
    botao('Confirmar cancelamento').click();
    const req = http.expectOne('/api/v1/tabs/t-1/items/l-1/cancel');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ quantity: 1, expectedQuantity: 2, reason: 'Cliente desistiu' });
    req.flush({
      cancellationId: 'c-1',
      orderLineId: 'l-1',
      tabId: 't-1',
      productName: 'Chopp',
      quantity: 1,
      originalQuantity: 2,
      cancelledQuantity: 1,
      remainingInTab: 1,
      requiresAck: true,
    });
    const depois = pedido();
    depois.lines[0] = { ...depois.lines[0], activeQuantity: 1, cancelledQuantity: 1, inTabQuantity: 1 };
    recarregaPedidos([depois]);
    expect(texto()).toContain('1 × Chopp cancelado(s); restam 1. A produção foi avisada.');
    expect(texto()).toContain('1× Chopp');
    expect(texto()).toContain('enviado 2, cancelado 1, restante 1');
  });

  it('sem permissão de cancelar não mostra o botão; quantidade mudada mostra a recusa e relê (H23)', () => {
    abrir([pedido()]);
    expect(element().textContent).not.toContain('Cancelar Chopp');
    TestBed.inject(UnitContextService).current.update((u) => u && { ...u, permissions: ['CANCELAR_ITEM'] });
    fixture.detectChanges();
    botao('Cancelar Chopp').click();
    fixture.detectChanges();
    const motivo = element().querySelector<HTMLInputElement>('input[aria-label="Motivo do cancelamento de Chopp"]')!;
    motivo.value = 'Cliente desistiu';
    motivo.dispatchEvent(new Event('input'));
    botao('Confirmar cancelamento').click();
    http.expectOne('/api/v1/tabs/t-1/items/l-1/cancel').flush(
      {
        code: 'QUANTIDADE_DESATUALIZADA',
        detail: 'A quantidade deste item mudou desde que a tela foi carregada. Confira e tente de novo.',
      },
      { status: 409, statusText: 'Conflict' },
    );
    recarregaPedidos([pedido()]);
    expect(texto()).toContain('A quantidade deste item mudou');
    expect(element().querySelector('form.cancel')).toBeNull();
  });

  it('transfere unidades para outra comanda aberta com motivo e mostra o que ficou e o que saiu (H25)', () => {
    abrir([pedido()], ['OPERAR_COMANDA']);
    expect(element().textContent).not.toContain('Transferir Chopp');
    TestBed.inject(UnitContextService).current.update(
      (u) => u && { ...u, permissions: ['OPERAR_COMANDA', 'TRANSFERIR_UNIDADES'] },
    );
    fixture.detectChanges();
    botao('Transferir Chopp').click();
    http.expectOne('/api/v1/tabs').flush([ANA, { ...ANA, id: 't-2', number: 8, name: 'Bia', table: null }]);
    fixture.detectChanges();
    const destino = element().querySelector<HTMLSelectElement>(
      'select[aria-label="Comanda de destino de Chopp"]',
    )!;
    // A própria comanda não aparece como destino.
    expect(Array.from(destino.options).map((o) => o.textContent?.trim())).toEqual([
      'Escolha a comanda',
      'Comanda 8 · Bia',
    ]);
    const motivo = element().querySelector<HTMLInputElement>(
      'input[aria-label="Motivo da transferência de Chopp"]',
    )!;
    motivo.value = 'Dividiu com a amiga';
    motivo.dispatchEvent(new Event('input'));
    botao('Confirmar transferência').click();
    fixture.detectChanges();
    expect(texto()).toContain('Escolha a comanda de destino.');
    http.expectNone((r) => r.url.endsWith('/transfer'));

    destino.value = 't-2';
    destino.dispatchEvent(new Event('change'));
    botao('Confirmar transferência').click();
    const req = http.expectOne('/api/v1/tabs/t-1/items/l-1/transfer');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      toTabId: 't-2',
      quantity: 1,
      expectedQuantity: 2,
      reason: 'Dividiu com a amiga',
    });
    req.flush({
      transferId: 'x-1',
      orderLineId: 'l-1',
      productName: 'Chopp',
      fromTabId: 't-1',
      toTabId: 't-2',
      quantity: 1,
      remainingInFrom: 1,
      quantityInTo: 1,
      status: 'PRONTO',
      version: 3,
    });
    const depois = pedido();
    depois.lines[0] = { ...depois.lines[0], inTabQuantity: 1, transferredQuantity: 1 };
    recarregaPedidos([depois]);
    expect(texto()).toContain('1 × Chopp transferido(s) para a comanda 8 · Bia; restam 1 nesta.');
    expect(texto()).toContain('1× Chopp');
    expect(texto()).toContain('1 transferida(s) para outras comandas');
    expect(element().querySelector('form.cancel')).toBeNull();
  });

  it('mostra os itens recebidos de outra comanda, com origem, e permite corrigi-los aqui (H25)', () => {
    const recebido: ReceivedLine = {
      id: 'l-9',
      productName: 'Batata',
      unitPrice: '35.90',
      quantity: 2,
      lineTotal: '71.80',
      note: null,
      sectorId: 's-1',
      sentAt: '2026-11-03T20:05:00Z',
      origin: { id: 't-2', number: 8, name: 'Bia', table: null },
    };
    abrir([], ['OPERAR_COMANDA', 'CANCELAR_ITEM', 'TRANSFERIR_UNIDADES'], [recebido]);
    expect(texto()).toContain('Recebidos de outras comandas (1)');
    expect(texto()).toContain('2× Batata');
    expect(texto()).toContain('de Comanda 8 · Bia');
    botao('Cancelar Batata').click();
    fixture.detectChanges();
    const motivo = element().querySelector<HTMLInputElement>('input[aria-label="Motivo do cancelamento de Batata"]')!;
    motivo.value = 'Cliente desistiu';
    motivo.dispatchEvent(new Event('input'));
    botao('Confirmar cancelamento').click();
    const req = http.expectOne('/api/v1/tabs/t-1/items/l-9/cancel');
    expect(req.request.body).toEqual({ quantity: 1, expectedQuantity: 2, reason: 'Cliente desistiu' });
    req.flush(
      { code: 'PROBLEMA_NO_ITEM', detail: 'Há um problema do consumidor em aberto sobre este item.' },
      { status: 409, statusText: 'Conflict' },
    );
    recarregaPedidos([], [recebido]);
    expect(texto()).toContain('Há um problema do consumidor em aberto sobre este item.');
  });

  it('mostra falha de encaminhamento e reenvia à produção sem novo pedido', () => {
    abrir([pedido('FALHOU')]);
    expect(texto()).toContain('falha ao encaminhar à produção');
    botao('Reenviar à produção').click();
    const req = http.expectOne('/api/v1/orders/o-1/forwarding/retry');
    expect(req.request.method).toBe('POST');
    req.flush(pedido('PENDENTE'));
    recarregaPedidos([pedido('PENDENTE')]);
    expect(texto()).toContain('encaminhando à produção');
  });

  it('avisos da comanda relêem os pedidos e reconexão relê tudo, sem nunca enviar o rascunho', () => {
    abrir();
    botao('Adicionar Chopp').click();
    fixture.detectChanges();
    const conexao = FakeEventSource.last();
    expect(conexao.url).toBe('/api/v1/sync/stream');
    conexao.emit('ready', { cursor: '4', resync: false });

    conexao.emit('change', { cursor: '5', topic: 'PEDIDO', tabId: 'outra' });
    http.expectNone((r) => r.url === '/api/v1/orders');
    conexao.emit('change', { cursor: '6', topic: 'PRODUCAO', tabId: 't-1' });
    recarregaPedidos([pedido()]);
    conexao.emit('change', { cursor: '7', topic: 'COMANDA', tabId: 't-1' });
    http.expectOne('/api/v1/tabs/t-1').flush({ ...ANA, table: '9' });
    fixture.detectChanges();
    expect(texto()).toContain('Mesa 9');

    // Queda e volta: relê comanda, catálogo e pedidos pelo cursor; o rascunho fica onde está.
    conexao.fail();
    expect(conexao.closed).toBe(true);
    const polling = http.expectOne((r) => r.url === '/api/v1/sync/changes');
    expect(polling.request.params.get('after')).toBe('7');
    expect(polling.request.headers.get('X-Fastpay-Background')).toBe('1');
    polling.flush({ cursor: '9', resync: true, changes: [] });
    http.expectOne('/api/v1/tabs/t-1').flush(ANA);
    http.expectOne((r) => r.url === '/api/v1/catalog').flush(CATALOGO);
    recarregaPedidos([pedido()]);
    expect(texto()).toContain('Sem conexão em tempo real');
    http.expectNone((r) => r.url === '/api/v1/orders' && r.method === 'POST');
    expect(loadDraft(CHAVE).lines).toHaveLength(1);
  });

  it('rascunho de antes (outra visita ou novo login) pede conferência antes de enviar', () => {
    saveDraft(CHAVE, {
      lines: [{ key: 'x', productId: 'p-chopp', name: 'Chopp', price: '9.90', quantity: 2, note: '' }],
      pendingKey: null,
    });
    abrir();
    expect(texto()).toContain('Rascunho guardado neste aparelho');
    http.expectNone((r) => r.url === '/api/v1/orders' && r.method === 'POST');
  });
});
