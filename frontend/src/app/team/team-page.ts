import { DatePipe } from '@angular/common';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Observable } from 'rxjs';

import {
  MembershipRole,
  Permission,
  UnitContextService,
  can,
  failureMessage,
  problemCode,
  roleLabel,
} from '../session/unit-context.service';
import { PendingInvitation, TeamMember, TeamService, TeamView } from './team.service';

type State = 'loading' | 'ready' | 'forbidden' | 'error';

const ROLES: MembershipRole[] = ['ADMIN_ESTABELECIMENTO', 'GERENTE', 'GARCOM', 'PRODUCAO'];

const INVITATION_LABELS: Record<PendingInvitation['status'], string> = {
  ENVIO_PENDENTE: 'aguardando envio',
  ENVIADO: 'enviado',
  VENCIDO: 'vencido',
  ACEITO: 'aceito',
  INVALIDADO: 'substituído',
};

/**
 * Equipe da unidade (H11, FR-02–06; PRD §4.2). Administrador: convida papéis, troca papel e desativa. Gerente e
 * administrador: dão a garçons permissões específicas delegáveis. Os botões seguem as permissões da sessão, mas é
 * o servidor que autoriza cada ação; uma recusa aparece com a mensagem dele.
 */
@Component({
  selector: 'fp-team-page',
  imports: [
    DatePipe,
    ReactiveFormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
  ],
  template: `
    <h1>Equipe</h1>
    @if (state() === 'forbidden') {
      <p role="alert">Você não tem permissão para ver a equipe desta unidade.</p>
    } @else {
      <div role="status" aria-live="polite">
        @if (state() === 'loading') {
          <mat-progress-spinner mode="indeterminate" diameter="32" aria-hidden="true" />
          <p>Carregando…</p>
        } @else if (state() === 'error') {
          <p>Não foi possível carregar a equipe. Verifique sua conexão e tente novamente.</p>
        }
        @if (notice(); as message) {
          <p>{{ message }}</p>
        }
      </div>
      <div role="alert">
        @if (failure(); as message) {
          <p class="error">{{ message }}</p>
        }
      </div>
      @if (state() === 'error') {
        <button mat-stroked-button type="button" (click)="load()">Tentar novamente</button>
      }

      @if (manage()) {
        <mat-card appearance="outlined">
          <mat-card-header>
            <mat-card-title><h2>Convidar pessoa</h2></mat-card-title>
          </mat-card-header>
          <mat-card-content>
            <p class="hint">
              A pessoa recebe um convite por e-mail, válido por 48 horas, e cria a própria senha.
            </p>
            <form [formGroup]="invite" (ngSubmit)="sendInvite()" novalidate>
              <mat-form-field appearance="outline">
                <mat-label>E-mail</mat-label>
                <input matInput type="email" formControlName="email" autocomplete="off" />
                @if (invite.controls.email.hasError('required')) {
                  <mat-error>Informe o e-mail.</mat-error>
                } @else if (invite.controls.email.hasError('email')) {
                  <mat-error>Confira o e-mail.</mat-error>
                }
              </mat-form-field>
              <mat-form-field appearance="outline">
                <mat-label>Papel</mat-label>
                <select matNativeControl formControlName="role">
                  @for (role of roles; track role) {
                    <option [value]="role">{{ label(role) }}</option>
                  }
                </select>
              </mat-form-field>
              <button mat-flat-button type="submit" [disabled]="busy()">Enviar convite</button>
            </form>
          </mat-card-content>
        </mat-card>
      }

      @if (team(); as data) {
        <h2>Pessoas</h2>
        <ul class="list" aria-label="Pessoas da equipe">
          @for (member of data.members; track member.membershipId) {
            <li>
              <mat-card appearance="outlined">
                <mat-card-content>
                  <p class="name">
                    {{ member.displayName }}
                    @if (isMe(member)) {
                      (você)
                    }
                  </p>
                  <p>{{ member.email }}</p>
                  <p>
                    {{ label(member.role) }}
                    @if (!member.active) {
                      · desativado em {{ member.deactivatedAt | date: 'dd/MM/yyyy' }}
                    }
                  </p>
                  @if (member.active && member.role === 'GARCOM' && member.delegated.length > 0) {
                    <p>Pode também: {{ delegatedLabels(member) }}</p>
                  }

                  @if (member.active && !isMe(member) && manage()) {
                    <div class="row">
                      <mat-form-field appearance="outline">
                        <mat-label>Novo papel de {{ member.displayName }}</mat-label>
                        <select
                          matNativeControl
                          [value]="member.role"
                          (change)="pickRole(member, $any($event.target).value)"
                        >
                          @for (role of roles; track role) {
                            <option [value]="role">{{ label(role) }}</option>
                          }
                        </select>
                      </mat-form-field>
                      <button
                        mat-stroked-button
                        type="button"
                        [disabled]="busy() || (roleChoice()[member.membershipId] ?? member.role) === member.role"
                        (click)="changeRole(member)"
                      >
                        Trocar papel de {{ member.displayName }}
                      </button>
                      <button
                        mat-stroked-button
                        type="button"
                        [disabled]="busy()"
                        (click)="deactivate(member)"
                      >
                        Desativar acesso de {{ member.displayName }}
                      </button>
                    </div>
                  }

                  @if (member.active && member.role === 'GARCOM' && !isMe(member) && delegate()) {
                    <fieldset>
                      <legend>Permissões específicas de {{ member.displayName }}</legend>
                      @for (permission of data.delegable; track permission.code) {
                        <label class="check">
                          <input
                            type="checkbox"
                            [checked]="chosen(member).includes(permission.code)"
                            (change)="toggle(member, permission.code, $any($event.target).checked)"
                          />
                          {{ permission.label }}
                        </label>
                      }
                      <button
                        mat-stroked-button
                        type="button"
                        [disabled]="busy()"
                        (click)="savePermissions(member)"
                      >
                        Salvar permissões de {{ member.displayName }}
                      </button>
                    </fieldset>
                  }
                </mat-card-content>
              </mat-card>
            </li>
          }
        </ul>

        @if (manage() && data.pendingInvitations.length > 0) {
          <h2>Convites em aberto</h2>
          <ul class="list" aria-label="Convites em aberto">
            @for (invitation of data.pendingInvitations; track invitation.id) {
              <li>
                <mat-card appearance="outlined">
                  <mat-card-content>
                    <p class="name">{{ invitation.email }}</p>
                    <p>
                      {{ label(invitation.role) }} · convite {{ invitationLabel(invitation) }}
                      @if (invitation.status === 'ENVIADO') {
                        até {{ invitation.expiresAt | date: 'dd/MM/yyyy HH:mm' }}
                      }
                    </p>
                    <div class="row">
                      <button
                        mat-stroked-button
                        type="button"
                        [disabled]="busy()"
                        (click)="resend(invitation)"
                      >
                        Reenviar convite para {{ invitation.email }}
                      </button>
                      <button
                        mat-stroked-button
                        type="button"
                        [disabled]="busy()"
                        (click)="cancel(invitation)"
                      >
                        Cancelar convite para {{ invitation.email }}
                      </button>
                    </div>
                  </mat-card-content>
                </mat-card>
              </li>
            }
          </ul>
        }
      }
    }
  `,
  styles: `
    :host {
      display: block;
      max-width: 720px;
      margin: 0 auto;
    }
    h1,
    h2 {
      font: inherit;
      font-weight: 500;
    }
    form,
    fieldset {
      display: grid;
      gap: 12px;
    }
    fieldset {
      border: 1px solid var(--mat-sys-outline-variant);
      border-radius: 8px;
      padding: 12px;
    }
    .row {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      align-items: center;
    }
    .check {
      display: flex;
      gap: 8px;
      align-items: center;
    }
    .list {
      list-style: none;
      padding: 0;
      display: grid;
      gap: 8px;
    }
    .name {
      font-weight: 500;
    }
    .hint {
      color: var(--mat-sys-on-surface-variant);
    }
    .error {
      color: var(--mat-sys-error);
    }
  `,
})
export class TeamPage {
  private readonly service = inject(TeamService);
  private readonly unit = inject(UnitContextService).current;
  private readonly destroyRef = inject(DestroyRef);

  protected readonly roles = ROLES;
  protected readonly invite = inject(FormBuilder).nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    role: ['GARCOM' as MembershipRole, Validators.required],
  });
  protected readonly state = signal<State>('loading');
  protected readonly team = signal<TeamView | null>(null);
  protected readonly busy = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly failure = signal<string | null>(null);
  protected readonly roleChoice = signal<Record<string, MembershipRole>>({});
  private readonly permissionChoice = signal<Record<string, Permission[]>>({});

  protected readonly manage = computed(() => can(this.unit(), 'GERIR_EQUIPE'));
  protected readonly delegate = computed(() => can(this.unit(), 'DELEGAR_PERMISSOES'));

  constructor() {
    this.load();
  }

  protected label(role: MembershipRole): string {
    return roleLabel(role);
  }

  protected invitationLabel(invitation: PendingInvitation): string {
    return INVITATION_LABELS[invitation.status];
  }

  protected isMe(member: TeamMember): boolean {
    return this.team()?.currentMembershipId === member.membershipId;
  }

  protected delegatedLabels(member: TeamMember): string {
    const labels = new Map(this.team()?.delegable.map((p) => [p.code, p.label]) ?? []);
    return member.delegated.map((code) => labels.get(code) ?? code).join(', ');
  }

  protected chosen(member: TeamMember): Permission[] {
    return this.permissionChoice()[member.membershipId] ?? member.delegated;
  }

  protected toggle(member: TeamMember, code: Permission, checked: boolean): void {
    const current = this.chosen(member).filter((p) => p !== code);
    this.permissionChoice.update((all) => ({
      ...all,
      [member.membershipId]: checked ? [...current, code] : current,
    }));
  }

  protected pickRole(member: TeamMember, role: MembershipRole): void {
    this.roleChoice.update((all) => ({ ...all, [member.membershipId]: role }));
  }

  protected load(): void {
    this.state.set('loading');
    this.service
      .load()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (team) => this.adopt(team),
        error: (error: unknown) =>
          this.state.set(problemCode(error) === 'PERMISSAO_NEGADA' ? 'forbidden' : 'error'),
      });
  }

  protected sendInvite(): void {
    if (this.invite.invalid) {
      this.invite.markAllAsTouched();
      return;
    }
    const { email, role } = this.invite.getRawValue();
    this.run(this.service.invite(email.trim(), role), `Convite a caminho de ${email.trim()}.`, () =>
      this.invite.reset({ email: '', role: 'GARCOM' }),
    );
  }

  protected resend(invitation: PendingInvitation): void {
    this.run(
      this.service.resend(invitation.id),
      `Novo convite a caminho de ${invitation.email}. O anterior deixou de valer.`,
    );
  }

  protected cancel(invitation: PendingInvitation): void {
    this.run(this.service.cancel(invitation.id), `Convite para ${invitation.email} cancelado.`);
  }

  protected changeRole(member: TeamMember): void {
    const role = this.roleChoice()[member.membershipId] ?? member.role;
    this.run(
      this.service.changeRole(member.membershipId, role),
      `${member.displayName} agora é ${roleLabel(role)}.`,
    );
  }

  protected deactivate(member: TeamMember): void {
    this.run(
      this.service.deactivate(member.membershipId),
      `${member.displayName} não tem mais acesso a esta unidade.`,
    );
  }

  protected savePermissions(member: TeamMember): void {
    this.run(
      this.service.setPermissions(member.membershipId, this.chosen(member)),
      `Permissões de ${member.displayName} salvas.`,
    );
  }

  private run(action: Observable<TeamView>, success: string, after?: () => void): void {
    this.busy.set(true);
    this.notice.set(null);
    this.failure.set(null);
    action.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (team) => {
        this.busy.set(false);
        this.adopt(team);
        this.notice.set(success);
        after?.();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.failure.set(failureMessage(error));
      },
    });
  }

  private adopt(team: TeamView): void {
    this.team.set(team);
    this.roleChoice.set({});
    this.permissionChoice.set({});
    this.state.set('ready');
  }
}
