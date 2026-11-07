-- H27: login Google opcional do consumidor e histórico das comandas que ele vinculou (FR-04, FR-10, FR-11; P-13
-- aprovada por Diego em 07/11/2026; T-03 da H27 aprovada na mesma data).
--   * consumer_account é a conta do consumidor cadastrado, separada de user_identity (equipe): mesmo e-mail nunca
--     junta as duas nem passa vínculo ou privilégio de uma para a outra. A chave é o "sub" do Google; o e-mail fica
--     só para mostrar à própria pessoa e atender pedidos de privacidade. Sem nome, foto ou telefone (escopos
--     "openid email"). Guarda a versão do aviso de privacidade aceito e quando.
--   * consumer_tab_link é o vínculo explícito de uma comanda à conta, feito pelo próprio consumidor com acesso
--     válido à comanda (guest_grant) e confirmação. Uma conta por comanda; nunca por nome, número ou e-mail.
--   * auth_attempt ganha GOOGLE_INICIADO: logins Google iniciados por origem (T-03: 20 em 15 min).
--
-- Contextos de RLS:
--   * app.google_subject: o retorno do Google só enxerga a conta desse "sub" (para entrar ou criar a conta).
--   * app.consumer_account_id: a pessoa vê a própria conta, os próprios vínculos e as comandas vinculadas, em
--     qualquer unidade; nada de outras pessoas.
--   * app.establishment_id: a unidade vê só os vínculos das próprias comandas (a marca "cadastrado"), nunca a conta
--     nem o e-mail.

CREATE FUNCTION current_consumer_account_id() RETURNS uuid
    LANGUAGE sql
    STABLE
    PARALLEL SAFE
AS $$
    SELECT NULLIF(current_setting('app.consumer_account_id', true), '')::uuid
$$;

CREATE FUNCTION current_google_subject() RETURNS text
    LANGUAGE sql
    STABLE
    PARALLEL SAFE
AS $$
    SELECT NULLIF(current_setting('app.google_subject', true), '')
$$;

REVOKE ALL ON FUNCTION current_consumer_account_id() FROM PUBLIC;
REVOKE ALL ON FUNCTION current_google_subject() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION current_consumer_account_id() TO ${app_role};
GRANT EXECUTE ON FUNCTION current_google_subject() TO ${app_role};

ALTER TABLE auth_attempt DROP CONSTRAINT auth_attempt_kind_check;
ALTER TABLE auth_attempt ADD CONSTRAINT auth_attempt_kind_check
    CHECK (kind IN ('LOGIN_FALHOU', 'REDEFINICAO_PEDIDA', 'REDEFINICAO_FALHOU', 'MFA_FALHOU', 'CONVITE_FALHOU',
                    'GOOGLE_INICIADO'));

CREATE TABLE consumer_account (
    id                     uuid        PRIMARY KEY,
    google_subject         text        NOT NULL UNIQUE CHECK (char_length(google_subject) BETWEEN 1 AND 255),
    email                  text        NOT NULL
                                       CHECK (char_length(email) BETWEEN 3 AND 254 AND email = lower(btrim(email))),
    email_verified         boolean     NOT NULL,
    privacy_notice_version text        NOT NULL CHECK (char_length(privacy_notice_version) BETWEEN 1 AND 40),
    privacy_accepted_at    timestamptz NOT NULL,
    created_at             timestamptz NOT NULL,
    last_login_at          timestamptz NOT NULL
);

ALTER TABLE consumer_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE consumer_account FORCE ROW LEVEL SECURITY;

CREATE POLICY consumer_account_runtime_read ON consumer_account
    FOR SELECT TO ${app_role}
    USING (id = current_consumer_account_id() OR google_subject = current_google_subject());

CREATE POLICY consumer_account_runtime_insert ON consumer_account
    FOR INSERT TO ${app_role}
    WITH CHECK (google_subject = current_google_subject());

CREATE POLICY consumer_account_runtime_update ON consumer_account
    FOR UPDATE TO ${app_role}
    USING (google_subject = current_google_subject())
    WITH CHECK (google_subject = current_google_subject());

GRANT SELECT, INSERT ON consumer_account TO ${app_role};
GRANT UPDATE (email, email_verified, last_login_at, privacy_notice_version, privacy_accepted_at) ON consumer_account TO ${app_role};

CREATE TABLE consumer_tab_link (
    id                  uuid        PRIMARY KEY,
    consumer_account_id uuid        NOT NULL REFERENCES consumer_account (id),
    establishment_id    uuid        NOT NULL REFERENCES establishment (id),
    tab_id              uuid        NOT NULL UNIQUE,
    grant_id            uuid        NOT NULL,
    linked_at           timestamptz NOT NULL,
    FOREIGN KEY (establishment_id, tab_id) REFERENCES tab (establishment_id, id),
    FOREIGN KEY (establishment_id, grant_id) REFERENCES guest_grant (establishment_id, id)
);

CREATE INDEX consumer_tab_link_account_idx ON consumer_tab_link (consumer_account_id, linked_at DESC, id);

ALTER TABLE consumer_tab_link ENABLE ROW LEVEL SECURITY;
ALTER TABLE consumer_tab_link FORCE ROW LEVEL SECURITY;

CREATE POLICY consumer_tab_link_runtime_read ON consumer_tab_link
    FOR SELECT TO ${app_role}
    USING (consumer_account_id = current_consumer_account_id() OR establishment_id = current_establishment_id());

-- Vincular roda com a unidade da comanda (acesso conferido nela) e a conta da sessão no contexto.
CREATE POLICY consumer_tab_link_runtime_insert ON consumer_tab_link
    FOR INSERT TO ${app_role}
    WITH CHECK (consumer_account_id = current_consumer_account_id()
                AND establishment_id = current_establishment_id());

GRANT SELECT, INSERT ON consumer_tab_link TO ${app_role};

-- Histórico (FR-10): a pessoa lê, só com a conta no contexto, as comandas que ela mesma vinculou e o nome da unidade
-- delas. Nada além dessas linhas: pedidos, conta e equipe continuam só no contexto da unidade.
CREATE POLICY tab_runtime_consumer_read ON tab
    FOR SELECT TO ${app_role}
    USING (current_consumer_account_id() IS NOT NULL AND EXISTS (
        SELECT 1 FROM consumer_tab_link l
        WHERE l.tab_id = tab.id AND l.consumer_account_id = current_consumer_account_id()));

CREATE POLICY establishment_runtime_consumer_read ON establishment
    FOR SELECT TO ${app_role}
    USING (current_consumer_account_id() IS NOT NULL AND EXISTS (
        SELECT 1 FROM consumer_tab_link l
        WHERE l.establishment_id = establishment.id AND l.consumer_account_id = current_consumer_account_id()));
