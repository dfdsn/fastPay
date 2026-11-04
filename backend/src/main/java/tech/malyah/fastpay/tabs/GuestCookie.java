package tech.malyah.fastpay.tabs;

import java.util.Arrays;
import java.util.List;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;

import tech.malyah.fastpay.tabs.infrastructure.TabsProperties;

/**
 * Acessos do consumidor avulso guardados no navegador (H15): o cookie {@code FASTPAY_ACESSO}, do caminho
 * {@code /api/v1/guest}, com até o máximo configurado de tokens separados por ponto. Ler o cookie não valida nada: a
 * validade é sempre conferida em {@link GuestAccess}. Público para outros módulos com telas do consumidor (H21).
 */
@Component
public class GuestCookie {

    public static final String NAME = "FASTPAY_ACESSO";

    private final TabsProperties properties;

    GuestCookie(TabsProperties properties) {
        this.properties = properties;
    }

    public List<String> tokens(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return List.of();
        }
        return Arrays.stream(cookies).filter(c -> NAME.equals(c.getName())).findFirst()
                .map(c -> Arrays.stream(c.getValue().split("\\.")).filter(s -> !s.isBlank())
                        .limit(properties.guestCookieMaxTabs()).toList())
                .orElse(List.of());
    }
}
