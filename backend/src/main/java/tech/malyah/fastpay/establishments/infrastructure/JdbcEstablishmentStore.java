package tech.malyah.fastpay.establishments.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Registro de unidades (H10). Roda sob RLS com o contexto já aberto por {@code identity.PlatformAccess}: o admin
 * fastPay lê todas as unidades e grava só a do contexto.
 */
@Repository
public class JdbcEstablishmentStore {

    private final JdbcTemplate jdbc;

    JdbcEstablishmentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(UUID id, String name, String ownerEmail, String document, String status) {
    }

    /** Contexto da unidade nova, como admin fastPay. */
    public void insert(String name, String ownerEmail, String document, UUID createdBy) {
        jdbc.update("""
                INSERT INTO establishment (id, name, owner_email, document, created_by)
                VALUES (current_establishment_id(), ?, ?, ?, ?)
                """, name, ownerEmail, document, createdBy);
    }

    /** Unidades cadastradas pela plataforma (com dono), mais recentes primeiro, até 100. */
    public List<Row> list() {
        return jdbc.query("""
                SELECT id, name, owner_email, document, status FROM establishment
                 WHERE owner_email IS NOT NULL
                 ORDER BY created_at DESC, id DESC
                 LIMIT 100
                """, (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("owner_email"), rs.getString("document"), rs.getString("status")));
    }

    /** Contexto da unidade: ela mesma, se existir. */
    public Optional<Row> current() {
        return jdbc.query("""
                SELECT id, name, owner_email, document, status FROM establishment
                 WHERE id = current_establishment_id() AND owner_email IS NOT NULL
                """, (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("owner_email"), rs.getString("document"), rs.getString("status")))
                .stream().findFirst();
    }

    /** Nome e logo da unidade do contexto (H13); trava a linha para trocar o logo. */
    public record Branding(UUID id, String name, UUID logoFileId) {
    }

    public Optional<Branding> branding(boolean lock) {
        return jdbc.query("SELECT id, name, logo_file_id FROM establishment WHERE id = current_establishment_id()"
                + (lock ? " FOR UPDATE" : ""), (rs, n) -> new Branding(rs.getObject("id", UUID.class),
                rs.getString("name"), rs.getObject("logo_file_id", UUID.class))).stream().findFirst();
    }

    /** Nome, situação e logo da unidade do contexto (H15: abertura de comanda e tela do consumidor). */
    public record Profile(UUID id, String name, String status, UUID logoFileId) {
    }

    public Optional<Profile> profile() {
        return jdbc.query("SELECT id, name, status, logo_file_id FROM establishment WHERE id = current_establishment_id()",
                (rs, n) -> new Profile(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("status"),
                        rs.getObject("logo_file_id", UUID.class))).stream().findFirst();
    }

    public void setLogo(UUID fileId) {
        jdbc.update("""
                UPDATE establishment SET logo_file_id = ?, version = version + 1 WHERE id = current_establishment_id()
                """, fileId);
    }

    public void audit(String action, UUID subjectId, UUID actorId, String correlationId) {
        jdbc.update("""
                INSERT INTO audit_event (id, establishment_id, action, subject_id, actor_id, correlation_id)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?)
                """, UUID.randomUUID(), action, subjectId, actorId, correlationId);
    }
}
