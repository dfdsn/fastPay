-- H28: conferência, bloqueio e reabertura (FR-31–FR-33, CA-07, CA-08, CA-11; T-03 da H28, aprovada por Diego em
-- 07/11/2026).
--
--   * tab_closing é cada vez que a comanda entra em fechamento: a equipe em nome próprio (started_by) ou o cliente pelo
--     próprio acesso (started_by_grant). Enquanto não reaberta, a comanda está "em fechamento" e não recebe pedido,
--     cancelamento, transferência, desconto, couvert nem vínculo de continuação. Reabrir grava quem, quando e o motivo
--     (5 a 300 caracteres); a linha nunca é apagada. No máximo um fechamento em andamento por comanda.
--   * bill_version é a conta consolidada do fechamento: a versão 1 ao iniciar e uma nova a cada mudança do serviço
--     na conferência (FR-28, FR-32, CA-11). A cobrança (H30) referencia a versão e o total. Imutável.

CREATE TABLE tab_closing (
    id               uuid        PRIMARY KEY,
    establishment_id uuid        NOT NULL REFERENCES establishment (id),
    tab_id           uuid        NOT NULL,
    started_at       timestamptz NOT NULL,
    started_by       uuid        REFERENCES user_identity (id),
    started_by_grant uuid,
    reopened_at      timestamptz,
    reopened_by      uuid        REFERENCES user_identity (id),
    reopen_reason    text        CHECK (char_length(reopen_reason) BETWEEN 5 AND 300
                                        AND reopen_reason = btrim(reopen_reason)),
    UNIQUE (establishment_id, id),
    FOREIGN KEY (establishment_id, tab_id) REFERENCES tab (establishment_id, id),
    FOREIGN KEY (establishment_id, started_by_grant) REFERENCES guest_grant (establishment_id, id),
    CHECK ((started_by IS NULL) <> (started_by_grant IS NULL)),
    CHECK ((reopened_at IS NULL) = (reopened_by IS NULL)),
    CHECK ((reopened_at IS NULL) = (reopen_reason IS NULL)),
    CHECK (reopened_at IS NULL OR reopened_at >= started_at)
);

-- Um fechamento em andamento por comanda.
CREATE UNIQUE INDEX tab_closing_active_uq ON tab_closing (tab_id) WHERE reopened_at IS NULL;
CREATE INDEX tab_closing_tab_idx ON tab_closing (establishment_id, tab_id, started_at);

ALTER TABLE tab_closing ENABLE ROW LEVEL SECURITY;
ALTER TABLE tab_closing FORCE ROW LEVEL SECURITY;

CREATE POLICY tab_closing_runtime_read ON tab_closing
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

-- Comanda ativa; a equipe em nome próprio ou o cliente (sem identidade) só pelo acesso ativo da comanda.
CREATE POLICY tab_closing_runtime_insert ON tab_closing
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND reopened_at IS NULL
                AND EXISTS (SELECT 1 FROM tab t WHERE t.id = tab_id AND t.closed_at IS NULL)
                AND ((started_by = current_identity_id() AND started_by_grant IS NULL)
                     OR (current_identity_id() IS NULL AND started_by IS NULL
                         AND EXISTS (SELECT 1 FROM guest_grant g WHERE g.id = started_by_grant
                                       AND g.tab_id = tab_closing.tab_id AND g.revoked_at IS NULL))));

-- Reabrir: só a equipe, em nome próprio, o fechamento em andamento.
CREATE POLICY tab_closing_runtime_update ON tab_closing
    FOR UPDATE TO ${app_role}
    USING (establishment_id = current_establishment_id() AND reopened_at IS NULL)
    WITH CHECK (establishment_id = current_establishment_id() AND reopened_by = current_identity_id());

GRANT SELECT, INSERT ON tab_closing TO ${app_role};
GRANT UPDATE (reopened_at, reopened_by, reopen_reason) ON tab_closing TO ${app_role};

CREATE TABLE bill_version (
    id                  uuid           PRIMARY KEY,
    establishment_id    uuid           NOT NULL REFERENCES establishment (id),
    tab_id              uuid           NOT NULL,
    closing_id          uuid           NOT NULL,
    number              integer        NOT NULL CHECK (number >= 1),
    settings_version_id uuid           NOT NULL,
    discount_id         uuid,
    service_waived      boolean        NOT NULL,
    consumption         numeric(19, 2) NOT NULL CHECK (consumption >= 0),
    discount            numeric(19, 2) NOT NULL CHECK (discount >= 0 AND discount <= consumption),
    couvert             numeric(19, 2) NOT NULL CHECK (couvert >= 0),
    service             numeric(19, 2) NOT NULL CHECK (service >= 0),
    total               numeric(19, 2) NOT NULL CHECK (total >= 0),
    created_at          timestamptz    NOT NULL,
    created_by          uuid           REFERENCES user_identity (id),
    guest_grant_id      uuid,
    UNIQUE (establishment_id, id),
    UNIQUE (closing_id, number),
    FOREIGN KEY (establishment_id, tab_id) REFERENCES tab (establishment_id, id),
    FOREIGN KEY (establishment_id, closing_id) REFERENCES tab_closing (establishment_id, id),
    FOREIGN KEY (establishment_id, settings_version_id) REFERENCES unit_settings_version (establishment_id, id),
    FOREIGN KEY (establishment_id, discount_id) REFERENCES tab_discount (establishment_id, id),
    FOREIGN KEY (establishment_id, guest_grant_id) REFERENCES guest_grant (establishment_id, id),
    CHECK ((created_by IS NULL) <> (guest_grant_id IS NULL)),
    CHECK (total = consumption - discount + couvert + service)
);

CREATE INDEX bill_version_tab_idx ON bill_version (establishment_id, tab_id, created_at);

ALTER TABLE bill_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE bill_version FORCE ROW LEVEL SECURITY;

CREATE POLICY bill_version_runtime_read ON bill_version
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

-- Só no fechamento em andamento da comanda ativa; a equipe em nome próprio ou o cliente pelo acesso ativo.
CREATE POLICY bill_version_runtime_insert ON bill_version
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id()
                AND EXISTS (SELECT 1 FROM tab_closing c JOIN tab t ON t.id = c.tab_id
                             WHERE c.id = closing_id AND c.tab_id = bill_version.tab_id AND c.reopened_at IS NULL
                               AND t.closed_at IS NULL)
                AND ((created_by = current_identity_id() AND guest_grant_id IS NULL)
                     OR (current_identity_id() IS NULL AND created_by IS NULL
                         AND EXISTS (SELECT 1 FROM guest_grant g WHERE g.id = guest_grant_id
                                       AND g.tab_id = bill_version.tab_id AND g.revoked_at IS NULL))));

-- Versões não mudam nem somem: a mais alta do fechamento vale.
GRANT SELECT, INSERT ON bill_version TO ${app_role};
