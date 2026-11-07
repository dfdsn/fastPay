package tech.malyah.fastpay.identity.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Conta do consumidor com Google (H27, tabela {@code consumer_account}). Só roda dentro de
 * {@code TenantTransactions.forGoogleSubject} (entrar/criar) ou {@code asConsumer} (ler a própria conta); a RLS não
 * mostra nenhuma outra conta.
 */
@Repository
public class JdbcConsumerAccountStore {

    /** Conta como a própria pessoa a vê. */
    public record AccountRow(UUID id, String email, boolean emailVerified, String privacyNoticeVersion,
            Instant privacyAcceptedAt, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;

    JdbcConsumerAccountStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> idBySubject(String subject) {
        return jdbc.query("SELECT id FROM consumer_account WHERE google_subject = ?",
                (rs, i) -> rs.getObject("id", UUID.class), subject).stream().findFirst();
    }

    /** Entrou de novo: e-mail atualizado (pode ter mudado no Google) e data do último login. */
    public void touchLogin(UUID id, String email, boolean emailVerified, Instant at) {
        jdbc.update("UPDATE consumer_account SET email = ?, email_verified = ?, last_login_at = ? WHERE id = ?",
                email, emailVerified, Timestamp.from(at), id);
    }

    public void insert(UUID id, String subject, String email, boolean emailVerified, String noticeVersion,
            Instant at) {
        jdbc.update("""
                INSERT INTO consumer_account (id, google_subject, email, email_verified, privacy_notice_version,
                    privacy_accepted_at, created_at, last_login_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, subject, email, emailVerified, noticeVersion, Timestamp.from(at), Timestamp.from(at),
                Timestamp.from(at));
    }

    public Optional<AccountRow> account(UUID id) {
        return jdbc.query("""
                SELECT id, email, email_verified, privacy_notice_version, privacy_accepted_at, created_at
                FROM consumer_account WHERE id = ?
                """, (rs, i) -> new AccountRow(rs.getObject("id", UUID.class), rs.getString("email"),
                rs.getBoolean("email_verified"), rs.getString("privacy_notice_version"),
                rs.getTimestamp("privacy_accepted_at").toInstant(), rs.getTimestamp("created_at").toInstant()), id)
                .stream().findFirst();
    }
}
