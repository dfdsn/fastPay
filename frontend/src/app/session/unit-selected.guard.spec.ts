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

import { UnitContextService } from './unit-context.service';
import { unitSelectedGuard } from './unit-selected.guard';

describe('unitSelectedGuard', () => {
  let http: HttpTestingController;
  let context: UnitContextService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    context = TestBed.inject(UnitContextService);
  });

  afterEach(() => http.verify());

  const executar = () =>
    TestBed.runInInjectionContext(() =>
      unitSelectedGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
    );

  it('libera quando já há unidade ativa', () => {
    context.current.set({ establishmentId: 'a', name: 'Bar da Praia', role: 'GERENTE' });
    expect(executar()).toBe(true);
  });

  it('confirma no servidor depois de recarregar a página', async () => {
    const resultado = firstValueFrom(executar() as Observable<boolean | UrlTree>);
    http
      .expectOne('/api/v1/session/unit')
      .flush({ establishmentId: 'a', name: 'Bar da Praia', role: 'GERENTE' });

    expect(await resultado).toBe(true);
    expect(context.current()?.name).toBe('Bar da Praia');
  });

  it('sem unidade válida manda para a seleção', async () => {
    const resultado = firstValueFrom(executar() as Observable<boolean | UrlTree>);
    http
      .expectOne('/api/v1/session/unit')
      .flush({ code: 'UNIDADE_NAO_SELECIONADA' }, { status: 409, statusText: 'Conflict' });

    const destino = await resultado;
    expect(TestBed.inject(Router).serializeUrl(destino as UrlTree)).toBe('/unidades');
  });
});
