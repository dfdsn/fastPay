import { Location } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';

import { comCookieCsrf } from './auth-testing';
import { ResetPasswordPage } from './reset-password-page';

describe('ResetPasswordPage', () => {
  let fixture: ComponentFixture<ResetPasswordPage>;
  let http: HttpTestingController;
  let replaceState: ReturnType<typeof vi.spyOn>;

  const element = () => fixture.nativeElement as HTMLElement;
  const status = () => element().querySelector('[role="status"]')?.textContent?.trim() ?? '';
  const alerta = () => element().querySelector('[role="alert"]')?.textContent?.trim() ?? '';

  function abrir(fragment: string | null): void {
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [ResetPasswordPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { fragment } } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    replaceState = vi.spyOn(TestBed.inject(Location), 'replaceState');
    fixture = TestBed.createComponent(ResetPasswordPage);
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  function salvar(senha: string, repeticao = senha): void {
    for (const [name, value] of [
      ['password', senha],
      ['confirmation', repeticao],
    ]) {
      const input = element().querySelector<HTMLInputElement>(`input[formcontrolname="${name}"]`)!;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    }
    element().querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    fixture.detectChanges();
  }

  it('lê o token do fragmento, tira da barra de endereço e salva', () => {
    abrir('token=abc_123');
    expect(replaceState).toHaveBeenCalledWith('/redefinir-senha');

    salvar('frase nova bem longa');
    const pedido = http.expectOne('/api/v1/auth/password-reset/confirm');
    expect(pedido.request.body).toEqual({ token: 'abc_123', newPassword: 'frase nova bem longa' });
    pedido.flush(null, { status: 204, statusText: 'No Content' });
    fixture.detectChanges();

    expect(status()).toBe('Senha alterada. Entre com a nova senha.');
    expect(element().querySelector('a[href="/entrar"]')).not.toBeNull();
  });

  it('sem token pede um novo link', () => {
    abrir(null);
    expect(status()).toContain('O link está incompleto');
    expect(element().querySelector('form')).toBeNull();
    expect(element().querySelector('a[href="/esqueci-a-senha"]')).not.toBeNull();
  });

  it('repetição diferente não chama o servidor', () => {
    abrir('token=abc');
    salvar('frase nova bem longa', 'outra frase qualquer');
    expect(element().textContent).toContain('As senhas não são iguais.');
  });

  it('mostra o motivo da recusa da senha e mantém o formulário', () => {
    abrir('token=abc');
    salvar('123456789012');
    http
      .expectOne('/api/v1/auth/password-reset/confirm')
      .flush(
        { code: 'SENHA_RECUSADA', detail: 'Esta senha é muito comum. Escolha outra.' },
        { status: 400, statusText: 'Bad Request' },
      );
    fixture.detectChanges();

    expect(alerta()).toBe('Esta senha é muito comum. Escolha outra.');
    expect(element().querySelector('form')).not.toBeNull();
  });

  it('link usado ou vencido', () => {
    abrir('token=abc');
    salvar('frase nova bem longa');
    http
      .expectOne('/api/v1/auth/password-reset/confirm')
      .flush({ code: 'LINK_INVALIDO' }, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();

    expect(status()).toBe('Este link de redefinição não vale mais. Peça um novo.');
    expect(element().querySelector('form')).toBeNull();
  });
});
