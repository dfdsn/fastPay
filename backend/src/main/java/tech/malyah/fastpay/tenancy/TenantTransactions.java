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
        Objects.requireNonNull(work, "work");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Contexto de estabelecimento exige transação própria; já existe uma ativa.");
        }
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.establishment_id', ?, true)", String.class,
                    establishmentId.toString());
            return work.get();
        });
    }

    public void runInEstablishment(UUID establishmentId, Runnable work) {
        Objects.requireNonNull(work, "work");
        inEstablishment(establishmentId, () -> {
            work.run();
            return null;
        });
    }
}
