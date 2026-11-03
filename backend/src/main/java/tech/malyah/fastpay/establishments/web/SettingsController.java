package tech.malyah.fastpay.establishments.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.establishments.AttendanceMode;
import tech.malyah.fastpay.establishments.CouvertMode;
import tech.malyah.fastpay.establishments.EstablishmentSettings;
import tech.malyah.fastpay.establishments.ExitMode;
import tech.malyah.fastpay.establishments.OperationalDay;
import tech.malyah.fastpay.establishments.ServiceBase;
import tech.malyah.fastpay.establishments.SettingsValues;
import tech.malyah.fastpay.establishments.domain.SettingsRules;
import tech.malyah.fastpay.identity.SessionContext;

/**
 * Configuração da unidade escolhida na sessão (H14). Ver: gerente e administrador; salvar: administrador
 * (403 {@code PERMISSAO_NEGADA} para os demais). Valores monetários e percentuais como texto decimal com duas casas.
 * Salvar exige a versão lida ({@code version}): 409 {@code VERSAO_DESATUALIZADA} se outra pessoa salvou antes; 400
 * {@code DADOS_INVALIDOS} com o primeiro campo recusado em {@code field} e todos em {@code problems}.
 */
@RestController
@RequestMapping("/api/v1/establishment/settings")
class SettingsController {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");

    private final EstablishmentSettings settings;
    private final SessionContext session;

    SettingsController(EstablishmentSettings settings, SessionContext session) {
        this.settings = settings;
        this.session = session;
    }

    @GetMapping
    SettingsView view(HttpServletRequest request) {
        return SettingsView.of(settings.view(session.requireIdentity(request), session.requireSelectedUnit(request)));
    }

    @PutMapping
    SettingsView update(@RequestBody UpdateRequest body, HttpServletRequest request) {
        UUID actor = session.requireIdentity(request);
        UUID unit = session.requireSelectedUnit(request);
        if (body.version() == null) {
            throw problem(HttpStatus.BAD_REQUEST, "REQUISICAO_INVALIDA",
                    "Recarregue a configuração antes de salvar.");
        }
        SettingsRules.Draft draft = new SettingsRules.Draft(body.serviceEnabled(), body.servicePercent(),
                body.serviceBase(), body.serviceIncludesCouvert(), body.couvertEnabled(), body.couvertMode(),
                body.couvertAmount(), body.discountFixedAllowed(), body.discountPercentAllowed(),
                body.attendanceMode(), body.exitMode(), body.timeZone(), body.cutoff());
        return switch (settings.update(actor, unit, body.version(), draft, UUID.randomUUID().toString())) {
            case EstablishmentSettings.Done done -> SettingsView.of(done.view());
            case EstablishmentSettings.Rejected rejected -> {
                SettingsRules.Problem first = rejected.problems().getFirst();
                ErrorResponseException e = problem(HttpStatus.BAD_REQUEST, "DADOS_INVALIDOS", first.message());
                e.getBody().setProperty("field", first.field());
                e.getBody().setProperty("problems", rejected.problems());
                throw e;
            }
            case EstablishmentSettings.Outdated outdated -> throw problem(HttpStatus.CONFLICT, "VERSAO_DESATUALIZADA",
                    "Outra pessoa salvou a configuração enquanto você editava. Recarregue e confira antes de salvar.");
        };
    }

    private static ErrorResponseException problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:fastpay:problema:" + code.toLowerCase()));
        problem.setProperty("code", code);
        return new ErrorResponseException(status, problem, null);
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.setScale(2).toPlainString();
    }

    private static String hour(LocalTime time) {
        return time.format(HOUR);
    }

    /** Números aceitos como texto ou número JSON. */
    record UpdateRequest(Integer version, Boolean serviceEnabled, String servicePercent, String serviceBase,
            Boolean serviceIncludesCouvert, Boolean couvertEnabled, String couvertMode, String couvertAmount,
            Boolean discountFixedAllowed, Boolean discountPercentAllowed, String attendanceMode, String exitMode,
            String timeZone, String cutoff) {
    }

    record CalendarInUseView(String timeZone, String cutoff, Instant since) {
    }

    record PendingView(String timeZone, String cutoff, Instant effectiveFrom, LocalDate firstDate) {
    }

    record DayView(LocalDate date, Instant start, Instant end, String timeZone, String cutoff) {
        static DayView of(OperationalDay day) {
            return new DayView(day.date(), day.start(), day.end(), day.timeZone(), hour(day.cutoff()));
        }
    }

    record HistoryView(int version, Instant createdAt, String author, List<String> changed) {
    }

    /**
     * {@code timeZone}/{@code cutoff}: o calendário configurado (a mudança agendada, se houver, senão o em uso), que
     * a tela edita; {@code calendarInUse}: o que classifica o dia agora.
     */
    record SettingsView(int version, UUID versionId, Instant savedAt, boolean serviceEnabled, String servicePercent,
            ServiceBase serviceBase, boolean serviceIncludesCouvert, boolean couvertEnabled, CouvertMode couvertMode,
            String couvertAmount, boolean discountFixedAllowed, boolean discountPercentAllowed,
            AttendanceMode attendanceMode, ExitMode exitMode, String timeZone, String cutoff,
            CalendarInUseView calendarInUse, PendingView pendingCalendar, DayView today, List<HistoryView> history, boolean editable) {

        static SettingsView of(EstablishmentSettings.View view) {
            SettingsValues v = view.current().values();
            EstablishmentSettings.PendingCalendar p = view.pendingCalendar();
            return new SettingsView(view.current().number(), view.current().id(), view.current().createdAt(),
                    v.serviceEnabled(), decimal(v.servicePercent()), v.serviceBase(), v.serviceIncludesCouvert(),
                    v.couvertEnabled(), v.couvertMode(), decimal(v.couvertAmount()), v.discountFixedAllowed(),
                    v.discountPercentAllowed(), v.attendanceMode(), v.exitMode(),
                    p == null ? view.calendar().timeZone() : p.timeZone(),
                    hour(p == null ? view.calendar().cutoff() : p.cutoff()),
                    new CalendarInUseView(view.calendar().timeZone(), hour(view.calendar().cutoff()),
                            view.calendar().since()),
                    p == null ? null : new PendingView(p.timeZone(), hour(p.cutoff()), p.effectiveFrom(),
                            p.firstDate()),
                    DayView.of(view.today()),
                    view.history().stream().map(h -> new HistoryView(h.number(), h.createdAt(), h.author(),
                            h.changed())).toList(),
                    view.editable());
        }
    }
}
