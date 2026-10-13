import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { SESSAO, comCookieCsrf, sessaoNaEtapa } from './auth-testing';
import { LoginPage } from './login-page';

describe('LoginPage', () => {
  let fixture: ComponentFixture<LoginPage>;
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;

  const element = () => fixture.nativeElement as HTMLElement;
  const input = (name: string) =>
    element().querySelector<HTMLInputElement>(`input[formcontrolname="${name}"]`)!;
  const alerta = () => element().querySelector('[role="alert"]')?.textContent?.trim() ?? '';

  beforeEach(() => {
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [LoginPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    fixture = TestBed.createComponent(LoginPage);
    fixture.detectChanges();
    http
      .expectOne('/api/v1/auth/session')
      .flush({ code: 'SESSAO_AUSENTE' }, { status: 401, statusText: 'Unauthorized' });
  });

  afterEach(() => http.verify());

  function preencher(email: string, senha: string): void {
    input('email').value = email;
    input('email').dispatchEvent(new Event('input'));
    input('password').value = senha;
    input('password').dispatchEvent(new Event('input'));
    element().querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    fixture.detectChanges();
  }

  it('campos com rótulo e autocomplete de credenciais', () => {
    expect(element().querySelector('h1')?.textContent).toContain('Entrar');
    expect(input('email').getAttribute('autocomplete')).toBe('username');
    expect(input('password').type).toBe('password');
    expect(element().textContent).toContain('E-mail');
    expect(element().querySelector('a[href="/esqueci-a-senha"]')).not.toBeNull();
  });

  it('formulário vazio mostra o que falta e não chama o servidor', () => {
    preencher('', '');
    expect(element().textContent).toContain('Informe seu e-mail.');
    expect(element().textContent).toContain('Informe sua senha.');
  });

  it('entra e vai para a escolha de unidade', () => {
    preencher('ana@bar.com', 'segredo longo');
    http.expectOne('/api/v1/auth/login').flush(SESSAO);

    expect(navigate).toHaveBeenCalledWith(['/unidades']);
  });

  it('senha certa com MFA vai para a verificação ou para o cadastro do autenticador', () => {
    preencher('ana@bar.com', 'segredo longo');
    http.expectOne('/api/v1/auth/login').flush(sessaoNaEtapa('MFA_PENDENTE'));
    expect(navigate).toHaveBeenCalledWith(['/verificacao']);
    fixture.detectChanges();

    preencher('ana@bar.com', 'segredo longo');
    http.expectOne('/api/v1/auth/login').flush(sessaoNaEtapa('MFA_CADASTRO_OBRIGATORIO'));
    expect(navigate).toHaveBeenCalledWith(['/configurar-verificacao']);
  });

  it('credenciais erradas: mensagem que não revela a conta e senha apagada', () => {
    preencher('ana@bar.com', 'errada');
    http
      .expectOne('/api/v1/auth/login')
      .flush({ code: 'CREDENCIAIS_INVALIDAS' }, { status: 401, statusText: 'Unauthorized' });
    fixture.detectChanges();

    expect(alerta()).toBe('E-mail ou senha incorretos.');
    expect(input('password').value).toBe('');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('bloqueio por tentativas e falha de rede têm mensagens próprias', () => {
    preencher('ana@bar.com', 'errada');
    http
      .expectOne('/api/v1/auth/login')
      .flush({ code: 'MUITAS_TENTATIVAS' }, { status: 429, statusText: 'Too Many' });
    fixture.detectChanges();
    expect(alerta()).toContain('Muitas tentativas');

    preencher('ana@bar.com', 'outra');
    http.expectOne('/api/v1/auth/login').error(new ProgressEvent('error'));
    fixture.detectChanges();
    expect(alerta()).toContain('Verifique sua conexão');
  });
});

describe('LoginPage com sessão ativa', () => {
  it('vai direto para as unidades', () => {
    TestBed.configureTestingModule({
      imports: [LoginPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    TestBed.createComponent(LoginPage).detectChanges();

    TestBed.inject(HttpTestingController).expectOne('/api/v1/auth/session').flush(SESSAO);

    expect(navigate).toHaveBeenCalledWith(['/unidades']);
  });
});
