import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { comCookieCsrf } from '../auth/auth-testing';
import { GuestIssuesPanel } from './guest-issues';
import { GuestIssues } from './guest.service';

const DADOS: GuestIssues = {
  now: '2026-11-04T20:00:00Z',
  closed: false,
  items: [{ orderLineId: 'l-1', productName: 'Batata frita', quantity: 2, sentAt: '2026-11-04T19:00:00Z' }],
  issues: [
    {
      id: 'p-1',
      item: null,
      description: 'Cobraram couvert',
      status: 'TRATADO',
      afterClosure: false,
      createdAt: '2026-11-04T19:30:00Z',
      attendedAt: '2026-11-04T19:31:00Z',
      concludedAt: '2026-11-04T19:40:00Z',
      resolution: 'Couvert retirado',
    },
  ],
};

describe('Problemas do cliente', () => {
  let http: HttpTestingController;

  function montar() {
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(GuestIssuesPanel);
    fixture.componentRef.setInput('tabId', 't-1');
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => http.verify());

  it('lista os itens para apontar e os problemas com a resolução', () => {
    const fixture = montar();
    http.expectOne('/api/v1/guest/tabs/t-1/issues').flush(DADOS);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const opcoes = Array.from(el.querySelectorAll('option')).map((o) => o.textContent?.trim());
    expect(opcoes).toEqual(['A conta', '2 × Batata frita']);
    expect(el.textContent).toContain('Tratado');
    expect(el.textContent).toContain('Resolução: Couvert retirado');
  });

  it('informa problema do item e relê; recusa do servidor aparece', () => {
    const fixture = montar();
    http.expectOne('/api/v1/guest/tabs/t-1/issues').flush(DADOS);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const select = el.querySelector('select') as HTMLSelectElement;
    select.value = 'l-1';
    select.dispatchEvent(new Event('change'));
    const texto = el.querySelector('textarea') as HTMLTextAreaElement;
    texto.value = '  Veio fria  ';
    texto.dispatchEvent(new Event('input'));
    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const envio = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/guest/tabs/t-1/issues');
    expect(envio.request.body).toEqual({ orderLineId: 'l-1', description: 'Veio fria' });
    envio.flush({ ...DADOS.issues[0], id: 'p-2', status: 'ABERTO' });
    http.expectOne('/api/v1/guest/tabs/t-1/issues').flush(DADOS);
    fixture.detectChanges();
    expect(el.textContent).toContain('Problema informado.');

    texto.value = 'Veio fria de novo';
    texto.dispatchEvent(new Event('input'));
    select.value = 'l-1';
    select.dispatchEvent(new Event('change'));
    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    http
      .expectOne((r) => r.method === 'POST')
      .flush(
        { code: 'PROBLEMA_JA_ABERTO', detail: 'Você já informou um problema sobre isso.' },
        { status: 409, statusText: 'Conflict' },
      );
    fixture.detectChanges();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('Você já informou um problema');
  });

  it('texto curto não vai ao servidor; conta encerrada avisa que o pagamento continua válido', () => {
    const fixture = montar();
    http.expectOne('/api/v1/guest/tabs/t-1/issues').flush({ ...DADOS, closed: true });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('o pagamento continua válido');
    const texto = el.querySelector('textarea') as HTMLTextAreaElement;
    texto.value = 'ruim';
    texto.dispatchEvent(new Event('input'));
    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    expect(el.querySelector('[role="alert"]')?.textContent).toContain('5 a 300 caracteres');
  });
});
