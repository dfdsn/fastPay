#!/usr/bin/env bash
# Cria os papéis do fastPay na primeira inicialização do volume (docker-entrypoint-initdb.d).
# Usado pelo Compose (dev/HML) e pelos testes Testcontainers, para que testes e ambientes
# rodem com o mesmo papel de runtime (H03, especificação §3).
#   fastpay_migration: dono do schema fastpay; usado só pelo Flyway.
#   fastpay_app: runtime da aplicação; não é dono, superusuário nem BYPASSRLS.
# Senhas vêm do ambiente e são passadas como variáveis do psql (sem interpolar SQL).
set -euo pipefail

: "${FASTPAY_MIGRATION_PASSWORD:?defina FASTPAY_MIGRATION_PASSWORD}"
: "${FASTPAY_APP_PASSWORD:?defina FASTPAY_APP_PASSWORD}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v migration_password="$FASTPAY_MIGRATION_PASSWORD" \
  -v app_password="$FASTPAY_APP_PASSWORD" \
  -v db_name="$POSTGRES_DB" <<'SQL'
CREATE ROLE fastpay_migration LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOREPLICATION
  PASSWORD :'migration_password';
CREATE ROLE fastpay_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOREPLICATION NOINHERIT
  PASSWORD :'app_password';

REVOKE ALL ON DATABASE :"db_name" FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE :"db_name" TO fastpay_migration;
GRANT CONNECT ON DATABASE :"db_name" TO fastpay_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA fastpay AUTHORIZATION fastpay_migration;
GRANT USAGE ON SCHEMA fastpay TO fastpay_app;

ALTER ROLE fastpay_migration SET search_path = fastpay;
ALTER ROLE fastpay_app SET search_path = fastpay;
SQL
