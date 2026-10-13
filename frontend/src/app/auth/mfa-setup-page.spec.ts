import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { SESSAO, comCookieCsrf, sessaoNaEtapa } from './auth-testing';
import { MfaSetupPage } from './mfa-setup-page';

const CADASTRO = {
  secret: 'GEZD GNBV GY3T QOJQ GEZD GNBV GY3T QOJQ',
  otpauthUri: 'otpauth://totp/fastPay:ana%40bar.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ',
};
const CODIGOS = Array.from({ length: 10 }, (_, i) => `abcd-efgh-jkmn-pqr${i + 2}`);

describe('MfaSetupPage', () => {
  let fixture: ComponentFixture<MfaSetupPage>;
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;

  const element = () => fixture.nativeElement as HTMLElement;
  const campo = () => element().querySelector<HTMLInputElement>('input[formcontrolname="code"]')!;
  const alerta = () => element().querySelector('[role="alert"]')?.textContent?.trim() ?? '';

  function abrir(sessao: object): void {
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [MfaSetupPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    fixture = TestBed.createComponent(MfaSetupPage);
    fixture.detectChanges();
    http.expectOne('/api/v1/auth/session').flush(sessao);
    fixture.detectChanges();
  }

  function ativar(codigo: string): void {
    campo().value = codigo;
    campo().dispatchEvent(new Event('input'));
    element().querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it('cadastro obrigatório mostra a chave, o link do aplicativo e explica o motivo', () => {
    abrir(sessaoNaEtapa('MFA_CADASTRO_OBRIGATORIO'));
    http.expectOne('/api/v1/auth/mfa/setup').flush(CADASTRO);
    fixture.detectChanges();

    expect(element().textContent).toContain('Seu perfil exige a verificação em duas etapas');
    expect(element().querySelector('.secret')?.textContent).toContain('GEZD GNBV');
    expect(element().querySelector('a')?.getAttribute('href')).toBe(CADASTRO.otpauthUri);
    expect(campo().getAttribute('inputmode')).toBe('numeric');
  });

  it('código certo mostra os dez códigos de recuperação uma vez e depois segue', () => {
    abrir(sessaoNaEtapa('MFA_CADASTRO_OBRIGATORIO'));
    http.expectOne('/api/v1/auth/mfa/setup').flush(CADASTRO);
    fixture.detectChanges();

    ativar('123456');
    const pedido = http.expectOne('/api/v1/auth/mfa/setup/confirm');
    expect(pedido.request.body).toEqual({ code: '123456' });
    pedido.flush({ recoveryCodes: CODIGOS, session: SESSAO });
    fixture.detectChanges();

    expect(element().querySelectorAll('.codes li')).toHaveLength(10);
    expect(element().textContent).toContain('não serão mostrados de novo');
    expect(element().querySelector('form')).toBeNull();
    element().querySelector<HTMLButtonElement>('button[type="button"]')!.click();
    expect(navigate).toHaveBeenCalledWith(['/unidades']);
  });

  it('código errado mostra a mensagem e mantém a chave', () => {
    abrir(sessaoNaEtapa('MFA_RECADASTRO'));
    http.expectOne('/api/v1/auth/mfa/setup').flush(CADASTRO);
    fixture.detectChanges();
    expect(element().textContent).toContain('código de recuperação');

    ativar('000000');
    http
      .expectOne('/api/v1/auth/mfa/setup/confirm')
      .flush({ code: 'CODIGO_INVALIDO' }, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    expect(alerta()).toBe('Código incorreto ou já usado. Confira o aplicativo e tente de novo.');
    expect(element().querySelector('.secret')).not.toBeNull();
  });

  it('quem já tem MFA ativo vê o aviso', () => {
    abrir(SESSAO);
    http
      .expectOne('/api/v1/auth/mfa/setup')
      .flush({ code: 'MFA_JA_ATIVO' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();

    expect(element().textContent).toContain('já está ativa');
  });

  it('etapa de código pendente vai para a verificação', () => {
    abrir(sessaoNaEtapa('MFA_PENDENTE'));
    expect(navigate).toHaveBeenCalledWith(['/verificacao']);
  });
});
