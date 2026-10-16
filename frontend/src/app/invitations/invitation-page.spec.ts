import { Location } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';

import { comCookieCsrf } from '../auth/auth-testing';
import { InvitationPreview } from './invitation.service';
import { InvitationPage } from './invitation-page';

const CONVITE: InvitationPreview = {
  establishmentName: 'Bar do Zé',
  role: 'ADMIN_ESTABELECIMENTO',
  roleLabel: 'administrador do estabelecimento',
  email: 'ze@exemplo.com',
  expiresAt: '2026-10-16T12:00:00Z',
  accountExists: false,
  signedIn: false,
};

describe('InvitationPage', () => {
  let fixture: ComponentFixture<InvitationPage>;
  let http: HttpTestingController;
  let replaceState: ReturnType<typeof vi.spyOn>;

  const element = () => fixture.nativeElement as HTMLElement;
  const status = () => element().querySelector('[role="status"]')?.textContent?.trim() ?? '';
  const alerta = () => element().querySelector('[role="alert"]')?.textContent?.trim() ?? '';

  function abrir(fragment: string | null): void {
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [InvitationPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { fragment } } },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    replaceState = vi.spyOn(TestBed.inject(Location), 'replaceState');
    fixture = TestBed.createComponent(InvitationPage);
    fixture.detectChanges();
  }

  function mostrar(convite: Partial<InvitationPreview> = {}): void {
    const pedido = http.expectOne('/api/v1/invitations/preview');
    expect(pedido.request.method).toBe('POST');
    expect(pedido.request.body).toEqual({ token: 'abc_123' });
    pedido.flush({ ...CONVITE, ...convite });
    fixture.detectChanges();
  }

  function preencher(nome: string, senha: string, repeticao = senha): void {
    for (const [name, value] of [
      ['displayName', nome],
      ['password', senha],
      ['confirmation', repeticao],
    ]) {
      const input = element().querySelector<HTMLInputElement>(`input[formcontrolname="${name}"]`)!;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    }
    element().querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it('lê o token do fragmento, tira da barra de endereço e cria a conta no aceite', () => {
    abrir('token=abc_123');
    expect(replaceState).toHaveBeenCalledWith('/convite');
    expect(status()).toContain('Conferindo o convite');
    mostrar();
    expect(element().textContent).toContain(
      'Você recebeu um convite para participar de Bar do Zé como administrador do estabelecimento.',
    );
    expect(element().textContent).toContain('Convite para ze@exemplo.com');

    preencher('  Zé  ', 'frase do dono bem longa');
    const aceite = http.expectOne('/api/v1/invitations/accept');
    expect(aceite.request.body).toEqual({
      token: 'abc_123',
      displayName: 'Zé',
      password: 'frase do dono bem longa',
    });
    aceite.flush(
      { establishmentId: 'loja-1', signedIn: false },
      { status: 201, statusText: 'Created' },
    );
    fixture.detectChanges();

    expect(status()).toContain('Pronto! Agora você participa de Bar do Zé.');
    expect(status()).toContain('Entre com o seu e-mail e a senha que você acabou de criar.');
    expect(element().querySelector('a[href="/entrar"]')).not.toBeNull();
  });

  it('sem token não chama o servidor', () => {
    abrir(null);
    expect(status()).toContain('O link está incompleto');
    expect(element().querySelector('form')).toBeNull();
  });

  it('convite vencido, usado ou substituído', () => {
    abrir('token=abc_123');
    http
      .expectOne('/api/v1/invitations/preview')
      .flush({ code: 'CONVITE_INVALIDO' }, { status: 400, statusText: 'Bad Request' });
    fixture.detectChanges();
    expect(status()).toBe('Este convite não vale mais. Peça um novo a quem convidou você.');
    expect(element().querySelector('form')).toBeNull();
  });

  it('senhas diferentes não chamam o servidor; recusa da senha mostra o motivo', () => {
    abrir('token=abc_123');
    mostrar();
    preencher('Zé', 'frase do dono bem longa', 'outra frase');
    expect(element().textContent).toContain('As senhas não são iguais.');

    preencher('Zé', '123456789012');
    http
      .expectOne('/api/v1/invitations/accept')
      .flush(
        { code: 'DADOS_RECUSADOS', detail: 'Esta senha é muito comum. Escolha outra.' },
        { status: 400, statusText: 'Bad Request' },
      );
    fixture.detectChanges();
    expect(alerta()).toBe('Esta senha é muito comum. Escolha outra.');
    expect(element().querySelector('form')).not.toBeNull();
  });

  it('conta existente sem sessão: pede para entrar, sem formulário de senha', () => {
    abrir('token=abc_123');
    mostrar({ accountExists: true });
    expect(element().querySelector('form')).toBeNull();
    expect(element().textContent).toContain('Este e-mail já tem conta no fastPay. Entre com ela');
    expect(element().querySelector('a[href="/entrar"]')).not.toBeNull();
  });

  it('com sessão, aceita com a conta atual e leva à escolha da unidade', () => {
    abrir('token=abc_123');
    mostrar({ accountExists: true, signedIn: true });
    const botao = element().querySelector<HTMLButtonElement>('button')!;
    expect(botao.textContent?.trim()).toBe('Aceitar convite');
    botao.click();
    const aceite = http.expectOne('/api/v1/invitations/accept');
    expect(aceite.request.body).toEqual({ token: 'abc_123' });
    aceite.flush(
      { establishmentId: 'loja-1', signedIn: true },
      { status: 201, statusText: 'Created' },
    );
    fixture.detectChanges();
    expect(status()).toContain('Agora você participa de Bar do Zé.');
    expect(element().querySelector('a[href="/unidades"]')).not.toBeNull();
  });

  it('sessão de outra conta mostra o motivo e mantém o convite', () => {
    abrir('token=abc_123');
    mostrar({ accountExists: true, signedIn: true });
    element().querySelector<HTMLButtonElement>('button')!.click();
    http
      .expectOne('/api/v1/invitations/accept')
      .flush({ code: 'CONVITE_DE_OUTRA_CONTA' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();
    expect(alerta()).toBe(
      'Este convite foi enviado para outro e-mail. Saia e entre com a conta do e-mail convidado.',
    );
  });
});
