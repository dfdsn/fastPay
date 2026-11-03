package tech.malyah.fastpay.identity.domain;

import static tech.malyah.fastpay.identity.Permission.*;

import java.math.BigDecimal;
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
 * (não delegáveis); vê a equipe e delega a garçons; vê a configuração da unidade sem alterá-la (H14).
 * <li>Administrador do estabelecimento: tudo do gerente (exerce gerente) e o que é exclusivo dele: equipe,
 * conta PSP, autorização de suporte, catálogo e setores (H13) e configuração da unidade, como o logo (FR-67).
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
            EnumSet.of(GERIR_EQUIPE, CONECTAR_CONTA_PSP, AUTORIZAR_SUPORTE, GERIR_CATALOGO,
                    CONFIGURAR_ESTABELECIMENTO));

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

    /** Desconto até todo o consumo. */
    public static final BigDecimal FULL_DISCOUNT = new BigDecimal("100.0000");
    private static final BigDecimal NO_DISCOUNT = new BigDecimal("0.0000");

    /** Motivo de um limite de desconto recusado (H14). */
    public enum DiscountLimitRefusal {
        /** Quem muda não tem autoridade sobre o papel da pessoa. */
        SEM_AUTORIDADE,
        /** Ninguém altera o próprio limite. */
        PROPRIO_VINCULO,
        /** Administrador tem sempre 100%; produção não concede desconto. */
        PAPEL_NAO_RECEBE,
        /** O gerente não dá a um garçom limite maior que o dele. */
        ACIMA_DA_AUTORIDADE
    }

    private AccessPolicy() {
    }

    /**
     * Limite de desconto efetivo, em percentual do consumo (FR-30): administrador sempre 100%; gerente o gravado ou
     * 100%; garçom o gravado ou 0% (só vale com {@link Permission#CONCEDER_DESCONTO} delegada); produção 0%.
     */
    public static BigDecimal discountLimit(MembershipRole role, BigDecimal stored) {
        return switch (role) {
            case ADMIN_ESTABELECIMENTO -> FULL_DISCOUNT;
            case GERENTE -> stored == null ? FULL_DISCOUNT : stored.setScale(4);
            case GARCOM -> stored == null ? NO_DISCOUNT : stored.setScale(4);
            case PRODUCAO -> NO_DISCOUNT;
        };
    }

    /**
     * Pode quem tem o papel {@code granterRole} e o limite {@code granterLimit} dar o limite {@code limit} ao vínculo
     * {@code targetRole}? Administrador define o de gerentes e garçons; gerente só o de garçons e até o próprio.
     */
    public static Optional<DiscountLimitRefusal> discountLimitChange(MembershipRole granterRole,
            BigDecimal granterLimit, boolean sameMembership, MembershipRole targetRole, BigDecimal limit) {
        if (!byRole(granterRole).contains(DELEGAR_PERMISSOES)) {
            return Optional.of(DiscountLimitRefusal.SEM_AUTORIDADE);
        }
        if (sameMembership) {
            return Optional.of(DiscountLimitRefusal.PROPRIO_VINCULO);
        }
        if (targetRole == MembershipRole.ADMIN_ESTABELECIMENTO || targetRole == MembershipRole.PRODUCAO) {
            return Optional.of(DiscountLimitRefusal.PAPEL_NAO_RECEBE);
        }
        if (targetRole == MembershipRole.GERENTE && granterRole != MembershipRole.ADMIN_ESTABELECIMENTO) {
            return Optional.of(DiscountLimitRefusal.SEM_AUTORIDADE);
        }
        if (limit.compareTo(granterLimit) > 0) {
            return Optional.of(DiscountLimitRefusal.ACIMA_DA_AUTORIDADE);
        }
        return Optional.empty();
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
