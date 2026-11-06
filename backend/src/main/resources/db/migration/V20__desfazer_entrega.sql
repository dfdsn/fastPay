-- H24: desfazer entrega com histórico (FR-70, D-07, especificação §3.2 e §9).
--
--   * Desfazer volta a linha ENTREGUE para PRONTO (delivered_at limpo; ready_at e os instantes do preparo ficam). Só
--     acontece com todas as comandas com unidades ativas da linha abertas; a aplicação trava essas comandas (a mesma
--     trava do envio, do cancelamento, da conta e do fechamento) antes de travar a linha.
--   * O histórico ganha o passo ENTREGA_DESFEITA, com motivo, que aponta para o ENTREGUE cuja amostra de tempo deixa de
--     valer (invalidates_event_id). Nada é apagado nem editado: a métrica (H43) usa a última entrega não invalidada, e a
--     nova entrega válida redefine o tempo.
--   * Com alguma comanda de destino encerrada (paga), a entrega não é desfeita: registra-se OCORRENCIA_ENTREGA, com
--     motivo, sem mudar a linha, a conta ou o pagamento e sem devolução automática.

ALTER TABLE production_event ADD CONSTRAINT production_event_establishment_id_key UNIQUE (establishment_id, id);

ALTER TABLE production_event
    ADD COLUMN invalidates_event_id uuid,
    ADD CONSTRAINT production_event_invalidates_fk
        FOREIGN KEY (establishment_id, invalidates_event_id) REFERENCES production_event (establishment_id, id);

ALTER TABLE production_event DROP CONSTRAINT production_event_action_check;
ALTER TABLE production_event ADD CONSTRAINT production_event_action_check
    CHECK (action IN ('INICIADO', 'PRONTO', 'ENTREGUE', 'IMPOSSIVEL', 'IMPOSSIVEL_RETIRADO', 'CANCELADO',
                      'ENTREGA_DESFEITA', 'OCORRENCIA_ENTREGA'));

ALTER TABLE production_event
    -- Só o desfazer aponta para a entrega que invalida, e sempre aponta.
    ADD CONSTRAINT production_event_invalida_check
        CHECK ((action = 'ENTREGA_DESFEITA') = (invalidates_event_id IS NOT NULL)),
    -- Desfazer e ocorrência sempre têm motivo; desfazer vai de entregue a pronto; ocorrência não muda a etapa.
    ADD CONSTRAINT production_event_desfazer_check
        CHECK ((action <> 'ENTREGA_DESFEITA' OR (from_status = 'ENTREGUE' AND to_status = 'PRONTO'
                                                  AND reason IS NOT NULL))
               AND (action <> 'OCORRENCIA_ENTREGA' OR (from_status = 'ENTREGUE' AND to_status = 'ENTREGUE'
                                                        AND reason IS NOT NULL)));

-- Uma entrega é invalidada uma vez só.
CREATE UNIQUE INDEX production_event_invalidates_idx ON production_event (establishment_id, invalidates_event_id)
    WHERE invalidates_event_id IS NOT NULL;

-- Entregues mais recentes, para a tela de desfazer.
CREATE INDEX production_line_delivered_idx ON production_line (establishment_id, delivered_at DESC)
    WHERE status = 'ENTREGUE';
