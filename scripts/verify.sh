#!/usr/bin/env bash
# Verificação completa usada localmente e, a partir de H02, pela CI.
# Pré-requisitos: JDK 25 (JAVA_HOME), Node 24.18.0 e npm 11.16.0. Maven vem do wrapper.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"

echo "== backend: mvnw clean verify"
(cd "$root/backend" && ./mvnw -B -ntp clean verify)

echo "== frontend: npm ci, lint, test:ci, build"
(
  cd "$root/frontend"
  npm ci
  npm run lint
  npm run test:ci
  npm run build
)

echo "== verificação concluída"
