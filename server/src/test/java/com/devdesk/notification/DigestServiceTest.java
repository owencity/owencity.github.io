package com.devdesk.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DigestServiceTest {
    private JdbcTemplate jdbc;
    private DigestService service;
    private DigestComposer composer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE digest_delivery (digest_date DATE NOT NULL, channel VARCHAR(20) NOT NULL, "
            + "status VARCHAR(20) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, "
            + "updated_at TIMESTAMP WITH TIME ZONE NOT NULL, detail VARCHAR(250), PRIMARY KEY(digest_date, channel))");
        composer = mock(DigestComposer.class);
        ObjectProvider<JavaMailSender> mail = mock(ObjectProvider.class);
        service = new DigestService(jdbc, composer, mail, new ObjectMapper(), false, "", "", "");
    }

    @Test
    void uncertainDeliveryIsNotResentAfterRestartButDifferentChannelCanProceed() {
        var day = LocalDate.of(2026, 11, 22);
        assertTrue(service.claim(day, "slack"));
        assertFalse(service.claim(day, "slack"));
        jdbc.update("UPDATE digest_delivery SET status='UNKNOWN'");
        assertFalse(service.claim(day, "slack"));
        assertTrue(service.claim(day, "email"));
        assertTrue(service.claim(day.plusDays(1), "slack"));
    }

    @Test
    void definiteFailureMayBeRetriedOnceAtATime() {
        var day = LocalDate.of(2026, 11, 22);
        assertTrue(service.claim(day, "email"));
        jdbc.update("UPDATE digest_delivery SET status='FAILED'");
        assertTrue(service.claim(day, "email"));
        assertFalse(service.claim(day, "email"));
    }

    @Test
    void sendsAreOptInAndDoNotEvenComposeWhenDisabled() {
        var failure = assertThrows(ResponseStatusException.class, () -> service.send("email"));
        assertEquals(409, failure.getStatusCode().value());
        verifyNoInteractions(composer);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM digest_delivery", Integer.class));
    }

    @Test
    void webhookMustBeAnExactSlackHttpsEndpoint() {
        assertTrue(DigestService.validSlackWebhook("https://hooks.slack.com/services/T/B/secret"));
        assertFalse(DigestService.validSlackWebhook("http://hooks.slack.com/services/T/B/secret"));
        assertFalse(DigestService.validSlackWebhook("https://hooks.slack.com.evil.test/services/T/B/secret"));
        assertFalse(DigestService.validSlackWebhook("https://hooks.slack.com@127.0.0.1/services/T/B/secret"));
        assertFalse(DigestService.validSlackWebhook("https://hooks.slack.com/services/T/B/secret?other=1"));
        assertFalse(DigestService.validSlackWebhook("https://127.0.0.1/services/T/B/secret"));
    }
}
