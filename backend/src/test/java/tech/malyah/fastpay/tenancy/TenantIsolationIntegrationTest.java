package tech.malyah.fastpay.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import tech.malyah.fastpay.testsupport.PostgresIntegrationTest;

/**
 * Prova do isolamento por estabelecimento com PostgreSQL real e o papel de runtime (H03, NFR-02, CA-03):
 * acesso cruzado, contexto ausente, conexão reaproveitada após rollback e papel sem privilégios.
 * Pool com uma conexão, para que a reutilização da mesma conexão seja certa.
 */
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=1")
class TenantIsolationIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    TenantTransactions tenants;

    @Autowired
    JdbcTemplate jdbc;

    UUID lojaA;
    UUID lojaB;
    UUID eventoB;

    @BeforeEach
    void prepararDuasUnidades() {
        lojaA = UUID.randomUUID();
        lojaB = UUID.randomUUID();
        eventoB = UUID.randomUUID();
        JdbcTemplate admin = admin();
        admin.update("INSERT INTO fastpay.establishment (id, name) VALUES (?, 'Loja A'), (?, 'Loja B')", lojaA, lojaB);
        admin.update("INSERT INTO fastpay.audit_event (id, establishment_id, action) VALUES (?, ?, 'teste.evento_a'), (?, ?, 'teste.evento_b')",
                UUID.randomUUID(), lojaA, eventoB, lojaB);
    }

    @Test
    void runtimeNaoEhSuperusuarioNemIgnoraRlsNemEhDonoDasTabelas() {
        Map<String, Object> papel = jdbc.queryForMap(
                "SELECT current_user AS usuario, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user");
        assertThat(papel).containsEntry("usuario", "fastpay_app")
                .containsEntry("rolsuper", false)
                .containsEntry("rolbypassrls", false);

        Integer tabelasSemRlsForcado = jdbc.queryForObject("""
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'fastpay' AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history'
                  AND NOT (c.relrowsecurity AND c.relforcerowsecurity)
                """, Integer.class);
        assertThat(tabelasSemRlsForcado).isZero();

        Integer tabelasDoRuntime = jdbc.queryForObject("""
                SELECT count(*) FROM pg_tables WHERE schemaname = 'fastpay' AND tableowner = current_user
                """, Integer.class);
        assertThat(tabelasDoRuntime).isZero();
    }

    @Test
    void semContextoNadaEhVisivelNemGravavel() {
        assertThat(contarEventos()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM establishment", Integer.class)).isZero();
        assertThatThrownBy(() -> inserirEvento(lojaA)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("row-level security");
    }

    @Test
    void unidadeSoEnxergaEGravaOsPropriosDados() {
        tenants.runInEstablishment(lojaA, () -> {
            assertThat(contarEventos()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE id = ?", Integer.class, eventoB)).isZero();
            assertThat(jdbc.queryForList("SELECT id FROM establishment", UUID.class)).containsExactly(lojaA);
            inserirEvento(lojaA);
            assertThat(contarEventos()).isEqualTo(2);
        });
    }

    @Test
    void gravarComIdDeOutraUnidadeEhRecusado() {
        assertThatThrownBy(() -> tenants.runInEstablishment(lojaA, () -> inserirEvento(lojaB)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("row-level security");
        assertThat(admin().queryForObject("SELECT count(*) FROM fastpay.audit_event WHERE establishment_id = ?",
                Integer.class, lojaB)).isEqualTo(1);
    }

    @Test
    void auditoriaNaoPodeSerAlteradaNemApagadaPeloRuntime() {
        assertThatThrownBy(() -> tenants.runInEstablishment(lojaA,
                () -> jdbc.update("UPDATE audit_event SET action = 'teste.alterado'")))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("permission denied");
        assertThatThrownBy(() -> tenants.runInEstablishment(lojaA, () -> jdbc.update("DELETE FROM audit_event")))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("permission denied");
    }

    @Test
    void runtimeNaoConsegueDesligarRls() {
        assertThatThrownBy(() -> tenants.runInEstablishment(lojaA, () -> {
            jdbc.execute("SET LOCAL row_security = off");
            contarEventos();
        })).isInstanceOf(DataAccessException.class).hasMessageContaining("row-level security");
    }

    @Test
    void conexaoReaproveitadaAposRollbackOuCommitNaoCarregaUnidadeAnterior() {
        assertThatThrownBy(() -> tenants.runInEstablishment(lojaA, () -> {
            assertThat(contarEventos()).isEqualTo(1);
            throw new IllegalStateException("falha simulada");
        })).hasMessage("falha simulada");
        assertThat(contextoAtual()).isNullOrEmpty();
        assertThat(contarEventos()).isZero();

        tenants.runInEstablishment(lojaA, () -> assertThat(contarEventos()).isEqualTo(1));
        assertThat(contextoAtual()).isNullOrEmpty();
        assertThat(contarEventos()).isZero();

        tenants.runInEstablishment(lojaB, () -> assertThat(jdbc.queryForList("SELECT id FROM audit_event", UUID.class))
                .containsExactly(eventoB));
    }

    @Test
    void trocarDeUnidadeDentroDeUmaTransacaoEhRecusado() {
        assertThatThrownBy(() -> tenants.runInEstablishment(lojaA, () -> tenants.runInEstablishment(lojaB, () -> {
        }))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void chaveEstrangeiraEscopadaImpedeReferenciaAOutraUnidade() {
        UUID paiA = UUID.randomUUID();
        UUID paiB = UUID.randomUUID();
        admin().update("INSERT INTO fastpay.fixture_parent (id, establishment_id) VALUES (?, ?), (?, ?)",
                paiA, lojaA, paiB, lojaB);

        tenants.runInEstablishment(lojaA,
                () -> jdbc.update("INSERT INTO fixture_child (id, establishment_id, parent_id) VALUES (?, ?, ?)",
                        UUID.randomUUID(), lojaA, paiA));

        assertThatThrownBy(() -> tenants.runInEstablishment(lojaA,
                () -> jdbc.update("INSERT INTO fixture_child (id, establishment_id, parent_id) VALUES (?, ?, ?)",
                        UUID.randomUUID(), lojaA, paiB)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Integer contarEventos() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class);
    }

    private void inserirEvento(UUID estabelecimento) {
        jdbc.update("INSERT INTO audit_event (id, establishment_id, action) VALUES (?, ?, 'teste.inserido')",
                UUID.randomUUID(), estabelecimento);
    }

    private String contextoAtual() {
        return jdbc.queryForObject("SELECT current_setting('app.establishment_id', true)", String.class);
    }
}
