package tech.malyah.fastpay.identity;

import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.stereotype.Component;

/**
 * Leitura da identidade autenticada e da unidade escolhida, guardadas na sessão do servidor (nunca em
 * header ou parâmetro). O login (H08) grava a identidade; a seleção de unidade (H07) grava a unidade,
 * sempre depois de validar o vínculo. A unidade da sessão é só a escolha: cada operação revalida o
 * vínculo com {@link Memberships#inUnit}.
 */
@Component
public class SessionContext {

    public static final String IDENTITY_ATTRIBUTE = "fastpay.identityId";
    public static final String UNIT_ATTRIBUTE = "fastpay.establishmentId";

    /** @throws AuthenticationRequiredException sem identidade autenticada na sessão */
    public UUID requireIdentity(HttpServletRequest request) {
        return attribute(request, IDENTITY_ATTRIBUTE).orElseThrow(AuthenticationRequiredException::new);
    }

    public Optional<UUID> selectedUnit(HttpServletRequest request) {
        return attribute(request, UNIT_ATTRIBUTE);
    }

    /** @throws UnitNotSelectedException sem unidade escolhida nesta sessão */
    public UUID requireSelectedUnit(HttpServletRequest request) {
        return selectedUnit(request).orElseThrow(UnitNotSelectedException::new);
    }

    /**
     * Grava a unidade já validada e troca o id da sessão, porque o contexto de privilégio mudou
     * (especificação §5).
     */
    public void selectUnit(HttpServletRequest request, UUID establishmentId) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new AuthenticationRequiredException();
        }
        request.changeSessionId();
        session.setAttribute(UNIT_ATTRIBUTE, establishmentId);
    }

    /** Esquece a unidade escolhida; a identidade continua autenticada. */
    public void clearUnit(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(UNIT_ATTRIBUTE);
        }
    }

    private static Optional<UUID> attribute(HttpServletRequest request, String name) {
        HttpSession session = request.getSession(false);
        return session != null && session.getAttribute(name) instanceof UUID id ? Optional.of(id) : Optional.empty();
    }
}
