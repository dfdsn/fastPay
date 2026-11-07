-- H29: encerramento de conta zerada e de comanda abandonada vazia (FR-41, FR-42, FR-11, CA-20, D-02; T-03 da H29).
--
--   * tab_closure é o encerramento da comanda: uma linha por comanda, nunca alterada nem apagada. Sempre fecha um
--     fechamento em andamento (tab_closing, H28) e aponta a versão consolidada da conta, que precisa ser zero: nada
--     aqui é pagamento, cobrança ou comissão (FR-41, CA-20).
--       SEM_VALOR: conta zerada elegível, pelo cliente (pelo próprio acesso) ou pela equipe em nome próprio.
--       ABANDONO:  comanda sem consumo nem pendência, pela equipe em nome próprio e com motivo (FR-42). Comanda
--                  abandonada com valor é encerramento com débito, só do gerente (H34).
--   * tab.closed_at passa a ser gravado só junto com o tab_closure (policy restritiva); tab.access_until é o prazo do
--     acesso avulso (FR-11): 7 dias depois do encerramento, suspenso por problema aberto depois e reiniciado pela
--     última resolução. Comanda encerrada só muda o prazo do acesso (gatilho).

CREATE TABLE tab_closure (
    id               uuid        PRIMARY KEY,
    establishment_id uuid        NOT NULL REFERENCES establishment (id),
    tab_id           uuid        NOT NULL,
    closing_id       uuid        NOT NULL,
    bill_version_id  uuid        NOT NULL,
    kind             text        NOT NULL CHECK (kind IN ('SEM_VALOR', 'ABANDONO')),
    closed_at        timestamptz NOT NULL,
    closed_by        uuid        REFERENCES user_identity (id),
    closed_by_grant  uuid,
    reason           text        CHECK (char_length(reason) BETWEEN 5 AND 300 AND reason = btrim(reason)),
    UNIQUE (establishment_id, id),
    UNIQUE (tab_id),
    UNIQUE (closing_id),
    FOREIGN KEY (establishment_id, tab_id) REFERENCES tab (establishment_id, id),
    FOREIGN KEY (establishment_id, closing_id) REFERENCES tab_closing (establishment_id, id),
    FOREIGN KEY (establishment_id, bill_version_id) REFERENCES bill_version (establishment_id, id),
    FOREIGN KEY (establishment_id, closed_by_grant) REFERENCES guest_grant (establishment_id, id),
    CHECK ((closed_by IS NULL) <> (closed_by_grant IS NULL)),
    CHECK ((kind = 'ABANDONO') = (reason IS NOT NULL)),
    CHECK (kind <> 'ABANDONO' OR closed_by IS NOT NULL)
);

ALTER TABLE tab_closure ENABLE ROW LEVEL SECURITY;
ALTER TABLE tab_closure FORCE ROW LEVEL SECURITY;

CREATE POLICY tab_closure_runtime_read ON tab_closure
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

-- Comanda ativa, o fechamento em andamento dela e a versão mais alta desse fechamento, com total zero; a equipe em
-- nome próprio ou o cliente (sem identidade) só pelo acesso ativo da comanda.
CREATE POLICY tab_closure_runtime_insert ON tab_closure
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id()
                AND EXISTS (SELECT 1 FROM tab t WHERE t.id = tab_id AND t.closed_at IS NULL)
                AND EXISTS (SELECT 1 FROM tab_closing c WHERE c.id = closing_id AND c.tab_id = tab_closure.tab_id
                              AND c.reopened_at IS NULL)
                AND EXISTS (SELECT 1 FROM bill_version b WHERE b.id = bill_version_id AND b.closing_id = tab_closure.closing_id
                              AND b.total = 0
                              AND b.number = (SELECT max(m.number) FROM bill_version m
                                               WHERE m.closing_id = tab_closure.closing_id))
                AND ((closed_by = current_identity_id() AND closed_by_grant IS NULL)
                     OR (current_identity_id() IS NULL AND closed_by IS NULL
                         AND EXISTS (SELECT 1 FROM guest_grant g WHERE g.id = closed_by_grant
                                       AND g.tab_id = tab_closure.tab_id AND g.revoked_at IS NULL))));

GRANT SELECT, INSERT ON tab_closure TO ${app_role};

-- Encerrar: a comanda ativa recebe closed_at igual ao do tab_closure dela (equipe ou cliente; quem fez está no
-- tab_closure). A policy restritiva vale para todo UPDATE: closed_at só existe com o encerramento registrado.
CREATE POLICY tab_runtime_close ON tab
    FOR UPDATE TO ${app_role}
    USING (establishment_id = current_establishment_id() AND closed_at IS NULL)
    WITH CHECK (establishment_id = current_establishment_id() AND closed_at IS NOT NULL);

-- Prazo do acesso avulso depois do encerramento (FR-11): problema aberto suspende, a última resolução reinicia.
CREATE POLICY tab_runtime_access_after_closure ON tab
    FOR UPDATE TO ${app_role}
    USING (establishment_id = current_establishment_id() AND closed_at IS NOT NULL)
    WITH CHECK (establishment_id = current_establishment_id() AND closed_at IS NOT NULL);

CREATE POLICY tab_runtime_closed_by_closure ON tab
    AS RESTRICTIVE
    FOR UPDATE TO ${app_role}
    USING (true)
    WITH CHECK (closed_at IS NULL
                OR EXISTS (SELECT 1 FROM tab_closure c WHERE c.tab_id = tab.id AND c.closed_at = tab.closed_at));

GRANT UPDATE (closed_at, access_until) ON tab TO ${app_role};

-- Comanda encerrada é histórico: só o prazo do acesso avulso muda.
CREATE FUNCTION tab_closed_only_access() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = fastpay, pg_temp
AS $$
DECLARE
    proposta tab;
BEGIN
    proposta := NEW;
    proposta.access_until := OLD.access_until;
    IF proposta IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'comanda encerrada: só o prazo do acesso avulso muda' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

REVOKE ALL ON FUNCTION tab_closed_only_access() FROM PUBLIC;

CREATE TRIGGER tab_closed_only_access
    BEFORE UPDATE ON tab
    FOR EACH ROW WHEN (OLD.closed_at IS NOT NULL)
    EXECUTE FUNCTION tab_closed_only_access();
