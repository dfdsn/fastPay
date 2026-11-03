-- H14: configurações versionadas por estabelecimento (FR-23, FR-28–30, FR-40, FR-57, FR-68; D-08).
--   * unit_settings_version guarda cada versão da configuração comercial e operacional da unidade: serviço
--     (habilitado, percentual com até 4 casas como na especificação §7, base, couvert na base), couvert (habilitado, automático ou manual, valor por
--     pessoa), formatos de desconto aceitos e modalidades de atendimento e de saída. Versões são imutáveis: o
--     runtime só lê e insere. Salvar cria a versão seguinte; comandas guardam o id da versão capturada na abertura
--     (H15) e nunca são reescritas por mudanças posteriores.
--   * operational_calendar guarda fuso IANA e horário de corte do dia operacional com o instante em que passam a
--     valer. Mudança vale só a partir do início do próximo dia operacional (FR-57, CA-26); uma mudança ainda
--     pendente pode ser substituída ou cancelada, uma que já vale nunca é alterada. O dia em que a mudança entra
--     fica mais longo ou mais curto, sem repetir nem pular data e sem reclassificar o que já passou.
--   * Toda unidade nasce com a versão 1 e o calendário padrão (gatilho abaixo); as já cadastradas recebem os
--     mesmos padrões. created_by nulo = padrão inicial do sistema.
--   * membership ganha o limite de desconto da pessoa (FR-30, FR-68): percentual máximo do consumo da comanda,
--     inclusive para desconto em reais. Nulo = padrão do papel (gerente 100%, garçom 0%); administrador sempre
--     100%. Vale na operação seguinte, como as permissões; trocar o papel volta ao padrão do novo papel.
--   * Quem pode ver e alterar é conferido na aplicação (identity.Permission), como nas demais tabelas.

ALTER TABLE membership ADD COLUMN discount_limit_percent numeric(7, 4)
    CHECK (discount_limit_percent BETWEEN 0 AND 100);
GRANT UPDATE (discount_limit_percent) ON membership TO ${app_role};

CREATE TABLE unit_settings_version (
    id                       uuid         PRIMARY KEY,
    establishment_id         uuid         NOT NULL REFERENCES establishment (id),
    number                   integer      NOT NULL CHECK (number >= 1),
    service_enabled          boolean      NOT NULL,
    service_percent          numeric(6, 4) NOT NULL CHECK (service_percent BETWEEN 0 AND 20),
    service_base             text         NOT NULL CHECK (service_base IN ('APOS_DESCONTO', 'ANTES_DESCONTO')),
    service_includes_couvert boolean      NOT NULL,
    couvert_enabled          boolean      NOT NULL,
    couvert_mode             text         NOT NULL CHECK (couvert_mode IN ('AUTOMATICO', 'MANUAL')),
    couvert_amount           numeric(5, 2) CHECK (couvert_amount BETWEEN 0.01 AND 200.00),
    discount_fixed_allowed   boolean      NOT NULL,
    discount_percent_allowed boolean      NOT NULL,
    attendance_mode          text         NOT NULL CHECK (attendance_mode IN ('GARCOM_RESPONSAVEL', 'CENTRAL')),
    exit_mode                text         NOT NULL CHECK (exit_mode IN ('AUTOMATICA', 'VALIDADA')),
    created_by               uuid         REFERENCES user_identity (id),
    created_at               timestamptz  NOT NULL,
    UNIQUE (establishment_id, id),
    UNIQUE (establishment_id, number),
    CHECK (NOT service_enabled OR service_percent > 0),
    CHECK (NOT couvert_enabled OR couvert_amount IS NOT NULL),
    CHECK (created_by IS NOT NULL OR number = 1)
);

ALTER TABLE unit_settings_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE unit_settings_version FORCE ROW LEVEL SECURITY;

CREATE POLICY unit_settings_version_runtime_read ON unit_settings_version
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

CREATE POLICY unit_settings_version_runtime_insert ON unit_settings_version
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND created_by = current_identity_id()
                AND number > 1);

-- Sem UPDATE nem DELETE: versão gravada não muda.
GRANT SELECT, INSERT ON unit_settings_version TO ${app_role};

CREATE TABLE operational_calendar (
    id               uuid        PRIMARY KEY,
    establishment_id uuid        NOT NULL REFERENCES establishment (id),
    time_zone        text        NOT NULL CHECK (char_length(time_zone) BETWEEN 1 AND 64),
    -- Corte em minutos exatos, de 00:00 a 12:00 (madrugada e manhã; ver T-03 da H14).
    cutoff           time        NOT NULL CHECK (cutoff BETWEEN time '00:00' AND time '12:00'
                                                 AND date_part('second', cutoff) = 0),
    effective_from   timestamptz NOT NULL,
    -- Data do primeiro dia sob este calendário, que começa em effective_from e vai até o primeiro corte novo de
    -- data maior (dia de transição mais longo ou mais curto). Nulo no calendário inicial: dias naturais.
    first_date       date,
    created_by       uuid        REFERENCES user_identity (id),
    created_at       timestamptz NOT NULL,
    canceled_at      timestamptz,
    canceled_by      uuid        REFERENCES user_identity (id),
    UNIQUE (establishment_id, id),
    CHECK ((first_date IS NULL) = (created_by IS NULL)),
    CHECK ((canceled_at IS NULL) = (canceled_by IS NULL)),
    -- Só se cancela uma mudança que ainda não começou a valer.
    CHECK (canceled_at IS NULL OR canceled_at < effective_from)
);

CREATE UNIQUE INDEX operational_calendar_effective_uq ON operational_calendar (establishment_id, effective_from)
    WHERE canceled_at IS NULL;

ALTER TABLE operational_calendar ENABLE ROW LEVEL SECURITY;
ALTER TABLE operational_calendar FORCE ROW LEVEL SECURITY;

CREATE POLICY operational_calendar_runtime_read ON operational_calendar
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

CREATE POLICY operational_calendar_runtime_insert ON operational_calendar
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND created_by = current_identity_id()
                AND canceled_at IS NULL);

CREATE POLICY operational_calendar_runtime_cancel ON operational_calendar
    FOR UPDATE TO ${app_role}
    USING (establishment_id = current_establishment_id() AND canceled_at IS NULL)
    WITH CHECK (establishment_id = current_establishment_id() AND canceled_by = current_identity_id());

GRANT SELECT, INSERT ON operational_calendar TO ${app_role};
GRANT UPDATE (canceled_at, canceled_by) ON operational_calendar TO ${app_role};

-- Padrões de toda unidade nova (proposta da H14 em T-03): serviço 10% sobre o consumo após desconto, sem
-- couvert na base; couvert desligado (automático quando ligado); desconto em reais e percentual aceitos;
-- garçom responsável; saída automática; America/Sao_Paulo com corte às 06:00, valendo desde o cadastro.
CREATE FUNCTION establishment_default_settings() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = fastpay, pg_temp
AS $$
BEGIN
    INSERT INTO unit_settings_version (id, establishment_id, number, service_enabled, service_percent, service_base,
                                       service_includes_couvert, couvert_enabled, couvert_mode, couvert_amount,
                                       discount_fixed_allowed, discount_percent_allowed, attendance_mode,
                                       exit_mode, created_by, created_at)
    VALUES (gen_random_uuid(), NEW.id, 1, true, 10.00, 'APOS_DESCONTO', false, false, 'AUTOMATICO', NULL,
            true, true, 'GARCOM_RESPONSAVEL', 'AUTOMATICA', NULL, NEW.created_at);
    INSERT INTO operational_calendar (id, establishment_id, time_zone, cutoff, effective_from, created_by,
                                      created_at)
    VALUES (gen_random_uuid(), NEW.id, 'America/Sao_Paulo', time '06:00', NEW.created_at, NULL, NEW.created_at);
    RETURN NEW;
END
$$;

REVOKE ALL ON FUNCTION establishment_default_settings() FROM PUBLIC;

CREATE TRIGGER establishment_default_settings
    AFTER INSERT ON establishment
    FOR EACH ROW EXECUTE FUNCTION establishment_default_settings();

-- O gatilho roda como dono do schema (SECURITY DEFINER), sujeito ao FORCE: só pode inserir os padrões iniciais.
CREATE POLICY unit_settings_version_defaults ON unit_settings_version
    FOR INSERT TO CURRENT_USER
    WITH CHECK (number = 1 AND created_by IS NULL);

CREATE POLICY operational_calendar_defaults ON operational_calendar
    FOR INSERT TO CURRENT_USER
    WITH CHECK (created_by IS NULL AND first_date IS NULL);

-- Unidades já cadastradas recebem os mesmos padrões. O dono do schema não tem policy de leitura em
-- establishment: o FORCE é desligado só durante esta cópia, na transação da migration.
ALTER TABLE establishment NO FORCE ROW LEVEL SECURITY;
INSERT INTO unit_settings_version (id, establishment_id, number, service_enabled, service_percent, service_base,
                                   service_includes_couvert, couvert_enabled, couvert_mode, couvert_amount,
                                   discount_fixed_allowed, discount_percent_allowed, attendance_mode, exit_mode,
                                   created_by, created_at)
SELECT gen_random_uuid(), e.id, 1, true, 10.00, 'APOS_DESCONTO', false, false, 'AUTOMATICO', NULL, true, true,
       'GARCOM_RESPONSAVEL', 'AUTOMATICA', NULL, e.created_at
  FROM establishment e;

INSERT INTO operational_calendar (id, establishment_id, time_zone, cutoff, effective_from, created_by, created_at)
SELECT gen_random_uuid(), e.id, 'America/Sao_Paulo', time '06:00', e.created_at, NULL, e.created_at
  FROM establishment e;

ALTER TABLE establishment FORCE ROW LEVEL SECURITY;
