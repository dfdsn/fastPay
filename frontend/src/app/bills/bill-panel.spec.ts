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
