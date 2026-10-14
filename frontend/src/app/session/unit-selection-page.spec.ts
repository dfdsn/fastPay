import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { UnitContextService } from './unit-context.service';
import { UnitSelectionPage } from './unit-selection-page';

describe('UnitSelectionPage', () => {
  const unitsUrl = '/api/v1/session/units';
  const unitUrl = '/api/v1/session/unit';
  const lojaA = { establishmentId: 'a', name: 'Bar da Praia', role: 'GERENTE' };
  const lojaB = { establishmentId: 'b', name: 'Adega Centro', role: 'GARCOM' };

  let fixture: ComponentFixture<UnitSelectionPage>;
  let http: HttpTestingController;
  let navigate: ReturnType<typeof vi.spyOn>;

  const element = () => fixture.nativeElement as HTMLElement;
  const text = () => element().textContent ?? '';
  const statusText = () => element().querySelector('[role="status"]')?.textContent ?? '';
  const unitButtons = () =>
    Array.from(element().querySelectorAll<HTMLButtonElement>('ul[aria-label] button'));

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [UnitSelectionPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    fixture = TestBed.createComponent(UnitSelectionPage);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  function responder(body: object): void {
    http.expectOne(unitsUrl).flush(body);
    fixture.detectChanges();
  }

  it('anuncia o carregamento em região de status', () => {
    expect(statusText()).toContain('Carregando suas unidades');
    http.expectOne(unitsUrl);
  });

  it('lista as unidades com o papel de cada uma, como botões', () => {
    responder({ displayName: 'Ana', units: [lojaB, lojaA], currentEstablishmentId: null });

    expect(text()).toContain('Olá, Ana');
    const botoes = unitButtons();
    expect(botoes.map((b) => b.textContent?.replace(/\s+/g, ' ').trim())).toEqual([
      'Adega Centro · Garçom',
      'Bar da Praia · Gerente',
    ]);
    expect(botoes.every((b) => b.type === 'button' && !b.disabled)).toBe(true);
  });

  it('marca a unidade atual', () => {
    responder({ displayName: 'Ana', units: [lojaB, lojaA], currentEstablishmentId: 'a' });
    expect(unitButtons()[1].textContent).toContain('(unidade atual)');
  });

  it('escolher uma unidade a confirma no servidor e vai para o início', () => {
    responder({ displayName: 'Ana', units: [lojaB, lojaA] });

    unitButtons()[1].click();
    const request = http.expectOne(unitUrl);
    expect(request.request.body).toEqual({ establishmentId: 'a' });
    request.flush(lojaA);

    expect(TestBed.inject(UnitContextService).current()).toEqual(lojaA);
    expect(navigate).toHaveBeenCalledWith(['/inicio']);
  });

  it('com um único vínculo entra direto nele', () => {
    responder({ displayName: 'Bruno', units: [lojaA] });

    http.expectOne(unitUrl).flush(lojaA);
    expect(navigate).toHaveBeenCalledWith(['/inicio']);
  });

  it('acesso revogado durante a escolha avisa e recarrega a lista', () => {
    responder({ displayName: 'Ana', units: [lojaB, lojaA] });

    unitButtons()[1].click();
    http
      .expectOne(unitUrl)
      .flush({ code: 'UNIDADE_NAO_AUTORIZADA' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();

    expect(statusText()).toContain('Você não tem mais acesso a esta unidade');
    responder({ displayName: 'Ana', units: [lojaB] });
    expect(unitButtons()).toHaveLength(1);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('falha ao entrar na unidade permite tentar de novo', () => {
    responder({ displayName: 'Ana', units: [lojaB, lojaA] });
    unitButtons()[0].click();
    http.expectOne(unitUrl).flush(null, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();

    expect(statusText()).toContain('Não foi possível entrar na unidade');
    expect(unitButtons()[0].disabled).toBe(false);
  });

  it('sem vínculos explica que não há unidade disponível', () => {
    responder({ displayName: 'Admin', units: [] });
    expect(text()).toContain('Você ainda não tem acesso a nenhuma unidade.');
    expect(unitButtons()).toHaveLength(0);
  });

  it('admin fastPay vê o caminho para a área da plataforma', () => {
    responder({ displayName: 'Bia', units: [], currentEstablishmentId: null, platformAdmin: true });
    expect(element().querySelector('a[href="/admin/estabelecimentos"]')?.textContent?.trim()).toBe(
      'Área da equipe fastPay',
    );
  });

  it('sem papel global não mostra a área da plataforma', () => {
    responder({ displayName: 'Ana', units: [lojaA, lojaB], currentEstablishmentId: null });
    expect(element().querySelector('a[href="/admin/estabelecimentos"]')).toBeNull();
  });

  it('sem sessão pede para entrar novamente', () => {
    http
      .expectOne(unitsUrl)
      .flush({ code: 'SESSAO_AUSENTE' }, { status: 401, statusText: 'Unauthorized' });
    fixture.detectChanges();
    expect(statusText()).toContain('Sua sessão não está ativa');
  });

  it('erro de conexão permite tentar novamente', () => {
    http.expectOne(unitsUrl).flush(null, { status: 503, statusText: 'Unavailable' });
    fixture.detectChanges();
    expect(statusText()).toContain('Não foi possível carregar suas unidades');

    element().querySelector<HTMLButtonElement>('mat-card-actions button')?.click();
    responder({ displayName: 'Ana', units: [lojaB, lojaA] });
    expect(unitButtons()).toHaveLength(2);
  });
});
