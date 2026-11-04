-- H20 — Atendimento responsável ou central (FR-23, FR-26, FR-27, FR-68).
--
--   * A modalidade de atendimento já é capturada na abertura: a comanda guarda a versão da configuração (H14), e a
--     modalidade dessa versão vale para ela até o fim, mesmo que a unidade troque de modalidade depois.
--   * tab.responsible_id é o garçom responsável da comanda aberta na modalidade "garçom responsável": quem abre
--     assume; o gerente reatribui. Na modalidade "central" fica nulo: cada chamado é assumido por alguém.
--   * attendance_call é o aviso para quem atende: item pronto para entregar ou "não é possível atender" (problemas do
--     consumidor chegam com a H21). Um por linha de produção e comanda de destino, com a modalidade da comanda copiada
--     na criação. Garçom responsável: o dono é o responsável atual da comanda. Central: o chamado nasce sem dono e uma
--     pessoa o assume de forma exclusiva (claimed_by); o gerente reatribui. Ler não conclui: o aviso de item pronto
--     termina com a entrega, o de impossibilidade quando a produção retira a sinalização (ou o item é cancelado, H23).

ALTER TABLE tab ADD COLUMN responsible_id uuid REFERENCES user_identity (id);

-- Comandas já abertas na modalidade "garçom responsável": quem abriu é o responsável.
UPDATE tab t SET responsible_id = t.opened_by
  FROM unit_settings_version v
 WHERE v.id = t.settings_version_id AND v.attendance_mode = 'GARCOM_RESPONSAVEL';

-- Na abertura, o responsável só pode ser quem abre (ou ninguém, na central).
DROP POLICY tab_runtime_insert ON tab;
CREATE POLICY tab_runtime_insert ON tab
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND opened_by = current_identity_id()
                AND updated_by = current_identity_id() AND closed_at IS NULL AND access_until IS NULL
                AND (responsible_id IS NULL OR responsible_id = opened_by));

GRANT UPDATE (responsible_id) ON tab TO ${app_role};

CREATE TABLE attendance_call (
    id                 uuid        PRIMARY KEY,
    establishment_id   uuid        NOT NULL REFERENCES establishment (id),
    tab_id             uuid        NOT NULL,
    production_line_id uuid        NOT NULL,
    kind               text        NOT NULL CHECK (kind IN ('ITEM_PRONTO', 'IMPOSSIBILIDADE')),
    mode               text        NOT NULL CHECK (mode IN ('GARCOM_RESPONSAVEL', 'CENTRAL')),
    created_at         timestamptz NOT NULL,
    claimed_by         uuid        REFERENCES user_identity (id),
    claimed_at         timestamptz,
    concluded_at       timestamptz,
    concluded_by       uuid        REFERENCES user_identity (id),
    resolution         text        CHECK (resolution IN ('ENTREGUE', 'SINALIZACAO_RETIRADA')),
    version            bigint      NOT NULL DEFAULT 0,
    UNIQUE (establishment_id, id),
    FOREIGN KEY (establishment_id, tab_id) REFERENCES tab (establishment_id, id),
    FOREIGN KEY (establishment_id, production_line_id) REFERENCES production_line (establishment_id, id),
    CHECK ((claimed_by IS NULL) = (claimed_at IS NULL)),
    -- Só a central tem dono por chamado; no garçom responsável o dono é o responsável da comanda.
    CHECK (mode = 'CENTRAL' OR claimed_by IS NULL),
    CHECK ((concluded_at IS NULL) = (resolution IS NULL)),
    CHECK (concluded_by IS NULL OR concluded_at IS NOT NULL),
    CHECK (resolution IS NULL OR (kind = 'ITEM_PRONTO') = (resolution = 'ENTREGUE'))
);

-- Um aviso em aberto por linha, comanda e tipo; o mesmo item pode ser sinalizado de novo depois de retirado.
CREATE UNIQUE INDEX attendance_call_open_uq ON attendance_call (production_line_id, tab_id, kind)
    WHERE concluded_at IS NULL;
CREATE INDEX attendance_call_open_idx ON attendance_call (establishment_id, created_at) WHERE concluded_at IS NULL;

ALTER TABLE attendance_call ENABLE ROW LEVEL SECURITY;
ALTER TABLE attendance_call FORCE ROW LEVEL SECURITY;

CREATE POLICY attendance_call_runtime_read ON attendance_call
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

-- O job de recebimento (item sem preparo nasce pronto) cria avisos no contexto da unidade, sem identidade.
CREATE POLICY attendance_call_runtime_insert ON attendance_call
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND claimed_by IS NULL AND concluded_at IS NULL);

-- Assumir, reatribuir e concluir: só a equipe, com identidade no contexto.
CREATE POLICY attendance_call_runtime_update ON attendance_call
    FOR UPDATE TO ${app_role}
    USING (establishment_id = current_establishment_id() AND concluded_at IS NULL)
    WITH CHECK (establishment_id = current_establishment_id() AND current_identity_id() IS NOT NULL);

-- Histórico: sem DELETE; comanda, linha, tipo, modalidade e criação são imutáveis.
GRANT SELECT, INSERT ON attendance_call TO ${app_role};
GRANT UPDATE (claimed_by, claimed_at, concluded_at, concluded_by, resolution, version) ON attendance_call
    TO ${app_role};

-- Itens já prontos ou sinalizados nas comandas abertas ganham o aviso, com a modalidade da comanda.
INSERT INTO attendance_call (id, establishment_id, tab_id, production_line_id, kind, mode, created_at)
SELECT gen_random_uuid(), p.establishment_id, a.tab_id, p.id,
       CASE WHEN p.impossible_at IS NOT NULL THEN 'IMPOSSIBILIDADE' ELSE 'ITEM_PRONTO' END,
       v.attendance_mode, COALESCE(p.impossible_at, p.ready_at)
  FROM production_line p
  JOIN tab_allocation a ON a.establishment_id = p.establishment_id AND a.order_line_id = p.order_line_id
  JOIN tab t ON t.establishment_id = a.establishment_id AND t.id = a.tab_id AND t.closed_at IS NULL
  JOIN unit_settings_version v ON v.id = t.settings_version_id
 WHERE p.status = 'PRONTO' OR p.impossible_at IS NOT NULL
ON CONFLICT DO NOTHING;

-- Novo tópico de aviso da equipe (H17): chamados de atendimento. O consumidor não recebe.
ALTER TABLE sync_change DROP CONSTRAINT sync_change_topic_check;
ALTER TABLE sync_change ADD CONSTRAINT sync_change_topic_check
    CHECK (topic IN ('COMANDA', 'PEDIDO', 'PRODUCAO', 'ATENDIMENTO'));
