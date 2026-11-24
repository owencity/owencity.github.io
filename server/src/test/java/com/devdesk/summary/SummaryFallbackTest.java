package com.devdesk.summary;

import com.devdesk.summary.source.ArticleContentService;
import com.devdesk.summary.source.SourceContent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static com.devdesk.summary.SummaryGenerationException.Category.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SummaryFallbackTest {
    private static final String PRIMARY = "gemini-3.5-flash-lite";
    private static final String FALLBACK = "gemini-3.1-flash-lite";
    private static final List<String> MODELS = List.of(PRIMARY, FALLBACK);
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 22);
    private JdbcTemplate jdbc;
    private SummaryRepository repository;
    private SummarySettings settings;
    private ArticleContentService sources;
    private SummaryGenerator generator;
    private ObjectMapper json;

    @BeforeEach void prepare() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:fallback-" + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        repository = new SummaryRepository(jdbc);
        settings = mock(SummarySettings.class);
        when(settings.enabled()).thenReturn(true);
        when(settings.configured()).thenReturn(true);
        when(settings.model()).thenReturn(PRIMARY);
        when(settings.modelOrder()).thenReturn(MODELS);
        when(settings.modelDailyLimit()).thenReturn(500);
        when(settings.dailyLimit()).thenReturn(20);
        when(settings.maxAttempts()).thenReturn(3);
        when(settings.batchSize()).thenReturn(3);
        sources = mock(ArticleContentService.class);
        when(sources.fetch(anyString(), anyString(), anyString())).thenAnswer(call ->
            new SourceContent(call.getArgument(0), call.getArgument(1), "Article facts and developer context.", "ARTICLE_BODY", null));
        generator = mock(SummaryGenerator.class);
        when(generator.generateForModel(any(), anyString())).thenReturn(result());
        json = new ObjectMapper().findAndRegisterModules();
    }

    private SummaryResult result() {
        return new SummaryResult(List.of(new SummaryResult.Section("Facts", List.of("Complete relevant facts."))), List.of("Developer interpretation."), List.of());
    }
    private SummaryJob job(Instant time) {
        return new SummaryJob(repository, settings, sources, generator, json, Clock.fixed(time, ZoneOffset.UTC));
    }
    private SummaryService service(Instant time) {
        return new SummaryService(repository, settings, json, Clock.fixed(time, ZoneOffset.UTC));
    }
    private long queue(String name) {
        jdbc.update("INSERT INTO news_item(source_id,title,url,description,collected_at,topic) VALUES (?,?,?,?,?,?)",
            name, name, "https://example.com/" + name, "Feed description", NOW.atOffset(ZoneOffset.UTC), "Java");
        long id = jdbc.queryForObject("SELECT id FROM news_item WHERE source_id=?", Long.class, name);
        repository.enqueue(id, NOW);
        return id;
    }
    private void modelUsage(String model, int used) {
        jdbc.update("INSERT INTO summary_daily_budget(budget_date,attempts,model_accounted) SELECT ?,0,TRUE WHERE NOT EXISTS (SELECT 1 FROM summary_daily_budget WHERE budget_date=?)", DAY, DAY);
        jdbc.update("INSERT INTO summary_model_daily_budget(budget_date,model,attempts) VALUES (?,?,?)", DAY, model, used);
    }
    private SummaryGenerationException limited(SummaryGenerationException.Category category) {
        return new SummaryGenerationException(category, "Safe categorized failure");
    }

    @Test void fullPrimaryLocalBudgetSkipsItsCallAndConsumesOnlyOneGlobalAttempt() {
        when(settings.dailyLimit()).thenReturn(1);
        modelUsage(PRIMARY, 500);
        long id = queue("local-primary-full");
        job(NOW).process(repository.claimNext(NOW, 3));
        assertThat(repository.find(id).model()).isEqualTo(FALLBACK);
        assertThat(repository.find(id).attempts()).isEqualTo(1);
        assertThat(repository.attemptsToday(DAY)).isEqualTo(1);
        assertThat(repository.modelBudget(DAY, PRIMARY).attempts()).isEqualTo(500);
        assertThat(repository.modelBudget(DAY, FALLBACK).attempts()).isEqualTo(1);
        verify(generator).generateForModel(any(), eq(FALLBACK));
        verify(generator, never()).generateForModel(any(), eq(PRIMARY));
    }

    @Test void confirmedModelDailyQuotaQueuesFallbackAndSavesItsActualModel() {
        when(generator.generateForModel(any(), eq(PRIMARY))).thenThrow(limited(DAILY_MODEL_LIMIT));
        long id = queue("daily-model");
        job(NOW).process(repository.claimNext(NOW, 3));
        assertThat(repository.find(id).status()).isEqualTo("QUEUED");
        assertThat(repository.find(id).retryModel()).isNull();
        assertThat(repository.modelBudget(DAY, PRIMARY).blockedUntil()).isEqualTo(Instant.parse("2026-11-23T08:00:00Z"));
        job(NOW).process(repository.claimNext(NOW, 3));
        assertThat(service(NOW).get(id).model()).isEqualTo(FALLBACK);
        assertThat(repository.find(id).attempts()).isEqualTo(2);
        assertThat(repository.attemptsToday(DAY)).isEqualTo(2);
        assertThat(repository.modelBudget(DAY, PRIMARY).attempts()).isEqualTo(1);
        assertThat(repository.modelBudget(DAY, FALLBACK).attempts()).isEqualTo(1);
        verify(sources, times(1)).fetch(anyString(), anyString(), anyString());
    }

    @Test void globalCapOnePreventsSecondProviderCallAfterPrimaryDailyQuota() {
        when(settings.dailyLimit()).thenReturn(1);
        when(generator.generateForModel(any(), eq(PRIMARY))).thenThrow(limited(DAILY_MODEL_LIMIT));
        long id = queue("global-one");
        job(NOW).runBatch();
        job(NOW).runBatch();
        assertThat(repository.find(id).status()).isEqualTo("QUEUED");
        assertThat(repository.attemptsToday(DAY)).isEqualTo(1);
        verify(generator, never()).generateForModel(any(), eq(FALLBACK));
    }

    @Test void articleAttemptCapOnePreventsFallbackEvenWithGlobalCapacity() {
        when(settings.maxAttempts()).thenReturn(1);
        when(generator.generateForModel(any(), eq(PRIMARY))).thenThrow(limited(DAILY_MODEL_LIMIT));
        long id = queue("article-one");
        job(NOW).runBatch();
        assertThat(repository.find(id).status()).isEqualTo("FAILED");
        assertThat(service(NOW).get(id).canRetry()).isFalse();
        assertThat(repository.attemptsToday(DAY)).isEqualTo(1);
        verify(generator, never()).generateForModel(any(), eq(FALLBACK));
    }

    @Test void unknownQuotaBlocksAllModelsUntilNextPacificDay() {
        when(generator.generateForModel(any(), eq(PRIMARY))).thenThrow(limited(RATE_LIMIT));
        long id = queue("unknown-quota");
        queue("unknown-next");
        job(NOW).runBatch();
        assertThat(repository.find(id).nextAttemptAt()).isEqualTo(Instant.parse("2026-11-23T08:00:00Z"));
        assertThat(repository.blocked(DAY, NOW.plusSeconds(3600))).isTrue();
        assertThat(repository.modelBudget(DAY, PRIMARY).blockedUntil()).isNull();
        verify(generator, times(1)).generateForModel(any(), eq(PRIMARY));
        verify(generator, never()).generateForModel(any(), eq(FALLBACK));
    }

    @Test void minuteQuotaPausesOtherArticlesAndRetriesSameModelAfterBackoff() {
        when(generator.generateForModel(any(), eq(PRIMARY))).thenThrow(limited(RATE_LIMIT_TRANSIENT)).thenReturn(result());
        long id = queue("minute-quota");
        queue("minute-next");
        job(NOW).runBatch();
        assertThat(repository.blocked(DAY, NOW.plusSeconds(119))).isTrue();
        assertThat(repository.blocked(DAY, NOW.plusSeconds(121))).isFalse();
        assertThat(repository.find(id).retryModel()).isEqualTo(PRIMARY);
        verify(generator, times(1)).generateForModel(any(), eq(PRIMARY));
        job(NOW.plusSeconds(121)).process(repository.claimNext(NOW.plusSeconds(121), 3));
        assertThat(repository.find(id).model()).isEqualTo(PRIMARY);
        verify(generator, never()).generateForModel(any(), eq(FALLBACK));
    }

    @Test void transientProviderFailureKeepsRetryOnPrimary() {
        when(generator.generateForModel(any(), eq(PRIMARY))).thenThrow(limited(TRANSIENT)).thenReturn(result());
        long id = queue("server-error");
        job(NOW).process(repository.claimNext(NOW, 3));
        job(NOW.plusSeconds(121)).process(repository.claimNext(NOW.plusSeconds(121), 3));
        assertThat(repository.find(id).model()).isEqualTo(PRIMARY);
        assertThat(repository.find(id).attempts()).isEqualTo(2);
        verify(generator, never()).generateForModel(any(), eq(FALLBACK));
    }

    @Test void permanentConfigurationFailureNeverAutomaticallySwitchesModel() {
        when(generator.generateForModel(any(), eq(PRIMARY))).thenThrow(limited(PERMANENT));
        long id = queue("bad-key");
        job(NOW).runBatch();
        assertThat(repository.find(id).status()).isEqualTo("FAILED");
        assertThat(repository.find(id).attempts()).isEqualTo(1);
        verify(generator, never()).generateForModel(any(), eq(FALLBACK));
    }

    @Test void locallyExhaustedRetryPinCanAdvanceToFallback() {
        when(settings.modelDailyLimit()).thenReturn(1);
        when(generator.generateForModel(any(), eq(PRIMARY))).thenThrow(limited(TRANSIENT));
        long id = queue("pin-now-full");
        job(NOW).process(repository.claimNext(NOW, 3));
        assertThat(repository.find(id).retryModel()).isEqualTo(PRIMARY);
        job(NOW.plusSeconds(121)).process(repository.claimNext(NOW.plusSeconds(121), 3));
        assertThat(repository.find(id).model()).isEqualTo(FALLBACK);
        assertThat(repository.attemptsToday(DAY)).isEqualTo(2);
    }

    @Test void allModelsFullDefersWithoutCallsThenPrimaryResumesNextPacificDay() {
        modelUsage(PRIMARY, 500);
        modelUsage(FALLBACK, 500);
        long id = queue("all-full");
        job(NOW).process(repository.claimNext(NOW, 3));
        Instant reset = Instant.parse("2026-11-23T08:00:00Z");
        assertThat(repository.find(id).nextAttemptAt()).isEqualTo(reset);
        assertThat(repository.attemptsToday(DAY)).isZero();
        verifyNoInteractions(generator);
        job(reset).process(repository.claimNext(reset, 3));
        assertThat(repository.find(id).model()).isEqualTo(PRIMARY);
        assertThat(repository.attemptsToday(DAY.plusDays(1))).isEqualTo(1);
    }

    @Test void quotaDayResetUsesPacificDaylightSavingTimeInSummer() {
        Instant summer = Instant.parse("2026-07-22T12:00:00Z");
        when(generator.generateForModel(any(), eq(PRIMARY))).thenThrow(limited(RATE_LIMIT));
        long id = queue("summer-reset");
        job(summer).process(repository.claimNext(NOW, 3));
        assertThat(repository.find(id).nextAttemptAt()).isEqualTo(Instant.parse("2026-07-23T07:00:00Z"));
    }

    @Test void fallbackTransientRetryReturnsToPrimaryAfterPacificDayResets() {
        modelUsage(PRIMARY, 500);
        when(generator.generateForModel(any(), eq(FALLBACK))).thenThrow(limited(TRANSIENT));
        long id = queue("fallback-yesterday");
        job(NOW).process(repository.claimNext(NOW, 3));
        assertThat(repository.find(id).retryModel()).isEqualTo(FALLBACK);
        assertThat(repository.find(id).retryModelDay()).isEqualTo(DAY);
        Instant reset = Instant.parse("2026-11-23T08:00:00Z");
        job(reset).process(repository.claimNext(reset, 3));
        assertThat(repository.find(id).model()).isEqualTo(PRIMARY);
        assertThat(repository.find(id).attempts()).isEqualTo(2);
        assertThat(repository.attemptsToday(DAY.plusDays(1))).isEqualTo(1);
        verify(generator).generateForModel(any(), eq(FALLBACK));
        verify(generator).generateForModel(any(), eq(PRIMARY));
    }

    @Test void legacyCountersAreProjectedReadOnlyAndConservativelySeededOnceForEveryModel() {
        jdbc.update("INSERT INTO summary_daily_budget(budget_date,attempts) VALUES (?,?)", DAY, 7);
        var status = service(NOW).status();
        assertThat(status.attemptsToday()).isEqualTo(7);
        assertThat(status.remainingToday()).isEqualTo(13);
        assertThat(status.models()).extracting(SummaryService.ModelStatus::model).containsExactlyElementsOf(MODELS);
        assertThat(status.models()).extracting(SummaryService.ModelStatus::attemptsToday).containsExactly(7, 7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM summary_model_daily_budget", Integer.class)).isZero();
        long id = queue("legacy-first");
        job(NOW).process(repository.claimNext(NOW, 3));
        assertThat(repository.find(id).model()).isEqualTo(PRIMARY);
        assertThat(repository.attemptsToday(DAY)).isEqualTo(8);
        assertThat(repository.modelBudget(DAY, PRIMARY).attempts()).isEqualTo(8);
        assertThat(repository.modelBudget(DAY, FALLBACK).attempts()).isEqualTo(7);
        queue("legacy-second");
        job(NOW).process(repository.claimNext(NOW, 3));
        assertThat(repository.attemptsToday(DAY)).isEqualTo(9);
        assertThat(repository.modelBudget(DAY, PRIMARY).attempts()).isEqualTo(9);
        assertThat(repository.modelBudget(DAY, FALLBACK).attempts()).isEqualTo(7);
        assertThat(repository.modelBudget(DAY.plusDays(1), PRIMARY).attempts()).isZero();
    }

    @Test void additiveSchemaPreservesOldDayBlockCountersAndSavedSummaries() {
        long id = queue("old-success");
        job(NOW).process(repository.claimNext(NOW, 3));
        var saved = repository.find(id);
        jdbc.execute("DROP TABLE summary_model_daily_budget");
        jdbc.execute("ALTER TABLE summary_daily_budget DROP COLUMN model_accounted");
        jdbc.execute("ALTER TABLE summary_daily_budget DROP COLUMN legacy_attempts");
        jdbc.execute("ALTER TABLE news_summary DROP COLUMN retry_model");
        jdbc.execute("ALTER TABLE news_summary DROP COLUMN retry_model_day");
        Instant reset = Instant.parse("2026-11-23T08:00:00Z");
        jdbc.update("UPDATE summary_daily_budget SET attempts=9,blocked_until=? WHERE budget_date=?", reset.atOffset(ZoneOffset.UTC), DAY);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(jdbc.getDataSource());
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(jdbc.getDataSource());
        assertThat(repository.find(id).resultJson()).isEqualTo(saved.resultJson());
        assertThat(repository.find(id).model()).isEqualTo(PRIMARY);
        assertThat(repository.attemptsToday(DAY)).isEqualTo(9);
        assertThat(repository.blocked(DAY, NOW)).isTrue();
        assertThat(service(NOW).status().remainingToday()).isZero();
        assertThat(repository.modelBudget(DAY, PRIMARY).attempts()).isEqualTo(9);
        assertThat(repository.modelBudget(DAY, FALLBACK).attempts()).isEqualTo(9);
    }

    @Test void concurrentReservationsRespectBothModelCapsAndGlobalCapAtomically() throws Exception {
        List<SummaryRepository.Stored> work = new ArrayList<>();
        for (int i = 0; i < 12; i++) { queue("parallel-" + i); work.add(repository.claimNext(NOW, 3)); }
        try (var threads = Executors.newFixedThreadPool(12)) {
            var start = new CountDownLatch(1);
            List<Callable<String>> tasks = work.stream().<Callable<String>>map(item -> () -> {
                start.await(); return repository.reserveNextInvocation(item, DAY, MODELS, 3, 2, 3, NOW);
            }).toList();
            var futures = tasks.stream().map(threads::submit).toList();
            start.countDown();
            List<String> chosen = new ArrayList<>();
            for (var future : futures) { String model = future.get(); if (model != null) chosen.add(model); }
            assertThat(chosen).containsExactlyInAnyOrder(PRIMARY, PRIMARY, FALLBACK);
        }
        assertThat(repository.attemptsToday(DAY)).isEqualTo(3);
        assertThat(repository.modelBudget(DAY, PRIMARY).attempts()).isEqualTo(2);
        assertThat(repository.modelBudget(DAY, FALLBACK).attempts()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT SUM(attempts) FROM news_summary", Integer.class)).isEqualTo(3);
    }
}
