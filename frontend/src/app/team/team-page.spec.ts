import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { comCookieCsrf } from '../auth/auth-testing';
import { Permission, UnitContextService } from '../session/unit-context.service';
import { TeamPage } from './team-page';
import { TeamView } from './team.service';

const EQUIPE: TeamView = {
  members: [
    {
      membershipId: 'v-dono',
      displayName: 'Diana',
      email: 'diana@exemplo.com',
      role: 'ADMIN_ESTABELECIMENTO',
      active: true,
      since: '2026-10-10T12:00:00Z',
      deactivatedAt: null,
      delegated: [],
    },
    {
      membershipId: 'v-garcom',
      displayName: 'Gabriel',
      email: 'gabriel@exemplo.com',
      role: 'GARCOM',
      active: true,
      since: '2026-10-11T12:00:00Z',
      deactivatedAt: null,
      delegated: ['CANCELAR_ITEM'],
    },
  ],
  pendingInvitations: [
    {
      id: 'c-1',
      email: 'novo@exemplo.com',
      role: 'PRODUCAO',
      status: 'ENVIADO',
      createdAt: '2026-10-16T10:00:00Z',
      sentAt: '2026-10-16T10:00:01Z',
      expiresAt: '2026-10-18T10:00:00Z',
    },
  ],
  delegable: [
    { code: 'CANCELAR_ITEM', label: 'Cancelar item' },
    { code: 'CONCEDER_DESCONTO', label: 'Conceder desconto' },
  ],
  currentMembershipId: 'v-dono',
};

const DONO: Permission[] = ['VER_EQUIPE', 'GERIR_EQUIPE', 'DELEGAR_PERMISSOES'];
const GERENTE: Permission[] = ['VER_EQUIPE', 'DELEGAR_PERMISSOES'];

describe('TeamPage', () => {
  let fixture: ComponentFixture<TeamPage>;
  let http: HttpTestingController;

  const element = () => fixture.nativeElement as HTMLElement;
  const status = () => element().querySelector('[role="status"]')?.textContent?.trim() ?? '';
  const alerta = () => element().querySelector('[role="alert"]')?.textContent?.trim() ?? '';
  const botao = (texto: string) =>
    Array.from(element().querySelectorAll<HTMLButtonElement>('button')).find((b) =>
      b.textContent?.includes(texto),
    );

  function abrir(permissions: Permission[], equipe: TeamView = EQUIPE): void {
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [TeamPage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    TestBed.inject(UnitContextService).current.set({
      establishmentId: 'a',
      name: 'Bar da Praia',
      role: 'ADMIN_ESTABELECIMENTO',
      permissions,
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TeamPage);
    fixture.detectChanges();
    http.expectOne('/api/v1/team').flush(equipe);
    fixture.detectChanges();
  }

  afterEach(() => http.verify());

  it('o dono vê a equipe, os convites e não altera o próprio perfil', () => {
    abrir(DONO);
    expect(element().textContent).toMatch(/Diana\s+\(você\)/);
    expect(element().textContent).toContain('Pode também: Cancelar item');
    expect(element().textContent).toContain('Produção · convite enviado');
    expect(botao('Trocar papel de Diana')).toBeUndefined();
    expect(botao('Desativar acesso de Diana')).toBeUndefined();
    expect(botao('Trocar papel de Gabriel')).toBeDefined();
  });

  it('convida com o papel escolhido', () => {
    abrir(DONO);
    const email = element().querySelector<HTMLInputElement>('input[formcontrolname="email"]')!;
    email.value = ' nova@exemplo.com ';
    email.dispatchEvent(new Event('input'));
    const papel = element().querySelector<HTMLSelectElement>('select[formcontrolname="role"]')!;
    papel.value = 'GERENTE';
    papel.dispatchEvent(new Event('change'));
    botao('Enviar convite')!.click();
    const pedido = http.expectOne('/api/v1/team/invitations');
    expect(pedido.request.method).toBe('POST');
    expect(pedido.request.body).toEqual({ email: 'nova@exemplo.com', role: 'GERENTE' });
    pedido.flush(EQUIPE);
    fixture.detectChanges();
    expect(status()).toContain('Convite a caminho de nova@exemplo.com.');
  });

  it('e-mail inválido não chama o servidor', () => {
    abrir(DONO);
    botao('Enviar convite')!.click();
    fixture.detectChanges();
    expect(element().textContent).toContain('Informe o e-mail.');
  });

  it('troca o papel e mostra a recusa do servidor quando é o último administrador', () => {
    abrir(DONO);
    const papel = element().querySelector<HTMLSelectElement>('li select')!;
    expect(botao('Trocar papel de Gabriel')!.disabled).toBe(true);
    papel.value = 'GERENTE';
    papel.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    botao('Trocar papel de Gabriel')!.click();
    const pedido = http.expectOne('/api/v1/team/members/v-garcom/role');
    expect(pedido.request.method).toBe('PUT');
    expect(pedido.request.body).toEqual({ role: 'GERENTE' });
    pedido.flush(
      {
        code: 'ULTIMO_ADMIN',
        detail: 'A unidade precisa de pelo menos um administrador ativo. Nomeie outro antes.',
      },
      { status: 409, statusText: 'Conflict' },
    );
    fixture.detectChanges();
    expect(alerta()).toBe(
      'A unidade precisa de pelo menos um administrador ativo. Nomeie outro antes.',
    );
  });

  it('desativa e reenvia convite', () => {
    abrir(DONO);
    botao('Desativar acesso de Gabriel')!.click();
    http.expectOne('/api/v1/team/members/v-garcom/deactivate').flush(EQUIPE);
    fixture.detectChanges();
    expect(status()).toContain('Gabriel não tem mais acesso a esta unidade.');

    botao('Reenviar convite para novo@exemplo.com')!.click();
    const pedido = http.expectOne('/api/v1/team/invitations/c-1/resend');
    expect(pedido.request.method).toBe('POST');
    pedido.flush(EQUIPE);
    fixture.detectChanges();
    expect(status()).toContain('Novo convite a caminho de novo@exemplo.com.');
  });

  it('cancela convite', () => {
    abrir(DONO);
    botao('Cancelar convite para novo@exemplo.com')!.click();
    const pedido = http.expectOne('/api/v1/team/invitations/c-1');
    expect(pedido.request.method).toBe('DELETE');
    pedido.flush({ ...EQUIPE, pendingInvitations: [] });
    fixture.detectChanges();
    expect(status()).toContain('Convite para novo@exemplo.com cancelado.');
    expect(element().textContent).not.toContain('Convites em aberto');
  });

  it('o gerente delega ao garçom, mas não convida, não troca papel nem desativa', () => {
    abrir(GERENTE, { ...EQUIPE, currentMembershipId: 'v-gerente' });
    expect(botao('Enviar convite')).toBeUndefined();
    expect(botao('Trocar papel de Gabriel')).toBeUndefined();
    expect(botao('Desativar acesso de Gabriel')).toBeUndefined();
    expect(element().textContent).not.toContain('Convites em aberto');

    const desconto = Array.from(element().querySelectorAll<HTMLLabelElement>('label.check'))
      .find((l) => l.textContent?.includes('Conceder desconto'))!
      .querySelector('input')!;
    desconto.click();
    fixture.detectChanges();
    botao('Salvar permissões de Gabriel')!.click();
    const pedido = http.expectOne('/api/v1/team/members/v-garcom/permissions');
    expect(pedido.request.body).toEqual({ permissions: ['CANCELAR_ITEM', 'CONCEDER_DESCONTO'] });
    pedido.flush(EQUIPE);
    fixture.detectChanges();
    expect(status()).toContain('Permissões de Gabriel salvas.');
  });

  it('sem permissão mostra o aviso; falha de rede permite tentar de novo', () => {
    comCookieCsrf(true);
    TestBed.configureTestingModule({
      imports: [TeamPage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TeamPage);
    fixture.detectChanges();
    http
      .expectOne('/api/v1/team')
      .flush({ code: 'PERMISSAO_NEGADA' }, { status: 403, statusText: 'Forbidden' });
    fixture.detectChanges();
    expect(alerta()).toBe('Você não tem permissão para ver a equipe desta unidade.');

    fixture = TestBed.createComponent(TeamPage);
    fixture.detectChanges();
    http.expectOne('/api/v1/team').error(new ProgressEvent('error'));
    fixture.detectChanges();
    expect(status()).toContain('Não foi possível carregar a equipe.');
    botao('Tentar novamente')!.click();
    http.expectOne('/api/v1/team').flush(EQUIPE);
    fixture.detectChanges();
    expect(element().textContent).toContain('Gabriel');
  });
});
