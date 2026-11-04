-- H22: composição da conta, serviço e desconto (FR-28–FR-30, FR-68, D-09, D-10, especificação §7).
--   * A conta não tem tabela de totais: C, D, K, S e o total são calculados a cada leitura, com BigDecimal e HALF_UP
--     em 2 casas, a partir das linhas enviadas alocadas na comanda (preço do envio, H16), da versão da configuração
--     capturada na abertura (H14/H15) e das escolhas abaixo. O snapshot da conta cobrada é das histórias de
--     fechamento e pagamento (H28/H30), que leem a composição na transação delas.
--   * tab_discount guarda cada desconto concedido, um ativo por comanda (FR-30: "alteração substitui anterior").
--     Fixo em reais (amount) ou percentual (percent, 4 casas) sobre o consumo, nunca sobre o couvert. Guarda quem
--     concedeu, o limite dessa pessoa na hora (FR-30, P-17) e o consumo e o desconto daquele momento. Desconto fixo
--     efetivo = min(valor, consumo atual); se uma redução de consumo leva o percentual efetivo acima da autoridade de
--     quem concedeu, a conta fica em revisão e a cobrança é bloqueada até alguém com autoridade revisar (FR-68).
--     A revisão grava quem revisou e o limite dele, que passa a ser a autoridade do desconto. Substituir ou retirar
--     encerra a linha (ended_*), nunca a apaga.
--   * tab_service_choice guarda cada escolha de retirar ou devolver o serviço (FR-28): o cliente pelo próprio
--     acesso (guest_grant_id, sem identidade) ou a equipe (chosen_by). A última escolha (seq) vale.
--   * Comanda encerrada não recebe desconto nem escolha (policy de INSERT); "em fechamento" chega com a H28.

CREATE TABLE tab_discount (
    id                     uuid           PRIMARY KEY,
    establishment_id       uuid           NOT NULL REFERENCES establishment (id),
    tab_id                 uuid           NOT NULL,
    kind                   text           NOT NULL CHECK (kind IN ('FIXO', 'PERCENTUAL')),
    amount                 numeric(9, 2)  CHECK (amount > 0),
    percent                numeric(7, 4)  CHECK (percent > 0 AND percent <= 100),
    reason                 text           NOT NULL CHECK (char_length(reason) BETWEEN 5 AND 300
                                                          AND reason = btrim(reason)),
    granted_by             uuid           NOT NULL REFERENCES user_identity (id),
    granted_at             timestamptz    NOT NULL,
    granter_limit_percent  numeric(7, 4)  NOT NULL CHECK (granter_limit_percent BETWEEN 0 AND 100),
    consumption_at_grant   numeric(19, 2) NOT NULL CHECK (consumption_at_grant > 0),
    discount_at_grant      numeric(19, 2) NOT NULL CHECK (discount_at_grant > 0
                                                          AND discount_at_grant <= consumption_at_grant),
    reviewed_by            uuid           REFERENCES user_identity (id),
    reviewed_at            timestamptz,
    reviewed_limit_percent numeric(7, 4)  CHECK (reviewed_limit_percent BETWEEN 0 AND 100),
    ended_at               timestamptz,
    ended_by               uuid           REFERENCES user_identity (id),
    end_kind               text           CHECK (end_kind IN ('SUBSTITUIDO', 'RETIRADO')),
    end_reason             text           CHECK (char_length(end_reason) BETWEEN 5 AND 300
                                                 AND end_reason = btrim(end_reason)),
    UNIQUE (establishment_id, id),
    FOREIGN KEY (establishment_id, tab_id) REFERENCES tab (establishment_id, id),
    CHECK ((kind = 'FIXO') = (amount IS NOT NULL)),
    CHECK ((kind = 'PERCENTUAL') = (percent IS NOT NULL)),
    CHECK ((reviewed_by IS NULL) = (reviewed_at IS NULL)),
    CHECK ((reviewed_by IS NULL) = (reviewed_limit_percent IS NULL)),
    CHECK ((ended_at IS NULL) = (ended_by IS NULL)),
    CHECK ((ended_at IS NULL) = (end_kind IS NULL)),
    CHECK ((end_kind = 'RETIRADO') = (end_reason IS NOT NULL))
);

-- Um desconto ativo por comanda (FR-30).
CREATE UNIQUE INDEX tab_discount_active_uq ON tab_discount (tab_id) WHERE ended_at IS NULL;
CREATE INDEX tab_discount_tab_idx ON tab_discount (establishment_id, tab_id, granted_at);

ALTER TABLE tab_discount ENABLE ROW LEVEL SECURITY;
ALTER TABLE tab_discount FORCE ROW LEVEL SECURITY;

CREATE POLICY tab_discount_runtime_read ON tab_discount
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

-- Só a equipe concede, em nome próprio, na comanda ativa.
CREATE POLICY tab_discount_runtime_insert ON tab_discount
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND granted_by = current_identity_id()
                AND reviewed_by IS NULL AND ended_at IS NULL
                AND EXISTS (SELECT 1 FROM tab t WHERE t.id = tab_id AND t.closed_at IS NULL));

-- Revisar, substituir e retirar: só a equipe, só o desconto ativo.
CREATE POLICY tab_discount_runtime_update ON tab_discount
    FOR UPDATE TO ${app_role}
    USING (establishment_id = current_establishment_id() AND ended_at IS NULL)
    WITH CHECK (establishment_id = current_establishment_id() AND current_identity_id() IS NOT NULL);

-- Histórico: sem DELETE; formato, valor, motivo e autoria da concessão são imutáveis.
GRANT SELECT, INSERT ON tab_discount TO ${app_role};
GRANT UPDATE (reviewed_by, reviewed_at, reviewed_limit_percent, ended_at, ended_by, end_kind, end_reason)
    ON tab_discount TO ${app_role};

CREATE TABLE tab_service_choice (
    id               uuid        PRIMARY KEY,
    seq              bigint      GENERATED ALWAYS AS IDENTITY UNIQUE,
    establishment_id uuid        NOT NULL REFERENCES establishment (id),
    tab_id           uuid        NOT NULL,
    waived           boolean     NOT NULL,
    chosen_by        uuid        REFERENCES user_identity (id),
    guest_grant_id   uuid,
    chosen_at        timestamptz NOT NULL,
    UNIQUE (establishment_id, id),
    FOREIGN KEY (establishment_id, tab_id) REFERENCES tab (establishment_id, id),
    FOREIGN KEY (establishment_id, guest_grant_id) REFERENCES guest_grant (establishment_id, id),
    CHECK ((chosen_by IS NULL) <> (guest_grant_id IS NULL))
);

CREATE INDEX tab_service_choice_tab_idx ON tab_service_choice (establishment_id, tab_id, seq);

ALTER TABLE tab_service_choice ENABLE ROW LEVEL SECURITY;
ALTER TABLE tab_service_choice FORCE ROW LEVEL SECURITY;

CREATE POLICY tab_service_choice_runtime_read ON tab_service_choice
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

-- A equipe escolhe em nome próprio; o cliente (sem identidade, no contexto do acesso) só pelo acesso dele.
CREATE POLICY tab_service_choice_runtime_insert ON tab_service_choice
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id()
                AND EXISTS (SELECT 1 FROM tab t WHERE t.id = tab_id AND t.closed_at IS NULL)
                AND ((chosen_by = current_identity_id() AND guest_grant_id IS NULL)
                     OR (current_identity_id() IS NULL AND chosen_by IS NULL
                         AND EXISTS (SELECT 1 FROM guest_grant g WHERE g.id = guest_grant_id
                                       AND g.tab_id = tab_service_choice.tab_id AND g.revoked_at IS NULL))));

-- Escolhas não mudam nem somem: a última vale.
GRANT SELECT, INSERT ON tab_service_choice TO ${app_role};

-- Aviso de mudança da conta (desconto e serviço), para a equipe e para o consumidor da comanda.
ALTER TABLE sync_change DROP CONSTRAINT sync_change_topic_check;
ALTER TABLE sync_change ADD CONSTRAINT sync_change_topic_check
    CHECK (topic IN ('COMANDA', 'PEDIDO', 'PRODUCAO', 'ATENDIMENTO', 'PROBLEMA', 'CONTA'));
