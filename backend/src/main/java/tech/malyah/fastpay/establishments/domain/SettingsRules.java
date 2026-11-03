package tech.malyah.fastpay.establishments.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import tech.malyah.fastpay.establishments.AttendanceMode;
import tech.malyah.fastpay.establishments.CouvertMode;
import tech.malyah.fastpay.establishments.ExitMode;
import tech.malyah.fastpay.establishments.ServiceBase;
import tech.malyah.fastpay.establishments.SettingsValues;

/**
 * Validação da configuração da unidade (H14, FR-28–30, FR-23, FR-40, FR-57). Limites da proposta T-03 da H14:
 * serviço de 0% a 20% com até quatro casas (maior que zero quando habilitado); couvert de R$ 0,01 a R$ 200,00 por
 * pessoa; corte de 00:00 a 12:00; fuso IANA de região. Nada é arredondado: valor com mais casas é recusado.
 */
public final class SettingsRules {

    /** Percentuais com até 4 casas (especificação §7); dinheiro com 2. */
    public static final int PERCENT_SCALE = 4;
    public static final int MONEY_SCALE = 2;
    public static final BigDecimal SERVICE_MAX = new BigDecimal("20");
    public static final BigDecimal COUVERT_MIN = new BigDecimal("0.01");
    public static final BigDecimal COUVERT_MAX = new BigDecimal("200.00");

    private static final Pattern NUMBER = Pattern.compile("\\d{1,6}([.,]\\d{1,4})?");

    /** O que chegou da tela, campo a campo; nulos e textos inválidos são recusados com o campo e a mensagem. */
    public record Draft(Boolean serviceEnabled, String servicePercent, String serviceBase,
            Boolean serviceIncludesCouvert, Boolean couvertEnabled, String couvertMode, String couvertAmount,
            Boolean discountFixedAllowed, Boolean discountPercentAllowed, String attendanceMode, String exitMode,
            String timeZone, String cutoff) {
    }

    public sealed interface Result {
    }

    /** Configuração válida: valores da versão e calendário pedido. */
    public record Valid(SettingsValues values, ZoneId zone, LocalTime cutoff) implements Result {
    }

    /** Todos os campos recusados, na ordem da tela. */
    public record Invalid(List<Problem> problems) implements Result {
    }

    public record Problem(String field, String message) {
    }

    private SettingsRules() {
    }

    public static Result check(Draft draft) {
        List<Problem> found = new ArrayList<>();
        boolean serviceEnabled = flag(draft.serviceEnabled(), "serviceEnabled",
                "Informe se a taxa de serviço está habilitada.", found);
        Optional<BigDecimal> percent = decimal(draft.servicePercent(), PERCENT_SCALE)
                .filter(p -> p.signum() >= 0 && p.compareTo(SERVICE_MAX) <= 0);
        if (percent.isEmpty()) {
            found.add(new Problem("servicePercent",
                    "Informe o percentual do serviço entre 0 e 20, com até quatro casas decimais (ex.: 10 ou 12,5)."));
        } else if (serviceEnabled && percent.get().signum() == 0) {
            found.add(new Problem("servicePercent",
                    "Com o serviço habilitado, o percentual precisa ser maior que zero. Para não cobrar, desabilite."));
        }
        ServiceBase base = choice(ServiceBase.class, draft.serviceBase(), "serviceBase",
                "Escolha se o serviço é calculado antes ou depois do desconto.", found);
        boolean includesCouvert = flag(draft.serviceIncludesCouvert(), "serviceIncludesCouvert",
                "Informe se o couvert entra na base do serviço.", found);
        boolean couvertEnabled = flag(draft.couvertEnabled(), "couvertEnabled", "Informe se há couvert.", found);
        CouvertMode couvertMode = choice(CouvertMode.class, draft.couvertMode(), "couvertMode",
                "Escolha se o couvert é automático ou lançado pela equipe.", found);
        BigDecimal couvertAmount = null;
        boolean amountGiven = draft.couvertAmount() != null && !draft.couvertAmount().isBlank();
        if (amountGiven || couvertEnabled) {
            Optional<BigDecimal> amount = decimal(draft.couvertAmount(), MONEY_SCALE)
                    .filter(a -> a.compareTo(COUVERT_MIN) >= 0 && a.compareTo(COUVERT_MAX) <= 0);
            if (amount.isEmpty()) {
                found.add(new Problem("couvertAmount",
                        "Informe o couvert por pessoa entre R$ 0,01 e R$ 200,00, com até duas casas (ex.: 15,00)."));
            } else {
                couvertAmount = amount.get();
            }
        }
        boolean fixed = flag(draft.discountFixedAllowed(), "discountFixedAllowed",
                "Informe se o desconto em reais é aceito.", found);
        boolean percentual = flag(draft.discountPercentAllowed(), "discountPercentAllowed",
                "Informe se o desconto percentual é aceito.", found);
        AttendanceMode attendance = choice(AttendanceMode.class, draft.attendanceMode(), "attendanceMode",
                "Escolha atendimento por garçom responsável ou central.", found);
        ExitMode exit = choice(ExitMode.class, draft.exitMode(), "exitMode",
                "Escolha saída automática após quitação ou validada pela equipe.", found);
        Optional<ZoneId> zone = CalendarRules.zone(draft.timeZone());
        if (zone.isEmpty()) {
            found.add(new Problem("timeZone", "Escolha o fuso horário da unidade na lista (ex.: America/Sao_Paulo)."));
        }
        Optional<LocalTime> cutoff = CalendarRules.cutoff(draft.cutoff());
        if (cutoff.isEmpty()) {
            found.add(new Problem("cutoff",
                    "Informe o horário de corte do dia entre 00:00 e 12:00, no formato HH:MM (ex.: 06:00)."));
        }
        if (!found.isEmpty()) {
            return new Invalid(List.copyOf(found));
        }
        return new Valid(new SettingsValues(serviceEnabled, percent.get(), base, includesCouvert, couvertEnabled,
                couvertMode, couvertAmount, fixed, percentual, attendance, exit), zone.get(), cutoff.get());
    }

    /**
     * Número com até {@code scale} casas, com ponto ou vírgula decimal, sem milhar; devolvido com {@code scale}
     * casas. Vazio se o texto não é um número assim (mais casas são recusadas, nunca arredondadas).
     */
    public static Optional<BigDecimal> decimal(String raw, int scale) {
        if (raw == null) {
            return Optional.empty();
        }
        String s = raw.strip();
        if (s.startsWith("R$")) {
            s = s.substring(2).strip();
        }
        if (s.endsWith("%")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        if (!NUMBER.matcher(s).matches()) {
            return Optional.empty();
        }
        BigDecimal value = new BigDecimal(s.replace(',', '.'));
        if (value.scale() > scale) {
            return Optional.empty();
        }
        return Optional.of(value.setScale(scale, RoundingMode.UNNECESSARY));
    }

    /** Nomes dos campos que mudaram de uma versão para outra, para a auditoria. */
    public static List<String> changed(SettingsValues before, SettingsValues after) {
        List<String> names = new ArrayList<>();
        if (before.serviceEnabled() != after.serviceEnabled()
                || before.servicePercent().compareTo(after.servicePercent()) != 0
                || before.serviceBase() != after.serviceBase()
                || before.serviceIncludesCouvert() != after.serviceIncludesCouvert()) {
            names.add("servico");
        }
        if (before.couvertEnabled() != after.couvertEnabled() || before.couvertMode() != after.couvertMode()
                || !sameAmount(before.couvertAmount(), after.couvertAmount())) {
            names.add("couvert");
        }
        if (before.discountFixedAllowed() != after.discountFixedAllowed()
                || before.discountPercentAllowed() != after.discountPercentAllowed()) {
            names.add("desconto");
        }
        if (before.attendanceMode() != after.attendanceMode()) {
            names.add("atendimento");
        }
        if (before.exitMode() != after.exitMode()) {
            names.add("saida");
        }
        return names;
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static boolean flag(Boolean value, String field, String message, List<Problem> found) {
        if (value == null) {
            found.add(new Problem(field, message));
            return false;
        }
        return value;
    }

    private static <E extends Enum<E>> E choice(Class<E> type, String raw, String field, String message,
            List<Problem> found) {
        if (raw != null) {
            try {
                return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                // cai na recusa abaixo
            }
        }
        found.add(new Problem(field, message));
        return null;
    }
}
