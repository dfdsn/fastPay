package tech.malyah.fastpay.identity.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.http.Cookie;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Navegador de teste sobre o MockMvc: guarda cookies entre requisições (sessão e {@code XSRF-TOKEN}),
 * manda o header CSRF como o Angular e usa um endereço de origem próprio, para que limites por origem
 * de um teste não afetem outro.
 */
public final class Browser {

    private static final AtomicInteger ADDRESSES = new AtomicInteger();

    private final MockMvc mvc;
    private final String address;
    private final Map<String, Cookie> cookies = new LinkedHashMap<>();

    public Browser(MockMvc mvc) {
        this.mvc = mvc;
        int n = ADDRESSES.incrementAndGet();
        this.address = "10.%d.%d.%d".formatted((n >> 16) & 0xff, (n >> 8) & 0xff, n & 0xff);
    }

    public ResultActions get(String url) throws Exception {
        return send(MockMvcRequestBuilders.get(url));
    }

    public ResultActions background(String url) throws Exception {
        return send(MockMvcRequestBuilders.get(url)
                .header(SessionExpiryFilter.BACKGROUND_HEADER, "1"));
    }

    public ResultActions post(String url, String json) throws Exception {
        return mutate(MockMvcRequestBuilders.post(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    public ResultActions put(String url, String json) throws Exception {
        return mutate(MockMvcRequestBuilders.put(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    public ResultActions delete(String url) throws Exception {
        return mutate(MockMvcRequestBuilders.delete(url));
    }

    /** Envia como está, sem buscar nem mandar o token CSRF. */
    public ResultActions send(MockHttpServletRequestBuilder request) throws Exception {
        request.with(r -> {
            r.setRemoteAddr(address);
            return r;
        });
        if (!cookies.isEmpty()) {
            request.cookie(cookies.values().toArray(Cookie[]::new));
        }
        ResultActions result = mvc.perform(request);
        for (Cookie cookie : result.andReturn().getResponse().getCookies()) {
            if (cookie.getMaxAge() == 0) {
                cookies.remove(cookie.getName());
            } else {
                cookies.put(cookie.getName(), cookie);
            }
        }
        return result;
    }

    public ResultActions login(String email, String password) throws Exception {
        return post("/api/v1/auth/login", "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
    }

    /** Segundo passo do login (H09): código do autenticador ou de recuperação. */
    public ResultActions verify(String code) throws Exception {
        return post("/api/v1/auth/mfa/verify", "{\"code\":\"%s\"}".formatted(code));
    }

    public Optional<String> cookie(String name) {
        return Optional.ofNullable(cookies.get(name)).map(Cookie::getValue);
    }

    /** Leva os cookies de sessão deste navegador para outro (outra aba, ou um atacante com o cookie). */
    public void copyCookiesTo(Browser other) {
        other.cookies.putAll(cookies);
    }

    private ResultActions mutate(MockHttpServletRequestBuilder request) throws Exception {
        if (!cookies.containsKey("XSRF-TOKEN")) {
            send(MockMvcRequestBuilders.get("/api/v1/platform/info"));
        }
        request.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN").getValue());
        return send(request);
    }
}
