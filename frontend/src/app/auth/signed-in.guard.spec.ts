import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  Router,
  RouterStateSnapshot,
  UrlTree,
  provideRouter,
} from '@angular/router';
import { Observable, firstValueFrom } from 'rxjs';

import { AuthService } from './auth.service';
import { SESSAO } from './auth-testing';
import { signedInGuard } from './signed-in.guard';

describe('signedInGuard', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const executar = () =>
    TestBed.runInInjectionContext(() =>
      signedInGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
    );

  it('libera com sessão conhecida', () => {
    TestBed.inject(AuthService).session.set(SESSAO);
    expect(executar()).toBe(true);
  });

  it('confirma a sessão no servidor depois de recarregar', async () => {
    const resultado = firstValueFrom(executar() as Observable<boolean | UrlTree>);
    http.expectOne('/api/v1/auth/session').flush(SESSAO);

    expect(await resultado).toBe(true);
    expect(TestBed.inject(AuthService).session()?.displayName).toBe('Ana');
  });

  it('sem sessão vai para o login', async () => {
    const resultado = firstValueFrom(executar() as Observable<boolean | UrlTree>);
    http
      .expectOne('/api/v1/auth/session')
      .flush({ code: 'SESSAO_AUSENTE' }, { status: 401, statusText: 'Unauthorized' });

    const destino = await resultado;
    expect(TestBed.inject(Router).serializeUrl(destino as UrlTree)).toBe('/entrar');
  });
});
