import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { UnitContextService } from '../session/unit-context.service';
import { AuthService } from './auth.service';
import { SESSAO, comCookieCsrf } from './auth-testing';

describe('AuthService', () => {
  let http: HttpTestingController;
  let auth: AuthService;
  let units: UnitContextService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    units = TestBed.inject(UnitContextService);
    comCookieCsrf(true);
  });

  afterEach(() => http.verify());

  it('login guarda a sessão e limpa dados de unidade de quem usou antes', () => {
    const limpeza = vi.fn();
    units.onUnitCleared(limpeza);
    units.current.set({ establishmentId: 'x', name: 'Outra', role: 'GARCOM' });

    auth.login('ana@bar.com', 'segredo longo').subscribe();
    const pedido = http.expectOne('/api/v1/auth/login');
    expect(pedido.request.method).toBe('POST');
    expect(pedido.request.body).toEqual({ email: 'ana@bar.com', password: 'segredo longo' });
    pedido.flush(SESSAO);

    expect(auth.session()?.displayName).toBe('Ana');
    expect(units.current()).toBeNull();
    expect(limpeza).toHaveBeenCalled();
  });

  it('sem cookie CSRF faz uma leitura antes da primeira mutação', () => {
    comCookieCsrf(false);

    auth.requestPasswordReset('ana@bar.com').subscribe();
    http.expectOne('/api/v1/platform/info').flush({});
    http.expectOne('/api/v1/auth/password-reset').flush({ detail: 'ok' });
  });

  it('logout esquece a sessão mesmo se o servidor não responder', () => {
    auth.session.set(SESSAO);
    units.current.set({ establishmentId: 'a', name: 'Bar', role: 'GERENTE' });

    auth.logout().subscribe();
    http.expectOne('/api/v1/auth/logout').flush(null, { status: 503, statusText: 'x' });

    expect(auth.session()).toBeNull();
    expect(units.current()).toBeNull();
  });

  it('nova senha confirmada encerra a sessão local', () => {
    auth.session.set(SESSAO);

    auth.confirmPasswordReset('tok', 'frase nova bem longa').subscribe();
    const pedido = http.expectOne('/api/v1/auth/password-reset/confirm');
    expect(pedido.request.body).toEqual({ token: 'tok', newPassword: 'frase nova bem longa' });
    pedido.flush(null, { status: 204, statusText: 'No Content' });

    expect(auth.session()).toBeNull();
  });
});
