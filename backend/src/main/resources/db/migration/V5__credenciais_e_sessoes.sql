-- H08: login da equipe por e-mail e senha, sessão no servidor e redefinição de senha (FR-04, NFR-04,
-- especificação §5).
--   * user_credential guarda só o hash da senha (algoritmo prefixado, ver especificação §5), uma linha
--     por identidade. MFA chega em H09 em tabela própria: redefinir a senha não toca nela.
--   * password_reset_token guarda só o hash SHA-256 do token. 30 minutos, uso único; um token novo
--     invalida o anterior (no máximo um em aberto por identidade).
--   * auth_attempt registra tentativas para limitar abuso (T-03). Sem e-mail nem IP em claro: só hashes.
--   * spring_session/spring_session_attributes: schema do Spring Session JDBC 4.1, migrado pelo Flyway.
--
-- Contextos de RLS, todos por transação:
--   * app.login_email: antes do login ou pedido de redefinição, a única linha de user_identity visível
--     é a do e-mail digitado. A credencial só aparece depois, no contexto da identidade.
--   * app.reset_token_hash: ao confirmar a redefinição, só o token apresentado é visível.
--   * app.identity_id (H07): a própria credencial e os próprios tokens.

CREATE FUNCTION current_login_email() RETURNS text
    LANGUAGE sql
    STABLE
    PARALLEL SAFE
AS $$
    SELECT NULLIF(current_setting('app.login_email', true), '')
$$;

CREATE FUNCTION current_reset_token_hash() RETURNS text
    LANGUAGE sql
    STABLE
    PARALLEL SAFE
AS $$
    SELECT NULLIF(current_setting('app.reset_token_hash', true), '')
$$;

REVOKE ALL ON FUNCTION current_login_email() FROM PUBLIC;
REVOKE ALL ON FUNCTION current_reset_token_hash() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION current_login_email() TO ${app_role};
GRANT EXECUTE ON FUNCTION current_reset_token_hash() TO ${app_role};

CREATE POLICY user_identity_runtime_login ON user_identity
    FOR SELECT TO ${app_role}
    USING (email = current_login_email());

CREATE TABLE user_credential (
    identity_id   uuid        PRIMARY KEY REFERENCES user_identity (id),
    password_hash text        NOT NULL CHECK (char_length(password_hash) BETWEEN 20 AND 200),
    changed_at    timestamptz NOT NULL,
    version       bigint      NOT NULL DEFAULT 0
);

ALTER TABLE user_credential ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_credential FORCE ROW LEVEL SECURITY;

CREATE POLICY user_credential_runtime_self ON user_credential
    FOR ALL TO ${app_role}
    USING (identity_id = current_identity_id())
    WITH CHECK (identity_id = current_identity_id());

GRANT SELECT, INSERT, UPDATE ON user_credential TO ${app_role};

CREATE TABLE password_reset_token (
    id             uuid        PRIMARY KEY,
    identity_id    uuid        NOT NULL REFERENCES user_identity (id),
    token_hash     text        NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    created_at     timestamptz NOT NULL,
    expires_at     timestamptz NOT NULL,
    used_at        timestamptz,
    invalidated_at timestamptz,
    CHECK (expires_at > created_at),
    CHECK (used_at IS NULL OR invalidated_at IS NULL)
);

-- Um token em aberto por identidade: pedir de novo invalida o anterior na mesma transação.
CREATE UNIQUE INDEX password_reset_token_open_uq ON password_reset_token (identity_id)
    WHERE used_at IS NULL AND invalidated_at IS NULL;

ALTER TABLE password_reset_token ENABLE ROW LEVEL SECURITY;
ALTER TABLE password_reset_token FORCE ROW LEVEL SECURITY;

CREATE POLICY password_reset_token_runtime_self ON password_reset_token
    FOR ALL TO ${app_role}
    USING (identity_id = current_identity_id())
    WITH CHECK (identity_id = current_identity_id());

CREATE POLICY password_reset_token_runtime_presented ON password_reset_token
    FOR SELECT TO ${app_role}
    USING (token_hash = current_reset_token_hash());

GRANT SELECT, INSERT ON password_reset_token TO ${app_role};
GRANT UPDATE (used_at, invalidated_at) ON password_reset_token TO ${app_role};

-- Tabela técnica e global de limitação de tentativas. O runtime precisa contar e limpar tentativas
-- antes de existir qualquer identidade no contexto; as chaves são hashes, sem dado pessoal em claro.
CREATE TABLE auth_attempt (
    id          uuid        PRIMARY KEY,
    kind        text        NOT NULL CHECK (kind IN ('LOGIN_FALHOU', 'REDEFINICAO_PEDIDA', 'REDEFINICAO_FALHOU')),
    subject_key text        CHECK (subject_key ~ '^[0-9a-f]{64}$'),
    client_key  text        NOT NULL CHECK (client_key ~ '^[0-9a-f]{64}$'),
    occurred_at timestamptz NOT NULL
);

CREATE INDEX auth_attempt_subject_idx ON auth_attempt (kind, subject_key, occurred_at);
CREATE INDEX auth_attempt_client_idx ON auth_attempt (kind, client_key, occurred_at);
CREATE INDEX auth_attempt_occurred_idx ON auth_attempt (occurred_at);

ALTER TABLE auth_attempt ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth_attempt FORCE ROW LEVEL SECURITY;

CREATE POLICY auth_attempt_runtime ON auth_attempt
    FOR ALL TO ${app_role}
    USING (true)
    WITH CHECK (true);

GRANT SELECT, INSERT, DELETE ON auth_attempt TO ${app_role};

-- Spring Session JDBC (schema-postgresql.sql do spring-session-jdbc 4.1.1). A sessão é procurada pelo
-- cookie antes de qualquer contexto, e a limpeza de expiradas percorre todas; por isso a policy é
-- ampla. A sessão guarda só ids (identidade, unidade) e instantes, nunca senha ou token.
CREATE TABLE spring_session (
    primary_id            char(36)     NOT NULL,
    session_id            char(36)     NOT NULL,
    creation_time         bigint       NOT NULL,
    last_access_time      bigint       NOT NULL,
    max_inactive_interval int          NOT NULL,
    expiry_time           bigint       NOT NULL,
    principal_name        varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id char(36)     NOT NULL,
    attribute_name     varchar(200) NOT NULL,
    attribute_bytes    bytea        NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);

ALTER TABLE spring_session ENABLE ROW LEVEL SECURITY;
ALTER TABLE spring_session FORCE ROW LEVEL SECURITY;
ALTER TABLE spring_session_attributes ENABLE ROW LEVEL SECURITY;
ALTER TABLE spring_session_attributes FORCE ROW LEVEL SECURITY;

CREATE POLICY spring_session_runtime ON spring_session
    FOR ALL TO ${app_role}
    USING (true)
    WITH CHECK (true);

CREATE POLICY spring_session_attributes_runtime ON spring_session_attributes
    FOR ALL TO ${app_role}
    USING (true)
    WITH CHECK (true);

GRANT SELECT, INSERT, UPDATE, DELETE ON spring_session, spring_session_attributes TO ${app_role};
