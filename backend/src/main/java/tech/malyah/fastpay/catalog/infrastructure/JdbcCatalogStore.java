package tech.malyah.fastpay.catalog.infrastructure;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Produtos e setores (H13), sempre na unidade do contexto da transação (RLS). */
@Repository
public class JdbcCatalogStore {

    private final JdbcTemplate jdbc;

    JdbcCatalogStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SectorRow(UUID id, String name, long version) {
    }

    public record ProductRow(UUID id, String name, String category, String description, BigDecimal price,
            UUID sectorId, boolean available, UUID photoFileId, long version) {
    }

    // Setores

    public List<SectorRow> activeSectors() {
        return jdbc.query("""
                SELECT id, name, version FROM sector WHERE archived_at IS NULL ORDER BY lower(name), id
                """, JdbcCatalogStore::sector);
    }

    public Optional<SectorRow> activeSector(UUID id) {
        return jdbc.query("SELECT id, name, version FROM sector WHERE id = ? AND archived_at IS NULL",
                JdbcCatalogStore::sector, id).stream().findFirst();
    }

    public boolean activeSectorNamed(String name, UUID exceptId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM sector WHERE archived_at IS NULL AND lower(name) = lower(?)
                                                    AND id IS DISTINCT FROM ?)
                """, Boolean.class, name, exceptId));
    }

    public void insertSector(UUID id, String name, UUID actorId, Instant now) {
        jdbc.update("""
                INSERT INTO sector (id, establishment_id, name, created_by, created_at, updated_by, updated_at)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?)
                """, id, name, actorId, Timestamp.from(now), actorId, Timestamp.from(now));
    }

    /** @return {@code false} se o setor não existe, está arquivado ou a versão mudou */
    public boolean renameSector(UUID id, long version, String name, UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE sector SET name = ?, updated_by = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND archived_at IS NULL
                """, name, actorId, Timestamp.from(now), id, version) == 1;
    }

    public boolean archiveSector(UUID id, long version, UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE sector SET archived_at = ?, updated_by = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND archived_at IS NULL
                """, Timestamp.from(now), actorId, Timestamp.from(now), id, version) == 1;
    }

    /** Trava o setor ativo (troca de setor de produto e arquivamento não se cruzam). */
    public Optional<SectorRow> lockActiveSector(UUID id) {
        return jdbc.query("SELECT id, name, version FROM sector WHERE id = ? AND archived_at IS NULL FOR UPDATE",
                JdbcCatalogStore::sector, id).stream().findFirst();
    }

    public long activeProductsInSector(UUID sectorId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM product WHERE sector_id = ? AND archived_at IS NULL",
                Long.class, sectorId);
        return n == null ? 0 : n;
    }

    // Produtos

    private static final String PRODUCT_COLUMNS =
            "id, name, category, description, price, sector_id, available, photo_file_id, version";

    public long countActiveProducts() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM product WHERE archived_at IS NULL", Long.class);
        return n == null ? 0 : n;
    }

    /** Página de produtos ativos por categoria e nome. */
    public List<ProductRow> activeProducts(int offset, int limit) {
        return jdbc.query("SELECT " + PRODUCT_COLUMNS + " FROM product WHERE archived_at IS NULL"
                + " ORDER BY lower(category), lower(name), id LIMIT ? OFFSET ?", JdbcCatalogStore::product, limit, offset);
    }

    public List<String> activeCategories() {
        return jdbc.queryForList("""
                SELECT min(category) FROM product WHERE archived_at IS NULL GROUP BY lower(category) ORDER BY lower(min(category))
                """, String.class);
    }

    public Optional<ProductRow> activeProduct(UUID id) {
        return jdbc.query("SELECT " + PRODUCT_COLUMNS + " FROM product WHERE id = ? AND archived_at IS NULL",
                JdbcCatalogStore::product, id).stream().findFirst();
    }

    public Optional<ProductRow> lockActiveProduct(UUID id) {
        return jdbc.query("SELECT " + PRODUCT_COLUMNS + " FROM product WHERE id = ? AND archived_at IS NULL FOR UPDATE",
                JdbcCatalogStore::product, id).stream().findFirst();
    }

    public boolean activeProductNamed(String name, UUID exceptId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM product WHERE archived_at IS NULL AND lower(name) = lower(?)
                                                     AND id IS DISTINCT FROM ?)
                """, Boolean.class, name, exceptId));
    }

    public void insertProduct(UUID id, String name, String category, String description, BigDecimal price,
            UUID sectorId, boolean available, UUID actorId, Instant now) {
        jdbc.update("""
                INSERT INTO product (id, establishment_id, name, category, description, price, sector_id, available,
                                     created_by, created_at, updated_by, updated_at)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, name, category, description, price, sectorId, available, actorId, Timestamp.from(now),
                actorId, Timestamp.from(now));
    }

    public boolean updateProduct(UUID id, long version, String name, String category, String description,
            BigDecimal price, UUID sectorId, UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE product SET name = ?, category = ?, description = ?, price = ?, sector_id = ?,
                                   updated_by = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND archived_at IS NULL
                """, name, category, description, price, sectorId, actorId, Timestamp.from(now), id, version) == 1;
    }

    public void setAvailability(UUID id, boolean available, UUID actorId, Instant now) {
        jdbc.update("""
                UPDATE product SET available = ?, updated_by = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND archived_at IS NULL
                """, available, actorId, Timestamp.from(now), id);
    }

    public void setPhoto(UUID id, UUID fileId, UUID actorId, Instant now) {
        jdbc.update("""
                UPDATE product SET photo_file_id = ?, updated_by = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND archived_at IS NULL
                """, fileId, actorId, Timestamp.from(now), id);
    }

    public boolean archiveProduct(UUID id, long version, UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE product SET archived_at = ?, updated_by = ?, updated_at = ?, version = version + 1
                 WHERE id = ? AND version = ? AND archived_at IS NULL
                """, Timestamp.from(now), actorId, Timestamp.from(now), id, version) == 1;
    }

    public void audit(String action, UUID subjectId, UUID actorId, String detail, String correlationId) {
        jdbc.update("""
                INSERT INTO audit_event (id, establishment_id, action, subject_id, actor_id, detail, correlation_id)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), action, subjectId, actorId, detail, correlationId);
    }

    private static SectorRow sector(ResultSet rs, int n) throws SQLException {
        return new SectorRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getLong("version"));
    }

    private static ProductRow product(ResultSet rs, int n) throws SQLException {
        return new ProductRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("category"),
                rs.getString("description"), rs.getBigDecimal("price"), rs.getObject("sector_id", UUID.class),
                rs.getBoolean("available"), rs.getObject("photo_file_id", UUID.class), rs.getLong("version"));
    }
}
