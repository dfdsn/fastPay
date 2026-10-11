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
        assertThat(resultado.migrationsExecuted).isEqualTo(2);
        assertThat(resultado.targetSchemaVersion).isEqualTo("2");
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

        assertThat(resultado.migrationsExecuted).isEqualTo(1);
        assertThat(resultado.targetSchemaVersion).isEqualTo("2");
        assertThat(admin.queryForObject(
                "SELECT tableowner FROM pg_tables WHERE schemaname = 'fastpay' AND tablename = 'audit_event'", String.class))
                .isEqualTo("fastpay_migration");
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
