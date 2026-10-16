-- H10: admin fastPay cria o estabelecimento e convida o dono (FR-01, FR-02, especificação §5).
--   * establishment ganha e-mail do dono, documento opcional (CNPJ, sem pontuação) e situação. Toda unidade
--     nasce em CONFIGURACAO: cadastrar não ativa, não cobra mensalidade e não libera dinheiro real (FR-72,
--     H39). Documento não é credencial nem chave única (validação cadastral fica em P-02).
--   * invitation é o convite para um papel numa unidade, para um e-mail. O token só existe no envio: o
--     executor do job gera o token, envia e grava o hash, o envio e o prazo (48 h a partir do envio). Um
--     convite em aberto por unidade e e-mail: convidar de novo invalida o anterior na mesma transação.
--   * Aceite pelo contexto app.invitation_token_hash: só o convite apresentado é visível. Conta nova é criada
--     nesse contexto (identidade + senha + vínculo); conta existente aceita autenticada, com o e-mail do
--     convite. O runtime não cria identidade nem vínculo fora de um convite em aberto.
--   * Admin fastPay (current_platform_admin) cria unidades e lê o registro de unidades e convites; não ganha
--     acesso aos dados operacionais das lojas.

CREATE FUNCTION current_invitation_token_hash() RETURNS text
    LANGUAGE sql
    STABLE
    PARALLEL SAFE
AS $$
    SELECT NULLIF(current_setting('app.invitation_token_hash', true), '')
$$;

-- Verdadeiro se a identidade do contexto é admin fastPay. Lê user_identity pela policy da própria identidade;
-- não usar em policies de user_identity (recursão).
CREATE FUNCTION current_platform_admin() RETURNS boolean
    LANGUAGE sql
    STABLE
AS $$
    SELECT EXISTS (SELECT 1 FROM user_identity
                    WHERE id = current_identity_id() AND platform_role = 'ADMIN_FASTPAY')
$$;

REVOKE ALL ON FUNCTION current_invitation_token_hash() FROM PUBLIC;
REVOKE ALL ON FUNCTION current_platform_admin() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION current_invitation_token_hash() TO ${app_role};
GRANT EXECUTE ON FUNCTION current_platform_admin() TO ${app_role};

ALTER TABLE auth_attempt DROP CONSTRAINT auth_attempt_kind_check;
ALTER TABLE auth_attempt ADD CONSTRAINT auth_attempt_kind_check
    CHECK (kind IN ('LOGIN_FALHOU', 'REDEFINICAO_PEDIDA', 'REDEFINICAO_FALHOU', 'MFA_FALHOU', 'CONVITE_FALHOU'));

-- Unidades anteriores (fixtures de teste) ficam sem dono e sem autor.
ALTER TABLE establishment
    ADD COLUMN owner_email text CHECK (char_length(owner_email) BETWEEN 3 AND 254
                                       AND owner_email = lower(btrim(owner_email))),
    ADD COLUMN document    text CHECK (document ~ '^[0-9A-Z]{12}[0-9]{2}$'),
    ADD COLUMN status      text NOT NULL DEFAULT 'CONFIGURACAO' CHECK (status IN ('CONFIGURACAO')),
    ADD COLUMN created_by  uuid REFERENCES user_identity (id);

CREATE POLICY establishment_platform_admin_read ON establishment
    FOR SELECT TO ${app_role}
    USING (current_platform_admin());

-- A unidade nova já vem com o contexto dela (para gravar convite, job e auditoria na mesma transação).
CREATE POLICY establishment_platform_admin_insert ON establishment
    FOR INSERT TO ${app_role}
    WITH CHECK (current_platform_admin() AND id = current_establishment_id() AND created_by = current_identity_id()
                AND status = 'CONFIGURACAO');

GRANT INSERT (id, name, owner_email, document, created_by) ON establishment TO ${app_role};

CREATE TABLE invitation (
    id             uuid        PRIMARY KEY,
    establishment_id uuid      NOT NULL REFERENCES establishment (id),
    email          text        NOT NULL CHECK (char_length(email) BETWEEN 3 AND 254 AND email = lower(btrim(email))),
    role           text        NOT NULL
                               CHECK (role IN ('ADMIN_ESTABELECIMENTO', 'GERENTE', 'GARCOM', 'PRODUCAO')),
    invited_by     uuid        NOT NULL REFERENCES user_identity (id),
    -- Nome de quem convidou, como estava no convite: o e-mail é montado no contexto da unidade.
    inviter_name   text        NOT NULL CHECK (char_length(btrim(inviter_name)) BETWEEN 1 AND 120),
    created_at     timestamptz NOT NULL,
    token_hash     text        UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    sent_at        timestamptz,
    expires_at     timestamptz,
    accepted_at    timestamptz,
    accepted_by    uuid        REFERENCES user_identity (id),
    invalidated_at timestamptz,
    UNIQUE (establishment_id, id),
    CHECK ((token_hash IS NULL) = (sent_at IS NULL) AND (sent_at IS NULL) = (expires_at IS NULL)),
    CHECK (expires_at IS NULL OR expires_at > sent_at),
    CHECK ((accepted_at IS NULL) = (accepted_by IS NULL)),
    CHECK (accepted_at IS NULL OR (token_hash IS NOT NULL AND invalidated_at IS NULL))
);

CREATE UNIQUE INDEX invitation_open_uq ON invitation (establishment_id, email)
    WHERE accepted_at IS NULL AND invalidated_at IS NULL;
CREATE INDEX invitation_establishment_idx ON invitation (establishment_id, created_at);

ALTER TABLE invitation ENABLE ROW LEVEL SECURITY;
ALTER TABLE invitation FORCE ROW LEVEL SECURITY;

-- Na unidade do contexto: convidar, enviar (token, envio, prazo) e invalidar. Aceitar não é por aqui.
CREATE POLICY invitation_runtime_establishment_read ON invitation
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

CREATE POLICY invitation_runtime_establishment_insert ON invitation
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND accepted_at IS NULL AND token_hash IS NULL);

CREATE POLICY invitation_runtime_establishment_update ON invitation
    FOR UPDATE TO ${app_role}
    USING (establishment_id = current_establishment_id() AND accepted_at IS NULL)
    WITH CHECK (establishment_id = current_establishment_id() AND accepted_at IS NULL);

-- Registro de unidades do admin fastPay: situação do convite do dono.
CREATE POLICY invitation_platform_admin_read ON invitation
    FOR SELECT TO ${app_role}
    USING (current_platform_admin());

-- Aceite: só o convite apresentado; quem aceita é a identidade do contexto, com o e-mail do convite.
CREATE POLICY invitation_runtime_presented ON invitation
    FOR SELECT TO ${app_role}
    USING (token_hash = current_invitation_token_hash());

CREATE POLICY invitation_runtime_accept ON invitation
    FOR UPDATE TO ${app_role}
    USING (token_hash = current_invitation_token_hash() AND accepted_at IS NULL AND invalidated_at IS NULL)
    WITH CHECK (token_hash = current_invitation_token_hash() AND accepted_by = current_identity_id()
                AND email = (SELECT u.email FROM user_identity u WHERE u.id = current_identity_id()));

GRANT SELECT, INSERT ON invitation TO ${app_role};
GRANT UPDATE (token_hash, sent_at, expires_at, invalidated_at, accepted_at, accepted_by) ON invitation TO ${app_role};

-- Nome da unidade na tela de aceite.
CREATE POLICY establishment_runtime_invitation_read ON establishment
    FOR SELECT TO ${app_role}
    USING (EXISTS (SELECT 1 FROM invitation i
                    WHERE i.establishment_id = establishment.id
                      AND i.token_hash = current_invitation_token_hash()));

-- Conta nova pelo convite: a identidade do contexto, sem papel global, com o e-mail de um convite em aberto.
CREATE POLICY user_identity_runtime_invitation_insert ON user_identity
    FOR INSERT TO ${app_role}
    WITH CHECK (id = current_identity_id() AND platform_role IS NULL
                AND EXISTS (SELECT 1 FROM invitation i
                             WHERE i.token_hash = current_invitation_token_hash() AND i.email = user_identity.email
                               AND i.accepted_at IS NULL AND i.invalidated_at IS NULL));

GRANT INSERT (id, email, display_name) ON user_identity TO ${app_role};

-- Vínculo pelo convite já aceito por esta identidade, com a unidade e o papel do convite.
CREATE POLICY membership_runtime_invitation_insert ON membership
    FOR INSERT TO ${app_role}
    WITH CHECK (identity_id = current_identity_id() AND status = 'ACTIVE'
                AND EXISTS (SELECT 1 FROM invitation i
                             WHERE i.token_hash = current_invitation_token_hash()
                               AND i.establishment_id = membership.establishment_id
                               AND i.role = membership.role
                               AND i.accepted_by = current_identity_id()));

GRANT INSERT (id, establishment_id, identity_id, role) ON membership TO ${app_role};

-- Auditoria do aceite na unidade do convite apresentado.
CREATE POLICY audit_event_runtime_invitation_insert ON audit_event
    FOR INSERT TO ${app_role}
    WITH CHECK (EXISTS (SELECT 1 FROM invitation i
                         WHERE i.establishment_id = audit_event.establishment_id
                           AND i.token_hash = current_invitation_token_hash()));
