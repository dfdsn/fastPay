import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { comCookieCsrf } from './auth-testing';
import { ForgotPasswordPage } from './forgot-password-page';

describe('ForgotPasswordPage', () => {
  let fixture: ComponentFixture<ForgotPasswordPage>;
  let http: HttpTestingController;

  const element = () => fixture.nativeElement as HTMLElement;

  beforeEach(() => {
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [ForgotPasswordPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ForgotPasswordPage);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  function enviar(email: string): void {
    const input = element().querySelector<HTMLInputElement>('input[type="email"]')!;
    input.value = email;
    input.dispatchEvent(new Event('input'));
    element().querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    fixture.detectChanges();
  }

  it('mostra a resposta genérica do servidor no lugar do formulário', () => {
    enviar('ana@bar.com');
    const pedido = http.expectOne('/api/v1/auth/password-reset');
    expect(pedido.request.body).toEqual({ email: 'ana@bar.com' });
    pedido.flush({ detail: 'Se o e-mail estiver cadastrado, enviaremos um link.' });
    fixture.detectChanges();

    expect(element().querySelector('[role="status"]')?.textContent).toContain(
      'Se o e-mail estiver cadastrado',
    );
    expect(element().querySelector('form')).toBeNull();
  });

  it('e-mail inválido não chama o servidor', () => {
    enviar('sem-arroba');
    expect(element().textContent).toContain('Informe um e-mail válido.');
  });

  it('muitas tentativas', () => {
    enviar('ana@bar.com');
    http
      .expectOne('/api/v1/auth/password-reset')
      .flush({ code: 'MUITAS_TENTATIVAS' }, { status: 429, statusText: 'Too Many' });
    fixture.detectChanges();

    expect(element().querySelector('[role="alert"]')?.textContent).toContain('Muitas tentativas');
  });
});
