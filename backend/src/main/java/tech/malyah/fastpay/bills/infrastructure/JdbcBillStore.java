package tech.malyah.fastpay.bills.infrastructure;

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

import tech.malyah.fastpay.bills.domain.BillRules.Kind;
import tech.malyah.fastpay.bills.domain.CouvertRules;
import tech.malyah.fastpay.bills.domain.CouvertRules.EndKind;
import tech.malyah.fastpay.bills.domain.CouvertRules.Origin;
import tech.malyah.fastpay.bills.domain.CouvertRules.Reason;

/**
 * Desconto e escolha do serviço (H22, V18) e couvert (H26, V22) da comanda, sempre na transação de unidade de quem
 * chama (RLS).
 */
@Repository
public class JdbcBillStore {

    private static final String DISCOUNT_COLUMNS = """
            id, tab_id, kind, amount, percent, reason, granted_by, granted_at, granter_limit_percent,
            consumption_at_grant, discount_at_grant, reviewed_by, reviewed_at, reviewed_limit_percent
            """;

    private final JdbcTemplate jdbc;

    JdbcBillStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Desconto ativo. {@code value}: reais (fixo) ou percentual. */
    public record DiscountRow(UUID id, UUID tabId, Kind kind, BigDecimal value, String reason, UUID grantedBy,
            Instant grantedAt, BigDecimal granterLimitPercent, BigDecimal consumptionAtGrant,
            BigDecimal discountAtGrant, UUID reviewedBy, Instant reviewedAt, BigDecimal reviewedLimitPercent) {

        /** Autoridade que hoje cobre o desconto: a de quem revisou por último ou, sem revisão, a de quem concedeu. */
        public BigDecimal authorityPercent() {
            return reviewedLimitPercent != null ? reviewedLimitPercent : granterLimitPercent;
        }
    }

    public Optional<DiscountRow> activeDiscount(UUID tabId) {
        return jdbc.query("SELECT " + DISCOUNT_COLUMNS + " FROM tab_discount WHERE tab_id = ? AND ended_at IS NULL",
                JdbcBillStore::discount, tabId).stream().findFirst();
    }

    public void insertDiscount(UUID id, UUID tabId, Kind kind, BigDecimal value, String reason, UUID grantedBy,
            Instant grantedAt, BigDecimal granterLimitPercent, BigDecimal consumption, BigDecimal discount) {
        jdbc.update("""
                INSERT INTO tab_discount (id, establishment_id, tab_id, kind, amount, percent, reason, granted_by,
                                          granted_at, granter_limit_percent, consumption_at_grant, discount_at_grant)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tabId, kind.name(), kind == Kind.FIXO ? value : null, kind == Kind.PERCENTUAL ? value : null,
                reason, grantedBy, Timestamp.from(grantedAt), granterLimitPercent, consumption, discount);
    }

    /** Encerra o desconto ativo (substituído ou retirado); {@code false} se já não estava ativo. */
    public boolean endDiscount(UUID id, String endKind, String endReason, UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE tab_discount SET ended_at = ?, ended_by = ?, end_kind = ?, end_reason = ?
                 WHERE id = ? AND ended_at IS NULL
                """, Timestamp.from(now), actorId, endKind, endReason, id) == 1;
    }

    public boolean reviewDiscount(UUID id, UUID actorId, BigDecimal limitPercent, Instant now) {
        return jdbc.update("""
                UPDATE tab_discount SET reviewed_by = ?, reviewed_at = ?, reviewed_limit_percent = ?
                 WHERE id = ? AND ended_at IS NULL
                """, actorId, Timestamp.from(now), limitPercent, id) == 1;
    }

    /** Última escolha do serviço: {@code true} retirado; vazio sem escolha (vale o padrão, incluído). */
    public Optional<Boolean> serviceWaived(UUID tabId) {
        return jdbc.query("SELECT waived FROM tab_service_choice WHERE tab_id = ? ORDER BY seq DESC LIMIT 1",
                (rs, n) -> rs.getBoolean("waived"), tabId).stream().findFirst();
    }

    /** Escolha da equipe ({@code chosenBy}) ou do cliente pelo acesso ({@code guestGrantId}). */
    public void insertServiceChoice(UUID tabId, boolean waived, UUID chosenBy, UUID guestGrantId, Instant now) {
        jdbc.update("""
                INSERT INTO tab_service_choice (id, establishment_id, tab_id, waived, chosen_by, guest_grant_id,
                                                chosen_at)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tabId, waived, chosenBy, guestGrantId, Timestamp.from(now));
    }

    // Couvert (H26, V22)

    /** Couvert lançado; {@code endKind} nulo = válido. */
    public record CouvertRow(UUID id, UUID tabId, Origin origin, BigDecimal amount, String reason, UUID createdBy,
            Instant createdAt, Instant endedAt, UUID endedBy, EndKind endKind, Reason endReasonKind,
            String endReason) {

        public CouvertRules.Charge charge() {
            return new CouvertRules.Charge(id, tabId, amount, endKind, endReasonKind);
        }
    }

    /** Couverts das comandas, válidos e encerrados, do mais antigo ao mais novo. */
    public List<CouvertRow> couverts(List<UUID> tabIds) {
        if (tabIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                SELECT id, tab_id, origin, amount, reason, created_by, created_at, ended_at, ended_by, end_kind,
                       end_reason_kind, end_reason
                  FROM couvert_charge WHERE tab_id = ANY (?) ORDER BY created_at, id
                """, ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", tabIds.toArray())),
                JdbcBillStore::couvert);
    }

    public void insertCouvert(UUID id, UUID tabId, Origin origin, BigDecimal amount, UUID settingsVersionId,
            String reason, UUID actorId, Instant now) {
        jdbc.update("""
                INSERT INTO couvert_charge (id, establishment_id, tab_id, origin, amount, settings_version_id, reason,
                                            created_by, created_at)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?, ?, ?)
                """, id, tabId, origin.name(), amount, settingsVersionId, reason, actorId, Timestamp.from(now));
    }

    /** Encerra o couvert válido; {@code false} se já não estava válido. */
    public boolean endCouvert(UUID id, EndKind kind, Reason reasonKind, String reason, UUID actorId, Instant now) {
        return jdbc.update("""
                UPDATE couvert_charge SET ended_at = ?, ended_by = ?, end_kind = ?, end_reason_kind = ?, end_reason = ?
                 WHERE id = ? AND ended_at IS NULL
                """, Timestamp.from(now), actorId, kind.name(), reasonKind == null ? null : reasonKind.name(), reason,
                id) == 1;
    }

    public void audit(String action, UUID subjectId, UUID actorId, String detail, String correlationId) {
        jdbc.update("""
                INSERT INTO audit_event (id, establishment_id, action, subject_id, actor_id, detail, correlation_id)
                VALUES (?, current_establishment_id(), ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), action, subjectId, actorId, detail, correlationId);
    }

    private static CouvertRow couvert(ResultSet rs, int n) throws SQLException {
        Timestamp endedAt = rs.getTimestamp("ended_at");
        String endKind = rs.getString("end_kind");
        String endReasonKind = rs.getString("end_reason_kind");
        return new CouvertRow(rs.getObject("id", UUID.class), rs.getObject("tab_id", UUID.class),
                Origin.valueOf(rs.getString("origin")), rs.getBigDecimal("amount"), rs.getString("reason"),
                rs.getObject("created_by", UUID.class), rs.getTimestamp("created_at").toInstant(),
                endedAt == null ? null : endedAt.toInstant(), rs.getObject("ended_by", UUID.class),
                endKind == null ? null : EndKind.valueOf(endKind),
                endReasonKind == null ? null : Reason.valueOf(endReasonKind), rs.getString("end_reason"));
    }

    private static DiscountRow discount(ResultSet rs, int n) throws SQLException {
        Kind kind = Kind.valueOf(rs.getString("kind"));
        Timestamp reviewedAt = rs.getTimestamp("reviewed_at");
        return new DiscountRow(rs.getObject("id", UUID.class), rs.getObject("tab_id", UUID.class), kind,
                kind == Kind.FIXO ? rs.getBigDecimal("amount") : rs.getBigDecimal("percent"), rs.getString("reason"),
                rs.getObject("granted_by", UUID.class), rs.getTimestamp("granted_at").toInstant(),
                rs.getBigDecimal("granter_limit_percent"), rs.getBigDecimal("consumption_at_grant"),
                rs.getBigDecimal("discount_at_grant"), rs.getObject("reviewed_by", UUID.class),
                reviewedAt == null ? null : reviewedAt.toInstant(), rs.getBigDecimal("reviewed_limit_percent"));
    }
}
