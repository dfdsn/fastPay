# syntax=docker/dockerfile:1
# Imagem do backend fastPay. Contexto de build: diretório backend/.
# Bases fixadas por tag + digest (T-01/H02); atualizar ambas juntas e registrar em docs/evidencias.

FROM eclipse-temurin:25.0.4.1_1-jdk-noble@sha256:589ff4cc3f71aab462e7048a47a0d10edf57fbccde3fceea2281e610bf5880b4 AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp dependency:go-offline
COPY src/ src/
# Testes rodam no job de verificação da CI e em scripts/verify.sh; aqui só empacota.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp package -DskipTests \
    && cp target/fastpay-backend-*.jar /workspace/app.jar

FROM eclipse-temurin:25.0.4.1_1-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab
ARG FASTPAY_REVISION=unknown
LABEL org.opencontainers.image.title="fastpay-backend" \
      org.opencontainers.image.revision="${FASTPAY_REVISION}"
RUN groupadd --system fastpay && useradd --system --gid fastpay --no-create-home fastpay
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER fastpay
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
