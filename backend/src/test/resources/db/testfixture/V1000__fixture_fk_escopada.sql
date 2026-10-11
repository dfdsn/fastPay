-- Somente testes (H03): exemplo da convenção de FK escopada entre tabelas do estabelecimento.
-- O pai expõe UNIQUE (establishment_id, id); o filho referencia (establishment_id, parent_id).
CREATE TABLE fixture_parent (
    id               uuid NOT NULL PRIMARY KEY,
    establishment_id uuid NOT NULL REFERENCES establishment (id),
    UNIQUE (establishment_id, id)
);

CREATE TABLE fixture_child (
    id               uuid NOT NULL PRIMARY KEY,
    establishment_id uuid NOT NULL,
    parent_id        uuid NOT NULL,
    FOREIGN KEY (establishment_id, parent_id) REFERENCES fixture_parent (establishment_id, id)
);

ALTER TABLE fixture_parent ENABLE ROW LEVEL SECURITY;
ALTER TABLE fixture_parent FORCE ROW LEVEL SECURITY;
ALTER TABLE fixture_child ENABLE ROW LEVEL SECURITY;
ALTER TABLE fixture_child FORCE ROW LEVEL SECURITY;

CREATE POLICY fixture_parent_runtime ON fixture_parent TO ${app_role}
    USING (establishment_id = current_establishment_id())
    WITH CHECK (establishment_id = current_establishment_id());
CREATE POLICY fixture_child_runtime ON fixture_child TO ${app_role}
    USING (establishment_id = current_establishment_id())
    WITH CHECK (establishment_id = current_establishment_id());

GRANT SELECT, INSERT ON fixture_parent, fixture_child TO ${app_role};
