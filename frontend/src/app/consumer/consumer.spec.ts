import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';

import { comCookieCsrf } from '../auth/auth-testing';
import { ConsumerService, ConsumerSession, safeReturn } from './consumer.service';
import { HistoryPage } from './history-page';
import { PrivacyPage } from './privacy-page';
import { TabHistoryPanel } from './tab-history-panel';

const TAB = '0f8fad5b-d9cb-469f-a165-70867728950e';

const SEM_LOGIN: ConsumerSession = {
  signedIn: false,
  email: null,
  idleDeadline: null,
  absoluteDeadline: null,
  pendingEmail: null,
  noticeVersion: '2026-11-07',
};

const CONECTADO: ConsumerSession = {
  ...SEM_LOGIN,
  signedIn: true,
  email: 'ana@exemplo.com',
  idleDeadline: '2026-11-14T20:00:00Z',
  absoluteDeadline: '2026-12-07T20:00:00Z',
};

describe('Histórico com Google (H27)', () => {
  let http: HttpTestingController;

  function configurar(query: Record<string, string> = {}): void {
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(query) } } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  }

  afterEach(() => http.verify());

  function texto(fixture: { nativeElement: unknown }): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function botao(fixture: { nativeElement: unknown }, rotulo: string): HTMLButtonElement {
    const encontrado = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find((b) =>
      (b.textContent ?? '').includes(rotulo),
    );
    if (!encontrado) {
      throw new Error(`Botão ${rotulo} não encontrado`);
    }
    return encontrado;
  }

  function painel() {
    const fixture = TestBed.createComponent(TabHistoryPanel);
    fixture.componentRef.setInput('tabId', TAB);
    fixture.detectChanges();
    return fixture;
  }

  it('sem login oferece entrar com Google e volta para a comanda', () => {
    configurar({ google: 'recusado' });
    const entrar = vi.spyOn(TestBed.inject(ConsumerService), 'signInWithGoogle').mockImplementation(() => undefined);
    const fixture = painel();
    http.expectOne('/api/v1/consumer/session').flush(SEM_LOGIN);
    http.expectOne(`/api/v1/guest/tabs/${TAB}/account-link`).flush({ linked: false, mine: false });
    fixture.detectChanges();

    expect(texto(fixture)).toContain('É opcional');
    expect(texto(fixture)).toContain('Login com Google cancelado');
    botao(fixture, 'Entrar com Google').click();
    expect(entrar).toHaveBeenCalledWith(`/minha-comanda/${TAB}`);
  });

  it('conectado só guarda depois de confirmar que a comanda é dele', () => {
    configurar();
    const fixture = painel();
    http.expectOne('/api/v1/consumer/session').flush(CONECTADO);
    http.expectOne(`/api/v1/guest/tabs/${TAB}/account-link`).flush({ linked: false, mine: false });
    fixture.detectChanges();

    expect(botao(fixture, 'Guardar no meu histórico').disabled).toBe(true);
    (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[type="checkbox"]')?.click();
    fixture.detectChanges();
    botao(fixture, 'Guardar no meu histórico').click();
    const pedido = http.expectOne(`/api/v1/guest/tabs/${TAB}/account-link`);
    expect(pedido.request.method).toBe('POST');
    expect(pedido.request.body).toEqual({ confirmed: true });
    pedido.flush({ linked: true, mine: true });
    fixture.detectChanges();
    expect(texto(fixture)).toContain('Esta comanda está no seu histórico');
  });

  it('comanda de outra conta Google: avisa e não oferece guardar', () => {
    configurar();
    const fixture = painel();
    http.expectOne('/api/v1/consumer/session').flush(CONECTADO);
    http.expectOne(`/api/v1/guest/tabs/${TAB}/account-link`).flush({ linked: true, mine: false });
    fixture.detectChanges();
    expect(texto(fixture)).toContain('histórico de outra conta Google');
    expect(texto(fixture)).not.toContain('Guardar no meu histórico');
  });

  it('recusa do servidor ao guardar aparece na tela', () => {
    configurar();
    const fixture = painel();
    http.expectOne('/api/v1/consumer/session').flush(CONECTADO);
    http.expectOne(`/api/v1/guest/tabs/${TAB}/account-link`).flush({ linked: false, mine: false });
    fixture.detectChanges();
    (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[type="checkbox"]')?.click();
    fixture.detectChanges();
    botao(fixture, 'Guardar no meu histórico').click();
    http.expectOne(`/api/v1/guest/tabs/${TAB}/account-link`).flush(
      { code: 'COMANDA_DE_OUTRA_CONTA', detail: 'Esta comanda já está no histórico de outra conta Google.' },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')?.textContent).toContain(
      'outra conta Google',
    );
  });

  it('aviso de privacidade: aceitar cria a conta e volta para a tela de origem', () => {
    configurar({ returnTo: '/meu-historico' });
    const navegar = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const fixture = TestBed.createComponent(PrivacyPage);
    fixture.detectChanges();
    http.expectOne('/api/v1/consumer/session').flush({ ...SEM_LOGIN, pendingEmail: 'ana@exemplo.com' });
    fixture.detectChanges();

    expect(texto(fixture)).toContain('ana@exemplo.com');
    expect(texto(fixture)).toContain('Não guardamos nome, foto nem telefone');
    expect(texto(fixture)).toContain('sem o seu e-mail');
    botao(fixture, 'Aceitar e criar conta').click();
    const aceite = http.expectOne('/api/v1/consumer/privacy/accept');
    expect(aceite.request.body).toEqual({ noticeVersion: '2026-11-07' });
    aceite.flush({ returnTo: '/meu-historico' });
    expect(navegar).toHaveBeenCalledWith('/meu-historico?google=conectado');
  });

  it('aviso de privacidade: recusar não cria conta e volta como avulso', () => {
    configurar({ returnTo: 'https://malicioso.example' });
    const navegar = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const fixture = TestBed.createComponent(PrivacyPage);
    fixture.detectChanges();
    http.expectOne('/api/v1/consumer/session').flush({ ...SEM_LOGIN, pendingEmail: 'ana@exemplo.com' });
    fixture.detectChanges();
    botao(fixture, 'Não, continuar sem conta').click();
    http.expectOne('/api/v1/consumer/privacy/decline').flush(null);
    expect(navegar).toHaveBeenCalledWith('/minhas-comandas');
  });

  it('aviso de privacidade sem login pendente: avisa que expirou', () => {
    configurar();
    const fixture = TestBed.createComponent(PrivacyPage);
    fixture.detectChanges();
    http.expectOne('/api/v1/consumer/session').flush(SEM_LOGIN);
    fixture.detectChanges();
    expect(texto(fixture)).toContain('expirou');
    expect(texto(fixture)).not.toContain('Aceitar e criar conta');
  });

  it('histórico lista as comandas guardadas, pagina e permite sair', () => {
    configurar({ google: 'conectado' });
    const fixture = TestBed.createComponent(HistoryPage);
    fixture.detectChanges();
    http.expectOne('/api/v1/consumer/session').flush(CONECTADO);
    const entrada = {
      tabId: TAB,
      establishmentName: 'Bar da Praia',
      number: 3,
      name: 'Ana',
      openedAt: '2026-11-03T20:00:00Z',
      closedAt: '2026-11-03T23:00:00Z',
      linkedAt: '2026-11-03T20:05:00Z',
    };
    http.expectOne((r) => r.url === '/api/v1/consumer/history' && r.params.get('page') === '0').flush({
      items: [entrada],
      page: 0,
      size: 1,
      total: 2,
    });
    fixture.detectChanges();
    expect(texto(fixture)).toContain('Você entrou com Google.');
    expect(texto(fixture)).toContain('Bar da Praia · comanda 3 · Ana');
    expect(texto(fixture)).toContain('encerrada');

    botao(fixture, 'Ver mais').click();
    http.expectOne((r) => r.url === '/api/v1/consumer/history' && r.params.get('page') === '1').flush({
      items: [{ ...entrada, tabId: 'outra', establishmentName: 'Outro Bar', closedAt: null }],
      page: 1,
      size: 1,
      total: 2,
    });
    fixture.detectChanges();
    expect(texto(fixture)).toContain('Outro Bar');
    expect(texto(fixture)).not.toContain('Ver mais');

    botao(fixture, 'Sair da conta Google').click();
    http.expectOne('/api/v1/consumer/logout').flush(null);
    fixture.detectChanges();
    expect(texto(fixture)).not.toContain('Bar da Praia');
    expect(texto(fixture)).toContain('Entrar com Google');
  });

  it('histórico sem login oferece entrar e volta para o histórico', () => {
    configurar();
    const entrar = vi.spyOn(TestBed.inject(ConsumerService), 'signInWithGoogle').mockImplementation(() => undefined);
    const fixture = TestBed.createComponent(HistoryPage);
    fixture.detectChanges();
    http.expectOne('/api/v1/consumer/session').flush(SEM_LOGIN);
    fixture.detectChanges();
    botao(fixture, 'Entrar com Google').click();
    expect(entrar).toHaveBeenCalledWith('/meu-historico');
  });

  it('retorno só para telas do consumidor', () => {
    expect(safeReturn('/meu-historico')).toBe('/meu-historico');
    expect(safeReturn(`/minha-comanda/${TAB}`)).toBe(`/minha-comanda/${TAB}`);
    expect(safeReturn('//malicioso.example')).toBe('/minhas-comandas');
    expect(safeReturn('/inicio')).toBe('/minhas-comandas');
    expect(safeReturn(null)).toBe('/minhas-comandas');
  });
});
