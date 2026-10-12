package tech.malyah.fastpay.tenancy;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executa trabalho numa transação nova com o contexto do estabelecimento definido no PostgreSQL.
 *
 * <p>O contexto é gravado com {@code set_config('app.establishment_id', ?, true)}: vale só para a
 * transação corrente e é descartado no commit ou no rollback, então uma conexão devolvida ao pool não
 * carrega a unidade anterior. Sem contexto, as policies de RLS não liberam nenhuma linha.
 *
 * <p>Não pode ser chamado dentro de outra transação: trocar de unidade no meio de uma transação
 * misturaria dados de estabelecimentos diferentes.
 */
@Component
public class TenantTransactions {

    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;

    TenantTransactions(PlatformTransactionManager transactionManager, JdbcTemplate jdbc) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.jdbc = jdbc;
    }

    public <T> T inEstablishment(UUID establishmentId, Supplier<T> work) {
        Objects.requireNonNull(establishmentId, "establishmentId");
        return inContext("app.establishment_id", establishmentId.toString(), work);
    }

    /**
     * Transação nova com a identidade no contexto ({@code app.identity_id}) e sem unidade: só enxerga o que
     * pertence à própria pessoa, como os seus vínculos para escolher a unidade (H07). Mesmas garantias de
     * escopo de {@link #inEstablishment}.
     */
    public <T> T asIdentity(UUID identityId, Supplier<T> work) {
        Objects.requireNonNull(identityId, "identityId");
        return inContext("app.identity_id", identityId.toString(), work);
    }

    /**
     * Transação nova em que só a identidade com este e-mail (já normalizado) é visível, sem credencial:
     * localizar a conta no login ou no pedido de redefinição de senha (H08).
     */
    public <T> T forLoginEmail(String normalizedEmail, Supplier<T> work) {
        Objects.requireNonNull(normalizedEmail, "normalizedEmail");
        return inContext("app.login_email", normalizedEmail, work);
    }

    /** Transação nova em que só o token de redefinição com este hash é visível (H08). */
    public <T> T forResetToken(String tokenHash, Supplier<T> work) {
        Objects.requireNonNull(tokenHash, "tokenHash");
        return inContext("app.reset_token_hash", tokenHash, work);
    }

    public void runInEstablishment(UUID establishmentId, Runnable work) {
        Objects.requireNonNull(work, "work");
        inEstablishment(establishmentId, () -> {
            work.run();
            return null;
        });
    }

    private <T> T inContext(String setting, String value, Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Contexto " + setting + " exige transação própria; já existe uma ativa.");
        }
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config(?, ?, true)", String.class, setting, value);
            return work.get();
        });
    }
}
