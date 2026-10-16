package tech.malyah.fastpay.identity.web;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import tech.malyah.fastpay.identity.IdentityFixtures;
import tech.malyah.fastpay.identity.MfaFixtures;
import tech.malyah.fastpay.testsupport.AdjustableClock;

/**
 * Entrada de uma pessoa da equipe pela API, como o navegador faria (H11): senha, segundo fator quando a pessoa o
 * tem ou precisa dele (o autenticador de teste é cadastrado na hora) e, se pedida, a unidade da sessão.
 */
public final class StaffLogin {

    public static final String SENHA = "uma senha longa de teste";

    private final MockMvc mvc;
    private final IdentityFixtures fixtures;
    private final MfaFixtures mfa;
    private final AdjustableClock clock;

    public StaffLogin(MockMvc mvc, IdentityFixtures fixtures, MfaFixtures mfa, AdjustableClock clock) {
        this.mvc = mvc;
        this.fixtures = fixtures;
        this.mfa = mfa;
        this.clock = clock;
    }

    public Browser signIn(UUID identity) throws Exception {
        fixtures.credential(identity, SENHA);
        Browser browser = new Browser(mvc);
        String stage = stage(browser.login(IdentityFixtures.email(identity), SENHA).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        if (stage.equals("MFA_CADASTRO_OBRIGATORIO")) {
            mfa.enroll(identity);
            stage = stage(browser.login(IdentityFixtures.email(identity), SENHA).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
        }
        if (stage.equals("MFA_PENDENTE")) {
            browser.verify(mfa.nextCode(identity, clock.instant())).andExpect(status().isOk());
        }
        return browser;
    }

    /** Entra e escolhe a unidade. */
    public Browser inUnit(UUID identity, UUID establishment) throws Exception {
        Browser browser = signIn(identity);
        browser.put("/api/v1/session/unit", "{\"establishmentId\":\"" + establishment + "\"}")
                .andExpect(status().isOk());
        return browser;
    }

    private static String stage(String body) {
        return JsonPath.read(body, "$.stage");
    }
}
