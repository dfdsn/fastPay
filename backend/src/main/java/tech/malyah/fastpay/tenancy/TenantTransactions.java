package tech.malyah.fastpay.tenancy;

import java.util.LinkedHashMap;
import java.util.Map;
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

    /**
     * Transação nova com a unidade e a identidade no contexto: o admin fastPay cria a unidade e grava, na
     * mesma transação, o convite do dono, o job e a auditoria dela (H10); a operação de loja roda com a unidade
     * e quem age ({@code identity.Memberships.inUnit}, H11). Quem chama confirma o vínculo ou o papel da
     * identidade dentro do trabalho; o contexto da unidade não é prova de vínculo.
     */
    public <T> T inEstablishmentAs(UUID establishmentId, UUID identityId, Supplier<T> work) {
        Objects.requireNonNull(establishmentId, "establishmentId");
        Objects.requireNonNull(identityId, "identityId");
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("app.establishment_id", establishmentId.toString());
        settings.put("app.identity_id", identityId.toString());
        return inContext(settings, work);
    }

    /**
     * Como {@link #inEstablishmentAs}, numa transação somente leitura no PostgreSQL ({@code SET TRANSACTION READ
     * ONLY}, antes de qualquer consulta): qualquer escrita falha no banco, seja qual for o código chamado. Usada
     * pela consulta do suporte autorizado (H11, FR-66).
     */
    public <T> T readOnlyInEstablishmentAs(UUID establishmentId, UUID identityId, Supplier<T> work) {
        Objects.requireNonNull(establishmentId, "establishmentId");
        Objects.requireNonNull(identityId, "identityId");
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("app.establishment_id", establishmentId.toString());
        settings.put("app.identity_id", identityId.toString());
        return inContext(settings, true, work);
    }

    /**
     * Transação nova em que só o convite com este hash é visível (H10). Com {@code identityId}, também a
     * própria identidade: aceitar o convite com a conta existente ou com a conta criada nele.
     */
    public <T> T forInvitationToken(String tokenHash, UUID identityId, Supplier<T> work) {
        Objects.requireNonNull(tokenHash, "tokenHash");
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("app.invitation_token_hash", tokenHash);
        if (identityId != null) {
            settings.put("app.identity_id", identityId.toString());
        }
        return inContext(settings, work);
    }

    /**
     * Transação nova em que só o QR de vínculo com este hash é visível (H15): o consumidor que abriu o link, sem
     * sessão, descobre a unidade e a comanda do QR. O resto do trabalho roda depois em {@link #inEstablishment}.
     */
    public <T> T forBindingToken(String tokenHash, Supplier<T> work) {
        Objects.requireNonNull(tokenHash, "tokenHash");
        return inContext("app.binding_token_hash", tokenHash, work);
    }

    /** Transação nova em que só o acesso de consumidor com este hash é visível (H15), como {@link #forBindingToken}. */
    public <T> T forGuestGrant(String tokenHash, Supplier<T> work) {
        Objects.requireNonNull(tokenHash, "tokenHash");
        return inContext("app.guest_grant_hash", tokenHash, work);
    }

    /**
     * Transação nova em que só a conta de consumidor com este "sub" do Google é visível (H27): o retorno do login
     * entra na conta existente ou a cria, sem enxergar nenhuma outra.
     */
    public <T> T forGoogleSubject(String subject, Supplier<T> work) {
        Objects.requireNonNull(subject, "subject");
        return inContext("app.google_subject", subject, work);
    }

    /**
     * Transação nova com a conta do consumidor no contexto ({@code app.consumer_account_id}) e sem unidade (H27): só a
     * própria conta, os próprios vínculos e as comandas vinculadas por ela, em qualquer unidade.
     */
    public <T> T asConsumer(UUID consumerAccountId, Supplier<T> work) {
        Objects.requireNonNull(consumerAccountId, "consumerAccountId");
        return inContext("app.consumer_account_id", consumerAccountId.toString(), work);
    }

    /**
     * Transação nova com a unidade e a conta do consumidor (H27): vincular uma comanda da unidade à conta. Quem chama
     * confere o acesso do consumidor à comanda dentro do trabalho; o contexto não é prova de acesso.
     */
    public <T> T inEstablishmentAsConsumer(UUID establishmentId, UUID consumerAccountId, Supplier<T> work) {
        Objects.requireNonNull(establishmentId, "establishmentId");
        Objects.requireNonNull(consumerAccountId, "consumerAccountId");
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("app.establishment_id", establishmentId.toString());
        settings.put("app.consumer_account_id", consumerAccountId.toString());
        return inContext(settings, work);
    }

    public void runInEstablishment(UUID establishmentId, Runnable work) {
        Objects.requireNonNull(work, "work");
        inEstablishment(establishmentId, () -> {
            work.run();
            return null;
        });
    }

    private <T> T inContext(String setting, String value, Supplier<T> work) {
        return inContext(Map.of(setting, value), work);
    }

    private <T> T inContext(Map<String, String> settings, Supplier<T> work) {
        return inContext(settings, false, work);
    }

    private <T> T inContext(Map<String, String> settings, boolean readOnly, Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Contexto " + settings.keySet() + " exige transação própria; já existe uma ativa.");
        }
        return transactions.execute(status -> {
            if (readOnly) {
                jdbc.execute("SET TRANSACTION READ ONLY");
            }
            settings.forEach((setting, value) ->
                    jdbc.queryForObject("SELECT set_config(?, ?, true)", String.class, setting, value));
            return work.get();
        });
    }
}
