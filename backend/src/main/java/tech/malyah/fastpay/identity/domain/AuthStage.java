package tech.malyah.fastpay.identity.domain;

import java.util.Collection;

import tech.malyah.fastpay.identity.MembershipRole;

/**
 * Etapa da autenticação de uma sessão (H09). Só {@link #AUTENTICADO} dá acesso às funções; as demais
 * guardam a identidade à parte e só permitem concluir a verificação em duas etapas.
 */
public enum AuthStage {
    /** Senha e, quando exigido ou ativado, segundo fator confirmados. */
    AUTENTICADO,
    /** Senha certa; falta o código do autenticador ou um código de recuperação. */
    MFA_PENDENTE,
    /** Perfil privilegiado sem autenticador: precisa cadastrar antes de acessar qualquer função (FR-05). */
    MFA_CADASTRO_OBRIGATORIO,
    /** Senha e código de recuperação certos: só pode cadastrar um autenticador novo. */
    MFA_RECADASTRO;

    /**
     * MFA obrigatório para admin fastPay, admin do estabelecimento e gerente em qualquer unidade (FR-05);
     * opcional para garçom e produção.
     */
    public static boolean mfaRequired(boolean platformAdmin, Collection<MembershipRole> activeRoles) {
        return platformAdmin || activeRoles.contains(MembershipRole.ADMIN_ESTABELECIMENTO)
                || activeRoles.contains(MembershipRole.GERENTE);
    }

    /** Etapa logo depois da senha certa. */
    public static AuthStage afterPassword(boolean mfaEnrolled, boolean mfaRequired) {
        if (mfaEnrolled) {
            return MFA_PENDENTE;
        }
        return mfaRequired ? MFA_CADASTRO_OBRIGATORIO : AUTENTICADO;
    }

    /** Etapas em que a sessão pode cadastrar um autenticador novo. */
    public boolean allowsEnrollment() {
        return this == MFA_CADASTRO_OBRIGATORIO || this == MFA_RECADASTRO;
    }
}
