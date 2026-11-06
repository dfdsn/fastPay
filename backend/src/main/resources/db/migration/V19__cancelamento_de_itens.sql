-- H23: cancelar quantidade inteira e alertar a produção (FR-20, FR-21, CA-09, D-06, especificação §3.2).
--
--   * item_cancellation é o fato do cancelamento: quantas unidades de qual linha, em qual comanda, com motivo, quem,
--     quando e em que etapa a produção estava. Nunca é apagado nem editado (só a ciência da produção é gravada depois).
--     Em preparo ou pronto (linha com setor), a produção precisa dar ciência (requires_ack); aguardando preparo, a fila
--     só é ajustada; entregue, o cancelamento é só a correção financeira, com o histórico da entrega preservado.
--     A ciência nunca condiciona a correção: a conta já muda no commit do cancelamento.
--   * tab_allocation.quantity passa a ser as unidades ativas da linha na comanda: o cancelamento a diminui (até zero),
--     e o consumo da conta (H22) acompanha. Original = order_line.quantity; cancelado = original − soma das alocações.
--   * production_line guarda cancelled_quantity; quantity continua sendo a original. Cancelamento total antes da
--     entrega encerra a linha (CANCELADA), retirando a sinalização de impossibilidade; depois da entrega a linha
--     continua ENTREGUE. Os instantes das etapas (preparo, pronto) ficam como estavam. Todo cancelamento incrementa a
--     versão da linha (contrato da entrega, H18).
--   * Avisos de atendimento (H20) de linha cancelada por inteiro terminam com a resolução CANCELADO.
--   * Comanda encerrada não recebe cancelamento (policy); "em fechamento" (H28) e cobrança existente (H30) chegam com
--     as suas histórias.

CREATE TABLE item_cancellation (
    id                 uuid        PRIMARY KEY,
    establishment_id   uuid        NOT NULL REFERENCES establishment (id),
    order_line_id      uuid        NOT NULL,
    production_line_id uuid        NOT NULL,
    tab_id             uuid        NOT NULL,
    quantity           integer     NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    remaining_in_tab   integer     NOT NULL CHECK (remaining_in_tab BETWEEN 0 AND 98),
    stage              text        NOT NULL CHECK (stage IN ('AGUARDANDO_PREPARO', 'EM_PREPARO', 'PRONTO',
                                                             'ENTREGUE')),
    reason             text        NOT NULL CHECK (char_length(reason) BETWEEN 5 AND 300 AND reason = btrim(reason)),
    cancelled_by       uuid        NOT NULL REFERENCES user_identity (id),
    cancelled_at       timestamptz NOT NULL,
    requires_ack       boolean     NOT NULL,
    acknowledged_by    uuid        REFERENCES user_identity (id),
    acknowledged_at    timestamptz,
    correlation_id     text        CHECK (char_length(correlation_id) <= 100),
    UNIQUE (establishment_id, id),
    FOREIGN KEY (establishment_id, order_line_id) REFERENCES order_line (establishment_id, id),
    FOREIGN KEY (establishment_id, production_line_id) REFERENCES production_line (establishment_id, id),
    FOREIGN KEY (establishment_id, tab_id) REFERENCES tab (establishment_id, id),
    CHECK ((acknowledged_by IS NULL) = (acknowledged_at IS NULL)),
    CHECK (requires_ack OR acknowledged_at IS NULL),
    CHECK (acknowledged_at IS NULL OR acknowledged_at >= cancelled_at),
    -- Ciência só para o que estava em preparo ou pronto.
    CHECK (NOT requires_ack OR stage IN ('EM_PREPARO', 'PRONTO'))
);

CREATE INDEX item_cancellation_line_idx ON item_cancellation (establishment_id, order_line_id, cancelled_at);
CREATE INDEX item_cancellation_pending_idx ON item_cancellation (establishment_id, cancelled_at)
    WHERE requires_ack AND acknowledged_at IS NULL;

ALTER TABLE item_cancellation ENABLE ROW LEVEL SECURITY;
ALTER TABLE item_cancellation FORCE ROW LEVEL SECURITY;

CREATE POLICY item_cancellation_runtime_read ON item_cancellation
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

-- Só a equipe, em nome próprio, em comanda ativa.
CREATE POLICY item_cancellation_runtime_insert ON item_cancellation
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND cancelled_by = current_identity_id()
                AND acknowledged_at IS NULL
                AND EXISTS (SELECT 1 FROM tab t WHERE t.id = tab_id AND t.closed_at IS NULL));

-- Ciência: só a pendente, uma vez, por quem está no contexto.
CREATE POLICY item_cancellation_runtime_update ON item_cancellation
    FOR UPDATE TO ${app_role}
    USING (establishment_id = current_establishment_id() AND requires_ack AND acknowledged_at IS NULL)
    WITH CHECK (establishment_id = current_establishment_id() AND acknowledged_by = current_identity_id());

GRANT SELECT, INSERT ON item_cancellation TO ${app_role};
GRANT UPDATE (acknowledged_by, acknowledged_at) ON item_cancellation TO ${app_role};

-- Alocação: unidades ativas na comanda; zero quando tudo foi cancelado (a linha do histórico fica).
ALTER TABLE tab_allocation DROP CONSTRAINT tab_allocation_quantity_check;
ALTER TABLE tab_allocation ADD CONSTRAINT tab_allocation_quantity_check CHECK (quantity BETWEEN 0 AND 99);

CREATE POLICY tab_allocation_runtime_update ON tab_allocation
    FOR UPDATE TO ${app_role}
    USING (establishment_id = current_establishment_id()
           AND EXISTS (SELECT 1 FROM tab t WHERE t.id = tab_id AND t.closed_at IS NULL))
    WITH CHECK (establishment_id = current_establishment_id() AND current_identity_id() IS NOT NULL);

GRANT UPDATE (quantity) ON tab_allocation TO ${app_role};

-- Linha de produção: unidades canceladas e a etapa terminal CANCELADA.
ALTER TABLE production_line
    ADD COLUMN cancelled_quantity integer NOT NULL DEFAULT 0,
    ADD COLUMN cancelled_at       timestamptz,
    ADD CONSTRAINT production_line_cancelado_check
        CHECK (cancelled_quantity BETWEEN 0 AND quantity
               AND (cancelled_quantity = 0) = (cancelled_at IS NULL)
               -- Tudo cancelado antes da entrega encerra a linha; entregue continua entregue.
               AND ((status = 'CANCELADA') = (cancelled_quantity = quantity AND delivered_at IS NULL)));

ALTER TABLE production_line DROP CONSTRAINT production_line_status_check;
ALTER TABLE production_line DROP CONSTRAINT production_line_sem_setor_check;
ALTER TABLE production_line
    ADD CONSTRAINT production_line_status_check
        CHECK (status IN ('AGUARDANDO_PREPARO', 'EM_PREPARO', 'PRONTO', 'ENTREGUE', 'CANCELADA')),
    ADD CONSTRAINT production_line_sem_setor_check
        CHECK (sector_id IS NOT NULL OR status IN ('PRONTO', 'ENTREGUE', 'CANCELADA'));

GRANT UPDATE (cancelled_quantity, cancelled_at) ON production_line TO ${app_role};

-- Histórico da produção ganha o cancelamento.
ALTER TABLE production_event DROP CONSTRAINT production_event_action_check;
ALTER TABLE production_event ADD CONSTRAINT production_event_action_check
    CHECK (action IN ('INICIADO', 'PRONTO', 'ENTREGUE', 'IMPOSSIVEL', 'IMPOSSIVEL_RETIRADO', 'CANCELADO'));

-- Avisos de atendimento de linha cancelada por inteiro terminam como CANCELADO (pronto ou impossibilidade).
DO $$
DECLARE
    c record;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
              WHERE conrelid = 'attendance_call'::regclass AND contype = 'c'
                AND pg_get_constraintdef(oid) LIKE '%resolution%'
                AND pg_get_constraintdef(oid) NOT LIKE '%concluded_at%'
    LOOP
        EXECUTE format('ALTER TABLE attendance_call DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

ALTER TABLE attendance_call
    ADD CONSTRAINT attendance_call_resolution_check
        CHECK (resolution IN ('ENTREGUE', 'SINALIZACAO_RETIRADA', 'CANCELADO')),
    ADD CONSTRAINT attendance_call_resolution_kind_check
        CHECK (resolution IS NULL OR resolution = 'CANCELADO'
               OR (kind = 'ITEM_PRONTO') = (resolution = 'ENTREGUE'));
