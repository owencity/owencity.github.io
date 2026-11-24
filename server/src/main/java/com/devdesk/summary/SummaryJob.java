package com.devdesk.summary;

import com.devdesk.summary.source.ArticleContentService;
import com.devdesk.summary.source.SourceContent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SummaryJob {
    public static final String PROMPT_VERSION = "developer-briefing-v1";
    private static final Logger log = LoggerFactory.getLogger(SummaryJob.class);
    private final SummaryRepository repository;
    private final SummarySettings settings;
    private final ArticleContentService sources;
    private final SummaryGenerator generator;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public SummaryJob(SummaryRepository repository, SummarySettings settings, ArticleContentService sources,
            SummaryGenerator generator, ObjectMapper json) {
        this(repository, settings, sources, generator, json, Clock.systemUTC());
    }

    SummaryJob(SummaryRepository repository, SummarySettings settings, ArticleContentService sources,
            SummaryGenerator generator, ObjectMapper json, Clock clock) {
        this.repository = repository; this.settings = settings; this.sources = sources;
        this.generator = generator; this.json = json; this.clock = clock;
    }

    @Scheduled(initialDelayString = "${devdesk.summary.initial-delay-ms:20000}",
        fixedDelayString = "${devdesk.summary.interval-ms:60000}", scheduler = "summaryScheduler")
    public void runBatch() {
        if (!settings.enabled() || !settings.configured()) return;
        try {
            Instant now = clock.instant();
            repository.recoverStale(now.minus(Duration.ofMinutes(10)), now, settings.maxAttempts());
            repository.enqueueRecent(now.minus(Duration.ofDays(2)), now, settings.batchSize());
            for (int i = 0; i < settings.batchSize(); i++) {
                LocalDate day = quotaDay();
                if (repository.attemptsToday(day) >= settings.dailyLimit() || repository.blocked(day, clock.instant())) return;
                var work = repository.claimNext(clock.instant(), settings.maxAttempts());
                if (work == null) return;
                process(work);
            }
        } catch (RuntimeException failure) {
            // Avoid credential-bearing upstream messages. Processing leases recover after an interruption.
            log.warn("News summary batch interrupted; pending jobs remain saved");
        }
    }

    void process(SummaryRepository.Stored work) {
        var news = repository.news(work.newsId());
        if (news == null) return;
        SourceContent source;
        try {
            if (work.sourceContent() != null && !work.sourceContent().isBlank()) {
                source = new SourceContent(news.title(), work.sourceUrl(), work.sourceContent(),
                    work.sourceScope(), work.sourceWarning());
            } else {
                repository.beginSourceAttempt(work, clock.instant());
                source = sources.fetch(news.title(), news.url(), news.description());
                if (!repository.saveSource(work, source, inputHash(source), clock.instant())) return;
            }
        } catch (RuntimeException failure) {
            if (work.sourceAttempts() + 1 >= settings.maxAttempts()) {
                repository.fail(work, "요약할 본문을 확보하지 못했습니다. 원문에서 확인해 주세요.", clock.instant());
            } else {
                repository.defer(work, clock.instant().plus(Duration.ofMinutes(10)),
                    "본문을 확보하지 못해 나중에 다시 시도합니다.", clock.instant());
            }
            return;
        }

        LocalDate day = quotaDay();
        String selectedModel = repository.reserveNextInvocation(work, day, settings.modelOrder(),
            settings.dailyLimit(), settings.modelDailyLimit(), settings.maxAttempts(), clock.instant());
        if (selectedModel == null) {
            repository.defer(work, nextQuotaDay(), "앱 또는 설정된 모든 모델의 오늘 호출 한도에 도달했습니다. 다음 한도 초기화 후 처리합니다.", clock.instant());
            return;
        }
        // Do not open a database transaction around content fetching or model calls.
        try {
            SummaryResult result = generator.generateForModel(source, selectedModel);
            if (result == null || result.sections() == null || result.sections().isEmpty()) {
                throw new SummaryGenerationException(SummaryGenerationException.Category.INVALID_OUTPUT, "Invalid summary");
            }
            repository.complete(work, json.writeValueAsString(result), selectedModel, PROMPT_VERSION, clock.instant());
        } catch (SummaryGenerationException failure) {
            handleFailure(work, failure.category(), day, selectedModel);
        } catch (JsonProcessingException invalid) {
            handleFailure(work, SummaryGenerationException.Category.INVALID_OUTPUT, day, selectedModel);
        } catch (RuntimeException failure) {
            handleFailure(work, SummaryGenerationException.Category.TRANSIENT, day, selectedModel);
        }
    }

    private void handleFailure(SummaryRepository.Stored work, SummaryGenerationException.Category category, LocalDate day, String selectedModel) {
        Instant now = clock.instant();
        Instant backoff = now.plus(Duration.ofMinutes(2L << Math.min(work.attempts(), 5)));
        if (category == SummaryGenerationException.Category.RATE_LIMIT) repository.blockUntil(day, nextQuotaDay());
        if (category == SummaryGenerationException.Category.RATE_LIMIT_TRANSIENT) repository.blockUntil(day, backoff);
        if (category == SummaryGenerationException.Category.DAILY_MODEL_LIMIT) repository.blockModelUntil(day, selectedModel, nextQuotaDay());
        if (category == SummaryGenerationException.Category.PERMANENT || work.attempts() + 1 >= settings.maxAttempts()) {
            repository.fail(work, category == SummaryGenerationException.Category.PERMANENT
                ? "AI 연결 설정 또는 사용 가능한 모델을 확인해 주세요."
                : "정리 결과를 완성하지 못해 자동 재시도를 중단했습니다. 원문을 확인해 주세요.", now);
        } else if (category == SummaryGenerationException.Category.DAILY_MODEL_LIMIT) {
            repository.deferForFallback(work, now,
                "현재 모델의 일일 한도에 도달했습니다. 설정된 다음 모델의 남은 한도를 확인해 처리합니다.", now);
        } else {
            Instant next = category == SummaryGenerationException.Category.RATE_LIMIT ? nextQuotaDay()
                : backoff;
            String message = category == SummaryGenerationException.Category.RATE_LIMIT
                ? "제공자 호출 한도에 도달했습니다. 다음 한도 초기화 후 다시 시도합니다."
                : category == SummaryGenerationException.Category.RATE_LIMIT_TRANSIENT
                    ? "제공자 분당 요청·토큰 한도에 도달했습니다. 잠시 기다린 뒤 같은 모델로 다시 시도합니다."
                : "정리 결과를 완성하지 못해 잠시 후 다시 시도합니다.";
            repository.defer(work, next, message, now);
        }
    }

    private LocalDate quotaDay() { return LocalDate.now(clock.withZone(SummaryService.QUOTA_ZONE)); }
    private Instant nextQuotaDay() { return quotaDay().plusDays(1).atStartOfDay(SummaryService.QUOTA_ZONE).toInstant(); }
    private String inputHash(SourceContent source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                (source.scope() + "\n" + source.url() + "\n" + source.text()).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
