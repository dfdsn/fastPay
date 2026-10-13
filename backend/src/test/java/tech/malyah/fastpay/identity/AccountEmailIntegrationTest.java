package tech.malyah.fastpay.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;

import tech.malyah.fastpay.identity.application.PasswordResets;
import tech.malyah.fastpay.identity.application.PasswordResets.InvalidLink;
import tech.malyah.fastpay.identity.application.PasswordResets.PasswordChanged;
import tech.malyah.fastpay.jobs.application.JobWorker;
import tech.malyah.fastpay.mail.EmailMessage;
import tech.malyah.fastpay.mail.application.EmailPort;
import tech.malyah.fastpay.tenancy.TenantTransactions;
import tech.malyah.fastpay.testsupport.AdjustableClock;
import tech.malyah.fastpay.testsupport.PostgresIntegrationTest;

/**
 * E-mail durável da conta (H12): o pedido grava a intenção na fila; o envio repete em falha do servidor até 5
 * tentativas e depois fica na fila de falhas, sem link válido; o reenvio é um pedido novo pela mesma origem;
 * repetição depois de o servidor aceitar usa o mesmo Message-ID e deixa só o último link valendo; destinatário
 * fora da lista não recebe nem ganha link; o prazo do link conta do envio. Transporte controlado pelo teste.
 */
@SpringBootTest(properties = {
        "fastpay.jobs.initial-backoff=0s",
        "fastpay.jobs.max-backoff=0s",
        "fastpay.mail.allowed-recipients=@permitido.invalid"
})
@Import(AccountEmailIntegrationTest.Transporte.class)
class AccountEmailIntegrationTest extends PostgresIntegrationTest {

    static final String SENHA = "uma senha longa de teste";
    static final String NOVA_SENHA = "outra frase bem comprida";
    static final Pattern TOKEN = Pattern.compile("#token=([A-Za-z0-9_-]+)");

    /** Servidor de e-mail do teste: aceita, recusa as próximas N mensagens ou aceita e cai antes de responder. */
    static class ServidorControlado implements EmailPort {
        record Aceita(EmailMessage message, String messageId) {
        }

        final List<Aceita> aceitas = new ArrayList<>();
        final AtomicInteger recusar = new AtomicInteger();
        final AtomicInteger cairDepoisDeAceitar = new AtomicInteger();

        @Override
        public synchronized void send(EmailMessage message, String messageId) {
            if (recusar.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new MailSendException("Conexão recusada por smtp.segredo.interno para " + message.to());
            }
            aceitas.add(new Aceita(message, messageId));
            if (cairDepoisDeAceitar.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new MailSendException("Conexão encerrada antes da resposta");
            }
        }

        synchronized void reset() {
            aceitas.clear();
            recusar.set(0);
            cairDepoisDeAceitar.set(0);
        }
    }

    @TestConfiguration
    static class Transporte {
        @Bean
        @Primary
        ServidorControlado servidorControlado() {
            return new ServidorControlado();
        }

        @Bean
        @Primary
        AdjustableClock relogio() {
            return new AdjustableClock(Instant.now());
        }
    }

    @Autowired
    ServidorControlado servidor;

    @Autowired
    AdjustableClock relogio;

    @Autowired
    PasswordResets resets;

    @Autowired
    JobWorker worker;

    @Autowired
    TenantTransactions tenants;

    @Autowired
    JdbcTemplate jdbc;

    IdentityFixtures fixtures = new IdentityFixtures(admin());
    int origem;

    @BeforeEach
    void preparar() {
        // Contextos compartilham o banco: tira da fila o que outros testes deixaram.
        admin().update("""
                UPDATE fastpay.job SET status = 'SUCCEEDED', lease_until = NULL, lease_token = NULL
                WHERE status IN ('PENDING', 'RUNNING')
                """);
        servidor.reset();
    }

    @Test
    void falhaDoServidorTentaCincoVezesFicaNaFilaDeFalhasEOReenvioFunciona() {
        UUID ana = pessoaPermitida("Ana");
        servidor.recusar.set(5);

        pedir(ana);
        for (int i = 0; i < 5; i++) {
            assertThat(worker.runOnce()).isEqualTo(1);
        }

        assertThat(worker.runOnce()).isZero();
        Map<String, Object> job = jobDaConta(ana);
        assertThat(job).containsEntry("status", "EXHAUSTED").containsEntry("attempts", 5)
                .containsEntry("last_error", "Servidor de e-mail não aceitou a mensagem (MailSendException).");
        assertThat(servidor.aceitas).isEmpty();
        assertThat(links(ana)).isZero();

        // Reenvio: a pessoa pede de novo pela mesma tela, com os mesmos limites do pedido original.
        pedir(ana);
        entregar();

        assertThat(servidor.aceitas).singleElement()
                .satisfies(a -> assertThat(a.message().to()).isEqualTo(email(ana)));
        assertThat(resets.confirm(token(servidor.aceitas.getFirst()), NOVA_SENHA, novaOrigem()))
                .isInstanceOf(PasswordChanged.class);
    }

    @Test
    void repeticaoDepoisDeOServidorAceitarUsaOMesmoMessageIdESoOUltimoLinkVale() {
        UUID ana = pessoaPermitida("Ana");
        servidor.cairDepoisDeAceitar.set(1);

        pedir(ana);
        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(worker.runOnce()).isEqualTo(1);

        assertThat(servidor.aceitas).hasSize(2);
        assertThat(servidor.aceitas.get(0).messageId()).isEqualTo(servidor.aceitas.get(1).messageId());
        assertThat(jobDaConta(ana)).containsEntry("status", "SUCCEEDED").containsEntry("attempts", 2);
        assertThat(links(ana)).isEqualTo(1);
        assertThat(resets.confirm(token(servidor.aceitas.get(0)), NOVA_SENHA, novaOrigem()))
                .isInstanceOf(InvalidLink.class);
        assertThat(resets.confirm(token(servidor.aceitas.get(1)), NOVA_SENHA, novaOrigem()))
                .isInstanceOf(PasswordChanged.class);
    }

    @Test
    void destinatarioForaDaListaNaoRecebeNemGanhaLink() {
        UUID bruno = fixtures.identity("Bruno");
        fixtures.credential(bruno, SENHA);

        pedir(bruno);
        entregar();

        assertThat(servidor.aceitas).isEmpty();
        assertThat(jobDaConta(bruno)).containsEntry("status", "SUCCEEDED");
        assertThat(links(bruno)).isZero();
    }

    @Test
    void prazoDoLinkContaDoEnvioEVenceEmTrintaMinutos() {
        UUID ana = pessoaPermitida("Ana");
        UUID carla = pessoaPermitida("Carla");
        pedir(ana);
        pedir(carla);
        relogio.advance(Duration.ofMinutes(20));
        entregar();
        String linkAna = token(paraQuem(ana));
        String linkCarla = token(paraQuem(carla));

        relogio.advance(Duration.ofMinutes(29));
        assertThat(resets.confirm(linkAna, NOVA_SENHA, novaOrigem())).isInstanceOf(PasswordChanged.class);

        relogio.advance(Duration.ofMinutes(2));
        assertThat(resets.confirm(linkCarla, NOVA_SENHA, novaOrigem())).isInstanceOf(InvalidLink.class);
    }

    @Test
    void jobDaContaSoAparecePraPropriaPessoa() {
        UUID ana = pessoaPermitida("Ana");
        UUID bruno = pessoaPermitida("Bruno");
        UUID loja = fixtures.establishment("Bar da Praia");
        pedir(ana);

        String conta = "SELECT count(*) FROM job WHERE identity_id = ?";
        assertThat(tenants.asIdentity(ana, () -> jdbc.queryForObject(conta, Integer.class, ana))).isEqualTo(1);
        assertThat(tenants.asIdentity(bruno, () -> jdbc.queryForObject(conta, Integer.class, ana))).isZero();
        assertThat(tenants.inEstablishment(loja, () -> jdbc.queryForObject(conta, Integer.class, ana))).isZero();
        assertThat(jdbc.queryForObject(conta, Integer.class, ana)).isZero();
        // A fila guarda só a intenção: nenhum link nem token em claro.
        assertThat(admin().queryForObject("SELECT payload::text FROM fastpay.job WHERE identity_id = ?", String.class,
                ana)).isEqualTo("{}");
        assertThat(links(ana)).isZero();
    }

    private UUID pessoaPermitida(String nome) {
        UUID id = fixtures.identity(nome);
        admin().update("UPDATE fastpay.user_identity SET email = ? WHERE id = ?", email(id), id);
        fixtures.credential(id, SENHA);
        return id;
    }

    private static String email(UUID id) {
        return id + "@permitido.invalid";
    }

    private void pedir(UUID identidade) {
        String email = admin().queryForObject("SELECT email FROM fastpay.user_identity WHERE id = ?", String.class,
                identidade);
        assertThat(resets.request(email, novaOrigem())).isEqualTo(PasswordResets.RequestOutcome.ACCEPTED);
    }

    private void entregar() {
        while (worker.runOnce() > 0) {
            // até não haver mais jobs vencidos
        }
    }

    private String novaOrigem() {
        return "10.77." + (origem / 250) + "." + (origem++ % 250);
    }

    private Map<String, Object> jobDaConta(UUID identidade) {
        return admin().queryForMap("SELECT status, attempts, last_error FROM fastpay.job WHERE identity_id = ?",
                identidade);
    }

    private int links(UUID identidade) {
        return admin().queryForObject("""
                SELECT count(*) FROM fastpay.password_reset_token
                WHERE identity_id = ? AND used_at IS NULL AND invalidated_at IS NULL
                """, Integer.class, identidade);
    }

    private ServidorControlado.Aceita paraQuem(UUID identidade) {
        return servidor.aceitas.stream().filter(a -> a.message().to().equals(email(identidade))).findFirst()
                .orElseThrow();
    }

    private static String token(ServidorControlado.Aceita aceita) {
        Matcher matcher = TOKEN.matcher(aceita.message().body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
