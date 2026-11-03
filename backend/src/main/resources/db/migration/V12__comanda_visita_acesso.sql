-- H15: comanda individual, visita, QR de vínculo e acesso avulso do consumidor (FR-07–FR-12, CA-01, CA-02, CA-23,
-- especificação §6; P-07 recuperação aprovada por Diego em 03/11/2026).
--   * visit agrupa as comandas de uma mesma permanência do consumidor na unidade. Na H15 cada comanda nova abre a
--     sua visita; vincular continuação de visita é da H26.
--   * tab é a comanda de uma pessoa: número curto automático, distinto entre as comandas ativas da unidade (só
--     exibição, nunca autorização), nome/apelido (repetido é permitido) e mesa opcional. Guarda a versão da
--     configuração da unidade vigente na abertura (H14). closed_at nulo = ativa; encerramento é da H28/H29.
--     access_until é o fim do acesso avulso depois da resolução final (FR-11): nulo enquanto a comanda está em
--     atendimento ou com pendência (débito, pagamento incerto, devolução, problema), que suspendem a expiração.
--   * binding_token é o QR de vínculo: só o hash SHA-256 do token aleatório, 2 minutos, uso único; gerar outro
--     invalida o anterior (no máximo um em aberto por comanda). Ver o link não consome; confirmar consome.
--   * guest_grant é o acesso do consumidor avulso, guardado no navegador dele (só o hash aqui). Um ativo por
--     comanda; a recuperação revoga o anterior.
--   * access_recovery registra a recuperação (P-07): quem conferiu, o motivo e as conferências feitas
--     presencialmente (nome/apelido e um item da conta, ou a hora da abertura se ainda não houve pedido).
--
-- Contextos de RLS:
--   * app.establishment_id (equipe e consumidor já identificado pelo acesso): tudo da unidade.
--   * app.binding_token_hash: o consumidor que abriu o link só vê o QR apresentado (para achar a unidade).
--   * app.guest_grant_hash: o navegador do consumidor só vê o próprio acesso (para achar a unidade).

CREATE FUNCTION current_binding_token_hash() RETURNS text
    LANGUAGE sql
    STABLE
    PARALLEL SAFE
AS $$
    SELECT NULLIF(current_setting('app.binding_token_hash', true), '')
$$;

CREATE FUNCTION current_guest_grant_hash() RETURNS text
    LANGUAGE sql
    STABLE
    PARALLEL SAFE
AS $$
    SELECT NULLIF(current_setting('app.guest_grant_hash', true), '')
$$;

REVOKE ALL ON FUNCTION current_binding_token_hash() FROM PUBLIC;
REVOKE ALL ON FUNCTION current_guest_grant_hash() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION current_binding_token_hash() TO ${app_role};
GRANT EXECUTE ON FUNCTION current_guest_grant_hash() TO ${app_role};

CREATE TABLE visit (
    id               uuid        PRIMARY KEY,
    establishment_id uuid        NOT NULL REFERENCES establishment (id),
    started_by       uuid        NOT NULL REFERENCES user_identity (id),
    started_at       timestamptz NOT NULL,
    UNIQUE (establishment_id, id)
);

ALTER TABLE visit ENABLE ROW LEVEL SECURITY;
ALTER TABLE visit FORCE ROW LEVEL SECURITY;

CREATE POLICY visit_runtime_read ON visit
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

CREATE POLICY visit_runtime_insert ON visit
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND started_by = current_identity_id());

GRANT SELECT, INSERT ON visit TO ${app_role};

CREATE TABLE tab (
    id                  uuid        PRIMARY KEY,
    establishment_id    uuid        NOT NULL REFERENCES establishment (id),
    visit_id            uuid        NOT NULL,
    number              integer     NOT NULL CHECK (number BETWEEN 1 AND 9999),
    display_name        text        NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 60
                                                    AND display_name = btrim(display_name)),
    table_label         text        CHECK (char_length(table_label) BETWEEN 1 AND 20 AND table_label = btrim(table_label)),
    settings_version_id uuid        NOT NULL,
    opened_by           uuid        NOT NULL REFERENCES user_identity (id),
    opened_at           timestamptz NOT NULL,
    updated_by          uuid        NOT NULL REFERENCES user_identity (id),
    updated_at          timestamptz NOT NULL,
    closed_at           timestamptz,
    access_until        timestamptz,
    version             bigint      NOT NULL DEFAULT 0,
    UNIQUE (establishment_id, id),
    FOREIGN KEY (establishment_id, visit_id) REFERENCES visit (establishment_id, id),
    FOREIGN KEY (establishment_id, settings_version_id) REFERENCES unit_settings_version (establishment_id, id),
    CHECK (access_until IS NULL OR closed_at IS NOT NULL)
);

CREATE UNIQUE INDEX tab_active_number_uq ON tab (establishment_id, number) WHERE closed_at IS NULL;
CREATE INDEX tab_active_opened_idx ON tab (establishment_id, opened_at) WHERE closed_at IS NULL;

ALTER TABLE tab ENABLE ROW LEVEL SECURITY;
ALTER TABLE tab FORCE ROW LEVEL SECURITY;

CREATE POLICY tab_runtime_read ON tab
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

CREATE POLICY tab_runtime_insert ON tab
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND opened_by = current_identity_id()
                AND updated_by = current_identity_id() AND closed_at IS NULL AND access_until IS NULL);

CREATE POLICY tab_runtime_update ON tab
    FOR UPDATE TO ${app_role}
    USING (establishment_id = current_establishment_id() AND closed_at IS NULL)
    WITH CHECK (establishment_id = current_establishment_id() AND updated_by = current_identity_id());

-- Encerramento e prazo do acesso avulso ganham permissão nas histórias de fechamento (H28/H29).
GRANT SELECT, INSERT ON tab TO ${app_role};
GRANT UPDATE (table_label, updated_by, updated_at, version) ON tab TO ${app_role};

CREATE TABLE binding_token (
    id               uuid        PRIMARY KEY,
    establishment_id uuid        NOT NULL REFERENCES establishment (id),
    tab_id           uuid        NOT NULL,
    token_hash       text        NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    -- QR gerado por uma recuperação de acesso (P-07); o acesso anterior já foi revogado.
    recovery         boolean     NOT NULL,
    created_by       uuid        NOT NULL REFERENCES user_identity (id),
    created_at       timestamptz NOT NULL,
    expires_at       timestamptz NOT NULL,
    consumed_at      timestamptz,
    invalidated_at   timestamptz,
    UNIQUE (establishment_id, id),
    FOREIGN KEY (establishment_id, tab_id) REFERENCES tab (establishment_id, id),
    CHECK (expires_at > created_at),
    CHECK (consumed_at IS NULL OR invalidated_at IS NULL)
);

-- No máximo um QR em aberto (não consumido nem invalidado) por comanda; vencer não libera a vaga, invalidar sim.
CREATE UNIQUE INDEX binding_token_open_uq ON binding_token (tab_id) WHERE consumed_at IS NULL AND invalidated_at IS NULL;

ALTER TABLE binding_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE binding_token FORCE ROW LEVEL SECURITY;

CREATE POLICY binding_token_runtime_read ON binding_token
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

CREATE POLICY binding_token_runtime_presented ON binding_token
    FOR SELECT TO ${app_role}
    USING (token_hash = current_binding_token_hash());

CREATE POLICY binding_token_runtime_insert ON binding_token
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND created_by = current_identity_id()
                AND consumed_at IS NULL AND invalidated_at IS NULL);

CREATE POLICY binding_token_runtime_update ON binding_token
    FOR UPDATE TO ${app_role}
    USING (establishment_id = current_establishment_id() AND consumed_at IS NULL AND invalidated_at IS NULL)
    WITH CHECK (establishment_id = current_establishment_id());

GRANT SELECT, INSERT ON binding_token TO ${app_role};
GRANT UPDATE (consumed_at, invalidated_at) ON binding_token TO ${app_role};

CREATE TABLE guest_grant (
    id               uuid        PRIMARY KEY,
    establishment_id uuid        NOT NULL REFERENCES establishment (id),
    tab_id           uuid        NOT NULL,
    binding_token_id uuid        NOT NULL,
    token_hash       text        NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    created_at       timestamptz NOT NULL,
    revoked_at       timestamptz,
    revoked_by       uuid        REFERENCES user_identity (id),
    revoke_reason    text        CHECK (revoke_reason IN ('RECUPERACAO')),
    UNIQUE (establishment_id, id),
    UNIQUE (binding_token_id),
    FOREIGN KEY (establishment_id, tab_id) REFERENCES tab (establishment_id, id),
    FOREIGN KEY (establishment_id, binding_token_id) REFERENCES binding_token (establishment_id, id),
    CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL))
);

-- Um único acesso ativo de consumidor por comanda (FR-09).
CREATE UNIQUE INDEX guest_grant_active_uq ON guest_grant (tab_id) WHERE revoked_at IS NULL;

ALTER TABLE guest_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE guest_grant FORCE ROW LEVEL SECURITY;

CREATE POLICY guest_grant_runtime_read ON guest_grant
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

CREATE POLICY guest_grant_runtime_presented ON guest_grant
    FOR SELECT TO ${app_role}
    USING (token_hash = current_guest_grant_hash());

CREATE POLICY guest_grant_runtime_insert ON guest_grant
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND revoked_at IS NULL);

CREATE POLICY guest_grant_runtime_revoke ON guest_grant
    FOR UPDATE TO ${app_role}
    USING (establishment_id = current_establishment_id() AND revoked_at IS NULL)
    WITH CHECK (establishment_id = current_establishment_id() AND revoked_by = current_identity_id());

GRANT SELECT, INSERT ON guest_grant TO ${app_role};
GRANT UPDATE (revoked_at, revoked_by, revoke_reason) ON guest_grant TO ${app_role};

CREATE TABLE access_recovery (
    id               uuid        PRIMARY KEY,
    establishment_id uuid        NOT NULL REFERENCES establishment (id),
    tab_id           uuid        NOT NULL,
    revoked_grant_id uuid        NOT NULL,
    binding_token_id uuid        NOT NULL,
    performed_by     uuid        NOT NULL REFERENCES user_identity (id),
    performed_at     timestamptz NOT NULL,
    -- Conferências presenciais: nome/apelido dito pela pessoa e um item da conta (ou a hora da abertura sem pedido).
    name_checked     boolean     NOT NULL CHECK (name_checked),
    account_check    text        NOT NULL CHECK (account_check IN ('ITEM_DA_CONTA', 'HORA_DA_ABERTURA')),
    reason           text        NOT NULL CHECK (char_length(reason) BETWEEN 5 AND 300 AND reason = btrim(reason)),
    UNIQUE (establishment_id, id),
    FOREIGN KEY (establishment_id, tab_id) REFERENCES tab (establishment_id, id),
    FOREIGN KEY (establishment_id, revoked_grant_id) REFERENCES guest_grant (establishment_id, id),
    FOREIGN KEY (establishment_id, binding_token_id) REFERENCES binding_token (establishment_id, id)
);

ALTER TABLE access_recovery ENABLE ROW LEVEL SECURITY;
ALTER TABLE access_recovery FORCE ROW LEVEL SECURITY;

CREATE POLICY access_recovery_runtime_read ON access_recovery
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

CREATE POLICY access_recovery_runtime_insert ON access_recovery
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND performed_by = current_identity_id());

GRANT SELECT, INSERT ON access_recovery TO ${app_role};
