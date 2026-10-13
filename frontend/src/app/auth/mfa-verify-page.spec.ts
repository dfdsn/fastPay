import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { SESSAO, comCookieCsrf, sessaoNaEtapa } from './auth-testing';
import { MfaVerifyPage } from './mfa-verify-page';

describe('MfaVerifyPage', () => {
  let fixture: ComponentFixture<MfaVerifyPage>;
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;

  const element = () => fixture.nativeElement as HTMLElement;
  const campo = () => element().querySelector<HTMLInputElement>('input[formcontrolname="code"]')!;
  const alerta = () => element().querySelector('[role="alert"]')?.textContent?.trim() ?? '';

  function abrir(sessao: object, status = 200): void {
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [MfaVerifyPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    fixture = TestBed.createComponent(MfaVerifyPage);
    fixture.detectChanges();
    http
      .expectOne('/api/v1/auth/session')
      .flush(sessao, { status, statusText: status === 200 ? 'OK' : 'Unauthorized' });
  }

  function enviar(codigo: string): void {
    campo().value = codigo;
    campo().dispatchEvent(new Event('input'));
    element().querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it('campo com rótulo e preenchimento de código único', () => {
    abrir(sessaoNaEtapa('MFA_PENDENTE'));
    expect(element().querySelector('h1')?.textContent).toContain('Verificação em duas etapas');
    expect(campo().getAttribute('autocomplete')).toBe('one-time-code');
    expect(element().textContent).toContain('códigos de recuperação');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('código certo entra e vai para as unidades', () => {
    abrir(sessaoNaEtapa('MFA_PENDENTE'));
    enviar(' 123456 ');
    const pedido = http.expectOne('/api/v1/auth/mfa/verify');
    expect(pedido.request.body).toEqual({ code: '123456' });
    pedido.flush(SESSAO);

    expect(navigate).toHaveBeenCalledWith(['/unidades']);
  });

  it('código de recuperação leva ao recadastro do autenticador', () => {
    abrir(sessaoNaEtapa('MFA_PENDENTE'));
    enviar('abcd-efgh-jkmn-pqrs');
    http.expectOne('/api/v1/auth/mfa/verify').flush(sessaoNaEtapa('MFA_RECADASTRO'));

    expect(navigate).toHaveBeenCalledWith(['/configurar-verificacao']);
  });

  it('código errado mostra mensagem e limpa o campo; bloqueio também', () => {
    abrir(sessaoNaEtapa('MFA_PENDENTE'));
    enviar('000000');
    http
      .expectOne('/api/v1/auth/mfa/verify')
      .flush({ code: 'CODIGO_INVALIDO' }, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();
    expect(alerta()).toBe('Código incorreto ou já usado. Confira o aplicativo e tente de novo.');
    expect(campo().value).toBe('');

    enviar('111111');
    http
      .expectOne('/api/v1/auth/mfa/verify')
      .flush({ code: 'MUITAS_TENTATIVAS' }, { status: 429, statusText: 'Too Many' });
    fixture.detectChanges();
    expect(alerta()).toContain('Muitas tentativas');
  });

  it('etapa vencida volta para o login', () => {
    abrir(sessaoNaEtapa('MFA_PENDENTE'));
    enviar('123456');
    http
      .expectOne('/api/v1/auth/mfa/verify')
      .flush({ code: 'SESSAO_AUSENTE' }, { status: 401, statusText: 'Unauthorized' });

    expect(navigate).toHaveBeenCalledWith(['/entrar']);
  });

  it('sem sessão vai para o login; já autenticado vai para as unidades', () => {
    abrir({ code: 'SESSAO_AUSENTE' }, 401);
    expect(navigate).toHaveBeenCalledWith(['/entrar']);
    http.verify();
    TestBed.resetTestingModule();

    abrir(SESSAO);
    expect(navigate).toHaveBeenCalledWith(['/unidades']);
  });
});
