-- H03: contexto de estabelecimento usado pelas policies de RLS.
-- O valor é definido por transação (set_config(..., true)) pela aplicação; sem valor, retorna NULL
-- e toda policy que compara com ele nega o acesso (fail-closed).
CREATE FUNCTION current_establishment_id() RETURNS uuid
    LANGUAGE sql
    STABLE
    PARALLEL SAFE
AS $$
    SELECT NULLIF(current_setting('app.establishment_id', true), '')::uuid
$$;

REVOKE ALL ON FUNCTION current_establishment_id() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION current_establishment_id() TO ${app_role};
