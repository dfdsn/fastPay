package tech.malyah.fastpay.identity.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tech.malyah.fastpay.identity.domain.AuthKeys;

/**
 * Comandos de operador da plataforma (H09, T-05, P-07), fora da aplicação web e sem cadastro público:
 * rodam por linha de comando com a credencial de migração, que fica com quem opera o servidor e nunca com
 * o runtime. Não existe credencial padrão: cada comando gera um link de uso único (30 minutos) para a
 * pessoa criar a própria senha; depois do login ela é obrigada a cadastrar o autenticador. O link sai
 * só na saída do comando, para o operador entregar à pessoa por um canal já confirmado; nunca vai para
 * o log. Tudo fica na trilha de segurança da identidade, com operador e motivo.
 */
public final class OperatorCommands {

    static final String BOOTSTRAP_LOCK = "fastpay.operador.admin-fastpay";

    /** Link de uso único entregue pelo operador à pessoa. */
    public record IssuedLink(UUID identityId, String link, Instant expiresAt) {
    }

    /** Pedido recusado; a mensagem é para o operador, em português. */
    public static class Refused extends RuntimeException {
        public Refused(String message) {
            super(message);
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final String linkBase;
    private final Clock clock;

    /** @param operatorDataSource conexão com a credencial de migração (dono do schema) */
    public OperatorCommands(DataSource operatorDataSource, String linkBase, Clock clock) {
        this.jdbc = new JdbcTemplate(operatorDataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(operatorDataSource));
        this.linkBase = Objects.requireNonNull(linkBase, "linkBase");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Cria o primeiro admin fastPay. Recusa se já existe algum (os seguintes chegam por convite,
     * H10/H11) ou se o e-mail já tem identidade (sem fusão implícita de privilégios).
     */
    public IssuedLink createPlatformAdmin(String email, String displayName, String operator, String reason) {
        String normalized = requireEmail(email);
        String name = requireText(displayName, 1, 120, "Informe o nome do admin fastPay (até 120 caracteres).");
        String actor = actor(operator);
        String why = requireReason(reason);
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))::text", String.class, BOOTSTRAP_LOCK);
            if (exists("SELECT 1 FROM user_identity WHERE platform_role = 'ADMIN_FASTPAY'")) {
                throw new Refused("Já existe um admin fastPay. Este comando só cria o primeiro; se o acesso foi "
                        + "perdido, use a recuperação excepcional (redefinir-mfa).");
            }
            if (exists("SELECT 1 FROM user_identity WHERE email = ?", normalized)) {
                throw new Refused("Este e-mail já tem uma identidade no fastPay. Use outro e-mail para o admin fastPay.");
            }
            UUID identityId = UUID.randomUUID();
            jdbc.update("INSERT INTO user_identity (id, email, display_name, platform_role) VALUES (?, ?, ?, 'ADMIN_FASTPAY')",
                    identityId, normalized, name);
            Instant now = clock.instant();
            IssuedLink link = issueLink(identityId, now);
            event(identityId, "admin_fastpay.criado", actor, why, now);
            return link;
        });
    }

    /**
     * Recuperação excepcional sem códigos (proposta P-07, aguarda aprovação de Diego): depois de o operador
     * confirmar a identidade da pessoa fora do sistema, remove o autenticador, os códigos e a senha,
     * encerra todas as sessões e gera o link de uso único. A pessoa cria a senha, entra e é obrigada a
     * cadastrar um autenticador novo se o perfil exigir. Vale também para o único admin fastPay.
     */
    public IssuedLink resetMfa(String email, String operator, String reason) {
        String normalized = requireEmail(email);
        String actor = actor(operator);
        String why = requireReason(reason);
        return transactions.execute(status -> {
            UUID identityId = jdbc.queryForList("SELECT id FROM user_identity WHERE email = ? FOR UPDATE", UUID.class,
                    normalized).stream().findFirst()
                    .orElseThrow(() -> new Refused("Nenhuma identidade com este e-mail."));
            Instant now = clock.instant();
            Timestamp at = Timestamp.from(now);
            jdbc.update("DELETE FROM user_mfa WHERE identity_id = ?", identityId);
            jdbc.update("""
                    UPDATE mfa_recovery_code SET invalidated_at = ?
                     WHERE identity_id = ? AND used_at IS NULL AND invalidated_at IS NULL
                    """, at, identityId);
            jdbc.update("DELETE FROM user_credential WHERE identity_id = ?", identityId);
            jdbc.update("DELETE FROM spring_session WHERE principal_name = ?", identityId.toString());
            IssuedLink link = issueLink(identityId, now);
            event(identityId, "mfa.recuperacao_excepcional", actor, why, now);
            return link;
        });
    }

    /** Invalida o link em aberto e cria outro (mesma regra e prazo da redefinição de senha). */
    private IssuedLink issueLink(UUID identityId, Instant now) {
        jdbc.update("""
                UPDATE password_reset_token SET invalidated_at = ?
                 WHERE identity_id = ? AND used_at IS NULL AND invalidated_at IS NULL
                """, Timestamp.from(now), identityId);
        String token = AuthKeys.newToken();
        Instant expiresAt = now.plus(PasswordResets.LINK_VALIDITY);
        jdbc.update("""
                INSERT INTO password_reset_token (id, identity_id, token_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), identityId, AuthKeys.sha256Hex(token), Timestamp.from(now),
                Timestamp.from(expiresAt));
        return new IssuedLink(identityId, linkBase + "#token=" + token, expiresAt);
    }

    private void event(UUID identityId, String action, String actor, String reason, Instant at) {
        jdbc.update("""
                INSERT INTO security_event (id, identity_id, occurred_at, action, actor, reason)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), identityId, Timestamp.from(at), action, actor, reason);
    }

    private boolean exists(String sql, Object... args) {
        return !jdbc.queryForList(sql, Integer.class, args).isEmpty();
    }

    private static String requireEmail(String email) {
        String normalized = AuthKeys.normalizeEmail(email);
        int at = normalized.indexOf('@');
        if (normalized.length() < 3 || normalized.length() > 254 || at < 1 || at == normalized.length() - 1
                || normalized.chars().anyMatch(Character::isWhitespace)) {
            throw new Refused("Informe um e-mail válido.");
        }
        return normalized;
    }

    private static String actor(String operator) {
        return "operador:" + requireText(operator, 1, 100, "Informe o nome de quem executa o comando (até 100 caracteres).");
    }

    private static String requireReason(String reason) {
        return requireText(reason, 10, 500, "Informe o motivo, com pelo menos 10 caracteres (até 500).");
    }

    private static String requireText(String value, int min, int max, String message) {
        String text = value == null ? "" : value.strip();
        if (text.length() < min || text.length() > max) {
            throw new Refused(message);
        }
        return text;
    }
}
