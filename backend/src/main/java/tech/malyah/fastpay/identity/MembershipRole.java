package tech.malyah.fastpay.identity;

/** Papel de uma identidade numa unidade (PRD §4.1). Permissões por ação chegam em H11. */
public enum MembershipRole {
    ADMIN_ESTABELECIMENTO("administrador do estabelecimento"),
    GERENTE("gerente"),
    GARCOM("garçom"),
    PRODUCAO("produção");

    private final String label;

    MembershipRole(String label) {
        this.label = label;
    }

    /** Nome do papel em português, para e-mails e telas. */
    public String label() {
        return label;
    }
}
