-- H25: transferir unidades entre comandas sem duplicar produção (FR-17, RN-01, CA-10, D-05, especificação §3.2).
--
--   * item_transfer é o fato da transferência: quantas unidades de qual linha saíram de qual comanda para qual, com
--     motivo, quem, quando e em que etapa a produção estava. Nunca é apagado nem editado.
--   * A linha enviada, o preço, a observação e a linha de produção ficam como estão: só as alocações mudam (a origem
--     diminui, o destino aumenta ou ganha uma alocação nova), e a soma das alocações continua igual. A conta (H22) das
--     duas comandas muda no mesmo commit.
--   * A transferência incrementa a versão da linha de produção (contrato da entrega, H18) e grava o passo TRANSFERIDO
--     no histórico, sem mudar a etapa.
--   * As duas comandas precisam estar abertas (policies); a aplicação as trava em ordem de id antes da linha.
--   * Avisos de atendimento (H20) que ficam numa comanda sem unidades da linha terminam com a resolução TRANSFERIDO;
--     a comanda de destino ganha o seu aviso.

CREATE TABLE item_transfer (
    id                 uuid        PRIMARY KEY,
    establishment_id   uuid        NOT NULL REFERENCES establishment (id),
    order_line_id      uuid        NOT NULL,
    production_line_id uuid        NOT NULL,
    from_tab_id        uuid        NOT NULL,
    to_tab_id          uuid        NOT NULL,
    quantity           integer     NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    remaining_in_from  integer     NOT NULL CHECK (remaining_in_from BETWEEN 0 AND 98),
    stage              text        NOT NULL CHECK (stage IN ('AGUARDANDO_PREPARO', 'EM_PREPARO', 'PRONTO',
                                                             'ENTREGUE')),
    reason             text        NOT NULL CHECK (char_length(reason) BETWEEN 5 AND 300 AND reason = btrim(reason)),
    transferred_by     uuid        NOT NULL REFERENCES user_identity (id),
    transferred_at     timestamptz NOT NULL,
    correlation_id     text        CHECK (char_length(correlation_id) <= 100),
    UNIQUE (establishment_id, id),
    FOREIGN KEY (establishment_id, order_line_id) REFERENCES order_line (establishment_id, id),
    FOREIGN KEY (establishment_id, production_line_id) REFERENCES production_line (establishment_id, id),
    FOREIGN KEY (establishment_id, from_tab_id) REFERENCES tab (establishment_id, id),
    FOREIGN KEY (establishment_id, to_tab_id) REFERENCES tab (establishment_id, id),
    CHECK (from_tab_id <> to_tab_id)
);

CREATE INDEX item_transfer_line_idx ON item_transfer (establishment_id, order_line_id, transferred_at);
CREATE INDEX item_transfer_from_idx ON item_transfer (establishment_id, from_tab_id, transferred_at);
CREATE INDEX item_transfer_to_idx ON item_transfer (establishment_id, to_tab_id, transferred_at);

ALTER TABLE item_transfer ENABLE ROW LEVEL SECURITY;
ALTER TABLE item_transfer FORCE ROW LEVEL SECURITY;

CREATE POLICY item_transfer_runtime_read ON item_transfer
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

-- Só a equipe, em nome próprio, entre duas comandas ativas.
CREATE POLICY item_transfer_runtime_insert ON item_transfer
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND transferred_by = current_identity_id()
                AND EXISTS (SELECT 1 FROM tab t WHERE t.id = from_tab_id AND t.closed_at IS NULL)
                AND EXISTS (SELECT 1 FROM tab t WHERE t.id = to_tab_id AND t.closed_at IS NULL));

GRANT SELECT, INSERT ON item_transfer TO ${app_role};

-- Histórico da produção ganha a transferência, que não muda a etapa e sempre tem motivo.
ALTER TABLE production_event DROP CONSTRAINT production_event_action_check;
ALTER TABLE production_event ADD CONSTRAINT production_event_action_check
    CHECK (action IN ('INICIADO', 'PRONTO', 'ENTREGUE', 'IMPOSSIVEL', 'IMPOSSIVEL_RETIRADO', 'CANCELADO',
                      'ENTREGA_DESFEITA', 'OCORRENCIA_ENTREGA', 'TRANSFERIDO'));
ALTER TABLE production_event ADD CONSTRAINT production_event_transferido_check
    CHECK (action <> 'TRANSFERIDO' OR (from_status = to_status AND reason IS NOT NULL));

-- Avisos de atendimento que saem da comanda com as unidades terminam como TRANSFERIDO.
ALTER TABLE attendance_call DROP CONSTRAINT attendance_call_resolution_check;
ALTER TABLE attendance_call DROP CONSTRAINT attendance_call_resolution_kind_check;
ALTER TABLE attendance_call
    ADD CONSTRAINT attendance_call_resolution_check
        CHECK (resolution IN ('ENTREGUE', 'SINALIZACAO_RETIRADA', 'CANCELADO', 'TRANSFERIDO')),
    ADD CONSTRAINT attendance_call_resolution_kind_check
        CHECK (resolution IS NULL OR resolution IN ('CANCELADO', 'TRANSFERIDO')
               OR (kind = 'ITEM_PRONTO') = (resolution = 'ENTREGUE'));
