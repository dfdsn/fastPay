-- H21 — Problemas do consumidor e conclusão autorizada (FR-24, FR-25, CA-14).
--
--   * consumer_issue é o problema informado pelo cliente sobre um item da comanda (order_line_id) ou sobre a conta
--     (order_line_id nulo). Estados: ABERTO -> EM_ATENDIMENTO -> TRATADO, sem volta. A modalidade de atendimento da
--     comanda é copiada na criação (H20): no garçom responsável, o dono é o responsável atual da comanda; na central,
--     quem atende (attended_by) fica com o problema de forma exclusiva.
--   * Concluir exige resolução escrita e a declaração da correção (sem correção ou correção já realizada): marcar
--     tratado não altera valores. Quem não pode concluir, ou precisa de autorização que não tem, encaminha ao gerente.
--   * after_closure: o problema chegou com a comanda já encerrada (paga). Não desfaz quitação; nasce encaminhado ao
--     gerente, que decide eventual devolução.
--   * Um problema em aberto por item e um da conta, por comanda.

CREATE TABLE consumer_issue (
    id               uuid        PRIMARY KEY,
    establishment_id uuid        NOT NULL REFERENCES establishment (id),
    tab_id           uuid        NOT NULL,
    order_line_id    uuid,
    description      text        NOT NULL CHECK (char_length(description) BETWEEN 5 AND 300
                                                 AND description = btrim(description)),
    mode             text        NOT NULL CHECK (mode IN ('GARCOM_RESPONSAVEL', 'CENTRAL')),
    after_closure    boolean     NOT NULL,
    created_grant_id uuid        NOT NULL,
    created_at       timestamptz NOT NULL,
    status           text        NOT NULL DEFAULT 'ABERTO' CHECK (status IN ('ABERTO', 'EM_ATENDIMENTO', 'TRATADO')),
    attended_by      uuid        REFERENCES user_identity (id),
    attended_at      timestamptz,
    forwarded_at     timestamptz,
    forwarded_by     uuid        REFERENCES user_identity (id),
    concluded_at     timestamptz,
    concluded_by     uuid        REFERENCES user_identity (id),
    resolution       text        CHECK (char_length(resolution) BETWEEN 5 AND 300 AND resolution = btrim(resolution)),
    correction       text        CHECK (correction IN ('SEM_CORRECAO', 'CORRECAO_REALIZADA')),
    version          bigint      NOT NULL DEFAULT 0,
    UNIQUE (establishment_id, id),
    FOREIGN KEY (establishment_id, tab_id) REFERENCES tab (establishment_id, id),
    FOREIGN KEY (establishment_id, order_line_id) REFERENCES order_line (establishment_id, id),
    FOREIGN KEY (created_grant_id) REFERENCES guest_grant (id),
    CHECK ((attended_by IS NULL) = (attended_at IS NULL)),
    -- Encaminhado por alguém da equipe, ou na criação (após o encerramento), sem autor.
    CHECK (forwarded_by IS NULL OR forwarded_at IS NOT NULL),
    CHECK (status <> 'ABERTO' OR attended_by IS NULL),
    CHECK (status = 'ABERTO' OR attended_by IS NOT NULL),
    CHECK ((status = 'TRATADO') = (concluded_at IS NOT NULL)),
    CHECK ((concluded_at IS NULL) = (concluded_by IS NULL)),
    CHECK ((concluded_at IS NULL) = (resolution IS NULL)),
    CHECK ((concluded_at IS NULL) = (correction IS NULL)),
    CHECK (NOT after_closure OR forwarded_at IS NOT NULL)
);

-- Um problema em aberto por item e um da conta, por comanda.
CREATE UNIQUE INDEX consumer_issue_open_item_uq ON consumer_issue (tab_id, order_line_id)
    WHERE status <> 'TRATADO' AND order_line_id IS NOT NULL;
CREATE UNIQUE INDEX consumer_issue_open_account_uq ON consumer_issue (tab_id)
    WHERE status <> 'TRATADO' AND order_line_id IS NULL;
CREATE INDEX consumer_issue_open_idx ON consumer_issue (establishment_id, created_at) WHERE status <> 'TRATADO';
CREATE INDEX consumer_issue_tab_idx ON consumer_issue (establishment_id, tab_id, created_at);

ALTER TABLE consumer_issue ENABLE ROW LEVEL SECURITY;
ALTER TABLE consumer_issue FORCE ROW LEVEL SECURITY;

CREATE POLICY consumer_issue_runtime_read ON consumer_issue
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

-- O consumidor (sem identidade, no contexto da unidade do acesso) só cria o problema aberto.
CREATE POLICY consumer_issue_runtime_insert ON consumer_issue
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND current_identity_id() IS NULL
                AND status = 'ABERTO' AND attended_by IS NULL AND forwarded_by IS NULL AND concluded_at IS NULL);

-- Atender, reatribuir, encaminhar e concluir: só a equipe, com identidade, e só o problema ainda não tratado.
CREATE POLICY consumer_issue_runtime_update ON consumer_issue
    FOR UPDATE TO ${app_role}
    USING (establishment_id = current_establishment_id() AND status <> 'TRATADO')
    WITH CHECK (establishment_id = current_establishment_id() AND current_identity_id() IS NOT NULL);

-- Histórico: sem DELETE; comanda, item, descrição, modalidade e criação são imutáveis.
GRANT SELECT, INSERT ON consumer_issue TO ${app_role};
GRANT UPDATE (status, attended_by, attended_at, forwarded_at, forwarded_by, concluded_at, concluded_by, resolution,
              correction, version) ON consumer_issue TO ${app_role};

-- Novo tópico de aviso (H17): problema do consumidor criado ou mudado. Vai à equipe e ao consumidor da comanda.
ALTER TABLE sync_change DROP CONSTRAINT sync_change_topic_check;
ALTER TABLE sync_change ADD CONSTRAINT sync_change_topic_check
    CHECK (topic IN ('COMANDA', 'PEDIDO', 'PRODUCAO', 'ATENDIMENTO', 'PROBLEMA'));
