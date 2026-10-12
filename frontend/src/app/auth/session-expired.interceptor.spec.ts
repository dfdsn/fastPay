import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { UnitContextService } from '../session/unit-context.service';
import { AuthService } from './auth.service';
import { SESSAO } from './auth-testing';
import { sessionExpiredInterceptor } from './session-expired.interceptor';

describe('sessionExpiredInterceptor', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  let auth: AuthService;
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([sessionExpiredInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
    auth = TestBed.inject(AuthService);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  });

  afterEach(() => http.verify());

  function falhar(status: number, code?: string): unknown {
    let recebido: unknown;
    client.get('/api/v1/qualquer').subscribe({ error: (error: unknown) => (recebido = error) });
    http.expectOne('/api/v1/qualquer').flush(code ? { code } : null, { status, statusText: 'x' });
    return recebido;
  }

  it('sessão vencida limpa sessão e unidade e volta para o login', () => {
    auth.session.set(SESSAO);
    const units = TestBed.inject(UnitContextService);
    units.current.set({ establishmentId: 'a', name: 'Bar', role: 'GERENTE' });

    expect(falhar(401, 'SESSAO_AUSENTE')).toBeDefined();

    expect(auth.session()).toBeNull();
    expect(units.current()).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/entrar']);
  });

  it('senha errada no login não é sessão vencida', () => {
    falhar(401, 'CREDENCIAIS_INVALIDAS');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('sem sessão conhecida deixa a decisão para quem chamou', () => {
    falhar(401, 'SESSAO_AUSENTE');
    expect(navigate).not.toHaveBeenCalled();
  });
});
