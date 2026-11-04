-- H18 — Fila de produção e entrega por linha (FR-18–FR-22, CA-08, D-06).
--
--   * production_line ganha as etapas EM_PREPARO e ENTREGUE, o instante de cada etapa e a sinalização "não é
--     possível atender" (motivo, quando, quem). Sem setor a linha nasce pronta e só pode ser entregue. Linha
--     impossível só existe antes do pronto e não avança até a sinalização ser retirada (ou a linha cancelada, H23).
--   * production_event é o histórico das etapas: quem, quando, de onde para onde e o motivo. Só INSERT no runtime.
--   * sector.delay_limit_minutes é o limite de atraso do setor (FR-22): só destaque visual, nada é automático.
--   * A versão da linha (production_line.version) é o contrato da entrega com a transferência (H25) e o cancelamento
--     (H23): quem mexer nas alocações da linha incrementa a versão, e a entrega com a versão lida antes é recusada.

ALTER TABLE sector
    ADD COLUMN delay_limit_minutes integer NOT NULL DEFAULT 15 CHECK (delay_limit_minutes BETWEEN 1 AND 240);

GRANT UPDATE (delay_limit_minutes) ON sector TO ${app_role};

ALTER TABLE production_line DROP CONSTRAINT production_line_status_check;
ALTER TABLE production_line DROP CONSTRAINT production_line_check;

ALTER TABLE production_line
    ADD COLUMN status_since      timestamptz,
    ADD COLUMN started_at        timestamptz,
    ADD COLUMN ready_at          timestamptz,
    ADD COLUMN delivered_at      timestamptz,
    ADD COLUMN impossible_at     timestamptz,
    ADD COLUMN impossible_by     uuid REFERENCES user_identity (id),
    ADD COLUMN impossible_reason text CHECK (char_length(impossible_reason) BETWEEN 5 AND 300
                                            AND impossible_reason = btrim(impossible_reason));

-- Linhas da H16: a etapa atual começou no recebimento; sem setor, pronta desde então.
UPDATE production_line SET status_since = received_at,
                           ready_at = CASE WHEN status = 'PRONTO' THEN received_at END;

ALTER TABLE production_line
    ALTER COLUMN status_since SET NOT NULL,
    ADD CONSTRAINT production_line_status_check
        CHECK (status IN ('AGUARDANDO_PREPARO', 'EM_PREPARO', 'PRONTO', 'ENTREGUE')),
    -- Sem setor não passa pela produção: pronta desde o recebimento, depois entregue.
    ADD CONSTRAINT production_line_sem_setor_check
        CHECK (sector_id IS NOT NULL OR status IN ('PRONTO', 'ENTREGUE')),
    ADD CONSTRAINT production_line_etapas_check
        CHECK ((started_at IS NULL OR started_at >= received_at)
               AND (status <> 'EM_PREPARO' OR started_at IS NOT NULL)
               AND (status NOT IN ('PRONTO', 'ENTREGUE') OR ready_at IS NOT NULL)
               AND ((status = 'ENTREGUE') = (delivered_at IS NOT NULL))),
    ADD CONSTRAINT production_line_impossivel_check
        CHECK ((impossible_at IS NULL) = (impossible_reason IS NULL)
               AND (impossible_at IS NULL) = (impossible_by IS NULL)
               AND (impossible_at IS NULL OR status IN ('AGUARDANDO_PREPARO', 'EM_PREPARO')));

CREATE INDEX production_line_status_idx ON production_line (establishment_id, status, received_at);

-- Etapas por quem está no contexto da unidade com identidade (equipe); o job de recebimento só insere.
CREATE POLICY production_line_runtime_update ON production_line
    FOR UPDATE TO ${app_role}
    USING (establishment_id = current_establishment_id())
    WITH CHECK (establishment_id = current_establishment_id() AND current_identity_id() IS NOT NULL);

-- Só as colunas das etapas: linha, setor, quantidade e recebimento continuam imutáveis.
GRANT UPDATE (status, status_since, started_at, ready_at, delivered_at, impossible_at, impossible_by,
              impossible_reason, version) ON production_line TO ${app_role};

CREATE TABLE production_event (
    id                 uuid        PRIMARY KEY,
    establishment_id   uuid        NOT NULL REFERENCES establishment (id),
    production_line_id uuid        NOT NULL,
    action             text        NOT NULL CHECK (action IN ('INICIADO', 'PRONTO', 'ENTREGUE', 'IMPOSSIVEL',
                                                              'IMPOSSIVEL_RETIRADO')),
    from_status        text        NOT NULL,
    to_status          text        NOT NULL,
    reason             text        CHECK (char_length(reason) BETWEEN 5 AND 300),
    line_version       bigint      NOT NULL,
    performed_by       uuid        NOT NULL REFERENCES user_identity (id),
    performed_at       timestamptz NOT NULL,
    correlation_id     text        CHECK (char_length(correlation_id) <= 100),
    FOREIGN KEY (establishment_id, production_line_id) REFERENCES production_line (establishment_id, id)
);

CREATE INDEX production_event_line_idx ON production_event (establishment_id, production_line_id, performed_at);

ALTER TABLE production_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE production_event FORCE ROW LEVEL SECURITY;

CREATE POLICY production_event_runtime_read ON production_event
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

CREATE POLICY production_event_runtime_insert ON production_event
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND performed_by = current_identity_id());

-- Histórico: sem UPDATE nem DELETE no runtime.
GRANT SELECT, INSERT ON production_event TO ${app_role};
