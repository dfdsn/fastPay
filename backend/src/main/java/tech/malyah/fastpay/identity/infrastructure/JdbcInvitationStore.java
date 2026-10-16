package tech.malyah.fastpay.identity.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tech.malyah.fastpay.identity.MembershipRole;

/**
 * Convites (H10). Cada método indica o contexto de RLS em que roda; quem chama abre a transação com
 * {@code TenantTransactions}. Token só como hash SHA-256.
 */
@Repository
public class JdbcInvitationStore {

    private static final String COLUMNS = """
            i.id, i.establishment_id, e.name AS establishment_name, i.email, i.role, i.inviter_name, i.created_at,
            i.sent_at, i.expires_at, i.accepted_at, i.invalidated_at
            """;

    private final JdbcTemplate jdbc;

    JdbcInvitationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Convite como gravado; {@code sentAt}/{@code expiresAt} vazios até o e-mail sair. */
    public record Invitation(UUID id, UUID establishmentId, String establishmentName, String email, MembershipRole role,
            String inviterName, Instant createdAt, Instant sentAt, Instant expiresAt, Instant acceptedAt,
            Instant invalidatedAt) {

        public boolean open() {
            return acceptedAt == null && invalidatedAt == null;
        }

        /** Enviado, em aberto e no prazo: pode ser aceito agora. */
        public boolean acceptableAt(Instant now) {
            return open() && expiresAt != null && now.isBefore(expiresAt);
        }
    }

    /** Contexto da unidade com a identidade de quem convida: nome dela, para o e-mail. */
    public Optional<String> currentIdentityName(UUID identityId) {
        return jdbc.queryForList("SELECT display_name FROM user_identity WHERE id = ? AND id = current_identity_id()",
                String.class, identityId).stream().findFirst();
    }

    /** Contexto da unidade: invalida o convite em aberto para o e-mail, se houver. */
    public int invalidateOpen(String email, Instant at) {
        return jdbc.update("""
                UPDATE invitation SET invalidated_at = ?
                 WHERE establishment_id = current_establishment_id() AND email = ?
                   AND accepted_at IS NULL AND invalidated_at IS NULL
                """, Timestamp.from(at), email);
    }

    /** Contexto da unidade. */
    public void insert(UUID id, String email, MembershipRole role, UUID invitedBy, String inviterName, Instant at) {
        jdbc.update("""
                INSERT INTO invitation (id, establishment_id, email, role, invited_by, inviter_name, created_at)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?)
                """, id, email, role.name(), invitedBy, inviterName, Timestamp.from(at));
    }

    /** Contexto da unidade: o convite, se for dela. */
    public Optional<Invitation> inCurrentUnit(UUID invitationId) {
        return jdbc.query("SELECT " + COLUMNS + """
                FROM invitation i JOIN establishment e ON e.id = i.establishment_id
                WHERE i.id = ? AND i.establishment_id = current_establishment_id()
                """, JdbcInvitationStore::invitation, invitationId).stream().findFirst();
    }

    /**
     * Contexto da unidade ou de admin fastPay: o convite vigente da unidade para o papel (o que não foi
     * substituído), senão o mais recente. Ordem estável por criação e id.
     */
    public Optional<Invitation> latest(UUID establishmentId, MembershipRole role) {
        return jdbc.query("SELECT " + COLUMNS + """
                FROM invitation i JOIN establishment e ON e.id = i.establishment_id
                WHERE i.establishment_id = ? AND i.role = ?
                ORDER BY i.invalidated_at IS NULL DESC, i.created_at DESC, i.id DESC
                LIMIT 1
                """, JdbcInvitationStore::invitation, establishmentId, role.name()).stream().findFirst();
    }

    /**
     * Contexto da unidade (executor do e-mail): grava o hash do token enviado e o prazo, só se o convite ainda
     * estiver em aberto e sem envio anterior.
     */
    public boolean markSent(UUID invitationId, String tokenHash, Instant sentAt, Instant expiresAt) {
        return jdbc.update("""
                UPDATE invitation SET token_hash = ?, sent_at = ?, expires_at = ?
                 WHERE id = ? AND establishment_id = current_establishment_id() AND token_hash IS NULL
                   AND accepted_at IS NULL AND invalidated_at IS NULL
                """, tokenHash, Timestamp.from(sentAt), Timestamp.from(expiresAt), invitationId) == 1;
    }

    /** Contexto {@code forInvitationToken}: o convite apresentado, se existir. */
    public Optional<Invitation> presented(String tokenHash) {
        return jdbc.query("SELECT " + COLUMNS + """
                FROM invitation i JOIN establishment e ON e.id = i.establishment_id
                WHERE i.token_hash = ?
                """, JdbcInvitationStore::invitation, tokenHash).stream().findFirst();
    }

    /**
     * Contexto {@code forInvitationToken}: trava o convite apresentado se ainda puder ser aceito. Aceites
     * simultâneos esperam aqui; depois do primeiro, os outros não encontram mais o convite em aberto.
     */
    public boolean lockAcceptable(String tokenHash, Instant at) {
        return !jdbc.queryForList("""
                SELECT id FROM invitation
                 WHERE token_hash = ? AND accepted_at IS NULL AND invalidated_at IS NULL AND expires_at > ?
                   FOR UPDATE
                """, UUID.class, tokenHash, Timestamp.from(at)).isEmpty();
    }

    /**
     * Contexto {@code forInvitationToken} com a identidade: marca o convite como aceito por ela, se ainda
     * estiver em aberto e no prazo. Só um de dois aceites simultâneos consegue (a linha fica travada pelo
     * primeiro). A policy confere que o e-mail da identidade é o do convite.
     */
    public boolean accept(String tokenHash, UUID identityId, Instant at) {
        return jdbc.update("""
                UPDATE invitation SET accepted_at = ?, accepted_by = ?
                 WHERE token_hash = ? AND accepted_at IS NULL AND invalidated_at IS NULL AND expires_at > ?
                """, Timestamp.from(at), identityId, tokenHash, Timestamp.from(at)) == 1;
    }

    /** Contexto {@code forInvitationToken} com a identidade: conta nova, sem papel global e sem senha ainda. */
    public void insertIdentity(UUID identityId, String email, String displayName) {
        jdbc.update("INSERT INTO user_identity (id, email, display_name) VALUES (?, ?, ?)", identityId, email,
                displayName);
    }

    /** Contexto {@code forInvitationToken} com a identidade: e-mail da própria identidade. */
    public Optional<String> currentIdentityEmail(UUID identityId) {
        return jdbc.queryForList("SELECT email FROM user_identity WHERE id = ? AND id = current_identity_id()",
                String.class, identityId).stream().findFirst();
    }

    /** Contexto {@code forInvitationToken} com a identidade: já tem vínculo ativo na unidade? */
    public boolean activeMembership(UUID identityId, UUID establishmentId) {
        return !jdbc.queryForList("""
                SELECT 1 FROM membership
                 WHERE identity_id = ? AND identity_id = current_identity_id() AND establishment_id = ?
                   AND status = 'ACTIVE'
                """, Integer.class, identityId, establishmentId).isEmpty();
    }

    /** Contexto {@code forInvitationToken} com a identidade, depois do aceite: o vínculo do convite. */
    public UUID insertMembership(UUID identityId, UUID establishmentId, MembershipRole role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO membership (id, establishment_id, identity_id, role) VALUES (?, ?, ?, ?)", id,
                establishmentId, identityId, role.name());
        return id;
    }

    /** Auditoria na unidade do contexto ({@code inEstablishment}) ou do convite apresentado. */
    public void audit(UUID establishmentId, String action, UUID subjectId, UUID actorId, String correlationId) {
        jdbc.update("""
                INSERT INTO audit_event (id, establishment_id, action, subject_id, actor_id, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), establishmentId, action, subjectId, actorId, correlationId);
    }

    private static Invitation invitation(ResultSet rs, int row) throws SQLException {
        return new Invitation(rs.getObject("id", UUID.class), rs.getObject("establishment_id", UUID.class),
                rs.getString("establishment_name"), rs.getString("email"), MembershipRole.valueOf(rs.getString("role")),
                rs.getString("inviter_name"), instant(rs, "created_at"), instant(rs, "sent_at"),
                instant(rs, "expires_at"), instant(rs, "accepted_at"), instant(rs, "invalidated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
