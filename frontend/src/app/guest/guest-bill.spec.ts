import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { comCookieCsrf } from '../auth/auth-testing';
import { GuestBill, GuestClosing } from '../bills/bills.service';
import { GuestBillPanel } from './guest-bill';

const URL = '/api/v1/guest/tabs/t-1/bill';
const FECHAMENTO = '/api/v1/guest/tabs/t-1/closing';

const ELEGIVEL: GuestClosing = {
  tabId: 't-1',
  state: 'ABERTA',
  startedAt: null,
  versionNumber: null,
  total: '90.47',
  blockers: { pendingItems: [], openIssue: false, discountReview: false },
  canStart: true,
};

const CONTA: GuestBill = {
  tabId: 't-1',
  open: true,
  closing: false,
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
  discount: '7.18',
  couvert: '15.00',
  couvertState: 'COBRADO',
  service: {
    enabled: true,
    percent: '12.5000',
    base: 'ANTES_DESCONTO',
    includesCouvert: true,
    waived: false,
    baseAmount: '86.80',
    amount: '10.85',
  },
  total: '90.47',
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

describe('Conta do cliente', () => {
  let http: HttpTestingController;

  function montar(conta: GuestBill) {
    comCookieCsrf(true);
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(GuestBillPanel);
    fixture.componentRef.setInput('tabId', 't-1');
    fixture.detectChanges();
    http.expectOne(URL).flush(conta);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => {
    http.match(`${FECHAMENTO}`);
    http.verify();
  });

  it('mostra itens com valores, desconto, couvert, serviço e total separados', () => {
    const t = texto(montar(CONTA).nativeElement as HTMLElement);
    expect(t).toContain('2 × Batata frita R$ 71,80');
    expect(t).toContain('Desconto − R$ 7,18');
    expect(t).toContain('Couvert R$ 15,00');
    expect(t).toContain('Serviço (12,5% sobre o consumo antes do desconto e o couvert) R$ 10,85');
    expect(t).toContain('Total R$ 90,47');
  });

  it('retira o serviço sem autorização; recusa do servidor aparece', () => {
    const fixture = montar(CONTA);
    const el = fixture.nativeElement as HTMLElement;
    (el.querySelector('button') as HTMLButtonElement).click();
    const pedido = http.expectOne((r) => r.method === 'PUT' && r.url === `${URL}/service`);
    expect(pedido.request.body).toEqual({ waived: true });
    pedido.flush({ ...CONTA, service: { ...CONTA.service, waived: true, amount: '0.00' }, total: '79.62' });
    fixture.detectChanges();
    expect(texto(el)).toContain('retirado');
    expect(texto(el)).toContain('Total R$ 79,62');
    expect(texto(el)).toContain('Serviço retirado da sua conta.');

    (el.querySelector('button') as HTMLButtonElement).click();
    http
      .expectOne(`${URL}/service`)
      .flush(
        { code: 'COMANDA_ENCERRADA', detail: 'Esta comanda já foi encerrada: a conta não muda mais.' },
        { status: 409, statusText: 'Conflict' },
      );
    fixture.detectChanges();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('já foi encerrada');
  });

  it('couvert já cobrado em outra comanda da visita não se repete e aparece explicado (H26)', () => {
    const t = texto(
      montar({ ...CONTA, couvert: '0.00', couvertState: 'COBRADO_EM_OUTRA', total: '75.47' })
        .nativeElement as HTMLElement,
    );
    expect(t).not.toContain('Couvert R$');
    expect(t).toContain('Couvert já cobrado em outra comanda desta visita: não se repete.');
    expect(t).not.toContain('Não concorda com o couvert?');
  });

  it('fecha a conta com o total que vê; com item pendente o botão fica desabilitado (H28)', () => {
    const fixture = montar(CONTA);
    http.expectOne(FECHAMENTO).flush({
      ...ELEGIVEL,
      blockers: {
        pendingItems: [{ orderLineId: 'l-1', productName: 'Batata frita', quantity: 2, stage: 'EM_PREPARO' }],
        openIssue: false,
        discountReview: false,
      },
      canStart: false,
    });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const fechar = () =>
      Array.from(el.querySelectorAll('button')).find((b) =>
        b.textContent?.includes('Fechar a conta'),
      ) as HTMLButtonElement;
    expect(texto(el)).toContain('2 × Batata frita: em preparo.');
    expect(fechar().disabled).toBe(true);

    http.match(FECHAMENTO);
    fixture.componentRef.setInput('refresh', 1);
    fixture.detectChanges();
    http.expectOne(URL).flush(CONTA);
    http.expectOne(FECHAMENTO).flush(ELEGIVEL);
    fixture.detectChanges();
    expect(texto(el)).toContain('Fechar a conta (R$ 90,47)');
    fechar().click();
    const pedido = http.expectOne((r) => r.method === 'POST' && r.url === FECHAMENTO);
    expect(pedido.request.body).toEqual({ expectedTotal: '90.47' });
    pedido.flush({ ...ELEGIVEL, state: 'EM_FECHAMENTO', versionNumber: 1, blockers: null, canStart: false });
    http.expectOne(URL).flush({ ...CONTA, closing: true });
    http.expectOne(FECHAMENTO).flush({ ...ELEGIVEL, state: 'EM_FECHAMENTO', versionNumber: 1, blockers: null });
    fixture.detectChanges();
    expect(texto(el)).toContain('Conta fechada em R$ 90,47');
    expect(el.querySelector('[role="status"]')?.textContent).toContain('Conta fechada.');
  });

  it('sem pedidos e conta encerrada: sem botão de serviço', () => {
    const el = montar({ ...CONTA, lines: [], open: false }).nativeElement as HTMLElement;
    expect(texto(el)).toContain('Nenhum pedido ainda');
    expect(el.querySelector('button')).toBeNull();
  });
});
