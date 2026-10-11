#!/usr/bin/env bash
# Smoke do ambiente Compose de desenvolvimento (H02), usado localmente e na CI.
# Sobe banco/backend/frontend, confere saúde e /api pelo frontend, grava um marcador no banco,
# derruba e recria os contêineres (sem apagar o volume) e confere que o marcador sobreviveu.
# Ao final remove contêineres e o volume do projeto fastpay-smoke (dados sintéticos).
# Uso: ./scripts/compose-smoke.sh   (requer Docker com Compose v2)
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
port="${FASTPAY_HTTP_PORT:-18088}"
env_file="$(mktemp)"
trap 'docker compose "${compose_args[@]}" down -v --remove-orphans >/dev/null 2>&1 || true; rm -f "$env_file"' EXIT

# Senhas descartáveis geradas na hora; só existem neste projeto Compose temporário.
printf 'POSTGRES_PASSWORD=%s\nFASTPAY_MIGRATION_PASSWORD=%s\nFASTPAY_APP_PASSWORD=%s\nFASTPAY_HTTP_PORT=%s\n' \
  "smoke-$RANDOM-$RANDOM-$$" "mig-$RANDOM-$RANDOM-$$" "app-$RANDOM-$RANDOM-$$" "$port" > "$env_file"
compose_args=(--env-file "$env_file" -f "$root/infra/compose.dev.yml" -p fastpay-smoke)

wait_http() {
  local url="$1" expected="$2"
  for _ in $(seq 1 60); do
    if body="$(curl -fsS "$url" 2>/dev/null)" && [[ "$body" == *"$expected"* ]]; then
      echo "ok $url -> $body"
      return 0
    fi
    sleep 2
  done
  echo "falhou: $url não respondeu com '$expected'" >&2
  docker compose "${compose_args[@]}" ps >&2
  docker compose "${compose_args[@]}" logs --tail 50 >&2
  return 1
}

psql_db() {
  docker compose "${compose_args[@]}" exec -T db sh -c 'psql -v ON_ERROR_STOP=1 -tA -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"' _ "$1"
}

# up --wait encerra antes de qualquer diagnóstico; em falha mostra estado e logs dos serviços.
up_or_dump() {
  if ! docker compose "${compose_args[@]}" up -d --wait "$@"; then
    echo "falhou: ambiente não ficou saudável" >&2
    docker compose "${compose_args[@]}" ps -a >&2 || true
    docker compose "${compose_args[@]}" logs --no-color --tail 80 >&2 || true
    exit 1
  fi
}

echo "== subir ambiente"
up_or_dump --build

echo "== verificar frontend e API pela mesma origem"
wait_http "http://127.0.0.1:$port/healthz" "ok"
wait_http "http://127.0.0.1:$port/api/v1/platform/info" '"environment":"development"'
wait_http "http://127.0.0.1:$port/" '<fp-root'

echo "== banco não publicado no host"
if docker compose "${compose_args[@]}" port db 5432 >/dev/null 2>&1; then
  echo "falhou: porta 5432 do banco está publicada" >&2
  exit 1
fi
echo "ok sem porta publicada"

echo "== migrations aplicadas pelo papel de migração e runtime sem privilégios"
applied="$(psql_db "SELECT count(*) FROM fastpay.flyway_schema_history WHERE success AND installed_by = 'fastpay_migration';")"
if [[ "$applied" -lt 2 ]]; then
  echo "falhou: migrations não aplicadas por fastpay_migration (count=$applied)" >&2
  exit 1
fi
privileged="$(psql_db "SELECT count(*) FROM pg_roles WHERE rolname = 'fastpay_app' AND (rolsuper OR rolbypassrls);")"
if [[ "$privileged" != "0" ]]; then
  echo "falhou: fastpay_app é superusuário ou ignora RLS" >&2
  exit 1
fi
echo "ok $applied migrations; fastpay_app sem SUPERUSER/BYPASSRLS"

echo "== gravar marcador e recriar contêineres preservando o volume"
marker="smoke-$(date +%s)"
psql_db "CREATE TABLE IF NOT EXISTS h02_smoke (marker text NOT NULL); INSERT INTO h02_smoke VALUES ('$marker');" >/dev/null
docker compose "${compose_args[@]}" down
up_or_dump
found="$(psql_db "SELECT count(*) FROM h02_smoke WHERE marker = '$marker';")"
if [[ "$found" != "1" ]]; then
  echo "falhou: marcador não encontrado após recriar (count=$found)" >&2
  exit 1
fi
echo "ok marcador preservado após down/up"
wait_http "http://127.0.0.1:$port/api/v1/platform/info" '"name":"fastPay"'

echo "== smoke concluído"
