package tech.malyah.fastpay.identity;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tech.malyah.fastpay.identity.domain.AccessPolicy;
import tech.malyah.fastpay.identity.domain.AccessPolicy.DelegationRefusal;
import tech.malyah.fastpay.identity.domain.AuthKeys;
import tech.malyah.fastpay.identity.infrastructure.JdbcInvitationStore;
import tech.malyah.fastpay.identity.infrastructure.JdbcInvitationStore.Invitation;
import tech.malyah.fastpay.identity.infrastructure.JdbcMembershipStore;
import tech.malyah.fastpay.identity.infrastructure.JdbcMembershipStore.LockedMembership;
import tech.malyah.fastpay.identity.infrastructure.JdbcMembershipStore.MemberRow;

/**
 * Equipe da unidade (H11, FR-02, FR-03, FR-06; PRD §4.2). O administrador do estabelecimento convida papéis,
 * troca o papel e desativa vínculos; gerente e administrador concedem a garçons só permissões delegáveis. Toda
 * mudança roda na unidade da sessão, com a permissão conferida no servidor e as linhas de {@code membership}
 * envolvidas travadas (inclusive a de quem age e as dos administradores ativos), para que mudanças simultâneas
 * nunca deixem a unidade sem administrador ativo nem deixem agir quem acabou de perder a autoridade. Ninguém
 * altera o próprio vínculo. Desativar preserva a linha e a autoria; a sessão da pessoa perde o acesso à unidade
 * na operação seguinte ({@link Memberships#inUnit}).
 */
@Service
public class Team {

    private static final Logger log = LoggerFactory.getLogger(Team.class);

    public record Member(UUID membershipId, UUID identityId, String displayName, String email, MembershipRole role,
            boolean active, Instant since, Instant deactivatedAt, List<Permission> delegated) {
    }

    /** {@code currentMembershipId}: vínculo de quem pediu (vazio na consulta do suporte, que não tem vínculo). */
    public record Overview(List<Member> members, List<Invitations.Summary> pendingInvitations,
            UUID currentMembershipId) {
    }

    /** Por que uma mudança de equipe foi recusada. */
    public enum Refusal {
        VINCULO_NAO_ENCONTRADO,
        CONVITE_NAO_ENCONTRADO,
        /** Ninguém altera o próprio papel, situação ou permissões. */
        PROPRIO_VINCULO,
        /** Sempre resta pelo menos um administrador ativo. */
        ULTIMO_ADMIN,
        /** O e-mail já tem vínculo ativo na unidade. */
        JA_PARTICIPA,
        /** Só garçom recebe permissão específica. */
        PAPEL_NAO_RECEBE,
        /** Ação exclusiva de papel, nunca delegável. */
        NAO_DELEGAVEL,
        EMAIL_INVALIDO
    }

    public sealed interface Outcome {
    }

    public record Done(Overview overview) implements Outcome {
    }

    public record Refused(Refusal refusal) implements Outcome {
    }

    private final Memberships memberships;
    private final Invitations invitations;
    private final JdbcMembershipStore store;
    private final JdbcInvitationStore invitationStore;
    private final Clock clock;

    Team(Memberships memberships, Invitations invitations, JdbcMembershipStore store,
            JdbcInvitationStore invitationStore, Clock clock) {
        this.memberships = memberships;
        this.invitations = invitations;
        this.store = store;
        this.invitationStore = invitationStore;
        this.clock = clock;
    }

    /** Equipe e convites em aberto, para quem tem {@link Permission#VER_EQUIPE}. */
    public Overview overview(UUID actorId, UUID establishmentId) {
        return memberships.inUnit(actorId, establishmentId, Permission.VER_EQUIPE, this::overviewFor);
    }

    /**
     * Equipe da unidade do contexto, na transação corrente, sem conferir quem lê: para a consulta do suporte
     * autorizado, que já confere a concessão e roda somente leitura ({@code platform.SupportAccess}).
     */
    public Overview overviewInCurrentUnit() {
        return overview(null);
    }

    private Overview overviewFor(UnitMembership me) {
        return overview(me.membershipId());
    }

    private Overview overview(UUID currentMembershipId) {
        Instant now = clock.instant();
        List<Member> members = store.members().stream().map(Team::member).toList();
        List<Invitations.Summary> pending = invitationStore.openInCurrentUnit().stream()
                .map(i -> Invitations.summary(i, now)).toList();
        return new Overview(members, pending, currentMembershipId);
    }

    /** Convida um e-mail para um papel; um convite em aberto para o mesmo e-mail deixa de valer. */
    public Outcome invite(UUID actorId, UUID establishmentId, String email, MembershipRole role, String correlationId) {
        Objects.requireNonNull(role, "role");
        String normalized = AuthKeys.normalizeEmail(email);
        if (!AuthKeys.plausibleEmail(normalized)) {
            return new Refused(Refusal.EMAIL_INVALIDO);
        }
        return manage(actorId, establishmentId, Permission.GERIR_EQUIPE, null, (me, locked) -> {
            if (store.activeMemberWithEmail(normalized)) {
                return new Refused(Refusal.JA_PARTICIPA);
            }
            UUID invitationId = invitations.issue(normalized, role, actorId, correlationId);
            store.audit("convite.criado", invitationId, actorId, role.name(), correlationId);
            return done(me);
        });
    }

    /** Novo convite para o mesmo e-mail e papel de um convite em aberto, que deixa de valer. */
    public Outcome resendInvitation(UUID actorId, UUID establishmentId, UUID invitationId, String correlationId) {
        return manage(actorId, establishmentId, Permission.GERIR_EQUIPE, null, (me, locked) -> {
            Optional<Invitation> found = invitationStore.inCurrentUnit(invitationId).filter(Invitation::open);
            if (found.isEmpty()) {
                return new Refused(Refusal.CONVITE_NAO_ENCONTRADO);
            }
            if (store.activeMemberWithEmail(found.get().email())) {
                return new Refused(Refusal.JA_PARTICIPA);
            }
            UUID reissued = invitations.issue(found.get().email(), found.get().role(), actorId, correlationId);
            store.audit("convite.reenviado", reissued, actorId, found.get().role().name(), correlationId);
            return done(me);
        });
    }

    public Outcome cancelInvitation(UUID actorId, UUID establishmentId, UUID invitationId, String correlationId) {
        return manage(actorId, establishmentId, Permission.GERIR_EQUIPE, null, (me, locked) -> {
            if (!invitationStore.invalidate(invitationId, clock.instant())) {
                return new Refused(Refusal.CONVITE_NAO_ENCONTRADO);
            }
            store.audit("convite.cancelado", invitationId, actorId, correlationId);
            return done(me);
        });
    }

    /**
     * Troca o papel de outra pessoa da equipe. Deixar de ser garçom revoga as permissões delegadas. Promoção a
     * gerente ou administrador exige o autenticador antes da operação seguinte da pessoa (FR-05).
     */
    public Outcome changeRole(UUID actorId, UUID establishmentId, UUID membershipId, MembershipRole role,
            String correlationId) {
        Objects.requireNonNull(role, "role");
        return manage(actorId, establishmentId, Permission.GERIR_EQUIPE, membershipId, (me, locked) -> {
            Optional<LockedMembership> target = activeTarget(locked, membershipId);
            if (target.isEmpty()) {
                return new Refused(Refusal.VINCULO_NAO_ENCONTRADO);
            }
            MembershipRole previous = target.get().role();
            if (previous == role) {
                return done(me);
            }
            if (previous == MembershipRole.ADMIN_ESTABELECIMENTO && activeAdmins(locked) <= 1) {
                return new Refused(Refusal.ULTIMO_ADMIN);
            }
            store.changeRole(membershipId, role);
            store.audit("vinculo.papel_alterado", membershipId, actorId, previous + ">" + role, correlationId);
            if (role != MembershipRole.GARCOM) {
                Instant now = clock.instant();
                for (Permission p : store.delegations(membershipId)) {
                    store.revoke(membershipId, p, actorId, now);
                    store.audit("permissao.revogada", membershipId, actorId, p.name(), correlationId);
                }
            }
            log.info("Vínculo {} passou de {} para {} por {}", membershipId, previous, role, actorId);
            return done(me);
        });
    }

    /** Desativa o vínculo de outra pessoa, preservando a linha e os vínculos dela em outras unidades. */
    public Outcome deactivate(UUID actorId, UUID establishmentId, UUID membershipId, String correlationId) {
        return manage(actorId, establishmentId, Permission.GERIR_EQUIPE, membershipId, (me, locked) -> {
            Optional<LockedMembership> target = activeTarget(locked, membershipId);
            if (target.isEmpty()) {
                return new Refused(Refusal.VINCULO_NAO_ENCONTRADO);
            }
            if (target.get().role() == MembershipRole.ADMIN_ESTABELECIMENTO && activeAdmins(locked) <= 1) {
                return new Refused(Refusal.ULTIMO_ADMIN);
            }
            store.deactivate(membershipId, actorId);
            store.audit("vinculo.desativado", membershipId, actorId, target.get().role().name(), correlationId);
            log.info("Vínculo {} desativado por {}", membershipId, actorId);
            return done(me);
        });
    }

    /**
     * Deixa o garçom com exatamente estas permissões específicas: concede as que faltam e revoga as demais. Só
     * permissões delegáveis e dentro da autoridade de quem concede; devolução e demais ações exclusivas nunca.
     */
    public Outcome setPermissions(UUID actorId, UUID establishmentId, UUID membershipId, Set<Permission> wanted,
            String correlationId) {
        Set<Permission> target = wanted.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(wanted);
        return manage(actorId, establishmentId, Permission.DELEGAR_PERMISSOES, membershipId, (me, locked) -> {
            Optional<LockedMembership> member = activeTarget(locked, membershipId);
            if (member.isEmpty()) {
                return new Refused(Refusal.VINCULO_NAO_ENCONTRADO);
            }
            MembershipRole granter = lockedRole(locked, me);
            Optional<DelegationRefusal> refusal = AccessPolicy.delegation(granter, false, member.get().role(), target);
            if (refusal.isPresent()) {
                return new Refused(switch (refusal.get()) {
                    case SEM_AUTORIDADE -> throw new PermissionDeniedException(Permission.DELEGAR_PERMISSOES);
                    case PROPRIO_VINCULO -> Refusal.PROPRIO_VINCULO;
                    case PAPEL_NAO_RECEBE -> Refusal.PAPEL_NAO_RECEBE;
                    case NAO_DELEGAVEL -> Refusal.NAO_DELEGAVEL;
                });
            }
            Instant now = clock.instant();
            List<Permission> current = store.delegations(membershipId);
            for (Permission p : current) {
                if (!target.contains(p)) {
                    store.revoke(membershipId, p, actorId, now);
                    store.audit("permissao.revogada", membershipId, actorId, p.name(), correlationId);
                }
            }
            for (Permission p : target) {
                if (!current.contains(p)) {
                    store.grant(membershipId, p, actorId, now);
                    store.audit("permissao.concedida", membershipId, actorId, p.name(), correlationId);
                }
            }
            return done(me);
        });
    }

    private interface Change {
        Outcome apply(UnitMembership me, List<LockedMembership> locked);
    }

    /**
     * Mudança de equipe: confere a permissão, recusa o próprio vínculo, trava as linhas envolvidas e confere de
     * novo, sobre as linhas travadas, que quem age continua ativo e com a autoridade (outra mudança simultânea
     * pode ter acabado de tirá-la).
     */
    private Outcome manage(UUID actorId, UUID establishmentId, Permission permission, UUID targetMembershipId,
            Change change) {
        return memberships.inUnit(actorId, establishmentId, permission, me -> {
            if (me.membershipId().equals(targetMembershipId)) {
                return new Refused(Refusal.PROPRIO_VINCULO);
            }
            List<UUID> ids = new ArrayList<>();
            ids.add(me.membershipId());
            if (targetMembershipId != null) {
                ids.add(targetMembershipId);
            }
            List<LockedMembership> locked = store.lockForTeamChange(ids);
            if (!AccessPolicy.byRole(lockedRole(locked, me)).contains(permission)) {
                throw new PermissionDeniedException(permission);
            }
            return change.apply(me, locked);
        });
    }

    /** Papel de quem age depois do lock; vínculo desativado no meio do caminho perde o acesso à unidade. */
    private static MembershipRole lockedRole(List<LockedMembership> locked, UnitMembership me) {
        return locked.stream().filter(l -> l.membershipId().equals(me.membershipId()) && l.active())
                .map(LockedMembership::role).findFirst()
                .orElseThrow(() -> new UnitAccessDeniedException(me.establishmentId()));
    }

    private static Optional<LockedMembership> activeTarget(List<LockedMembership> locked, UUID membershipId) {
        return locked.stream().filter(l -> l.membershipId().equals(membershipId) && l.active()).findFirst();
    }

    private static long activeAdmins(List<LockedMembership> locked) {
        return locked.stream().filter(l -> l.active() && l.role() == MembershipRole.ADMIN_ESTABELECIMENTO).count();
    }

    private Done done(UnitMembership me) {
        return new Done(overviewFor(me));
    }

    private static Member member(MemberRow row) {
        return new Member(row.membershipId(), row.identityId(), row.displayName(), row.email(), row.role(),
                row.active(), row.createdAt(), row.deactivatedAt(), row.delegated());
    }
}
