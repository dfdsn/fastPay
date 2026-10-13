-- H09: verificação em duas etapas (TOTP e códigos de recuperação), trilha de segurança da identidade e
-- comandos de operador fora da aplicação web (FR-05, especificação §5, P-07, T-05).
--   * user_mfa guarda o segredo TOTP cifrado (AES-GCM, chave fora do banco) e o último passo de 30 s já
--     aceito: o mesmo código, ou um anterior, não vale de novo. Redefinir a senha não toca nesta tabela.
--   * mfa_recovery_code guarda só o hash de cada um dos dez códigos de uso único.
--   * security_event é a trilha da própria identidade (cadastro e recadastro de MFA, recuperação,
--     comandos de operador). Append-only no runtime.
--
-- Comandos de operador (criar o primeiro admin fastPay, recuperação excepcional de MFA) rodam fora do
-- processo web com a credencial de migração, a mesma que já é dona do schema e não fica com o runtime.
-- Como as tabelas usam FORCE ROW LEVEL SECURITY, esse papel precisa de policies próprias; elas são
-- criadas para CURRENT_USER, que é o papel do Flyway (fastpay_migration). O runtime não ganha nada aqui.

ALTER TABLE auth_attempt DROP CONSTRAINT auth_attempt_kind_check;
ALTER TABLE auth_attempt ADD CONSTRAINT auth_attempt_kind_check
    CHECK (kind IN ('LOGIN_FALHOU', 'REDEFINICAO_PEDIDA', 'REDEFINICAO_FALHOU', 'MFA_FALHOU'));

CREATE TABLE user_mfa (
    identity_id       uuid        PRIMARY KEY REFERENCES user_identity (id),
    secret_ciphertext text        NOT NULL CHECK (char_length(secret_ciphertext) BETWEEN 20 AND 400),
    enrolled_at       timestamptz NOT NULL,
    last_used_step    bigint      NOT NULL CHECK (last_used_step >= 0),
    version           bigint      NOT NULL DEFAULT 0
);

ALTER TABLE user_mfa ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_mfa FORCE ROW LEVEL SECURITY;

CREATE POLICY user_mfa_runtime_self ON user_mfa
    FOR ALL TO ${app_role}
    USING (identity_id = current_identity_id())
    WITH CHECK (identity_id = current_identity_id());

-- Sem DELETE: recadastro troca o segredo; só o operador remove (recuperação excepcional).
GRANT SELECT, INSERT, UPDATE ON user_mfa TO ${app_role};

CREATE TABLE mfa_recovery_code (
    id             uuid        PRIMARY KEY,
    identity_id    uuid        NOT NULL REFERENCES user_identity (id),
    code_hash      text        NOT NULL UNIQUE CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    created_at     timestamptz NOT NULL,
    used_at        timestamptz,
    invalidated_at timestamptz,
    CHECK (used_at IS NULL OR invalidated_at IS NULL)
);

CREATE INDEX mfa_recovery_code_open_idx ON mfa_recovery_code (identity_id)
    WHERE used_at IS NULL AND invalidated_at IS NULL;

ALTER TABLE mfa_recovery_code ENABLE ROW LEVEL SECURITY;
ALTER TABLE mfa_recovery_code FORCE ROW LEVEL SECURITY;

CREATE POLICY mfa_recovery_code_runtime_self ON mfa_recovery_code
    FOR ALL TO ${app_role}
    USING (identity_id = current_identity_id())
    WITH CHECK (identity_id = current_identity_id());

GRANT SELECT, INSERT ON mfa_recovery_code TO ${app_role};
GRANT UPDATE (used_at, invalidated_at) ON mfa_recovery_code TO ${app_role};

CREATE TABLE security_event (
    id          uuid        PRIMARY KEY,
    identity_id uuid        NOT NULL REFERENCES user_identity (id),
    occurred_at timestamptz NOT NULL,
    action      text        NOT NULL CHECK (action ~ '^[a-z][a-z0-9_.]{2,79}$'),
    -- "propria-identidade" ou "operador:<nome>"; nunca e-mail, senha, código ou segredo.
    actor       text        NOT NULL CHECK (char_length(actor) BETWEEN 1 AND 120),
    reason      text        CHECK (char_length(reason) <= 500)
);

CREATE INDEX security_event_identity_idx ON security_event (identity_id, occurred_at);

ALTER TABLE security_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE security_event FORCE ROW LEVEL SECURITY;

CREATE POLICY security_event_runtime_read ON security_event
    FOR SELECT TO ${app_role}
    USING (identity_id = current_identity_id());

CREATE POLICY security_event_runtime_insert ON security_event
    FOR INSERT TO ${app_role}
    WITH CHECK (identity_id = current_identity_id());

GRANT SELECT, INSERT ON security_event TO ${app_role};

-- Comandos de operador (papel do Flyway). Acesso amplo só nas tabelas que eles tocam.
CREATE POLICY user_identity_operator ON user_identity FOR ALL TO CURRENT_USER USING (true) WITH CHECK (true);
CREATE POLICY user_credential_operator ON user_credential FOR ALL TO CURRENT_USER USING (true) WITH CHECK (true);
CREATE POLICY password_reset_token_operator ON password_reset_token
    FOR ALL TO CURRENT_USER USING (true) WITH CHECK (true);
CREATE POLICY user_mfa_operator ON user_mfa FOR ALL TO CURRENT_USER USING (true) WITH CHECK (true);
CREATE POLICY mfa_recovery_code_operator ON mfa_recovery_code FOR ALL TO CURRENT_USER USING (true) WITH CHECK (true);
CREATE POLICY security_event_operator_read ON security_event FOR SELECT TO CURRENT_USER USING (true);
CREATE POLICY security_event_operator_insert ON security_event FOR INSERT TO CURRENT_USER WITH CHECK (true);
CREATE POLICY spring_session_operator ON spring_session FOR ALL TO CURRENT_USER USING (true) WITH CHECK (true);
