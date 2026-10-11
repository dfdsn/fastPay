import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { UnitContextService, UnitView, roleLabel } from './unit-context.service';

describe('UnitContextService', () => {
  const lojaA: UnitView = { establishmentId: 'a', name: 'Bar da Praia', role: 'GERENTE' };
  const lojaB: UnitView = { establishmentId: 'b', name: 'Adega Centro', role: 'GARCOM' };

  let service: UnitContextService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(UnitContextService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function selecionar(unit: UnitView): void {
    service.select(unit.establishmentId).subscribe();
    const request = http.expectOne('/api/v1/session/unit');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ establishmentId: unit.establishmentId });
    request.flush(unit);
  }

  it('guarda a unidade confirmada pelo servidor', () => {
    selecionar(lojaA);
    expect(service.current()).toEqual(lojaA);
  });

  it('limpa os dados da unidade anterior ao trocar para outra', () => {
    const limpezas: string[] = [];
    service.onUnitCleared(() => limpezas.push('pedidos'));
    selecionar(lojaA);
    expect(limpezas).toEqual([]);

    selecionar(lojaB);

    expect(limpezas).toEqual(['pedidos']);
    expect(service.current()).toEqual(lojaB);
  });

  it('confirmar de novo a mesma unidade não apaga os dados dela', () => {
    const limpeza = vi.fn();
    service.onUnitCleared(limpeza);
    selecionar(lojaA);
    service.refresh().subscribe();
    http.expectOne('/api/v1/session/unit').flush(lojaA);

    expect(limpeza).not.toHaveBeenCalled();
  });

  it('sair da unidade avisa o servidor e limpa o contexto', () => {
    const limpeza = vi.fn();
    const cancelar = service.onUnitCleared(limpeza);
    selecionar(lojaA);

    service.leave().subscribe();
    const request = http.expectOne('/api/v1/session/unit');
    expect(request.request.method).toBe('DELETE');
    request.flush(null, { status: 204, statusText: 'No Content' });

    expect(service.current()).toBeNull();
    expect(limpeza).toHaveBeenCalledTimes(1);

    cancelar();
    service.clear();
    expect(limpeza).toHaveBeenCalledTimes(1);
  });

  it('traduz os papéis para português', () => {
    expect(roleLabel('ADMIN_ESTABELECIMENTO')).toBe('Administrador do estabelecimento');
    expect(roleLabel('GERENTE')).toBe('Gerente');
    expect(roleLabel('GARCOM')).toBe('Garçom');
    expect(roleLabel('PRODUCAO')).toBe('Produção');
  });
});
