package tech.malyah.fastpay.identity.infrastructure;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.session.autoconfigure.DefaultCookieSerializerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import tech.malyah.fastpay.identity.domain.PasswordPolicy;

/**
 * Spring Security no fastPay (H08): proteção CSRF para o Angular (cookie {@code XSRF-TOKEN}, header
 * {@code X-XSRF-TOKEN}) em toda mutação, inclusive login, e cabeçalhos de segurança. A autorização não
 * fica aqui: cada operação exige a identidade da sessão ({@code SessionContext}) e revalida o vínculo
 * ({@code Memberships.inUnit}). Não há login por formulário, HTTP Basic nem usuário em memória.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
class AuthConfiguration {

    private static final String CSRF_PROBLEM = """
            {"type":"urn:fastpay:problema:pagina_desatualizada","title":"Forbidden","status":403,\
            "detail":"Sua página está desatualizada. Recarregue e tente novamente.","code":"PAGINA_DESATUALIZADA"}""";

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Hash com prefixo do algoritmo ({bcrypt}), para trocar de algoritmo sem invalidar senhas. */
    @Bean
    PasswordEncoder passwordEncoder(AuthProperties properties) {
        return new DelegatingPasswordEncoder("bcrypt",
                Map.of("bcrypt", new BCryptPasswordEncoder(properties.bcryptStrength())));
    }

    /**
     * Cookie da sessão do Spring Session (especificação §5): HttpOnly, SameSite=Lax e Secure. Definido
     * aqui, e não por {@code server.servlet.session.cookie.*}, para valer igual com servidor embutido e
     * nos testes.
     */
    @Bean
    DefaultCookieSerializerCustomizer sessionCookie(AuthProperties properties) {
        return serializer -> {
            serializer.setCookieName("FASTPAY_SESSION");
            serializer.setCookiePath("/");
            serializer.setUseHttpOnlyCookie(true);
            serializer.setUseSecureCookie(properties.sessionCookieSecure());
            serializer.setSameSite("Lax");
        };
    }

    @Bean
    PasswordPolicy passwordPolicy(AuthProperties properties) {
        return new PasswordPolicy(properties.passwordMinLength());
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(csrf -> csrf.spa())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors.accessDeniedHandler((request, response, denied) -> {
                    // Única recusa do Spring Security aqui é CSRF ausente ou inválido.
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.getWriter().write(CSRF_PROBLEM);
                }));
        return http.build();
    }
}
