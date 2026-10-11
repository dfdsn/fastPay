-- H03: primeira tabela global e primeira tabela por estabelecimento, ambas com RLS forçado.
-- Convenções (especificação §3):
--   * Tabela do estabelecimento: establishment_id NOT NULL, RLS ENABLE + FORCE e policy por
--     current_establishment_id(); sem contexto nada é visível nem gravável.
--   * Relação entre tabelas do estabelecimento: UNIQUE (establishment_id, id) no pai e FK composta
--     (establishment_id, pai_id) no filho, para impedir referência a outra unidade.
--   * Tabela global: policy própria e mínima; nada de acesso amplo por padrão.

-- Registro de unidades (global). O runtime só enxerga a unidade do contexto atual. Criação e
-- consultas administrativas ganham policies específicas quando existirem (H10).
CREATE TABLE establishment (
    id         uuid        PRIMARY KEY,
    name       text        NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 120),
    created_at timestamptz NOT NULL DEFAULT now(),
    version    bigint      NOT NULL DEFAULT 0
);

ALTER TABLE establishment ENABLE ROW LEVEL SECURITY;
ALTER TABLE establishment FORCE ROW LEVEL SECURITY;

CREATE POLICY establishment_runtime_read ON establishment
    FOR SELECT TO ${app_role}
    USING (id = current_establishment_id());

GRANT SELECT ON establishment TO ${app_role};

-- Trilha de auditoria por estabelecimento, append-only no caminho da aplicação:
-- o runtime só lê e insere; não há UPDATE nem DELETE concedidos.
CREATE TABLE audit_event (
    id               uuid        PRIMARY KEY,
    establishment_id uuid        NOT NULL REFERENCES establishment (id),
    occurred_at      timestamptz NOT NULL DEFAULT now(),
    action           text        NOT NULL CHECK (action ~ '^[a-z][a-z0-9_.]{2,79}$'),
    actor_id         uuid,
    correlation_id   text        CHECK (char_length(correlation_id) <= 100)
);

CREATE INDEX audit_event_establishment_occurred_idx ON audit_event (establishment_id, occurred_at);

ALTER TABLE audit_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_event FORCE ROW LEVEL SECURITY;

CREATE POLICY audit_event_runtime_read ON audit_event
    FOR SELECT TO ${app_role}
    USING (establishment_id = current_establishment_id());

CREATE POLICY audit_event_runtime_insert ON audit_event
    FOR INSERT TO ${app_role}
    WITH CHECK (establishment_id = current_establishment_id());

GRANT SELECT, INSERT ON audit_event TO ${app_role};
