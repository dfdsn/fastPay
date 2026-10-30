#!/usr/bin/env bash
# Gera o .env.hml da homologação local (T-02) com envio real pelo Gmail de teste.
# Senhas do banco e chave MFA são aleatórias, criadas na hora; a senha de app do Gmail é pedida no
# terminal (sem eco) ou lida de SMTP_PASSWORD, e só fica no .env.hml, que o .gitignore exclui.
# Uso (da raiz, Git Bash/WSL/Linux):
#   ./scripts/gerar-env-hml.sh conta-de-teste@gmail.com
# Opcionais por variável: FASTPAY_VERSION (padrão develop), FASTPAY_IMAGE_OWNER (padrão dfdsn),
# FASTPAY_HTTP_PORT (padrão 8089), MAIL_ALLOWED_RECIPIENTS (padrão: a própria conta).
# Não sobrescreve um .env.hml existente: senhas novas não abrem o volume do banco já criado.
# Para recomeçar do zero: docker compose --env-file .env.hml -f infra/compose.hml.yml down -v
# (apaga os dados da homologação local) e remova o .env.hml.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
target="$root/.env.hml"
gmail="${1:-}"

if [[ -z "$gmail" || "$gmail" != *@* ]]; then
  echo "Informe o e-mail da conta Gmail de teste: ./scripts/gerar-env-hml.sh conta@gmail.com" >&2
  exit 2
fi
if [[ -e "$target" ]]; then
  echo "Já existe $target; nada foi alterado. Veja o cabeçalho do script para recomeçar do zero." >&2
  exit 2
fi

password="${SMTP_PASSWORD:-}"
if [[ -z "$password" ]]; then
  read -r -s -p "Senha de app do Gmail (não aparece na tela): " password
  echo
fi
# O Google mostra a senha de app em grupos de 4 com espaços; o SMTP aceita sem eles.
password="${password// /}"
if [[ ${#password} -ne 16 ]]; then
  echo "A senha de app do Gmail tem 16 letras; confira e rode de novo." >&2
  exit 2
fi

random() { head -c 24 /dev/urandom | base64 | tr -d '/+=\n'; }
port="${FASTPAY_HTTP_PORT:-8089}"

umask 077
cat > "$target" <<EOF
# Gerado por scripts/gerar-env-hml.sh em $(date -u +%Y-%m-%dT%H:%M:%SZ). Não versionar.
FASTPAY_IMAGE_OWNER=${FASTPAY_IMAGE_OWNER:-dfdsn}
FASTPAY_VERSION=${FASTPAY_VERSION:-develop}
FASTPAY_HTTP_PORT=$port
POSTGRES_DB=fastpay
POSTGRES_USER=fastpay_owner
POSTGRES_PASSWORD=$(random)
FASTPAY_MIGRATION_PASSWORD=$(random)
FASTPAY_APP_PASSWORD=$(random)
MFA_ENCRYPTION_KEY=$(head -c 32 /dev/urandom | base64 | tr -d '\n')
PUBLIC_BASE_URL=http://127.0.0.1:$port
MAIL_TRANSPORT=smtp
MAIL_FROM=fastPay HML <$gmail>
MAIL_ALLOWED_RECIPIENTS=${MAIL_ALLOWED_RECIPIENTS:-$gmail}
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=$gmail
SMTP_PASSWORD=$password
EOF

echo "Criado $target (envio real só para: ${MAIL_ALLOWED_RECIPIENTS:-$gmail})."
echo "Próximo passo: docker compose --env-file .env.hml -f infra/compose.hml.yml up -d"
