# syntax=docker/dockerfile:1
# Imagem do frontend fastPay: build Angular no Node e arquivos estáticos servidos pelo nginx sem root.
# Contexto de build: diretório frontend/. Bases fixadas por tag + digest (T-01/H02).

FROM node:24.18.0-trixie-slim@sha256:ae91dcc111a68c9d2d81ff2a17bda61be126426176fde6fe7d08ab13b7f50573 AS build
WORKDIR /workspace
COPY package.json package-lock.json .npmrc ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.30.5-alpine3.24@sha256:15c994d10d6d78658721c3bcafff14cb281fba2a4bdf9d5ba92c416a472516e3
ARG FASTPAY_REVISION=unknown
LABEL org.opencontainers.image.title="fastpay-frontend" \
      org.opencontainers.image.revision="${FASTPAY_REVISION}"
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /workspace/dist/frontend/browser/ /usr/share/nginx/html/
# Alvo do /api; o Compose usa o nome do serviço na rede interna.
ENV FASTPAY_BACKEND_URL=http://backend:8080
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
