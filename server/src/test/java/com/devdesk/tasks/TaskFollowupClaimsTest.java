package com.devdesk.tasks;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.*;

class TaskFollowupClaimsTest {
    @Test void databaseUniqueClaimSurvivesConcurrentWorkersAndUnknownOutcome() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:followup-" + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        var jdbc = new JdbcTemplate(ds);
        var claims = new TaskFollowupScheduler.JdbcClaims(jdbc);
        var now = Instant.parse("2026-11-24T09:00:00Z");
        try (var workers = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var work = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 8; i++) work.add(() -> {
                start.await(); return claims.claim("event", now, "slack", "a".repeat(64), now);
            });
            var futures = work.stream().map(workers::submit).toList(); start.countDown();
            int winners = 0;
            for (var result : futures) if (result.get()) winners++;
            assertThat(winners).isEqualTo(1);
        }
        claims.finish("event", now, "slack", "UNKNOWN", now);
        var afterRestart = new TaskFollowupScheduler.JdbcClaims(jdbc);
        assertThat(afterRestart.exists("event", now, "slack")).isTrue();
        assertThat(afterRestart.claim("event", now, "slack", "a".repeat(64), now)).isFalse();
        assertThat(afterRestart.claim("event", now.plusSeconds(60), "slack", "a".repeat(64), now)).isTrue();
        assertThat(afterRestart.claim("event", now, "email", "a".repeat(64), now)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_followup_delivery", Integer.class)).isEqualTo(3);
    }
}
