-- H13: catálogo, setores, disponibilidade, foto de produto e logo (FR-13, FR-14, FR-18, FR-67; especificação §10).
--   * file_object guarda os metadados de uma imagem já reprocessada pelo servidor (JPEG ou PNG, sem metadados do
--     original); os bytes ficam no storage (local em dev/HML, S3 na H49) sob uma chave aleatória. Remover não apaga
--     a linha nem os bytes: marca removed_at. Retenção e limpeza física ficam para T-04/P-08.
--   * sector é o destino de preparo (cozinha, bar ou outro). Arquivar preserva a linha para pedidos antigos.
--   * product tem nome, categoria, preço, descrição opcional, disponibilidade, foto opcional e setor de preparo;
--     sem setor = dispensa de produção. Arquivar tira do catálogo sem apagar (pedidos futuros guardam o preço
--     capturado no envio, FR-16). Nomes únicos entre os ativos da unidade, sem diferenciar maiúsculas.
--   * establishment ganha o logo opcional. Quem pode mudar cada coisa é conferido na aplicação
--     (identity.Permission), como nas demais tabelas da unidade.

CREATE TABLE file_object (
    id                     uuid        PRIMARY KEY,
    establishment_id       uuid        NOT NULL REFERENCES establishment (id),
    purpose                text        NOT NULL CHECK (purpose IN ('FOTO_PRODUTO', 'LOGO')),
    -- Formato guardado (reprocessado) e o formato que chegou, detectado pelo conteúdo.
    content_type           text        NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png')),
    original_content_type  text        NOT NULL CHECK (original_content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    original_size_bytes    integer     NOT NULL CHECK (original_size_bytes > 0),
    size_bytes             integer     NOT NULL CHECK (size_bytes > 0),
    sha256                 text        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    width                  integer     NOT NULL CHECK (width > 0),
    height                 integer     NOT NULL CHECK (height > 0),
    storage_key            text        NOT NULL UNIQUE CHECK (char_length(storage_key) BETWEEN 1 AND 200),
    created_by             uuid        NOT NULL REFERENCES user_identity (id),
    created_at             timestamptz NOT NULL,
    removed_at             timestamptz,
    removed_by             uuid        REFERENCES user_identity (id),
    UNIQUE (establishment_id, id),
    CHECK ((removed_at IS NULL) = (removed_by IS NULL))
);

ALTER TABLE file_object ENABLE ROW LEVEL SECURITY;
ALTER TABLE file_object FORCE ROW LEVEL SECURITY;

CREATE POLICY file_object_runtime_read ON file_object
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

CREATE POLICY file_object_runtime_insert ON file_object
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND created_by = current_identity_id()
                AND removed_at IS NULL);

CREATE POLICY file_object_runtime_remove ON file_object
    FOR UPDATE TO ${app_role}
    USING (establishment_id = current_establishment_id() AND removed_at IS NULL)
    WITH CHECK (establishment_id = current_establishment_id() AND removed_by = current_identity_id());

GRANT SELECT, INSERT ON file_object TO ${app_role};
GRANT UPDATE (removed_at, removed_by) ON file_object TO ${app_role};

CREATE TABLE sector (
    id               uuid        PRIMARY KEY,
    establishment_id uuid        NOT NULL REFERENCES establishment (id),
    name             text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 60 AND name = btrim(name)),
    created_by       uuid        NOT NULL REFERENCES user_identity (id),
    created_at       timestamptz NOT NULL,
    updated_by       uuid        NOT NULL REFERENCES user_identity (id),
    updated_at       timestamptz NOT NULL,
    archived_at      timestamptz,
    version          bigint      NOT NULL DEFAULT 0,
    UNIQUE (establishment_id, id)
);

CREATE UNIQUE INDEX sector_active_name_uq ON sector (establishment_id, lower(name)) WHERE archived_at IS NULL;

ALTER TABLE sector ENABLE ROW LEVEL SECURITY;
ALTER TABLE sector FORCE ROW LEVEL SECURITY;

CREATE POLICY sector_runtime_read ON sector
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

CREATE POLICY sector_runtime_insert ON sector
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND created_by = current_identity_id()
                AND archived_at IS NULL);

CREATE POLICY sector_runtime_update ON sector
    FOR UPDATE TO ${app_role}
    USING (establishment_id = current_establishment_id() AND archived_at IS NULL)
    WITH CHECK (establishment_id = current_establishment_id() AND updated_by = current_identity_id());

GRANT SELECT, INSERT ON sector TO ${app_role};
GRANT UPDATE (name, updated_by, updated_at, archived_at, version) ON sector TO ${app_role};

CREATE TABLE product (
    id               uuid          PRIMARY KEY,
    establishment_id uuid          NOT NULL REFERENCES establishment (id),
    name             text          NOT NULL CHECK (char_length(name) BETWEEN 1 AND 120 AND name = btrim(name)),
    category         text          NOT NULL CHECK (char_length(category) BETWEEN 1 AND 60 AND category = btrim(category)),
    description      text          CHECK (char_length(description) BETWEEN 1 AND 500),
    -- Preço fixo em reais; o valor da conta é capturado no envio do pedido (FR-16, H16).
    price            numeric(7, 2) NOT NULL CHECK (price > 0),
    -- Destino de preparo; sem setor o produto dispensa produção.
    sector_id        uuid,
    available        boolean       NOT NULL DEFAULT true,
    photo_file_id    uuid,
    created_by       uuid          NOT NULL REFERENCES user_identity (id),
    created_at       timestamptz   NOT NULL,
    updated_by       uuid          NOT NULL REFERENCES user_identity (id),
    updated_at       timestamptz   NOT NULL,
    archived_at      timestamptz,
    version          bigint        NOT NULL DEFAULT 0,
    UNIQUE (establishment_id, id),
    FOREIGN KEY (establishment_id, sector_id) REFERENCES sector (establishment_id, id),
    FOREIGN KEY (establishment_id, photo_file_id) REFERENCES file_object (establishment_id, id)
);

CREATE UNIQUE INDEX product_active_name_uq ON product (establishment_id, lower(name)) WHERE archived_at IS NULL;
CREATE INDEX product_listing_idx ON product (establishment_id, category, name) WHERE archived_at IS NULL;
CREATE INDEX product_sector_idx ON product (establishment_id, sector_id) WHERE archived_at IS NULL;

ALTER TABLE product ENABLE ROW LEVEL SECURITY;
ALTER TABLE product FORCE ROW LEVEL SECURITY;

CREATE POLICY product_runtime_read ON product
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

CREATE POLICY product_runtime_insert ON product
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id() AND created_by = current_identity_id()
                AND archived_at IS NULL);

CREATE POLICY product_runtime_update ON product
    FOR UPDATE TO ${app_role}
    USING (establishment_id = current_establishment_id() AND archived_at IS NULL)
    WITH CHECK (establishment_id = current_establishment_id() AND updated_by = current_identity_id());

GRANT SELECT, INSERT ON product TO ${app_role};
GRANT UPDATE (name, category, description, price, sector_id, available, photo_file_id, updated_by, updated_at,
              archived_at, version) ON product TO ${app_role};

-- Logo opcional da unidade, sempre um arquivo da própria unidade.
ALTER TABLE establishment ADD COLUMN logo_file_id uuid;
ALTER TABLE establishment ADD CONSTRAINT establishment_logo_fk
    FOREIGN KEY (id, logo_file_id) REFERENCES file_object (establishment_id, id);

CREATE POLICY establishment_runtime_logo_update ON establishment
    FOR UPDATE TO ${app_role}
    USING (id = current_establishment_id())
    WITH CHECK (id = current_establishment_id());

GRANT UPDATE (logo_file_id, version) ON establishment TO ${app_role};
