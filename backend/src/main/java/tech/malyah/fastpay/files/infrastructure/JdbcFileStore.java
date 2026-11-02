package tech.malyah.fastpay.files.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Metadados de arquivos (H13), sempre na unidade do contexto da transação (RLS). */
@Repository
public class JdbcFileStore {

    private final JdbcTemplate jdbc;

    JdbcFileStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(UUID id, String purpose, String contentType, String storageKey, String sha256) {
    }

    public record NewFile(UUID id, String purpose, String contentType, String originalContentType,
            int originalSize, int size, String sha256, int width, int height, String storageKey, UUID createdBy,
            Instant createdAt) {
    }

    public void insert(NewFile file) {
        jdbc.update("""
                INSERT INTO file_object (id, establishment_id, purpose, content_type, original_content_type,
                                         original_size_bytes, size_bytes, sha256, width, height, storage_key,
                                         created_by, created_at)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, file.id(), file.purpose(), file.contentType(), file.originalContentType(), file.originalSize(),
                file.size(), file.sha256(), file.width(), file.height(), file.storageKey(), file.createdBy(),
                Timestamp.from(file.createdAt()));
    }

    /** Arquivo vigente (não removido) da unidade do contexto. */
    public Optional<Row> active(UUID id) {
        return jdbc.query("""
                SELECT id, purpose, content_type, storage_key, sha256 FROM file_object
                 WHERE id = ? AND removed_at IS NULL
                """, (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("purpose"),
                rs.getString("content_type"), rs.getString("storage_key"), rs.getString("sha256")), id)
                .stream().findFirst();
    }

    /** @return {@code false} se o arquivo não existe na unidade ou já estava removido */
    public boolean remove(UUID id, UUID actorId, Instant at) {
        return jdbc.update("""
                UPDATE file_object SET removed_at = ?, removed_by = ? WHERE id = ? AND removed_at IS NULL
                """, Timestamp.from(at), actorId, id) == 1;
    }
}
