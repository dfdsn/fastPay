package tech.malyah.fastpay.mail;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import tech.malyah.fastpay.mail.infrastructure.MailProperties;

/**
 * Textos dos e-mails da equipe (H12, FR-27): convite, redefinição de senha e aviso de segurança. Texto
 * simples em português, sem dados além do necessário; horários no fuso configurado. O ambiente fica marcado
 * no envio ({@link Emails}), não aqui.
 */
@Component
public class EmailTemplates {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm");

    private final ZoneId zone;

    EmailTemplates(MailProperties properties) {
        this.zone = properties.timeZone();
    }

    /** Convite para uma unidade (H10/H11). O link vale 48 h e só uma vez; um convite novo anula o anterior. */
    public EmailMessage invitation(String to, String inviterName, String establishmentName, String roleLabel,
            String link, Instant expiresAt) {
        String body = """
                Olá.

                %s convidou você para participar de %s no fastPay como %s.

                Para aceitar, abra o link abaixo. Ele vale até %s e só pode ser usado uma vez. Se você já tem \
                conta no fastPay, entre com ela para aceitar; se não tem, você vai criar a sua senha. Ninguém \
                mais conhece essa senha, nem quem convidou você.

                %s

                Se você não esperava este convite, ignore esta mensagem.
                """.formatted(inviterName, establishmentName, roleLabel, when(expiresAt), link);
        return new EmailMessage(to, "Convite para " + establishmentName + " no fastPay", body);
    }

    public EmailMessage passwordReset(String to, String displayName, String link, Instant expiresAt) {
        String body = """
                Olá, %s.

                Para criar uma nova senha no fastPay, abra o link abaixo. Ele vale por 30 minutos, até %s, \
                e só pode ser usado uma vez:

                %s

                Se você não pediu a troca de senha, ignore esta mensagem. Sua senha atual continua valendo.
                """.formatted(displayName, when(expiresAt), link);
        return new EmailMessage(to, "Redefinição de senha do fastPay", body);
    }

    /** Aviso de que o autenticador e os códigos de recuperação foram trocados e as sessões encerradas (H09). */
    public EmailMessage mfaReplaced(String to, String displayName, Instant at) {
        String body = """
                Olá, %s.

                Em %s a verificação em duas etapas da sua conta no fastPay foi configurada de novo com um \
                código de recuperação. O autenticador e os códigos anteriores deixaram de valer e todas as \
                sessões abertas foram encerradas.

                Se não foi você, avise o administrador do fastPay imediatamente.
                """.formatted(displayName, when(at));
        return new EmailMessage(to, "Verificação em duas etapas reconfigurada no fastPay", body);
    }

    private String when(Instant instant) {
        String suffix = "America/Sao_Paulo".equals(zone.getId()) ? " (horário de Brasília)" : " (" + zone.getId() + ")";
        return WHEN.format(instant.atZone(zone)) + suffix;
    }
}
