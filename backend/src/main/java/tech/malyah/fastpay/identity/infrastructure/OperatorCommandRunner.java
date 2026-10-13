package tech.malyah.fastpay.identity.infrastructure;

import java.io.PrintStream;
import java.time.Clock;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import tech.malyah.fastpay.identity.application.OperatorCommands;
import tech.malyah.fastpay.identity.application.OperatorCommands.IssuedLink;
import tech.malyah.fastpay.identity.application.OperatorCommands.Refused;
import tech.malyah.fastpay.mail.PublicLinks;

/**
 * Executa um comando de operador quando a aplicação sobe com {@code fastpay.operator.command} (H09, T-05)
 * e termina em seguida. Usa a credencial de migração ({@code spring.flyway.*}), nunca a do runtime. O
 * processo sobe como a aplicação (porta aleatória, sem worker de jobs), executa e sai com código 0 (feito)
 * ou 2 (recusado). Exemplo (Compose de desenvolvimento, da raiz):
 *
 * <pre>
 * docker compose --env-file .env -f infra/compose.dev.yml run --rm backend \
 *   --server.port=0 --fastpay.jobs.worker-enabled=false \
 *   --fastpay.operator.command=criar-admin-fastpay --fastpay.operator.email=admin@exemplo.com \
 *   --fastpay.operator.display-name="Nome" --fastpay.operator.operator="Quem executa" \
 *   --fastpay.operator.reason="Primeiro acesso da plataforma"
 * </pre>
 *
 * O link sai só na saída padrão; nada dele vai para o log.
 */
@Component
@ConditionalOnProperty("fastpay.operator.command")
class OperatorCommandRunner implements ApplicationRunner, ExitCodeGenerator {

    static final String CREATE_ADMIN = "criar-admin-fastpay";
    static final String RESET_MFA = "redefinir-mfa";

    private final ConfigurableApplicationContext context;
    private final Environment environment;
    private final PublicLinks links;
    private final Clock clock;
    private final PrintStream out;
    private int exitCode;

    OperatorCommandRunner(ConfigurableApplicationContext context, Environment environment, PublicLinks links,
            Clock clock) {
        this.context = context;
        this.environment = environment;
        this.links = links;
        this.clock = clock;
        this.out = System.out;
    }

    @Override
    public void run(ApplicationArguments args) {
        String command = environment.getProperty("fastpay.operator.command", "");
        var operatorDb = new DriverManagerDataSource(environment.getRequiredProperty("spring.flyway.url"),
                environment.getRequiredProperty("spring.flyway.user"),
                environment.getProperty("spring.flyway.password", ""));
        var commands = new OperatorCommands(operatorDb, links.passwordResetBase(), clock);
        String email = environment.getProperty("fastpay.operator.email");
        String operator = environment.getProperty("fastpay.operator.operator");
        String reason = environment.getProperty("fastpay.operator.reason");
        try {
            IssuedLink link = switch (command) {
                case CREATE_ADMIN -> commands.createPlatformAdmin(email,
                        environment.getProperty("fastpay.operator.display-name"), operator, reason);
                case RESET_MFA -> commands.resetMfa(email, operator, reason);
                default -> throw new Refused("Comando desconhecido. Use " + CREATE_ADMIN + " ou " + RESET_MFA + ".");
            };
            out.println("Comando " + command + " concluído.");
            out.println("Entregue este link à pessoa por um canal já confirmado. Ele vale até " + link.expiresAt()
                    + " (UTC) e só pode ser usado uma vez:");
            out.println(link.link());
            out.println("Depois de criar a senha e entrar, a pessoa cadastra o aplicativo autenticador.");
            exitCode = 0;
        } catch (Refused refused) {
            out.println("Comando recusado: " + refused.getMessage());
            exitCode = 2;
        }
        out.flush();
        // Comando avulso: encerra o processo (agendadores do Spring Session não deixam a JVM sair sozinha).
        System.exit(SpringApplication.exit(context, this));
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
