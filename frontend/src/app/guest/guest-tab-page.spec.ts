import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';

import { GuestTabPage } from './guest-tab-page';
import { GuestTabsPage } from './guest-tabs-page';
import { FakeEventSource, provideFakeEventSource } from '../live/live-testing';

const SEM_PROBLEMAS = { now: '2026-11-04T20:00:00Z', closed: false, items: [], issues: [] };

describe('Comanda do cliente', () => {
  let http: HttpTestingController;

  function configurar(): void {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideFakeEventSource(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: 't-1' }) } } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  }

  afterEach(() => {
    // A conta (H22) tem testes próprios; aqui basta que a tela a releia junto.
    http.match('/api/v1/guest/tabs/t-1/bill');
    http.verify();
  });

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
    http.expectOne('/api/v1/guest/tabs/t-1/issues').flush(SEM_PROBLEMAS);
    fixture.detectChanges();
    const texto = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(texto).toContain('Comanda 3 · Ana');
    expect(texto).toContain('Algum problema?');
    expect(texto).toContain('Sem mesa');
    expect(texto).toContain('Em atendimento.');
  });

  it('aviso da comanda relê a tela; acesso encerrado mostra a recusa do servidor', () => {
    configurar();
    const fixture = TestBed.createComponent(GuestTabPage);
    fixture.detectChanges();
    const comanda = {
      tabId: 't-1',
      establishmentName: 'Bar da Praia',
      number: 3,
      name: 'Ana',
      table: null,
      openedAt: '2026-11-03T20:00:00Z',
      open: true,
    };
    http.expectOne('/api/v1/guest/tabs/t-1').flush(comanda);
    fixture.detectChanges();
    http.expectOne('/api/v1/guest/tabs/t-1/issues').flush(SEM_PROBLEMAS);
    const conexao = FakeEventSource.last();
    expect(conexao.url).toBe('/api/v1/guest/tabs/t-1/stream');
    conexao.emit('ready', { cursor: '1', resync: false });
    conexao.emit('change', { cursor: '2', topic: 'COMANDA', tabId: 't-1' });
    http.expectOne('/api/v1/guest/tabs/t-1').flush({ ...comanda, table: '5' });
    fixture.detectChanges();
    http.expectOne('/api/v1/guest/tabs/t-1/issues').flush(SEM_PROBLEMAS);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Mesa 5');

    conexao.emit('closed', { reason: 'ACESSO_ENCERRADO' });
    http.expectOne((r) => r.url === '/api/v1/guest/tabs/t-1/changes').flush(
      { code: 'COMANDA_NAO_ENCONTRADA' },
      { status: 404, statusText: 'Not Found' },
    );
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
