import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { comCookieCsrf } from '../auth/auth-testing';
import { SettingsPage } from './settings-page';
import { SettingsView, changedLabel } from './settings.service';

const URL = '/api/v1/establishment/settings';

function visao(parcial: Partial<SettingsView> = {}): SettingsView {
  return {
    version: 1,
    versionId: 'v-1',
    savedAt: '2026-11-03T12:00:00Z',
    serviceEnabled: true,
    servicePercent: '10.00',
    serviceBase: 'APOS_DESCONTO',
    serviceIncludesCouvert: false,
    couvertEnabled: false,
    couvertMode: 'AUTOMATICO',
    couvertAmount: null,
    discountFixedAllowed: true,
    discountPercentAllowed: true,
    attendanceMode: 'GARCOM_RESPONSAVEL',
    exitMode: 'AUTOMATICA',
    timeZone: 'America/Sao_Paulo',
    cutoff: '06:00',
    calendarInUse: { timeZone: 'America/Sao_Paulo', cutoff: '06:00', since: '2026-11-01T00:00:00Z' },
    pendingCalendar: null,
    today: {
      date: '2026-11-03',
      start: '2026-11-03T09:00:00Z',
      end: '2026-11-04T09:00:00Z',
      timeZone: 'America/Sao_Paulo',
      cutoff: '06:00',
    },
    history: [{ version: 1, createdAt: '2026-11-01T00:00:00Z', author: null, changed: [] }],
    editable: true,
    ...parcial,
  };
}

describe('SettingsPage', () => {
  let fixture: ComponentFixture<SettingsPage>;
  let http: HttpTestingController;
  const element = () => fixture.nativeElement as HTMLElement;
  const texto = () => element().textContent ?? '';
  const campo = (nome: string) =>
    element().querySelector<HTMLInputElement>(`[formcontrolname="${nome}"]`)!;

  function abrir(view: SettingsView): void {
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [SettingsPage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SettingsPage);
    fixture.detectChanges();
    http.expectOne(URL).flush(view);
    fixture.detectChanges();
  }

  function digitar(nome: string, valor: string): void {
    const input = campo(nome);
    input.value = valor;
    input.dispatchEvent(new Event('input'));
  }

  function salvar(): void {
    element().querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it('mostra os padrões e o dia operacional na hora local da unidade', () => {
    abrir(visao());
    expect(campo('servicePercent').value).toBe('10,00');
    expect(campo('serviceEnabled').checked).toBe(true);
    expect(campo('couvertEnabled').checked).toBe(false);
    expect(texto()).toContain('Versão 1.');
    expect(texto()).toContain('Hoje: dia 03/11/2026, de 03/11 06:00 a 04/11 06:00');
    expect(texto()).toContain('Versão 1, ');
    expect(texto()).toContain('padrão inicial');
  });

  it('administrador salva e a tela mostra a nova versão', () => {
    abrir(visao());
    digitar('servicePercent', '12,5');
    campo('couvertEnabled').click();
    digitar('couvertAmount', '15,00');
    salvar();
    const pedido = http.expectOne(URL);
    expect(pedido.request.method).toBe('PUT');
    expect(pedido.request.body).toEqual(
      expect.objectContaining({
        version: 1,
        servicePercent: '12,5',
        couvertEnabled: true,
        couvertAmount: '15,00',
        cutoff: '06:00',
      }),
    );
    pedido.flush(
      visao({
        version: 2,
        servicePercent: '12.50',
        couvertEnabled: true,
        couvertAmount: '15.00',
        history: [
          { version: 2, createdAt: '2026-11-03T13:00:00Z', author: 'Dora', changed: ['servico', 'couvert'] },
          { version: 1, createdAt: '2026-11-01T00:00:00Z', author: null, changed: [] },
        ],
      }),
    );
    fixture.detectChanges();
    expect(texto()).toContain('Configuração salva na versão 2.');
    expect(texto()).toContain('serviço e couvert, por Dora');
    expect(campo('couvertAmount').value).toBe('15,00');
  });

  it('mostra a recusa do servidor no campo e o conflito de versão', () => {
    abrir(visao());
    digitar('servicePercent', '25');
    salvar();
    http.expectOne(URL).flush(
      {
        code: 'DADOS_INVALIDOS',
        detail: 'Informe o percentual do serviço entre 0 e 20.',
        field: 'servicePercent',
        problems: [{ field: 'servicePercent', message: 'Informe o percentual do serviço entre 0 e 20.' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    fixture.detectChanges();
    expect(element().querySelector('#erro-servicePercent')?.textContent).toContain('entre 0 e 20');

    salvar();
    http.expectOne(URL).flush(
      { code: 'VERSAO_DESATUALIZADA', detail: 'Outra pessoa salvou a configuração.' },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();
    expect(element().querySelector('[role="alert"]')?.textContent).toContain('Outra pessoa salvou');
    const recarregar = Array.from(element().querySelectorAll('button')).find((b) =>
      b.textContent?.includes('Recarregar'),
    )!;
    recarregar.click();
    http.expectOne(URL).flush(visao({ version: 3 }));
    fixture.detectChanges();
    expect(texto()).toContain('Versão 3.');
  });

  it('gerente vê sem poder salvar e a mudança de corte agendada aparece', () => {
    abrir(
      visao({
        editable: false,
        cutoff: '08:00',
        pendingCalendar: {
          timeZone: 'America/Sao_Paulo',
          cutoff: '08:00',
          effectiveFrom: '2026-11-04T09:00:00Z',
          firstDate: '2026-11-04',
        },
      }),
    );
    expect(element().querySelector('button[type="submit"]')).toBeNull();
    expect(campo('servicePercent').disabled).toBe(true);
    expect(texto()).toContain('Só o administrador do estabelecimento altera.');
    expect(texto()).toContain('corte às 08:00, a partir do dia 04/11/2026');
    expect(campo('cutoff').value).toBe('08:00');
  });
});

describe('changedLabel', () => {
  it('lista as partes alteradas em português', () => {
    expect(changedLabel([])).toBe('padrão inicial');
    expect(changedLabel(['saida'])).toBe('saída');
    expect(changedLabel(['servico', 'couvert', 'desconto'])).toBe('serviço, couvert e desconto');
  });
});
