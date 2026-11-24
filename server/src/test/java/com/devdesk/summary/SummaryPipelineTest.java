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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SummaryPipelineTest {
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 22);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private JdbcTemplate jdbc;
    private SummaryRepository repository;
    private SummarySettings settings;
    private SummaryService service;
    private ArticleContentService sources;
    private SummaryGenerator generator;
    private SummaryJob job;

    @BeforeEach
    void prepare() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:summary-" + UUID.randomUUID()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        repository = new SummaryRepository(jdbc);
        settings = mock(SummarySettings.class);
        when(settings.enabled()).thenReturn(true);
        when(settings.configured()).thenReturn(true);
        when(settings.model()).thenReturn("test-model");
        when(settings.modelOrder()).thenReturn(List.of("test-model"));
        when(settings.modelDailyLimit()).thenReturn(500);
        when(settings.dailyLimit()).thenReturn(3);
        when(settings.maxAttempts()).thenReturn(2);
        when(settings.batchSize()).thenReturn(3);
        sources = mock(ArticleContentService.class);
        when(sources.fetch(anyString(), anyString(), anyString())).thenAnswer(call ->
            new SourceContent(call.getArgument(0), call.getArgument(1), "본문에 있는 중요한 기술 정보와 적용 조건.",
                "ARTICLE_BODY", null));
        generator = mock(SummaryGenerator.class);
        doCallRealMethod().when(generator).generateForModel(any(), anyString());
        var json = new ObjectMapper().findAndRegisterModules();
        service = new SummaryService(repository, settings, json, CLOCK);
        job = new SummaryJob(repository, settings, sources, generator, json, CLOCK);
    }

    private long news(String sourceId) {
        jdbc.update("INSERT INTO news_item(source_id,title,url,description,collected_at,topic) VALUES (?,?,?,?,?,?)",
            sourceId, "기사 " + sourceId, "https://example.com/" + sourceId, "RSS 설명", NOW.atOffset(ZoneOffset.UTC), "Java");
        return jdbc.queryForObject("SELECT id FROM news_item WHERE source_id=?", Long.class, sourceId);
    }

    private SummaryResult result() {
        return new SummaryResult(List.of(new SummaryResult.Section("주요 변경과 적용 조건",
            List.of("기술 변경 사항을 조건과 함께 정리한다.", "성능 수치는 측정 환경과 함께 보존한다."))),
            List.of("개발자 관점의 해석은 사실과 분리한다."), List.of("원문에 명시된 제한 사항."));
    }

    @Test
    void readDoesNotQueueWorkOrSpendQuotaAndRepeatedRequestDoesNotDuplicate() {
        long id = news("read-only");
        assertThat(service.get(id).status()).isEqualTo("NOT_REQUESTED");
        assertThat(service.get(id).status()).isEqualTo("NOT_REQUESTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_summary", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM summary_daily_budget", Long.class)).isZero();
        assertThat(service.request(id).status()).isEqualTo("QUEUED");
        assertThat(service.request(id).status()).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_summary", Long.class)).isEqualTo(1);
        verifyNoInteractions(generator, sources);
    }

    @Test
    void successfulBriefingRetainsFullSectionsAndSourceAndIsReusedWhileDisabled() {
        long id = news("successful");
        when(generator.generate(any())).thenReturn(result());
        service.request(id);
        job.process(repository.claimNext(NOW, 2));
        SummaryView view = service.get(id);
        assertThat(view.status()).isEqualTo("SUCCEEDED");
        assertThat(view.sections()).isEqualTo(result().sections());
        assertThat(view.developerNotes()).isEqualTo(result().developerNotes());
        assertThat(view.sourceScope()).isEqualTo("ARTICLE_BODY");
        assertThat(repository.find(id).sourceContent()).contains("적용 조건");
        assertThat(repository.find(id).inputHash()).hasSize(64);
        assertThat(repository.attemptsToday(DAY)).isEqualTo(1);
        when(settings.enabled()).thenReturn(false);
        assertThat(service.request(id).status()).isEqualTo("SUCCEEDED");
        verify(generator, times(1)).generate(any());
    }

    @Test
    void disabledAiStillAllowsExistingNewsAndDoesNotCreatePendingRows() {
        long id = news("disabled");
        when(settings.configured()).thenReturn(false);
        assertThat(service.get(id).status()).isEqualTo("DISABLED");
        assertThat(service.request(id).status()).isEqualTo("DISABLED");
        job.runBatch();
        assertThat(repository.find(id)).isNull();
        verifyNoInteractions(generator, sources);
    }

    @Test
    void invalidResponseNeverBecomesSuccessAndRetriesAreBounded() {
        long id = news("invalid");
        when(generator.generate(any())).thenThrow(new SummaryGenerationException(
            SummaryGenerationException.Category.INVALID_OUTPUT, "Invalid result"));
        service.request(id);
        job.process(repository.claimNext(NOW, 2));
        assertThat(service.get(id).status()).isEqualTo("QUEUED");
        assertThat(repository.find(id).resultJson()).isNull();
        job.process(repository.claimNext(NOW.plusSeconds(3600), 2));
        assertThat(service.get(id).status()).isEqualTo("FAILED");
        service.request(id);
        assertThat(repository.find(id).status()).isEqualTo("FAILED");
        assertThat(service.get(id).canRetry()).isFalse();
        assertThat(repository.find(id).attempts()).isEqualTo(2);
        assertThat(repository.attemptsToday(DAY)).isEqualTo(2);
        verify(sources, times(1)).fetch(anyString(), anyString(), anyString());
        verify(generator, times(2)).generate(any());
    }

    @Test
    void explicitRetryAfterConfigurationFailureRetainsCountersAndSource() {
        long id = news("configuration-fixed");
        when(generator.generate(any())).thenThrow(new SummaryGenerationException(
            SummaryGenerationException.Category.PERMANENT, "Configuration unavailable"));
        service.request(id);
        job.process(repository.claimNext(NOW, 2));
        assertThat(service.get(id).status()).isEqualTo("FAILED");
        assertThat(service.get(id).canRetry()).isTrue();
        assertThat(repository.find(id).attempts()).isEqualTo(1);
        when(settings.enabled()).thenReturn(false);
        assertThat(service.get(id).canRetry()).isFalse();
        when(settings.enabled()).thenReturn(true);
        assertThat(service.request(id).status()).isEqualTo("QUEUED");
        service.request(id);
        assertThat(repository.find(id).attempts()).isEqualTo(1);
        assertThat(repository.find(id).sourceAttempts()).isEqualTo(1);
        doReturn(result()).when(generator).generate(any());
        job.process(repository.claimNext(NOW, 2));
        assertThat(service.get(id).status()).isEqualTo("SUCCEEDED");
        assertThat(repository.find(id).attempts()).isEqualTo(2);
        assertThat(repository.attemptsToday(DAY)).isEqualTo(2);
        assertThat(service.get(id).canRetry()).isFalse();
        verify(sources, times(1)).fetch(anyString(), anyString(), anyString());
        verify(generator, times(2)).generate(any());
    }

    @Test
    void sourceFailuresDoNotSpendAiBudgetAndCannotLoopForever() {
        long id = news("missing-source");
        when(sources.fetch(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("No source"));
        service.request(id);
        job.process(repository.claimNext(NOW, 2));
        job.process(repository.claimNext(NOW.plusSeconds(3600), 2));
        assertThat(service.get(id).status()).isEqualTo("FAILED");
        assertThat(repository.find(id).sourceAttempts()).isEqualTo(2);
        assertThat(service.get(id).canRetry()).isFalse();
        assertThat(repository.attemptsToday(DAY)).isZero();
        verifyNoInteractions(generator);
    }

    @Test
    void providerQuotaFailureDefersAndStopsFurtherBatchCallsUntilQuotaDayResets() {
        long first = news("limited");
        long second = news("next");
        when(generator.generate(any())).thenThrow(new SummaryGenerationException(
            SummaryGenerationException.Category.RATE_LIMIT, "Limited"));
        service.request(first);
        service.request(second);
        job.runBatch();
        assertThat(repository.find(first).status()).isEqualTo("QUEUED");
        assertThat(repository.find(first).nextAttemptAt()).isEqualTo(Instant.parse("2026-11-23T08:00:00Z"));
        assertThat(service.status().remainingToday()).isZero();
        job.runBatch();
        verify(generator, times(1)).generate(any());
    }

    @Test
    void localDailyLimitKeepsExcessWorkQueuedWithoutCallingProvider() {
        when(settings.dailyLimit()).thenReturn(1);
        when(generator.generate(any())).thenReturn(result());
        long first = news("first");
        long second = news("second");
        service.request(first); service.request(second);
        job.runBatch();
        assertThat(repository.find(first).status()).isEqualTo("SUCCEEDED");
        assertThat(repository.find(second).status()).isEqualTo("QUEUED");
        assertThat(service.status().attemptsToday()).isEqualTo(1);
        assertThat(service.status().remainingToday()).isZero();
        verify(generator, times(1)).generate(any());
    }

    @Test
    void concurrentBudgetReservationsCannotExceedLimit() throws Exception {
        List<SummaryRepository.Stored> work = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            long id = news("concurrent-" + i);
            repository.enqueue(id, NOW);
            work.add(repository.claimNext(NOW, 2));
        }
        try (var threads = Executors.newFixedThreadPool(12)) {
            var start = new CountDownLatch(1);
            List<Callable<Boolean>> tasks = work.stream().<Callable<Boolean>>map(item -> () -> {
                start.await(); return repository.reserveInvocation(item, DAY, 3, 2, NOW);
            }).toList();
            var futures = tasks.stream().map(threads::submit).toList();
            start.countDown();
            int accepted = 0;
            for (var future : futures) if (future.get()) accepted++;
            assertThat(accepted).isEqualTo(3);
            assertThat(repository.attemptsToday(DAY)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT SUM(attempts) FROM news_summary", Integer.class)).isEqualTo(3);
        }
    }

    @Test
    void concurrentWorkersCannotClaimTheSameRow() throws Exception {
        long id = news("single-claim");
        repository.enqueue(id, NOW);
        try (var threads = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            List<Callable<SummaryRepository.Stored>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) tasks.add(() -> { start.await(); return repository.claimNext(NOW, 2); });
            var futures = tasks.stream().map(threads::submit).toList();
            start.countDown();
            int winners = 0;
            for (var future : futures) if (future.get() != null) winners++;
            assertThat(winners).isEqualTo(1);
        }
    }

    @Test
    void staleWorkersCannotOverwriteARecoveredClaim() {
        long id = news("stale");
        repository.enqueue(id, NOW);
        var old = repository.claimNext(NOW, 2);
        repository.recoverStale(NOW.plusSeconds(1), NOW.plusSeconds(900), 2);
        var current = repository.claimNext(NOW.plusSeconds(900), 2);
        repository.complete(old, "{}", "old-model", "old", NOW.plusSeconds(901));
        assertThat(repository.find(id).status()).isEqualTo("PROCESSING");
        assertThat(repository.find(id).claimToken()).isEqualTo(current.claimToken());
        assertThat(repository.find(id).resultJson()).isNull();
    }
}
