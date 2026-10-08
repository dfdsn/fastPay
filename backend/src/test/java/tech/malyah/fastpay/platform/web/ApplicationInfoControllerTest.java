package tech.malyah.fastpay.platform.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "fastpay.environment=test")
@AutoConfigureMockMvc
class ApplicationInfoControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void saudeRespondeUpSemDetalhes() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void informaIdentificacaoDaAplicacao() throws Exception {
        mvc.perform(get("/api/v1/platform/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("fastPay"))
                .andExpect(jsonPath("$.environment").value("test"))
                .andExpect(jsonPath("$.version").isNotEmpty());
    }

    @Test
    void rotaInexistenteRespondeProblemDetailSemStacktrace() throws Exception {
        mvc.perform(get("/api/v1/nao-existe"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().string(not(containsString("Exception"))));
    }
}
