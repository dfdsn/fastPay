-- H07: identidade global separada do vínculo com o estabelecimento (FR-03, FR-06, CA-03).
--   * user_identity é global: uma pessoa, várias unidades. Não guarda credencial (H08) nem MFA (H09).
--   * membership pertence ao estabelecimento: papel na unidade e situação do vínculo. Desativar não
--     apaga a linha (autoria histórica) e não toca vínculos da mesma pessoa em outras unidades.
--   * Papel global (admin fastPay) fica na identidade e não concede operação de loja: operar exige
--     vínculo ativo na unidade.
--   * Dois contextos de RLS, ambos por transação: app.identity_id (listar as próprias unidades, sem
--     unidade escolhida) e app.establishment_id (operar numa unidade). Com unidade no contexto, só os
--     vínculos dela aparecem: administradores de uma loja não veem os vínculos em outras.

CREATE FUNCTION current_identity_id() RETURNS uuid
    LANGUAGE sql
    STABLE
    PARALLEL SAFE
AS $$
    SELECT NULLIF(current_setting('app.identity_id', true), '')::uuid
$$;

REVOKE ALL ON FUNCTION current_identity_id() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION current_identity_id() TO ${app_role};

CREATE TABLE user_identity (
    id            uuid        PRIMARY KEY,
    email         text        NOT NULL UNIQUE
                              CHECK (char_length(email) BETWEEN 3 AND 254 AND email = lower(btrim(email))),
    display_name  text        NOT NULL CHECK (char_length(btrim(display_name)) BETWEEN 1 AND 120),
    platform_role text        CHECK (platform_role IN ('ADMIN_FASTPAY')),
    created_at    timestamptz NOT NULL DEFAULT now(),
    version       bigint      NOT NULL DEFAULT 0
);

ALTER TABLE user_identity ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_identity FORCE ROW LEVEL SECURITY;

-- Runtime lê só a própria identidade. Cadastro e busca por e-mail chegam com login/convites (H08/H10).
CREATE POLICY user_identity_runtime_self ON user_identity
    FOR SELECT TO ${app_role}
    USING (id = current_identity_id());

GRANT SELECT ON user_identity TO ${app_role};

CREATE TABLE membership (
    id               uuid        PRIMARY KEY,
    establishment_id uuid        NOT NULL REFERENCES establishment (id),
    identity_id      uuid        NOT NULL REFERENCES user_identity (id),
    role             text        NOT NULL
                                 CHECK (role IN ('ADMIN_ESTABELECIMENTO', 'GERENTE', 'GARCOM', 'PRODUCAO')),
    status           text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    deactivated_at   timestamptz,
    deactivated_by   uuid        REFERENCES user_identity (id),
    version          bigint      NOT NULL DEFAULT 0,
    UNIQUE (establishment_id, id),
    CHECK ((status = 'INACTIVE') = (deactivated_at IS NOT NULL))
);

-- Um vínculo ativo por pessoa e unidade; vínculos antigos (inativos) continuam como histórico.
CREATE UNIQUE INDEX membership_active_uq ON membership (establishment_id, identity_id) WHERE status = 'ACTIVE';
CREATE INDEX membership_identity_idx ON membership (identity_id) WHERE status = 'ACTIVE';

ALTER TABLE membership ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership FORCE ROW LEVEL SECURITY;

-- Na unidade do contexto: vínculos dela (equipe, revalidação a cada operação).
CREATE POLICY membership_runtime_establishment ON membership
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

-- Sem unidade no contexto: só os vínculos da própria identidade (seleção de unidade).
CREATE POLICY membership_runtime_own ON membership
    FOR SELECT TO ${app_role}
    USING (current_establishment_id() IS NULL AND identity_id = current_identity_id());

-- Desativação dentro da unidade. Sem DELETE: o vínculo desativado preserva a autoria histórica.
-- Criação de vínculos chega com convites (H10/H11).
CREATE POLICY membership_runtime_update ON membership
    FOR UPDATE TO ${app_role}
    USING (establishment_id = current_establishment_id())
    WITH CHECK (establishment_id = current_establishment_id());

GRANT SELECT ON membership TO ${app_role};
GRANT UPDATE (status, deactivated_at, deactivated_by, version) ON membership TO ${app_role};

-- Lista de unidades para escolher: sem unidade no contexto, a identidade enxerga o nome das unidades
-- em que tem vínculo ativo. A subconsulta passa pelas policies de membership acima.
CREATE POLICY establishment_runtime_member_read ON establishment
    FOR SELECT TO ${app_role}
    USING (current_establishment_id() IS NULL
           AND EXISTS (SELECT 1 FROM membership m
                        WHERE m.establishment_id = establishment.id
                          AND m.identity_id = current_identity_id()
                          AND m.status = 'ACTIVE'));
