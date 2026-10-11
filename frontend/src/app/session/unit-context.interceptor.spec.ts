import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { unitContextInterceptor } from './unit-context.interceptor';
import { UnitContextService } from './unit-context.service';

describe('unitContextInterceptor', () => {
  let http: HttpTestingController;
  let client: HttpClient;
  let context: UnitContextService;
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([unitContextInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    client = TestBed.inject(HttpClient);
    context = TestBed.inject(UnitContextService);
    context.current.set({ establishmentId: 'a', name: 'Bar da Praia', role: 'GERENTE' });
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  });

  afterEach(() => http.verify());

  function falhar(status: number, code?: string): unknown {
    let recebido: unknown;
    client.get('/api/v1/qualquer').subscribe({ error: (error: unknown) => (recebido = error) });
    http.expectOne('/api/v1/qualquer').flush(code ? { code } : null, { status, statusText: 'x' });
    return recebido;
  }

  it('vínculo revogado limpa a unidade e volta para a seleção', () => {
    const erro = falhar(403, 'UNIDADE_NAO_AUTORIZADA');

    expect(erro).toBeDefined();
    expect(context.current()).toBeNull();
    expect(navigate).toHaveBeenCalledWith(['/unidades']);
  });

  it('sem unidade escolhida no servidor também volta para a seleção', () => {
    falhar(409, 'UNIDADE_NAO_SELECIONADA');
    expect(context.current()).toBeNull();
  });

  it('outros erros não mexem na unidade', () => {
    falhar(500);
    falhar(403, 'OUTRO_CODIGO');
    expect(context.current()).not.toBeNull();
    expect(navigate).not.toHaveBeenCalled();
  });
});
