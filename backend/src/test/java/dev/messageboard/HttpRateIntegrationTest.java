package dev.messageboard;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"board.rate.read=3","board.rate.auth=3","board.rate.write=3"})
@AutoConfigureMockMvc
class HttpRateIntegrationTest {
    static final EmbeddedPostgres PG;
    static { try { PG=EmbeddedPostgres.builder().setServerConfig("listen_addresses","127.0.0.1").start(); } catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",() -> PG.getJdbcUrl("postgres","postgres"));
        registry.add("spring.datasource.username",() -> "postgres");
        registry.add("spring.datasource.password",() -> "");
    }
    @Autowired MockMvc mvc;
    @AfterAll static void stop() throws Exception { PG.close(); }
    @Test void returns429AndDoesNotTrustSpoofedForwardingHeader() throws Exception {
        for(int i=0;i<3;i++) mvc.perform(get("/api/auth/me")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").header("X-Forwarded-For","192.0.2.200"))
            .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","60"))
            .andExpect(jsonPath("$.requestId").isNotEmpty());
    }
}
