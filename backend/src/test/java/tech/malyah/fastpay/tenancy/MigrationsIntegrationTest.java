package tech.malyah.fastpay.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import tech.malyah.fastpay.testsupport.PostgresIntegrationTest;

/**
 * Migrations em banco vazio e por atualização incremental (H03), com a credencial de migração e o
 * schema preparado como em {@code infra/postgres/init/01-roles.sh}. Cada teste usa um banco novo.
 */
class MigrationsIntegrationTest extends PostgresIntegrationTest {

    @Test
    void bancoVazioRecebeTodasAsMigrationsEFicaValido() {
        Flyway flyway = flywayPara(novoBanco(), null);

        MigrateResult resultado = flyway.migrate();

        assertThat(resultado.success).isTrue();
        assertThat(resultado.migrationsExecuted).isEqualTo(21);
        assertThat(resultado.targetSchemaVersion).isEqualTo("21");
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void bancoNaVersaoAnteriorAtualizaSemPerderDados() {
        String banco = novoBanco();
        assertThat(flywayPara(banco, "1").migrate().targetSchemaVersion).isEqualTo("1");
        JdbcTemplate admin = new JdbcTemplate(dataSource(urlDo(banco), POSTGRES.getUsername(), POSTGRES.getPassword()));
        assertThat(admin.queryForObject("SELECT to_regclass('fastpay.establishment') IS NULL", Boolean.class)).isTrue();

        MigrateResult resultado = flywayPara(banco, null).migrate();

        assertThat(resultado.migrationsExecuted).isEqualTo(20);
        assertThat(resultado.targetSchemaVersion).isEqualTo("21");
        assertThat(admin.queryForObject(
                "SELECT tableowner FROM pg_tables WHERE schemaname = 'fastpay' AND tablename = 'audit_event'", String.class))
                .isEqualTo("fastpay_migration");
    }

    @Test
    void unidadeCadastradaAntesDaV11GanhaAConfiguracaoPadrao() {
        String banco = novoBanco();
        flywayPara(banco, "10").migrate();
        JdbcTemplate admin = new JdbcTemplate(dataSource(urlDo(banco), POSTGRES.getUsername(), POSTGRES.getPassword()));
        UUID loja = UUID.randomUUID();
        admin.update("INSERT INTO fastpay.establishment (id, name) VALUES (?, 'Bar Antigo')", loja);

        assertThat(flywayPara(banco, null).migrate().targetSchemaVersion).isEqualTo("21");

        assertThat(admin.queryForObject("""
                SELECT service_percent || ' ' || attendance_mode || ' ' || exit_mode FROM fastpay.unit_settings_version
                 WHERE establishment_id = ? AND number = 1
                """, String.class, loja)).isEqualTo("10.0000 GARCOM_RESPONSAVEL AUTOMATICA");
        assertThat(admin.queryForObject(
                "SELECT time_zone || ' ' || cutoff FROM fastpay.operational_calendar WHERE establishment_id = ?",
                String.class, loja)).isEqualTo("America/Sao_Paulo 06:00:00");
        // O FORCE de establishment volta depois da cópia.
        assertThat(admin.queryForObject(
                "SELECT relforcerowsecurity FROM pg_class WHERE oid = 'fastpay.establishment'::regclass",
                Boolean.class)).isTrue();
    }

    private String novoBanco() {
        String banco = "fastpay_mig_" + UUID.randomUUID().toString().replace("-", "");
        admin().execute("CREATE DATABASE " + banco);
        JdbcTemplate noBanco = new JdbcTemplate(dataSource(urlDo(banco), POSTGRES.getUsername(), POSTGRES.getPassword()));
        noBanco.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        noBanco.execute("CREATE SCHEMA fastpay AUTHORIZATION fastpay_migration");
        noBanco.execute("GRANT USAGE ON SCHEMA fastpay TO fastpay_app");
        return banco;
    }

    private Flyway flywayPara(String banco, String alvo) {
        var config = Flyway.configure()
                .dataSource(urlDo(banco), "fastpay_migration", migrationPassword())
                .schemas("fastpay")
                .defaultSchema("fastpay")
                .createSchemas(false)
                .locations("classpath:db/migration")
                .placeholders(Map.of("app_role", "fastpay_app"));
        if (alvo != null) {
            config.target(alvo);
        }
        return config.load();
    }

    private static String urlDo(String banco) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + banco;
    }
}
