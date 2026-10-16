import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { SupportAreaPage } from './support-area-page';
import { ActiveSupportGrant } from './support.service';

const GRANT: ActiveSupportGrant = {
  id: 'g-1',
  establishmentId: 'a',
  establishmentName: 'Bar da Praia',
  reason: 'Conferir convites',
  grantedAt: '2026-10-16T12:00:00Z',
  expiresAt: '2026-10-17T12:00:00Z',
};

describe('SupportAreaPage', () => {
  let fixture: ComponentFixture<SupportAreaPage>;
  let http: HttpTestingController;

  const element = () => fixture.nativeElement as HTMLElement;
  const consultar = () =>
    Array.from(element().querySelectorAll<HTMLButtonElement>('button'))
      .find((b) => b.textContent?.includes('Consultar equipe de Bar da Praia'))!
      .click();

  function abrir(grants: ActiveSupportGrant[] = [GRANT]): void {
    TestBed.configureTestingModule({
      imports: [SupportAreaPage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SupportAreaPage);
    fixture.detectChanges();
    http.expectOne('/api/v1/support/grants').flush(grants);
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it('sem autorização vigente avisa', () => {
    abrir([]);
    expect(element().textContent).toContain('Nenhuma unidade autorizou seu acesso no momento.');
  });

  it('consulta a equipe só para leitura, sem botões de alteração', () => {
    abrir();
    expect(element().textContent).toContain('Motivo: Conferir convites');
    consultar();
    http.expectOne('/api/v1/support/units/a/team').flush({
      members: [
        {
          displayName: 'Diana',
          email: 'diana@exemplo.com',
          role: 'ADMIN_ESTABELECIMENTO',
          active: true,
          delegated: [],
        },
      ],
      pendingInvitations: [
        { email: 'novo@exemplo.com', role: 'GARCOM', status: 'ENVIADO', expiresAt: null },
      ],
    });
    fixture.detectChanges();
    expect(element().textContent).toContain('Equipe de Bar da Praia (somente leitura)');
    expect(element().textContent).toContain('Diana · diana@exemplo.com · Administrador');
    expect(element().textContent).toContain('novo@exemplo.com · Garçom');
    expect(element().querySelectorAll('button').length).toBe(1);
  });

  it('autorização encerrada no meio do caminho mostra a recusa', () => {
    abrir();
    consultar();
    http.expectOne('/api/v1/support/units/a/team').flush(
      {
        code: 'SUPORTE_NAO_AUTORIZADO',
        detail: 'Sem autorização do dono para consultar esta unidade, ou a autorização venceu.',
      },
      { status: 403, statusText: 'Forbidden' },
    );
    fixture.detectChanges();
    expect(element().querySelector('[role="alert"]')?.textContent?.trim()).toBe(
      'Sem autorização do dono para consultar esta unidade, ou a autorização venceu.',
    );
  });

  it('falha de rede permite tentar de novo', () => {
    TestBed.configureTestingModule({
      imports: [SupportAreaPage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SupportAreaPage);
    fixture.detectChanges();
    http.expectOne('/api/v1/support/grants').error(new ProgressEvent('error'));
    fixture.detectChanges();
    expect(element().textContent).toContain('Não foi possível carregar.');
    element().querySelector<HTMLButtonElement>('button')!.click();
    http.expectOne('/api/v1/support/grants').flush([GRANT]);
    fixture.detectChanges();
    expect(element().textContent).toContain('Bar da Praia');
  });
});
