-- H16: envio atômico do pedido, preço capturado e encaminhamento durável à produção (FR-14–FR-16, CA-05–CA-07,
-- especificação §3.1, §3.2 e §4; D-03, D-04, D-13).
--   * O rascunho fica no aparelho de quem monta (NFR-11, especificação §2): o servidor só conhece o pedido enviado.
--   * customer_order é um envio confirmado. operation_key é a chave de idempotência gerada pelo aparelho para aquele
--     envio: repetir (clique duplo, timeout, reconexão) devolve o mesmo pedido; a mesma chave com outro conteúdo
--     (payload_hash diferente) ou de outra pessoa é recusada. A constraint única é a prova final contra duplicidade.
--   * order_line é a linha enviada: quantidade inteira (1–99), observação sem cobrança e o snapshot do produto no
--     envio (nome, preço, setor de preparo). Preço, quantidade e destino são imutáveis: correção é cancelamento
--     (H23) ou transferência (H25), nunca edição. Sem setor: dispensa produção.
--   * tab_allocation distribui as unidades da linha entre comandas (especificação §3.2). No envio, a linha inteira
--     vai para a comanda do pedido; transferência (H25) e cancelamento (H23) mexem só aqui.
--   * production_line é a entrada da linha na produção (uma sequência por linha, FR-19), criada pelo job
--     production.receber_pedido gravado no mesmo commit do envio. Sem setor entra pronta. Etapas seguintes: H18.

CREATE TABLE customer_order (
    id               uuid        PRIMARY KEY,
    establishment_id uuid        NOT NULL REFERENCES establishment (id),
    tab_id           uuid        NOT NULL,
    operation_key    uuid        NOT NULL,
    payload_hash     text        NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    line_count       integer     NOT NULL CHECK (line_count BETWEEN 1 AND 50),
    sent_by          uuid        NOT NULL REFERENCES user_identity (id),
    sent_at          timestamptz NOT NULL,
    correlation_id   text        CHECK (char_length(correlation_id) <= 100),
    UNIQUE (establishment_id, id),
    UNIQUE (establishment_id, operation_key),
    FOREIGN KEY (establishment_id, tab_id) REFERENCES tab (establishment_id, id)
);

CREATE INDEX customer_order_tab_idx ON customer_order (establishment_id, tab_id, sent_at);

ALTER TABLE customer_order ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer_order FORCE ROW LEVEL SECURITY;

CREATE POLICY customer_order_runtime_read ON customer_order
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

-- Só em comanda ativa, por quem está no contexto: defesa no banco além da trava da comanda na aplicação.
CREATE POLICY customer_order_runtime_insert ON customer_order
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND sent_by = current_identity_id()
                AND EXISTS (SELECT 1 FROM tab t WHERE t.id = tab_id AND t.closed_at IS NULL));

GRANT SELECT, INSERT ON customer_order TO ${app_role};

CREATE TABLE order_line (
    id               uuid          PRIMARY KEY,
    establishment_id uuid          NOT NULL REFERENCES establishment (id),
    order_id         uuid          NOT NULL,
    position         integer       NOT NULL CHECK (position BETWEEN 1 AND 50),
    product_id       uuid          NOT NULL,
    product_name     text          NOT NULL CHECK (char_length(product_name) BETWEEN 1 AND 120),
    unit_price       numeric(7, 2) NOT NULL CHECK (unit_price > 0),
    quantity         integer       NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    note             text          CHECK (char_length(note) BETWEEN 1 AND 140 AND note = btrim(note)),
    sector_id        uuid,
    UNIQUE (establishment_id, id),
    UNIQUE (order_id, position),
    FOREIGN KEY (establishment_id, order_id) REFERENCES customer_order (establishment_id, id),
    FOREIGN KEY (establishment_id, product_id) REFERENCES product (establishment_id, id),
    FOREIGN KEY (establishment_id, sector_id) REFERENCES sector (establishment_id, id)
);

ALTER TABLE order_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_line FORCE ROW LEVEL SECURITY;

CREATE POLICY order_line_runtime_read ON order_line
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

CREATE POLICY order_line_runtime_insert ON order_line
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id());

-- Linha enviada é imutável no caminho da aplicação: sem UPDATE nem DELETE.
GRANT SELECT, INSERT ON order_line TO ${app_role};

CREATE TABLE tab_allocation (
    id               uuid        PRIMARY KEY,
    establishment_id uuid        NOT NULL REFERENCES establishment (id),
    order_line_id    uuid        NOT NULL,
    tab_id           uuid        NOT NULL,
    quantity         integer     NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    created_at       timestamptz NOT NULL,
    UNIQUE (establishment_id, id),
    FOREIGN KEY (establishment_id, order_line_id) REFERENCES order_line (establishment_id, id),
    FOREIGN KEY (establishment_id, tab_id) REFERENCES tab (establishment_id, id)
);

CREATE INDEX tab_allocation_tab_idx ON tab_allocation (establishment_id, tab_id);
CREATE INDEX tab_allocation_line_idx ON tab_allocation (establishment_id, order_line_id);

ALTER TABLE tab_allocation ENABLE ROW LEVEL SECURITY;
ALTER TABLE tab_allocation FORCE ROW LEVEL SECURITY;

CREATE POLICY tab_allocation_runtime_read ON tab_allocation
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

CREATE POLICY tab_allocation_runtime_insert ON tab_allocation
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id()
                AND EXISTS (SELECT 1 FROM tab t WHERE t.id = tab_id AND t.closed_at IS NULL));

-- Transferência (H25) e cancelamento (H23) ganham as permissões de alteração nas suas histórias.
GRANT SELECT, INSERT ON tab_allocation TO ${app_role};

CREATE TABLE production_line (
    id               uuid        PRIMARY KEY,
    establishment_id uuid        NOT NULL REFERENCES establishment (id),
    order_line_id    uuid        NOT NULL,
    sector_id        uuid,
    quantity         integer     NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    status           text        NOT NULL CHECK (status IN ('AGUARDANDO_PREPARO', 'PRONTO')),
    received_at      timestamptz NOT NULL,
    version          bigint      NOT NULL DEFAULT 0,
    UNIQUE (establishment_id, id),
    -- Uma sequência de produção por linha enviada: reprocessar o job não duplica.
    UNIQUE (order_line_id),
    FOREIGN KEY (establishment_id, order_line_id) REFERENCES order_line (establishment_id, id),
    FOREIGN KEY (establishment_id, sector_id) REFERENCES sector (establishment_id, id),
    -- Sem setor entra pronta (FR-19); com setor, aguardando preparo.
    CHECK ((sector_id IS NULL) = (status = 'PRONTO'))
);

CREATE INDEX production_line_queue_idx ON production_line (establishment_id, sector_id, status, received_at);

ALTER TABLE production_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE production_line FORCE ROW LEVEL SECURITY;

CREATE POLICY production_line_runtime_read ON production_line
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

-- O job roda só com o contexto da unidade (sem identidade).
CREATE POLICY production_line_runtime_insert ON production_line
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id());

-- Etapas (em preparo, pronto, entregue) ganham UPDATE na H18.
GRANT SELECT, INSERT ON production_line TO ${app_role};
