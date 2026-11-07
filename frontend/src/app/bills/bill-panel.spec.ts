import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { comCookieCsrf } from '../auth/auth-testing';
import { BillPanel } from './bill-panel';
import { Bill } from './bills.service';

const URL = '/api/v1/tabs/t-1/bill';

const CONTA: Bill = {
  tabId: 't-1',
  open: true,
  lines: [
    {
      orderLineId: 'l-1',
      productName: 'Batata frita',
      quantity: 2,
      unitPrice: '35.90',
      lineTotal: '71.80',
      sentAt: '2026-11-04T19:00:00Z',
    },
  ],
  consumption: '71.80',
  discount: null,
  couvert: '0.00',
  couvertDetail: {
    mode: null,
    state: 'SEM_COUVERT',
    chargeId: null,
    origin: null,
    amount: '0.00',
    chargedOnTab: null,
    canInclude: false,
    includeAmount: null,
    canCancel: false,
    canCorrect: false,
    history: [],
  },
  service: {
    enabled: true,
    percent: '10.0000',
    base: 'APOS_DESCONTO',
    includesCouvert: false,
    waived: false,
    baseAmount: '71.80',
    amount: '7.18',
  },
  total: '78.98',
  reviewPending: false,
  policy: { fixedAllowed: true, percentAllowed: true, canGrant: true, myLimitPercent: '10.0000' },
};

const COM_DESCONTO_EM_REVISAO: Bill = {
  ...CONTA,
  consumption: '9.90',
  discount: {
    id: 'd-1',
    kind: 'FIXO',
    value: '20.30',
    amount: '9.90',
    effectivePercent: '100.00',
    reason: 'Demora no pedido',
    grantedBy: { id: 'u-1', name: 'Gui Gerente' },
    grantedAt: '2026-11-04T19:10:00Z',
    consumptionAtGrant: '101.50',
    authorityPercent: '20.0000',
    needsReview: true,
    reviewedBy: null,
    reviewedAt: null,
  },
  total: '0.00',
  reviewPending: true,
};

/** Texto visível com um espaço entre os elementos (o real formata R$ com espaço não separável). */
function texto(el: HTMLElement): string {
  const partes: string[] = [];
  const nos = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
  while (nos.nextNode()) {
    partes.push(nos.currentNode.textContent ?? '');
  }
  return partes.join(' ').replace(/[\s\u00a0]+/g, ' ');
}

function digitar(el: HTMLElement, seletor: string, valor: string): void {
  const campo = el.querySelector(seletor) as HTMLInputElement;
  campo.value = valor;
  campo.dispatchEvent(new Event('input'));
}

describe('Conta da comanda (equipe)', () => {
  let http: HttpTestingController;

  function montar(conta: Bill) {
    comCookieCsrf(true);
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(BillPanel);
    fixture.componentRef.setInput('tabId', 't-1');
    fixture.detectChanges();
    http.expectOne(URL).flush(conta);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => http.verify());

  it('mostra consumo, serviço e total como o servidor calculou', () => {
    const el = montar(CONTA).nativeElement as HTMLElement;
    const t = texto(el);
    expect(t).toContain('Consumo R$ 71,80');
    expect(t).toContain('Serviço (10% sobre o consumo após desconto) R$ 7,18');
    expect(t).toContain('Total R$ 78,98');
    expect(t).toContain('Seu limite: 10% do consumo');
    expect(t).not.toContain('Couvert');
  });

  it('concede desconto com o desconto atual, formato, valor e motivo; recusa do servidor aparece e relê', () => {
    const fixture = montar(CONTA);
    const el = fixture.nativeElement as HTMLElement;
    digitar(el, 'input[formcontrolname="value"]', '7,18');
    digitar(el, 'input[formcontrolname="reason"]', 'ruim');
    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('5 a 300 caracteres');

    digitar(el, 'input[formcontrolname="reason"]', '  Cliente fiel  ');
    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const pedido = http.expectOne((r) => r.method === 'PUT' && r.url === `${URL}/discount`);
    expect(pedido.request.body).toEqual({
      currentDiscountId: null,
      kind: 'FIXO',
      value: '7,18',
      reason: 'Cliente fiel',
    });
    pedido.flush(
      { code: 'DESCONTO_ACIMA_DO_LIMITE', detail: 'Este desconto passa do seu limite.' },
      { status: 403, statusText: 'Forbidden' },
    );
    http.expectOne(URL).flush(CONTA);
    fixture.detectChanges();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('passa do seu limite');
  });

  it('desconto em revisão avisa o bloqueio; revisar e retirar usam o desconto mostrado', () => {
    const fixture = montar(COM_DESCONTO_EM_REVISAO);
    const el = fixture.nativeElement as HTMLElement;
    const t = texto(el);
    expect(t).toContain('Desconto (R$ 20,30) − R$ 9,90');
    expect(t).toContain('"Demora no pedido"');
    expect(t).toContain('A cobrança fica bloqueada até alguém com limite de pelo menos 100%');
    expect(el.querySelector('h3')?.textContent).toContain('Trocar desconto');

    const revisar = Array.from(el.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Revisar e manter'),
    ) as HTMLButtonElement;
    revisar.click();
    const revisao = http.expectOne(`${URL}/discount/review`);
    expect(revisao.request.body).toEqual({ discountId: 'd-1' });
    revisao.flush({
      ...COM_DESCONTO_EM_REVISAO,
      reviewPending: false,
      discount: { ...COM_DESCONTO_EM_REVISAO.discount!, needsReview: false },
    });
    fixture.detectChanges();
    expect(texto(el)).toContain('a cobrança está liberada');

    const retirar = Array.from(el.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Retirar desconto'),
    ) as HTMLButtonElement;
    retirar.click();
    fixture.detectChanges();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('por que o desconto sai');
    digitar(el, 'input[maxlength="300"]:not([formcontrolname])', 'Concedido por engano');
    retirar.click();
    const retirada = http.expectOne(`${URL}/discount/remove`);
    expect(retirada.request.body).toEqual({ discountId: 'd-1', reason: 'Concedido por engano' });
    retirada.flush(CONTA);
  });

  it('serviço sai a pedido do cliente; sem a permissão não há desconto para conceder', () => {
    const fixture = montar({ ...CONTA, policy: { ...CONTA.policy, canGrant: false } });
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('form')).toBeNull();
    const botao = Array.from(el.querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Retirar o serviço'),
    ) as HTMLButtonElement;
    botao.click();
    const pedido = http.expectOne(`${URL}/service`);
    expect(pedido.request.body).toEqual({ waived: true });
    pedido.flush({ ...CONTA, service: { ...CONTA.service, waived: true, amount: '0.00' }, total: '71.80' });
    fixture.detectChanges();
    expect(texto(el)).toContain('retirado pelo cliente');
    expect(texto(el)).toContain('Total R$ 71,80');
    expect(texto(el)).toContain('Devolver o serviço');
  });
});

describe('Couvert na conta da comanda (H26)', () => {
  let http: HttpTestingController;

  function montar(conta: Bill) {
    comCookieCsrf(true);
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(BillPanel);
    fixture.componentRef.setInput('tabId', 't-1');
    fixture.detectChanges();
    http.expectOne(URL).flush(conta);
    fixture.detectChanges();
    return fixture;
  }

  function botao(el: HTMLElement, rotulo: string): HTMLButtonElement {
    return Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes(rotulo)) as HTMLButtonElement;
  }

  afterEach(() => http.verify());

  it('inclui o couvert manual com o valor que o servidor informa', () => {
    const fixture = montar({
      ...CONTA,
      couvertDetail: { ...CONTA.couvertDetail, mode: 'MANUAL', canInclude: true, includeAmount: '12.00' },
    });
    const el = fixture.nativeElement as HTMLElement;
    expect(texto(el)).toContain('Couvert lançado pela equipe: ainda não incluído.');
    botao(el, 'Incluir couvert').click();
    const pedido = http.expectOne((r) => r.method === 'POST' && r.url === `${URL}/couvert`);
    pedido.flush({
      ...CONTA,
      couvert: '12.00',
      total: '90.98',
      couvertDetail: {
        ...CONTA.couvertDetail,
        mode: 'MANUAL',
        state: 'COBRADO',
        chargeId: 'k-1',
        origin: 'MANUAL',
        amount: '12.00',
      },
    });
    fixture.detectChanges();
    expect(texto(el)).toContain('R$ 12,00 incluído pela equipe');
    expect(texto(el)).toContain('Couvert incluído.');
    expect(botao(el, 'Incluir couvert')).toBeUndefined();
  });

  it('mostra a comanda da visita que tem o couvert e o histórico do não repetido', () => {
    const el = montar({
      ...CONTA,
      couvertDetail: {
        ...CONTA.couvertDetail,
        mode: 'AUTOMATICO',
        state: 'COBRADO_EM_OUTRA',
        chargedOnTab: { id: 't-0', number: 7, name: 'Ana' },
        history: [
          {
            id: 'k-2',
            origin: 'AUTOMATICO',
            amount: '15.00',
            endKind: 'NAO_REPETIDO',
            reasonKind: null,
            reason: null,
            endedBy: { id: 'u-1', name: 'Gui Gerente' },
            endedAt: '2026-11-07T20:00:00Z',
          },
        ],
      },
    }).nativeElement as HTMLElement;
    const t = texto(el);
    expect(t).toContain('Couvert já cobrado na comanda 7, da mesma visita: não se repete.');
    expect(t).toContain('Couvert de R$ 15,00 não repetido');
    expect(botao(el, 'Cancelar couvert')).toBeUndefined();
  });

  it('cancela com motivo e texto; depois de correção inclui o valor correto', () => {
    const cobrado: Bill = {
      ...CONTA,
      couvert: '15.00',
      couvertDetail: {
        ...CONTA.couvertDetail,
        mode: 'AUTOMATICO',
        state: 'COBRADO',
        chargeId: 'k-1',
        origin: 'AUTOMATICO',
        amount: '15.00',
        canCancel: true,
      },
    };
    const fixture = montar(cobrado);
    const el = fixture.nativeElement as HTMLElement;
    expect(texto(el)).toContain('R$ 15,00 automático');
    const form = el.querySelector('form[aria-label="Cancelar couvert"]') as HTMLFormElement;
    const motivo = form.querySelector('select') as HTMLSelectElement;
    motivo.value = 'CORRECAO';
    motivo.dispatchEvent(new Event('change'));
    form.dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('por que o couvert sai');
    digitar(form, 'input[formcontrolname="reason"]', 'Valor da semana passada');
    form.dispatchEvent(new Event('submit'));
    const cancelamento = http.expectOne(`${URL}/couvert/cancel`);
    expect(cancelamento.request.body).toEqual({
      chargeId: 'k-1',
      reasonKind: 'CORRECAO',
      reason: 'Valor da semana passada',
    });
    cancelamento.flush({
      ...CONTA,
      couvertDetail: { ...CONTA.couvertDetail, mode: 'AUTOMATICO', state: 'AGUARDANDO_CORRECAO', canCorrect: true },
    });
    fixture.detectChanges();
    expect(texto(el)).toContain('aguarda a inclusão do valor correto');

    const correcao = el.querySelector('form[aria-label="Incluir correção do couvert"]') as HTMLFormElement;
    digitar(correcao, 'input[formcontrolname="amount"]', '10,00');
    digitar(correcao, 'input[formcontrolname="reason"]', 'Valor desta semana');
    correcao.dispatchEvent(new Event('submit'));
    const pedido = http.expectOne(`${URL}/couvert/correction`);
    expect(pedido.request.body).toEqual({ amount: '10.00', reason: 'Valor desta semana' });
    pedido.flush(cobrado);
  });
});
