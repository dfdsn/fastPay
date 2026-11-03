import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';

import { GuestTabPage } from './guest-tab-page';
import { GuestTabsPage } from './guest-tabs-page';

describe('Comanda do cliente', () => {
  let http: HttpTestingController;

  function configurar(): void {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: 't-1' }) } } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  }

  afterEach(() => http.verify());

  it('mostra a comanda deste aparelho', () => {
    configurar();
    const fixture = TestBed.createComponent(GuestTabPage);
    fixture.detectChanges();
    http.expectOne('/api/v1/guest/tabs/t-1').flush({
      tabId: 't-1',
      establishmentName: 'Bar da Praia',
      number: 3,
      name: 'Ana',
      table: null,
      openedAt: '2026-11-03T20:00:00Z',
      open: true,
    });
    fixture.detectChanges();
    const texto = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(texto).toContain('Comanda 3 · Ana');
    expect(texto).toContain('Sem mesa');
    expect(texto).toContain('Em atendimento.');
  });

  it('sem acesso neste aparelho: mensagem do servidor', () => {
    configurar();
    const fixture = TestBed.createComponent(GuestTabPage);
    fixture.detectChanges();
    http.expectOne('/api/v1/guest/tabs/t-1').flush(
      { code: 'COMANDA_NAO_ENCONTRADA', detail: 'Não encontramos esta comanda neste aparelho.' },
      { status: 404, statusText: 'Not Found' },
    );
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')?.textContent).toContain(
      'Não encontramos esta comanda',
    );
  });

  it('lista as comandas confirmadas neste aparelho', () => {
    configurar();
    const fixture = TestBed.createComponent(GuestTabsPage);
    fixture.detectChanges();
    http.expectOne('/api/v1/guest/tabs').flush([]);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Nenhuma comanda neste aparelho.');
  });
});
