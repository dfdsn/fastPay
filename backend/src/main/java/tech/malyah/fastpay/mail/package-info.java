/**
 * E-mail (H12, FR-27, especificação §9): modelos de convite, redefinição de senha e avisos, links com o
 * endereço público configurado, lista de destinatários permitidos fora de produção e envio por SMTP ou
 * pela caixa de teste. O envio durável é feito por executores da fila de jobs dos módulos donos do
 * conteúdo, que chamam {@link tech.malyah.fastpay.mail.Emails}: a falha do servidor vira nova tentativa
 * (5 no total) e depois fila de falhas. API pública: {@link tech.malyah.fastpay.mail.Emails},
 * {@link tech.malyah.fastpay.mail.EmailTemplates}, {@link tech.malyah.fastpay.mail.PublicLinks} e
 * {@link tech.malyah.fastpay.mail.EmailMessage}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "E-mail")
package tech.malyah.fastpay.mail;
