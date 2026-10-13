package tech.malyah.fastpay.testsupport;

import java.nio.file.Path;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Base dos testes com PostgreSQL real (H03). Usa a mesma imagem fixada no Compose e o mesmo script
 * de papéis ({@code infra/postgres/init/01-roles.sh}): a aplicação conecta como {@code fastpay_app},
 * sem BYPASSRLS, e o Flyway como {@code fastpay_migration}. Superusuário só via {@link #admin()},
 * para preparar dados que o runtime não pode gravar.
 *
 * <p>O contêiner é único por execução da JVM e é encerrado pelo Testcontainers ao final.
 */
public abstract class PostgresIntegrationTest {

    public static final String IMAGE =
            "postgres:18.6-trixie@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336";

    private static final String MIGRATION_PASSWORD = "mig-" + UUID.randomUUID();
    private static final String APP_PASSWORD = "app-" + UUID.randomUUID();
    /** Chave de teste do segredo TOTP (H09), nova a cada execução. */
    public static final String MFA_KEY = java.util.Base64.getEncoder().encodeToString(
            java.security.SecureRandom.getSeed(32));

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("fastpay")
            .withUsername("fastpay_owner")
            .withPassword("owner-" + UUID.randomUUID())
            .withEnv("FASTPAY_MIGRATION_PASSWORD", MIGRATION_PASSWORD)
            .withEnv("FASTPAY_APP_PASSWORD", APP_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("..", "infra", "postgres", "init", "01-roles.sh"), 0755),
                    "/docker-entrypoint-initdb.d/01-roles.sh");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
        registry.add("fastpay.auth.mfa-encryption-key", () -> MFA_KEY);
        // Fixtures de teste (versões 1000+) aplicadas depois das migrations reais.
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/testfixture");
        // Contextos de teste ficam em cache e compartilham o banco: sem worker periódico, cada teste
        // executa jobs explicitamente e um contexto não consome jobs de outro. Um teste do próprio
        // worker liga com fastpay.test.jobs-worker=true.
        registry.add("fastpay.jobs.worker-enabled", () -> "${fastpay.test.jobs-worker:false}");
    }

    protected static String migrationPassword() {
        return MIGRATION_PASSWORD;
    }

    /** Conexão de superusuário do contêiner; ignora RLS. Usar só para preparar ou inspecionar dados. */
    protected static JdbcTemplate admin() {
        return new JdbcTemplate(dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    protected static DataSource dataSource(String url, String user, String password) {
        return new DriverManagerDataSource(url, user, password);
    }
}
