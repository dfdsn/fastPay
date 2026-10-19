package tech.malyah.fastpay.identity.domain;

import static tech.malyah.fastpay.identity.Permission.*;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import tech.malyah.fastpay.identity.MembershipRole;
import tech.malyah.fastpay.identity.Permission;

/**
 * Matriz de acesso por papel (H11, PRD §4.2). Regras puras: quem chama lê papel e delegações do banco na
 * transação do comando.
 *
 * <ul>
 * <li>Garçom: operação de salão e atendimento; o resto só por permissão específica delegada, sempre delegável.
 * <li>Produção: só a fila do setor, sem visão financeira; não recebe delegação.
 * <li>Gerente: toda a operação, inclusive devolução, débito, regularização e invalidação de recebimento externo
 * (não delegáveis); vê a equipe e delega a garçons.
 * <li>Administrador do estabelecimento: tudo do gerente (exerce gerente) e o que é exclusivo dele: equipe,
 * conta PSP e autorização de suporte.
 * </ul>
 */
public final class AccessPolicy {

    private static final Set<Permission> DELEGABLE = EnumSet.noneOf(Permission.class);

    static {
        for (Permission p : Permission.values()) {
            if (p.delegable()) {
                DELEGABLE.add(p);
            }
        }
    }

    private static final Set<Permission> MANAGER = EnumSet.complementOf(
            EnumSet.of(GERIR_EQUIPE, CONECTAR_CONTA_PSP, AUTORIZAR_SUPORTE));

    private static final Map<MembershipRole, Set<Permission>> BY_ROLE = Map.of(
            MembershipRole.GARCOM, EnumSet.of(OPERAR_COMANDA, ATENDER_PROBLEMA),
            MembershipRole.PRODUCAO, EnumSet.of(OPERAR_PRODUCAO),
            MembershipRole.GERENTE, MANAGER,
            MembershipRole.ADMIN_ESTABELECIMENTO, EnumSet.allOf(Permission.class));

    /** Motivo de uma delegação recusada. */
    public enum DelegationRefusal {
        /** Quem concede não tem {@link Permission#DELEGAR_PERMISSOES}. */
        SEM_AUTORIDADE,
        /** Ninguém altera as próprias permissões. */
        PROPRIO_VINCULO,
        /** Só garçom recebe permissão específica. */
        PAPEL_NAO_RECEBE,
        /** Ação exclusiva de papel (devolução, débito, equipe...). */
        NAO_DELEGAVEL
    }

    private AccessPolicy() {
    }

    /** Permissões do papel, sem delegações. */
    public static Set<Permission> byRole(MembershipRole role) {
        return EnumSet.copyOf(BY_ROLE.get(role));
    }

    public static Set<Permission> delegable() {
        return EnumSet.copyOf(DELEGABLE);
    }

    /**
     * Permissões efetivas: as do papel mais as delegadas, que só valem para garçom e só se delegáveis (uma
     * linha antiga de outro papel ou uma ação exclusiva gravada por engano não concede nada).
     */
    public static Set<Permission> effective(MembershipRole role, Collection<Permission> delegated) {
        Set<Permission> result = byRole(role);
        if (role == MembershipRole.GARCOM) {
            delegated.stream().filter(DELEGABLE::contains).forEach(result::add);
        }
        return result;
    }

    /**
     * Pode o vínculo {@code granterRole} deixar o vínculo {@code targetRole} com exatamente estas permissões
     * específicas (inclusive nenhuma)? O gerente só delega o que está dentro da autoridade dele, e nenhuma ação
     * exclusiva.
     */
    public static Optional<DelegationRefusal> delegation(MembershipRole granterRole, boolean sameMembership,
            MembershipRole targetRole, Collection<Permission> permissions) {
        Set<Permission> granter = byRole(granterRole);
        if (!granter.contains(DELEGAR_PERMISSOES)) {
            return Optional.of(DelegationRefusal.SEM_AUTORIDADE);
        }
        if (sameMembership) {
            return Optional.of(DelegationRefusal.PROPRIO_VINCULO);
        }
        if (targetRole != MembershipRole.GARCOM) {
            return Optional.of(DelegationRefusal.PAPEL_NAO_RECEBE);
        }
        for (Permission permission : permissions) {
            if (!DELEGABLE.contains(permission) || !granter.contains(permission)) {
                return Optional.of(DelegationRefusal.NAO_DELEGAVEL);
            }
        }
        return Optional.empty();
    }
}
