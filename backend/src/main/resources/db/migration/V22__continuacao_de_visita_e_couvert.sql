-- H26: continuação de visita e ajustes de couvert (FR-12, FR-29, FR-68, CA-13, D-08, D-12; T-03 da H26).
--
--   * visit_link é o fato do vínculo: a comanda nova passa a fazer parte da visita da comanda anterior do mesmo
--     consumidor, escolhida por quem tem a permissão de vincular e com a permanência confirmada (nunca pelo nome).
--     Cada comanda é vinculada no máximo uma vez; o vínculo não é desfeito nem apagado. tab.visit_id passa a apontar
--     para a visita da anterior, no mesmo commit.
--   * visit.exit_validated_at é gravado pela saída validada (H32): a visita termina e a próxima entrada é visita nova.
--     A virada do dia e a saída automática não gravam nada aqui.
--   * couvert_charge é cada couvert lançado na comanda: o automático na abertura (valor da versão capturada), o manual
--     na inclusão (valor da configuração vigente) e a inclusão de correção (valor digitado, com motivo). No máximo um
--     válido por comanda; a aplicação, com a visita travada, garante um válido por visita. Encerrar nunca apaga:
--       - CANCELADO pela equipe, com o motivo (INDEVIDO, ISENCAO, DUPLICIDADE, CORRECAO) e o texto;
--       - DEVOLVIDO pela devolução (H35, contrato testado na H26), com o motivo;
--       - NAO_REPETIDO no vínculo, quando a visita já tinha couvert cobrado, dispensado ou aguardando correção.
--     Situação da visita, lida das linhas: há couvert válido → cobrado; senão algum encerrado por indevido/isenção →
--     dispensado; senão algum por correção → aguardando inclusão explícita; senão sem couvert.
--   * consumer_issue.about_couvert marca a contestação do couvert pelo cliente (FR-29), pelo fluxo de problemas (H21).

ALTER TABLE visit ADD COLUMN exit_validated_at timestamptz;
ALTER TABLE visit ADD COLUMN exit_tab_id uuid;
ALTER TABLE visit ADD CONSTRAINT visit_exit_tab_fk FOREIGN KEY (establishment_id, exit_tab_id)
    REFERENCES tab (establishment_id, id);
ALTER TABLE visit ADD CONSTRAINT visit_exit_check CHECK ((exit_validated_at IS NULL) = (exit_tab_id IS NULL));

-- Travar a visita (vínculo e couvert) e gravar a saída validada: só na unidade, uma vez.
CREATE POLICY visit_runtime_update ON visit
    FOR UPDATE TO ${app_role}
    USING (establishment_id = current_establishment_id())
    WITH CHECK (establishment_id = current_establishment_id() AND current_identity_id() IS NOT NULL);

GRANT UPDATE (exit_validated_at, exit_tab_id) ON visit TO ${app_role};

-- O vínculo troca a visita da comanda ativa (policy de UPDATE da tab já exige ativa e quem altera).
GRANT UPDATE (visit_id) ON tab TO ${app_role};

CREATE TABLE visit_link (
    id               uuid        PRIMARY KEY,
    establishment_id uuid        NOT NULL REFERENCES establishment (id),
    tab_id           uuid        NOT NULL,
    previous_tab_id  uuid        NOT NULL,
    from_visit_id    uuid        NOT NULL,
    to_visit_id      uuid        NOT NULL,
    stay_confirmed   boolean     NOT NULL CHECK (stay_confirmed),
    linked_by        uuid        NOT NULL REFERENCES user_identity (id),
    linked_at        timestamptz NOT NULL,
    UNIQUE (establishment_id, id),
    UNIQUE (tab_id),
    FOREIGN KEY (establishment_id, tab_id) REFERENCES tab (establishment_id, id),
    FOREIGN KEY (establishment_id, previous_tab_id) REFERENCES tab (establishment_id, id),
    FOREIGN KEY (establishment_id, from_visit_id) REFERENCES visit (establishment_id, id),
    FOREIGN KEY (establishment_id, to_visit_id) REFERENCES visit (establishment_id, id),
    CHECK (tab_id <> previous_tab_id),
    CHECK (from_visit_id <> to_visit_id)
);

CREATE INDEX visit_link_previous_idx ON visit_link (establishment_id, previous_tab_id);

ALTER TABLE visit_link ENABLE ROW LEVEL SECURITY;
ALTER TABLE visit_link FORCE ROW LEVEL SECURITY;

CREATE POLICY visit_link_runtime_read ON visit_link
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

-- Só a equipe, em nome próprio, para a comanda ativa.
CREATE POLICY visit_link_runtime_insert ON visit_link
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND linked_by = current_identity_id()
                AND EXISTS (SELECT 1 FROM tab t WHERE t.id = tab_id AND t.closed_at IS NULL));

GRANT SELECT, INSERT ON visit_link TO ${app_role};

CREATE TABLE couvert_charge (
    id                  uuid          PRIMARY KEY,
    establishment_id    uuid          NOT NULL REFERENCES establishment (id),
    tab_id              uuid          NOT NULL,
    origin              text          NOT NULL CHECK (origin IN ('AUTOMATICO', 'MANUAL', 'CORRECAO')),
    amount              numeric(5, 2) NOT NULL CHECK (amount BETWEEN 0.01 AND 200.00),
    -- Versão da configuração de onde veio o valor (automático: a capturada na abertura; manual: a vigente na
    -- inclusão); nula na correção, cujo valor foi digitado.
    settings_version_id uuid,
    reason              text          CHECK (char_length(reason) BETWEEN 5 AND 300 AND reason = btrim(reason)),
    created_by          uuid          NOT NULL REFERENCES user_identity (id),
    created_at          timestamptz   NOT NULL,
    ended_at            timestamptz,
    ended_by            uuid          REFERENCES user_identity (id),
    end_kind            text          CHECK (end_kind IN ('CANCELADO', 'DEVOLVIDO', 'NAO_REPETIDO')),
    end_reason_kind     text          CHECK (end_reason_kind IN ('INDEVIDO', 'ISENCAO', 'DUPLICIDADE', 'CORRECAO')),
    end_reason          text          CHECK (char_length(end_reason) BETWEEN 5 AND 300 AND end_reason = btrim(end_reason)),
    UNIQUE (establishment_id, id),
    FOREIGN KEY (establishment_id, tab_id) REFERENCES tab (establishment_id, id),
    FOREIGN KEY (establishment_id, settings_version_id) REFERENCES unit_settings_version (establishment_id, id),
    CHECK ((origin = 'CORRECAO') = (settings_version_id IS NULL)),
    CHECK ((origin = 'CORRECAO') = (reason IS NOT NULL)),
    CHECK ((ended_at IS NULL) = (ended_by IS NULL)),
    CHECK ((ended_at IS NULL) = (end_kind IS NULL)),
    CHECK ((end_kind IN ('CANCELADO', 'DEVOLVIDO')) = (end_reason_kind IS NOT NULL)),
    CHECK ((end_kind = 'CANCELADO') = (end_reason IS NOT NULL))
);

-- No máximo um couvert válido por comanda (FR-29).
CREATE UNIQUE INDEX couvert_charge_valid_uq ON couvert_charge (tab_id) WHERE ended_at IS NULL;
CREATE INDEX couvert_charge_tab_idx ON couvert_charge (establishment_id, tab_id, created_at);

ALTER TABLE couvert_charge ENABLE ROW LEVEL SECURITY;
ALTER TABLE couvert_charge FORCE ROW LEVEL SECURITY;

CREATE POLICY couvert_charge_runtime_read ON couvert_charge
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

-- Só a equipe lança, em nome próprio, na comanda ativa.
CREATE POLICY couvert_charge_runtime_insert ON couvert_charge
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND created_by = current_identity_id()
                AND ended_at IS NULL
                AND EXISTS (SELECT 1 FROM tab t WHERE t.id = tab_id AND t.closed_at IS NULL));

-- Encerrar (cancelar, devolver, não repetir): só a equipe, em nome próprio, só o couvert válido.
CREATE POLICY couvert_charge_runtime_update ON couvert_charge
    FOR UPDATE TO ${app_role}
    USING (establishment_id = current_establishment_id() AND ended_at IS NULL)
    WITH CHECK (establishment_id = current_establishment_id() AND ended_by = current_identity_id());

-- Histórico: sem DELETE; origem, valor e autoria do lançamento são imutáveis.
GRANT SELECT, INSERT ON couvert_charge TO ${app_role};
GRANT UPDATE (ended_at, ended_by, end_kind, end_reason_kind, end_reason) ON couvert_charge TO ${app_role};

-- O couvert automático das comandas já abertas (até a H25 calculado da versão capturada) vira lançamento, com o
-- mesmo valor e a autoria de quem abriu. O dono do schema não tem policy nessas tabelas: o FORCE é desligado só
-- durante esta cópia, na transação da migration (como na V11).
ALTER TABLE tab NO FORCE ROW LEVEL SECURITY;
ALTER TABLE unit_settings_version NO FORCE ROW LEVEL SECURITY;
ALTER TABLE couvert_charge NO FORCE ROW LEVEL SECURITY;
INSERT INTO couvert_charge (id, establishment_id, tab_id, origin, amount, settings_version_id, created_by, created_at)
SELECT gen_random_uuid(), t.establishment_id, t.id, 'AUTOMATICO', v.couvert_amount, v.id, t.opened_by, t.opened_at
  FROM tab t JOIN unit_settings_version v ON v.id = t.settings_version_id
 WHERE v.couvert_enabled AND v.couvert_mode = 'AUTOMATICO';
ALTER TABLE tab FORCE ROW LEVEL SECURITY;
ALTER TABLE unit_settings_version FORCE ROW LEVEL SECURITY;
ALTER TABLE couvert_charge FORCE ROW LEVEL SECURITY;

-- Contestação do couvert pelo cliente (FR-29): problema sem item, marcado.
ALTER TABLE consumer_issue ADD COLUMN about_couvert boolean NOT NULL DEFAULT false;
ALTER TABLE consumer_issue ADD CONSTRAINT consumer_issue_about_couvert_check
    CHECK (NOT (about_couvert AND order_line_id IS NOT NULL));
