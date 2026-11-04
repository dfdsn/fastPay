import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { comCookieCsrf } from '../auth/auth-testing';
import { Permission, UnitContextService } from '../session/unit-context.service';
import { ConsumerIssue } from './attendance.service';
import { IssuesPanel } from './issues-panel';

const AGORA = Date.parse('2026-11-04T20:20:00Z');
const PROBLEMAS = '/api/v1/attendance/issues';

function problema(changes: Partial<ConsumerIssue> = {}): ConsumerIssue {
  return {
    id: 'p-1',
    status: 'ABERTO',
    mode: 'GARCOM_RESPONSAVEL',
    tab: { id: 't-1', number: 12, name: 'Ana', table: '5' },
    item: { orderLineId: 'l-1', productName: 'Batata frita' },
    description: 'Veio fria',
    afterClosure: false,
    owner: { id: 'u-gabi', name: 'Gabi' },
    mine: true,
    attendedBy: null,
    forwarded: false,
    createdAt: '2026-11-04T20:15:00Z',
    attendedAt: null,
    forwardedAt: null,
    version: 0,
    ...changes,
  };
}

describe('IssuesPanel (H21)', () => {
  let fixture: ComponentFixture<IssuesPanel>;
  let http: HttpTestingController;
  let mudancas: number;
  const element = () => fixture.nativeElement as HTMLElement;
  const texto = () => element().textContent ?? '';
  const botao = (rotulo: string) =>
    Array.from(element().querySelectorAll('button')).find((b) => b.textContent?.includes(rotulo));

  function abrir(issues: ConsumerIssue[], permissions: Permission[]): void {
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [IssuesPanel],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(UnitContextService).current.set({
      establishmentId: 'a',
      name: 'Bar da Praia',
      role: 'GARCOM',
      permissions,
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(IssuesPanel);
    fixture.componentRef.setInput('issues', issues);
    fixture.componentRef.setInput('now', AGORA);
    mudancas = 0;
    fixture.componentInstance.changed.subscribe(() => mudancas++);
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it('mostra comanda, item, descrição e dono; garçom atende e encaminha, sem concluir', () => {
    abrir([problema()], ['OPERAR_COMANDA', 'ATENDER_PROBLEMA']);
    expect(texto()).toContain('Problemas do consumidor (1)');
    expect(texto()).toContain('Aberto · Comanda 12 · Ana · mesa 5');
    expect(texto()).toContain('Item: Batata frita');
    expect(texto()).toContain('"Veio fria"');
    expect(texto()).toContain('Há 5 min');
    expect(texto()).toContain('Responsável: Gabi');
    expect(botao('Concluir')).toBeUndefined();
    botao('Atender')!.click();
    http.expectOne(`${PROBLEMAS}/p-1/attend`).flush(problema({ status: 'EM_ATENDIMENTO', version: 1 }));
    expect(mudancas).toBe(1);

    fixture.componentRef.setInput('issues', [problema({ status: 'EM_ATENDIMENTO', version: 1 })]);
    fixture.detectChanges();
    expect(botao('Atender')).toBeUndefined();
    botao('Encaminhar ao gerente')!.click();
    const req = http.expectOne(`${PROBLEMAS}/p-1/forward`);
    expect(req.request.body).toEqual({ version: 1 });
    req.flush(problema({ status: 'EM_ATENDIMENTO', forwarded: true, version: 2 }));
    fixture.detectChanges();
    expect(texto()).toContain('encaminhado ao gerente');
  });

  it('conclui com correção e resolução; versão desatualizada relê', () => {
    abrir([problema({ status: 'EM_ATENDIMENTO', version: 1 })], ['ATENDER_PROBLEMA', 'CONCLUIR_PROBLEMA']);
    botao('Concluir como tratado')!.click();
    fixture.detectChanges();
    expect(texto()).toContain('Concluir não altera valores');
    (element().querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    expect(element().querySelector('[role="alert"]')?.textContent).toContain('Diga se houve correção');

    const radios = element().querySelectorAll<HTMLInputElement>('input[type="radio"]');
    radios[1].click();
    const area = element().querySelector('textarea') as HTMLTextAreaElement;
    area.value = 'Porção trocada';
    area.dispatchEvent(new Event('input'));
    (element().querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const req = http.expectOne(`${PROBLEMAS}/p-1/conclude`);
    expect(req.request.body).toEqual({ version: 1, resolution: 'Porção trocada', correction: 'CORRECAO_REALIZADA' });
    req.flush(
      { code: 'VERSAO_DESATUALIZADA', detail: 'Isto mudou desde que a tela foi carregada.' },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();
    expect(texto()).toContain('Isto mudou desde que a tela foi carregada.');
    expect(mudancas).toBe(1);
  });

  it('caso pago avisa que só o gerente conclui; gerente reatribui na central', () => {
    abrir(
      [
        problema({ afterClosure: true, forwarded: true }),
        problema({ id: 'p-2', mode: 'CENTRAL', owner: null, mine: false, item: null }),
      ],
      ['ATENDER_PROBLEMA', 'CONCLUIR_PROBLEMA', 'REATRIBUIR_ATENDIMENTO'],
    );
    expect(texto()).toContain('o pagamento continua válido. Só o gerente conclui');
    expect(texto()).toContain('Sobre a conta');
    expect(texto()).toContain('Central, sem ninguém');
    botao('Reatribuir')!.click();
    http.expectOne('/api/v1/attendance/issue-attendants').flush([{ id: 'u-hugo', displayName: 'Hugo' }]);
    fixture.detectChanges();
    botao('Passar para Hugo')!.click();
    const req = http.expectOne(`${PROBLEMAS}/p-2/assign`);
    expect(req.request.body).toEqual({ assigneeId: 'u-hugo', version: 0 });
    req.flush(problema({ id: 'p-2', mode: 'CENTRAL', owner: { id: 'u-hugo', name: 'Hugo' } }));
    fixture.detectChanges();
    expect(texto()).toContain('agora com Hugo');
  });
});
