package tech.malyah.fastpay.identity;

/**
 * Ações autorizadas no servidor, por vínculo na unidade (H11, PRD §4.2, especificação §5). Cada comando de loja
 * exige uma destas permissões em {@link Memberships#inUnit(java.util.UUID, java.util.UUID, Permission,
 * java.util.function.Function)}; o nome da rota ou o menu da tela não autorizam nada. Quem tem cada uma por
 * papel e o que pode ser delegado a um garçom ficam em {@code identity.domain.AccessPolicy}.
 */
public enum Permission {
    OPERAR_COMANDA("Abrir comanda, enviar pedido e entregar", false),
    OPERAR_PRODUCAO("Fila de produção do setor", false),
    ATENDER_PROBLEMA("Atender problema do consumidor", false),
    CONCLUIR_PROBLEMA("Concluir problema como tratado", true),
    CANCELAR_ITEM("Cancelar item", true),
    CONCEDER_DESCONTO("Conceder desconto", true),
    REABRIR_COMANDA("Reabrir comanda", true),
    TRANSFERIR_UNIDADES("Transferir unidades entre comandas", true),
    DESFAZER_ENTREGA("Desfazer entrega", true),
    ALTERAR_DISPONIBILIDADE("Alterar disponibilidade de produtos", true),
    REGISTRAR_RECEBIMENTO_EXTERNO("Registrar recebimento externo", true),
    RECUPERAR_ACESSO_CONSUMIDOR("Recuperar acesso do consumidor", true),
    VINCULAR_CONTINUACAO_VISITA("Vincular continuação de visita", true),
    AUTORIZAR_DEVOLUCAO("Autorizar devolução", false),
    ENCERRAR_COM_DEBITO("Encerrar comanda com débito", false),
    INICIAR_REGULARIZACAO("Iniciar regularização de débito", false),
    INVALIDAR_RECEBIMENTO_EXTERNO("Invalidar recebimento externo", false),
    VER_EQUIPE("Ver a equipe", false),
    DELEGAR_PERMISSOES("Delegar permissões a garçons", false),
    GERIR_EQUIPE("Convidar, trocar papel e desativar pessoas da equipe", false),
    CONECTAR_CONTA_PSP("Conectar ou substituir a conta de pagamentos", false),
    AUTORIZAR_SUPORTE("Autorizar acesso temporário do suporte", false);

    private final String label;
    private final boolean delegable;

    Permission(String label, boolean delegable) {
        this.label = label;
        this.delegable = delegable;
    }

    /** Nome da ação em português, para telas e auditoria legível. */
    public String label() {
        return label;
    }

    /**
     * Permissão específica que gerente ou administrador pode conceder a um garçom. Devolução, débito,
     * regularização, invalidação de recebimento externo e as ações do administrador nunca são delegáveis.
     */
    public boolean delegable() {
        return delegable;
    }
}
