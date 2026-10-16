import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { comCookieCsrf } from '../auth/auth-testing';
import { Establishment } from './establishments.service';
import { EstablishmentsPage } from './establishments-page';

const BAR: Establishment = {
  id: 'loja-1',
  name: 'Bar do Zé',
  ownerEmail: 'ze@exemplo.com',
  document: null,
  status: 'CONFIGURACAO',
  ownerInvitation: {
    status: 'ENVIADO',
    sentAt: null,
    expiresAt: '2026-10-16T12:00:00Z',
    acceptedAt: null,
  },
};

describe('EstablishmentsPage', () => {
  let fixture: ComponentFixture<EstablishmentsPage>;
  let http: HttpTestingController;

  const element = () => fixture.nativeElement as HTMLElement;
  const status = () => element().querySelector('[role="status"]')?.textContent?.trim() ?? '';
  const alerta = () => element().querySelector('[role="alert"]')?.textContent?.trim() ?? '';

  function abrir(lista: Establishment[] = [BAR]): void {
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [EstablishmentsPage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(EstablishmentsPage);
    fixture.detectChanges();
    http.expectOne('/api/v1/establishments').flush(lista);
    fixture.detectChanges();
  }

  function preencher(nome: string, email: string, cnpj = ''): void {
    for (const [name, value] of [
      ['name', nome],
      ['ownerEmail', email],
      ['document', cnpj],
    ]) {
      const input = element().querySelector<HTMLInputElement>(`input[formcontrolname="${name}"]`)!;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    }
    element().querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it('lista com a situação do convite do dono', () => {
    abrir();
    expect(element().textContent).toContain('Bar do Zé');
    expect(element().textContent).toContain('Dono: ze@exemplo.com');
    expect(element().textContent).toContain('Situação: em configuração');
    expect(element().textContent).toContain('Convite enviado');
  });

  it('cadastra sem CNPJ e mostra na lista', () => {
    abrir([]);
    expect(element().textContent).toContain('Nenhum estabelecimento cadastrado ainda.');
    preencher(' Bar Novo ', 'dono@exemplo.com');
    const pedido = http.expectOne('/api/v1/establishments');
    expect(pedido.request.method).toBe('POST');
    expect(pedido.request.body).toEqual({
      name: 'Bar Novo',
      ownerEmail: 'dono@exemplo.com',
      document: null,
    });
    pedido.flush(
      {
        ...BAR,
        id: 'loja-2',
        name: 'Bar Novo',
        ownerEmail: 'dono@exemplo.com',
        ownerInvitation: { ...BAR.ownerInvitation!, status: 'ENVIO_PENDENTE' },
      },
      { status: 201, statusText: 'Created' },
    );
    fixture.detectChanges();
    expect(status()).toContain(
      'Bar Novo cadastrado. O convite para dono@exemplo.com está a caminho.',
    );
    expect(element().textContent).toContain('Convite aguardando envio');
  });

  it('e-mail inválido não chama o servidor; CNPJ recusado mostra o motivo', () => {
    abrir([]);
    preencher('Bar', 'sem-arroba');
    expect(element().textContent).toContain('Confira o e-mail.');

    preencher('Bar', 'dono@exemplo.com', '11.222.333/0001-80');
    http
      .expectOne('/api/v1/establishments')
      .flush(
        {
          code: 'DADOS_RECUSADOS',
          field: 'document',
          detail: 'CNPJ inválido. Confira os números ou deixe em branco por enquanto.',
        },
        { status: 400, statusText: 'Bad Request' },
      );
    fixture.detectChanges();
    expect(alerta()).toBe('CNPJ inválido. Confira os números ou deixe em branco por enquanto.');
  });

  it('reenvia o convite do dono', () => {
    abrir();
    const botao = Array.from(element().querySelectorAll<HTMLButtonElement>('button')).find((b) =>
      b.textContent?.includes('Reenviar convite para Bar do Zé'),
    )!;
    botao.click();
    http
      .expectOne('/api/v1/establishments/loja-1/owner-invitation')
      .flush({ ...BAR, ownerInvitation: { ...BAR.ownerInvitation!, status: 'ENVIO_PENDENTE' } });
    fixture.detectChanges();
    expect(status()).toContain(
      'Novo convite a caminho de ze@exemplo.com. O anterior deixou de valer.',
    );
  });

  it('sem botão de reenvio depois do aceite', () => {
    abrir([{ ...BAR, ownerInvitation: { ...BAR.ownerInvitation!, status: 'ACEITO' } }]);
    expect(element().textContent).toContain('Dono aceitou');
    expect(element().textContent).not.toContain('Reenviar convite');
  });

  it('quem não é admin fastPay vê a recusa', () => {
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [EstablishmentsPage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(EstablishmentsPage);
    fixture.detectChanges();
    http
      .expectOne('/api/v1/establishments')
      .flush({ code: 'ACESSO_RESTRITO_PLATAFORMA' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();
    expect(alerta()).toBe('Esta área é exclusiva da equipe fastPay.');
    expect(element().querySelector('form')).toBeNull();
  });
});
