package tech.malyah.fastpay.establishments.web;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import tech.malyah.fastpay.establishments.application.EstablishmentRegistry;
import tech.malyah.fastpay.establishments.application.EstablishmentRegistry.Created;
import tech.malyah.fastpay.establishments.application.EstablishmentRegistry.Draft;
import tech.malyah.fastpay.establishments.application.EstablishmentRegistry.Establishment;
import tech.malyah.fastpay.establishments.application.EstablishmentRegistry.NotFound;
import tech.malyah.fastpay.establishments.application.EstablishmentRegistry.OwnerAlreadyAccepted;
import tech.malyah.fastpay.establishments.application.EstablishmentRegistry.Rejected;
import tech.malyah.fastpay.establishments.application.EstablishmentRegistry.Resent;
import tech.malyah.fastpay.identity.Invitations;
import tech.malyah.fastpay.identity.SessionContext;

/**
 * Cadastro de estabelecimentos na área do admin fastPay (H10). Só a identidade com papel global de admin
 * fastPay, já com a verificação em duas etapas, usa estes endpoints; as demais recebem 403
 * {@code ACESSO_RESTRITO_PLATAFORMA}. O papel é conferido no banco em cada chamada.
 */
@RestController
@RequestMapping("/api/v1/establishments")
class EstablishmentController {

    private final EstablishmentRegistry registry;
    private final SessionContext session;

    EstablishmentController(EstablishmentRegistry registry, SessionContext session) {
        this.registry = registry;
        this.session = session;
    }

    @GetMapping
    List<EstablishmentView> list(HttpServletRequest request) {
        return registry.list(session.requireIdentity(request)).stream().map(EstablishmentView::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    EstablishmentView create(@RequestBody CreateRequest body, HttpServletRequest request) {
        UUID actorId = session.requireIdentity(request);
        return switch (registry.create(actorId, new Draft(body.name(), body.ownerEmail(), body.document()),
                UUID.randomUUID().toString())) {
            case Created created -> EstablishmentView.of(created.establishment());
            case Rejected rejected -> {
                ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "DADOS_RECUSADOS", rejected.reason());
                problem.setProperty("field", rejected.field());
                throw new ErrorResponseException(HttpStatus.BAD_REQUEST, problem, null);
            }
        };
    }

    /** Novo convite para o dono; o anterior deixa de valer. */
    @PostMapping("/{id}/owner-invitation")
    EstablishmentView resendOwnerInvitation(@PathVariable UUID id, HttpServletRequest request) {
        UUID actorId = session.requireIdentity(request);
        return switch (registry.resendOwnerInvitation(actorId, id, UUID.randomUUID().toString())) {
            case Resent resent -> EstablishmentView.of(resent.establishment());
            case NotFound notFound -> throw error(HttpStatus.NOT_FOUND, "ESTABELECIMENTO_NAO_ENCONTRADO",
                    "Estabelecimento não encontrado.");
            case OwnerAlreadyAccepted accepted -> throw error(HttpStatus.CONFLICT, "DONO_JA_ACEITOU",
                    "O dono já aceitou o convite. A equipe passa a ser convidada por ele.");
        };
    }

    record CreateRequest(String name, String ownerEmail, String document) {
    }

    record OwnerInvitationView(Invitations.Status status, Instant sentAt, Instant expiresAt, Instant acceptedAt) {
    }

    record EstablishmentView(UUID id, String name, String ownerEmail, String document, String status,
            OwnerInvitationView ownerInvitation) {
        static EstablishmentView of(Establishment e) {
            return new EstablishmentView(e.id(), e.name(), e.ownerEmail(), e.document(), e.status(),
                    e.ownerInvitation().map(i -> new OwnerInvitationView(i.status(), i.sentAt(), i.expiresAt(),
                            i.acceptedAt())).orElse(null));
        }
    }

    private static ErrorResponseException error(HttpStatus status, String code, String detail) {
        return new ErrorResponseException(status, problem(status, code, detail), null);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:fastpay:problema:" + code.toLowerCase()));
        problem.setProperty("code", code);
        return problem;
    }
}
