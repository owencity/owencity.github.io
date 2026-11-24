package com.devdesk.summary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SummaryService {
    static final ZoneId QUOTA_ZONE = ZoneId.of("America/Los_Angeles");
    private final SummaryRepository repository;
    private final SummarySettings settings;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public SummaryService(SummaryRepository repository, SummarySettings settings, ObjectMapper json) {
        this(repository, settings, json, Clock.systemUTC());
    }

    SummaryService(SummaryRepository repository, SummarySettings settings, ObjectMapper json, Clock clock) {
        this.repository = repository; this.settings = settings; this.json = json; this.clock = clock;
    }

    public SummaryView get(long id) {
        requireNews(id);
        var stored = repository.find(id);
        if (stored == null) return SummaryView.empty(id, available() ? "NOT_REQUESTED" : "DISABLED", null);
        SummaryResult result = null;
        if ("SUCCEEDED".equals(stored.status())) {
            try { result = json.readValue(stored.resultJson(), SummaryResult.class); }
            catch (JsonProcessingException invalid) {
                return SummaryView.empty(id, "FAILED", "저장된 정리 내용을 읽을 수 없습니다.");
            }
        }
        return new SummaryView(id, stored.status(), stored.sourceScope(), stored.sourceUrl(),
            stored.sourceWarning(), stored.model(), stored.generatedAt(),
            result == null ? List.of() : result.sections(), result == null ? List.of() : result.developerNotes(),
            result == null ? List.of() : result.caveats(), stored.error(), canRetry(stored));
    }

    public SummaryView request(long id) {
        requireNews(id);
        var stored = repository.find(id);
        if (stored != null && "SUCCEEDED".equals(stored.status())) return get(id);
        if (!available()) return SummaryView.empty(id, "DISABLED", "서버의 Gemini 모델과 API 키 설정을 확인하세요.");
        if (canRetry(stored)) repository.retryFailed(id, settings.maxAttempts(), clock.instant());
        else repository.enqueue(id, clock.instant());
        return get(id);
    }

    public record Status(boolean enabled, boolean configured, String model,
            int dailyLimit, int attemptsToday, int remainingToday, List<ModelStatus> models) {}
    public record ModelStatus(String model, int dailyLimit, int attemptsToday, int remainingToday,
            java.time.Instant blockedUntil) {}

    public Status status() {
        LocalDate day = LocalDate.now(clock.withZone(QUOTA_ZONE));
        int used = repository.attemptsToday(day);
        int remaining = repository.blocked(day, clock.instant()) ? 0 : Math.max(0, settings.dailyLimit() - used);
        List<ModelStatus> models = settings.modelOrder().stream().map(model -> {
            var budget = repository.modelBudget(day, model);
            int modelRemaining = budget.available(settings.modelDailyLimit(), clock.instant())
                ? Math.max(0, settings.modelDailyLimit() - budget.attempts()) : 0;
            return new ModelStatus(model, settings.modelDailyLimit(), budget.attempts(), modelRemaining, budget.blockedUntil());
        }).toList();
        return new Status(settings.enabled(), settings.configured(), settings.model(),
            settings.dailyLimit(), used, remaining, models);
    }

    private boolean available() { return settings.enabled() && settings.configured(); }
    private boolean canRetry(SummaryRepository.Stored stored) {
        return available() && stored != null && "FAILED".equals(stored.status())
            && stored.attempts() < settings.maxAttempts()
            && (stored.sourceAttempts() < settings.maxAttempts()
                || (stored.sourceContent() != null && !stored.sourceContent().isBlank()));
    }
    private void requireNews(long id) {
        if (repository.news(id) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "News not found");
    }
}
