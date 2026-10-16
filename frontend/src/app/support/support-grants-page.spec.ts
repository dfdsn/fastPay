import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { comCookieCsrf } from '../auth/auth-testing';
import { SupportGrantsPage } from './support-grants-page';
import { SupportGrant, SupportGrants } from './support.service';

const VIGENTE: SupportGrant = {
  id: 'g-1',
  establishmentId: 'a',
  establishmentName: 'Bar da Praia',
  supportEmail: 'suporte@fastpay.test',
  reason: 'Conferir convites que não chegaram',
  grantedAt: '2026-10-16T12:00:00Z',
  expiresAt: '2026-10-17T12:00:00Z',
  revokedAt: null,
  status: 'ATIVA',
  consultations: 2,
  lastConsultationAt: '2026-10-16T13:00:00Z',
};

describe('SupportGrantsPage', () => {
  let fixture: ComponentFixture<SupportGrantsPage>;
  let http: HttpTestingController;

  const element = () => fixture.nativeElement as HTMLElement;
  const status = () => element().querySelector('[role="status"]')?.textContent?.trim() ?? '';
  const alerta = () => element().querySelector('[role="alert"]')?.textContent?.trim() ?? '';
  const botao = (texto: string) =>
    Array.from(element().querySelectorAll<HTMLButtonElement>('button')).find((b) =>
      b.textContent?.includes(texto),
    );

  function abrir(dados: SupportGrants = { grants: [VIGENTE], maxHours: 72 }): void {
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [SupportGrantsPage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SupportGrantsPage);
    fixture.detectChanges();
    http.expectOne('/api/v1/support-grants').flush(dados);
    fixture.detectChanges();
  }

  function preencher(email: string, motivo: string, horas: number): void {
    for (const [selector, value] of [
      ['input[formcontrolname="supportEmail"]', email],
      ['textarea[formcontrolname="reason"]', motivo],
      ['input[formcontrolname="hours"]', String(horas)],
    ]) {
      const input = element().querySelector<HTMLInputElement>(selector)!;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    }
    botao('Autorizar acesso')!.click();
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it('lista o acesso com prazo, motivo e consultas', () => {
    abrir();
    expect(element().textContent).toContain('suporte@fastpay.test · vigente');
    expect(element().textContent).toContain('Motivo: Conferir convites que não chegaram');
    expect(element().textContent).toContain('Consultas: 2');
  });

  it('concede com motivo e prazo', () => {
    abrir({ grants: [], maxHours: 72 });
    expect(element().textContent).toContain('Nenhum acesso de suporte concedido.');
    preencher(' suporte@fastpay.test ', ' Conferir convites ', 4);
    const pedido = http.expectOne('/api/v1/support-grants');
    expect(pedido.request.method).toBe('POST');
    expect(pedido.request.body).toEqual({
      supportEmail: 'suporte@fastpay.test',
      reason: 'Conferir convites',
      hours: 4,
    });
    pedido.flush({ grants: [VIGENTE], maxHours: 72 });
    fixture.detectChanges();
    expect(status()).toContain('suporte@fastpay.test pode consultar esta unidade por 4 h.');
  });

  it('motivo curto ou prazo acima do máximo não chamam o servidor', () => {
    abrir();
    preencher('suporte@fastpay.test', '  ab ', 4);
    expect(element().textContent).toContain('Explique o motivo, com 5 a 500 caracteres.');
    preencher('suporte@fastpay.test', 'Motivo suficiente', 100);
    expect(element().textContent).toContain('Escolha entre 1 e 72 horas.');
  });

  it('mostra a recusa do servidor e encerra o acesso', () => {
    abrir();
    preencher('outra@exemplo.com', 'Motivo suficiente', 4);
    http
      .expectOne('/api/v1/support-grants')
      .flush(
        { code: 'NAO_E_SUPORTE', detail: 'Este e-mail não é de uma pessoa da equipe fastPay.' },
        { status: 400, statusText: 'Bad Request' },
      );
    fixture.detectChanges();
    expect(alerta()).toBe('Este e-mail não é de uma pessoa da equipe fastPay.');

    botao('Encerrar acesso de suporte@fastpay.test')!.click();
    http
      .expectOne('/api/v1/support-grants/g-1/revoke')
      .flush({
        grants: [{ ...VIGENTE, status: 'ENCERRADA', revokedAt: '2026-10-16T14:00:00Z' }],
        maxHours: 72,
      });
    fixture.detectChanges();
    expect(status()).toContain('Acesso de suporte@fastpay.test encerrado.');
    expect(element().textContent).toContain('suporte@fastpay.test · encerrado');
    expect(botao('Encerrar acesso')).toBeUndefined();
  });

  it('quem não é dono vê o aviso', () => {
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [SupportGrantsPage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SupportGrantsPage);
    fixture.detectChanges();
    http
      .expectOne('/api/v1/support-grants')
      .flush({ code: 'PERMISSAO_NEGADA' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();
    expect(alerta()).toBe('Só o administrador do estabelecimento autoriza o suporte.');
  });
});
