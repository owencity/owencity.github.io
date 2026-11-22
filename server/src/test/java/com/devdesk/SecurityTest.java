package com.devdesk;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:security;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","devdesk.owner.password=test-password-16-characters",
    "devdesk.feed.enabled=false","devdesk.notifications.enabled=false","devdesk.notifications.scheduled-enabled=false"})
@AutoConfigureMockMvc
class SecurityTest {
    @Autowired MockMvc mvc;
    @Test void realOwnerPasswordCanLogIn()throws Exception {
        mvc.perform(formLogin().user("owner").password("test-password-16-characters"))
            .andExpect(authenticated().withUsername("owner"))
            .andExpect(redirectedUrl("/devdesk.html"));
    }
    @Test void privateApisReturn401AndHealthIsPublic()throws Exception {
        mvc.perform(get("/api/news")).andExpect(status().isUnauthorized());
        mvc.perform(get("/health")).andExpect(status().isOk());
    }
    @Test void updatesRequireCsrfEvenAfterLogin()throws Exception {
        mvc.perform(post("/api/news/1/feedback").with(user("owner").roles("OWNER"))
            .contentType("application/json").content("{\"feedback\":\"LATER\"}")).andExpect(status().isForbidden());
    }
    @Test void authenticatedSessionProvidesCsrfAndUnconfiguredCalendarIsExplicit()throws Exception {
        mvc.perform(get("/api/session").with(user("owner").roles("OWNER")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.csrfToken").isNotEmpty());
        mvc.perform(get("/api/calendar/today").with(user("owner").roles("OWNER")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.connected").value(false))
            .andExpect(jsonPath("$.events").isEmpty());
    }
}
