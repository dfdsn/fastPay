package tech.malyah.fastpay.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import tech.malyah.fastpay.tenancy.TenantTransactions;
import tech.malyah.fastpay.testsupport.PostgresIntegrationTest;

/**
 * RLS das credenciais e dos links de redefinição (H08) com o papel de runtime: o e-mail digitado só
 * revela a identidade, nunca o hash; a credencial e os links só aparecem no contexto da própria pessoa.
 */
@SpringBootTest
class CredentialsRlsIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    TenantTransactions tenants;

    @Autowired
    JdbcTemplate jdbc;

    IdentityFixtures fixtures = new IdentityFixtures(admin());

    UUID ana;
    UUID bruno;
    String tokenDaAna = "a".repeat(64);

    @BeforeEach
    void duasPessoas() {
        ana = fixtures.identity("Ana");
        bruno = fixtures.identity("Bruno");
        fixtures.credential(ana, "senha da ana bem longa");
        fixtures.credential(bruno, "senha do bruno bem longa");
        admin().update("DELETE FROM fastpay.password_reset_token WHERE token_hash = ?", tokenDaAna);
        admin().update("""
                INSERT INTO fastpay.password_reset_token (id, identity_id, token_hash, created_at, expires_at)
                VALUES (?, ?, ?, now(), now() + interval '30 minutes')
                """, UUID.randomUUID(), ana, tokenDaAna);
    }

    @Test
    void semContextoNadaDeCredencialOuLinkEhVisivel() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_credential", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_token", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_identity", Integer.class)).isZero();
    }

    @Test
    void emailDigitadoMostraSoAIdentidadeSemCredencial() {
        tenants.forLoginEmail(IdentityFixtures.email(ana), () -> {
            assertThat(jdbc.queryForList("SELECT id FROM user_identity", UUID.class)).containsExactly(ana);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_credential", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_token", Integer.class)).isZero();
            return null;
        });
    }

    @Test
    void contextoDaPessoaVeEGravaSoAPropriaCredencial() {
        tenants.asIdentity(ana, () -> {
            assertThat(jdbc.queryForList("SELECT identity_id FROM user_credential", UUID.class)).containsExactly(ana);
            assertThat(jdbc.update("UPDATE user_credential SET changed_at = now() WHERE identity_id = ?", bruno)).isZero();
            return null;
        });
        assertThatThrownBy(() -> tenants.asIdentity(ana, () -> jdbc.update(
                "INSERT INTO password_reset_token (id, identity_id, token_hash, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), bruno, "b".repeat(64), Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now().plusSeconds(60)))))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void tokenApresentadoMostraSoEle() {
        tenants.forResetToken(tokenDaAna, () -> {
            assertThat(jdbc.queryForList("SELECT identity_id FROM password_reset_token", UUID.class)).containsExactly(ana);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_credential", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_identity", Integer.class)).isZero();
            return null;
        });
        tenants.forResetToken("c".repeat(64), () -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_token", Integer.class)).isZero();
            return null;
        });
    }

    @Test
    void umLinkEmAbertoPorPessoa() {
        assertThatThrownBy(() -> admin().update("""
                INSERT INTO fastpay.password_reset_token (id, identity_id, token_hash, created_at, expires_at)
                VALUES (?, ?, ?, now(), now() + interval '30 minutes')
                """, UUID.randomUUID(), ana, "d".repeat(64))).isInstanceOf(DataAccessException.class);
    }
}
