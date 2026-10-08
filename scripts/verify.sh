#!/usr/bin/env bash
# Verificação completa usada localmente e pela CI (.github/workflows/ci.yml).
# Uso: ./scripts/verify.sh [backend|frontend]  (sem argumento roda os dois)
# Pré-requisitos: JDK 25 (JAVA_HOME), Node 24.18.0 e npm 11.16.0. Maven vem do wrapper.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
part="${1:-all}"

case "$part" in
  all|backend|frontend) ;;
  *) echo "uso: $0 [backend|frontend]" >&2; exit 2 ;;
esac

if [[ "$part" != frontend ]]; then
  echo "== backend: mvnw clean verify"
  (cd "$root/backend" && ./mvnw -B -ntp clean verify)
fi

if [[ "$part" != backend ]]; then
  echo "== frontend: npm ci, lint, test:ci, build"
  (
    cd "$root/frontend"
    npm ci
    npm run lint
    npm run test:ci
    npm run build
  )
fi

echo "== verificação concluída ($part)"
