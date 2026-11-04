-- H17: sincronização operacional por SSE e polling (FR-27, NFR-02, NFR-06, NFR-11; especificação §5, §9 e §11).
--   * sync_change é o registro de mudanças da unidade: só avisa QUE algo mudou (tópico e comanda), nunca o conteúdo.
--     Quem recebe o aviso relê pela API autorizada (leitura canônica); o aviso não substitui a leitura.
--   * A sequência (seq) é densa e própria de cada unidade, e cresce na ordem dos commits: sync_cursor guarda a última e
--     o incremento trava a linha da unidade até o commit da transação que publica. Com isso, quem leu last_seq = N sabe
--     que todas as mudanças até N já estão gravadas, e um buraco entre o cursor do cliente e N só existe se as linhas
--     foram apagadas pela retenção: aí o cliente refaz a leitura completa (cursor perdido).
--   * A mudança é gravada na mesma transação do fato: rollback do fato desfaz o aviso e a sequência.
--   * Retenção curta (fastpay.sync.retention): apagada pelo próprio caminho de publicação, sempre no contexto da unidade.

CREATE TABLE sync_cursor (
    establishment_id uuid   PRIMARY KEY REFERENCES establishment (id),
    last_seq         bigint NOT NULL CHECK (last_seq >= 1)
);

ALTER TABLE sync_cursor ENABLE ROW LEVEL SECURITY;
ALTER TABLE sync_cursor FORCE ROW LEVEL SECURITY;

CREATE POLICY sync_cursor_runtime_read ON sync_cursor
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

CREATE POLICY sync_cursor_runtime_insert ON sync_cursor
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id());

CREATE POLICY sync_cursor_runtime_update ON sync_cursor
    FOR UPDATE TO ${app_role}
    USING (establishment_id = current_establishment_id())
    WITH CHECK (establishment_id = current_establishment_id());

GRANT SELECT, INSERT, UPDATE ON sync_cursor TO ${app_role};

CREATE TABLE sync_change (
    establishment_id uuid        NOT NULL REFERENCES establishment (id),
    seq              bigint      NOT NULL CHECK (seq >= 1),
    topic            text        NOT NULL CHECK (topic IN ('COMANDA', 'PEDIDO', 'PRODUCAO')),
    tab_id           uuid,
    changed_at       timestamptz NOT NULL,
    PRIMARY KEY (establishment_id, seq),
    FOREIGN KEY (establishment_id, tab_id) REFERENCES tab (establishment_id, id)
);

CREATE INDEX sync_change_retention_idx ON sync_change (establishment_id, changed_at);

ALTER TABLE sync_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE sync_change FORCE ROW LEVEL SECURITY;

CREATE POLICY sync_change_runtime_read ON sync_change
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

CREATE POLICY sync_change_runtime_insert ON sync_change
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id());

-- Só a retenção apaga, e só da própria unidade. Aviso gravado não muda.
CREATE POLICY sync_change_runtime_delete ON sync_change
    FOR DELETE TO ${app_role}
    USING (establishment_id = current_establishment_id());

GRANT SELECT, INSERT, DELETE ON sync_change TO ${app_role};
