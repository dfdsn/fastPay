-- H11: equipe, permissões por ação e concessão de suporte (FR-02–06, FR-66, CA-04, CA-28; PRD §4.2).
--   * O administrador do estabelecimento convida papéis (convites da V8), troca o papel e desativa vínculos.
--     A regra do último administrador ativo é da aplicação, sob lock das linhas de membership (especificação §5).
--   * membership_permission guarda as permissões específicas delegadas a um garçom. Só ações delegáveis cabem
--     na coluna: devolução, débito, regularização e invalidação de recebimento externo nunca são delegáveis.
--     Revogar não apaga a linha (autoria histórica).
--   * support_grant é a autorização temporária de leitura dada pelo dono a uma identidade de suporte (admin
--     fastPay), com motivo e prazo. Suporte não ganha vínculo, sessão de unidade nem escrita: a consulta roda em
--     transação somente leitura no contexto da unidade, auditada em audit_event.
--   * audit_event ganha detail (o que mudou), sem dado pessoal.
--   * Na unidade do contexto, a equipe enxerga nome e e-mail das identidades com vínculo nela (lista da equipe).

-- Auditoria passa a dizer o que mudou, sem dado pessoal: papel anterior e novo, permissão, o que o suporte
-- consultou.
ALTER TABLE audit_event ADD COLUMN detail text CHECK (char_length(detail) <= 300);

-- Troca de papel na unidade (a policy membership_runtime_update da V4 já limita à unidade do contexto).
GRANT UPDATE (role) ON membership TO ${app_role};

-- Nome e e-mail de quem tem ou teve vínculo na unidade do contexto. Outras identidades seguem invisíveis.
CREATE POLICY user_identity_runtime_unit_member ON user_identity
    FOR SELECT TO ${app_role}
    USING (current_establishment_id() IS NOT NULL
           AND EXISTS (SELECT 1 FROM membership m
                        WHERE m.identity_id = user_identity.id
                          AND m.establishment_id = current_establishment_id()));

CREATE TABLE membership_permission (
    id               uuid        PRIMARY KEY,
    establishment_id uuid        NOT NULL REFERENCES establishment (id),
    membership_id    uuid        NOT NULL,
    permission       text        NOT NULL CHECK (permission IN (
                                     'CONCLUIR_PROBLEMA', 'CANCELAR_ITEM', 'CONCEDER_DESCONTO', 'REABRIR_COMANDA',
                                     'TRANSFERIR_UNIDADES', 'DESFAZER_ENTREGA', 'ALTERAR_DISPONIBILIDADE',
                                     'REGISTRAR_RECEBIMENTO_EXTERNO', 'RECUPERAR_ACESSO_CONSUMIDOR',
                                     'VINCULAR_CONTINUACAO_VISITA')),
    granted_by       uuid        NOT NULL REFERENCES user_identity (id),
    granted_at       timestamptz NOT NULL,
    revoked_at       timestamptz,
    revoked_by       uuid        REFERENCES user_identity (id),
    FOREIGN KEY (establishment_id, membership_id) REFERENCES membership (establishment_id, id),
    CHECK ((revoked_at IS NULL) = (revoked_by IS NULL)),
    CHECK (revoked_at IS NULL OR revoked_at >= granted_at)
);

-- Uma concessão vigente por vínculo e permissão; revogadas ficam como histórico.
CREATE UNIQUE INDEX membership_permission_active_uq ON membership_permission (membership_id, permission)
    WHERE revoked_at IS NULL;

ALTER TABLE membership_permission ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership_permission FORCE ROW LEVEL SECURITY;

CREATE POLICY membership_permission_runtime_read ON membership_permission
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

-- Sem unidade: as delegações dos próprios vínculos (lista de unidades com as permissões efetivas).
CREATE POLICY membership_permission_runtime_own ON membership_permission
    FOR SELECT TO ${app_role}
    USING (current_establishment_id() IS NULL
           AND EXISTS (SELECT 1 FROM membership m
                        WHERE m.id = membership_permission.membership_id
                          AND m.identity_id = current_identity_id()));

CREATE POLICY membership_permission_runtime_insert ON membership_permission
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND granted_by = current_identity_id()
                AND revoked_at IS NULL);

CREATE POLICY membership_permission_runtime_revoke ON membership_permission
    FOR UPDATE TO ${app_role}
    USING (establishment_id = current_establishment_id() AND revoked_at IS NULL)
    WITH CHECK (establishment_id = current_establishment_id() AND revoked_by = current_identity_id());

GRANT SELECT, INSERT ON membership_permission TO ${app_role};
GRANT UPDATE (revoked_at, revoked_by) ON membership_permission TO ${app_role};

CREATE TABLE support_grant (
    id                  uuid        PRIMARY KEY,
    establishment_id    uuid        NOT NULL REFERENCES establishment (id),
    support_identity_id uuid        NOT NULL REFERENCES user_identity (id),
    -- E-mail de quem recebeu, como o dono digitou: a lista do dono não lê identidades fora da unidade.
    support_email       text        NOT NULL CHECK (char_length(support_email) BETWEEN 3 AND 254
                                                    AND support_email = lower(btrim(support_email))),
    reason              text        NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 5 AND 500),
    granted_by          uuid        NOT NULL REFERENCES user_identity (id),
    granted_at          timestamptz NOT NULL,
    expires_at          timestamptz NOT NULL,
    revoked_at          timestamptz,
    revoked_by          uuid        REFERENCES user_identity (id),
    UNIQUE (establishment_id, id),
    CHECK (expires_at > granted_at),
    CHECK ((revoked_at IS NULL) = (revoked_by IS NULL))
);

CREATE INDEX support_grant_establishment_idx ON support_grant (establishment_id, granted_at);
CREATE INDEX support_grant_support_idx ON support_grant (support_identity_id, expires_at) WHERE revoked_at IS NULL;

ALTER TABLE support_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE support_grant FORCE ROW LEVEL SECURITY;

-- Na unidade do contexto: o dono concede, lista e encerra; a consulta do suporte confere a própria concessão.
CREATE POLICY support_grant_runtime_establishment_read ON support_grant
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

CREATE POLICY support_grant_runtime_establishment_insert ON support_grant
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND granted_by = current_identity_id()
                AND revoked_at IS NULL);

CREATE POLICY support_grant_runtime_establishment_revoke ON support_grant
    FOR UPDATE TO ${app_role}
    USING (establishment_id = current_establishment_id() AND revoked_at IS NULL)
    WITH CHECK (establishment_id = current_establishment_id() AND revoked_by = current_identity_id());

-- Sem unidade: a identidade de suporte vê só as concessões dadas a ela.
CREATE POLICY support_grant_runtime_support_read ON support_grant
    FOR SELECT TO ${app_role}
    USING (current_establishment_id() IS NULL AND support_identity_id = current_identity_id());

GRANT SELECT, INSERT ON support_grant TO ${app_role};
GRANT UPDATE (revoked_at, revoked_by) ON support_grant TO ${app_role};

-- Identidade de suporte pelo e-mail que o dono digitou: devolve o id só de admin fastPay; nada para as demais,
-- que continuam invisíveis ao runtime. Roda com os direitos do dono do schema (policy user_identity_operator, V6).
CREATE FUNCTION support_identity_by_email(p_email text) RETURNS uuid
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = fastpay, pg_temp
AS $$
    SELECT id FROM user_identity WHERE email = lower(btrim(p_email)) AND platform_role = 'ADMIN_FASTPAY'
$$;

REVOKE ALL ON FUNCTION support_identity_by_email(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION support_identity_by_email(text) TO ${app_role};
