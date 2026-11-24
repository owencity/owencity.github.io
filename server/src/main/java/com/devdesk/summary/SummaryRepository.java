package com.devdesk.summary;

import com.devdesk.summary.source.SourceContent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class SummaryRepository {
    public record News(long id, String title, String url, String description) {}
    public record Stored(long newsId, String status, String claimToken, String sourceScope,
            String sourceUrl, String sourceContent, String sourceWarning, String inputHash,
            String resultJson, String model, String promptVersion, int attempts, int sourceAttempts,
            Instant nextAttemptAt, Instant generatedAt, String error, String retryModel, LocalDate retryModelDay) {}
    public record ModelBudget(String model, int attempts, Instant blockedUntil) {
        public boolean available(int limit, Instant now) {
            return attempts < limit && (blockedUntil == null || !blockedUntil.isAfter(now));
        }
    }
    private record DayBudget(int attempts, boolean modelAccounted, int legacyAttempts, Instant blockedUntil) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public SummaryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(
            new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }

    public News news(long id) {
        return jdbc.query("SELECT id,title,url,description FROM news_item WHERE id=?",
            (rs, row) -> new News(rs.getLong("id"), rs.getString("title"), rs.getString("url"),
                rs.getString("description")), id).stream().findFirst().orElse(null);
    }

    public Stored find(long id) {
        return jdbc.query("SELECT * FROM news_summary WHERE news_id=?", SummaryRepository::map, id)
            .stream().findFirst().orElse(null);
    }

    /** The news_id primary key makes repeated requests idempotent. */
    public void enqueue(long id, Instant now) {
        try {
            jdbc.update("INSERT INTO news_summary(news_id,status,attempts,source_attempts,created_at,updated_at,next_attempt_at) "
                + "SELECT ?,'QUEUED',0,0,?,?,? WHERE NOT EXISTS (SELECT 1 FROM news_summary WHERE news_id=?)",
                id, stamp(now), stamp(now), stamp(now), id);
        } catch (DuplicateKeyException concurrentRequest) {
            // Another request enqueued the same item. Keep its state and saved result.
        }
    }

    public void enqueueRecent(Instant since, Instant now, int limit) {
        List<Long> ids = jdbc.queryForList("SELECT n.id FROM news_item n WHERE n.collected_at>=? "
            + "AND n.feedback NOT IN ('READ','KNOWN','NOT_INTERESTED') "
            + "AND NOT EXISTS (SELECT 1 FROM news_summary s WHERE s.news_id=n.id) "
            + "ORDER BY n.collected_at DESC,n.id DESC LIMIT ?", Long.class, stamp(since), limit);
        for (long id : ids) enqueue(id, now);
    }

    /** Explicit retry after a settings fix; retain both counters and the already collected source. */
    public void retryFailed(long id, int maxAttempts, Instant now) {
        jdbc.update("UPDATE news_summary SET status='QUEUED',next_attempt_at=?,updated_at=?,error=NULL "
            + "WHERE news_id=? AND status='FAILED' AND attempts<? "
            + "AND (source_attempts<? OR (source_content IS NOT NULL AND LENGTH(TRIM(source_content))>0))",
            stamp(now), stamp(now), id, maxAttempts, maxAttempts);
    }

    public Stored claimNext(Instant now, int maxAttempts) {
        List<Long> ids = jdbc.queryForList("SELECT news_id FROM news_summary WHERE status='QUEUED' "
            + "AND next_attempt_at<=? AND attempts<? AND source_attempts<=? "
            + "ORDER BY created_at,news_id LIMIT 10", Long.class, stamp(now), maxAttempts, maxAttempts);
        for (long id : ids) {
            String token = UUID.randomUUID().toString();
            if (jdbc.update("UPDATE news_summary SET status='PROCESSING',claim_token=?,updated_at=? "
                + "WHERE news_id=? AND status='QUEUED' AND next_attempt_at<=? AND attempts<?",
                token, stamp(now), id, stamp(now), maxAttempts) == 1) return find(id);
        }
        return null;
    }

    public void beginSourceAttempt(Stored work, Instant now) {
        jdbc.update("UPDATE news_summary SET source_attempts=source_attempts+1,updated_at=? "
            + "WHERE news_id=? AND status='PROCESSING' AND claim_token=?",
            stamp(now), work.newsId(), work.claimToken());
    }

    public boolean saveSource(Stored work, SourceContent source, String inputHash, Instant now) {
        return jdbc.update("UPDATE news_summary SET source_scope=?,source_url=?,source_content=?,source_warning=?,input_hash=?,updated_at=? "
            + "WHERE news_id=? AND status='PROCESSING' AND claim_token=?",
            source.scope(), source.url(), source.text(), source.warning(), inputHash, stamp(now),
            work.newsId(), work.claimToken()) == 1;
    }

    /** Compatibility entry point for existing single-budget callers. */
    public boolean reserveInvocation(Stored work, LocalDate day, int dailyLimit, int maxAttempts, Instant now) {
        return reserveNextInvocation(work, day, List.of("legacy-default"), dailyLimit,
            Integer.MAX_VALUE, maxAttempts, now) != null;
    }

    /** Select and reserve under one day-row lock: no model switch can bypass the global or article cap. */
    public String reserveNextInvocation(Stored work, LocalDate day, List<String> models,
            int dailyLimit, int modelDailyLimit, int maxAttempts, Instant now) {
        if (models.isEmpty()) return null;
        ensureBudget(day);
        return transactions.execute(tx -> {
            DayBudget budget = dayBudget(day, true);
            int legacy = budget.modelAccounted() ? budget.legacyAttempts() : budget.attempts();
            // Pre-feature failed attempts cannot be attributed reliably. Conservatively seed each model.
            for (String model : models) ensureModelBudget(day, model, legacy);
            if (!budget.modelAccounted()) jdbc.update("UPDATE summary_daily_budget SET model_accounted=TRUE,legacy_attempts=? WHERE budget_date=?", legacy, day);
            if (budget.attempts() >= dailyLimit || (budget.blockedUntil() != null && budget.blockedUntil().isAfter(now))) return null;
            List<String> candidates = models;
            if (day.equals(work.retryModelDay()) && work.retryModel() != null && models.contains(work.retryModel())
                    && rawModelBudget(day, work.retryModel()).available(modelDailyLimit, now)) {
                // A transient retry keeps its model while that model still has daily capacity.
                candidates = List.of(work.retryModel());
            }
            String selected = candidates.stream().filter(model -> rawModelBudget(day, model).available(modelDailyLimit, now))
                .findFirst().orElse(null);
            if (selected == null) return null;
            int reserved = jdbc.update("UPDATE summary_daily_budget SET attempts=attempts+1 WHERE budget_date=? "
                + "AND attempts<? AND (blocked_until IS NULL OR blocked_until<=?)", day, dailyLimit, stamp(now));
            if (reserved == 0) return null;
            int modelReserved = jdbc.update("UPDATE summary_model_daily_budget SET attempts=attempts+1 WHERE budget_date=? AND model=? "
                + "AND attempts<? AND (blocked_until IS NULL OR blocked_until<=?)", day, selected, modelDailyLimit, stamp(now));
            if (modelReserved == 0) { tx.setRollbackOnly(); return null; }
            int claimed = jdbc.update("UPDATE news_summary SET attempts=attempts+1,retry_model=?,retry_model_day=?,updated_at=? "
                + "WHERE news_id=? AND status='PROCESSING' AND claim_token=? AND attempts<?",
                selected, day, stamp(now), work.newsId(), work.claimToken(), maxAttempts);
            if (claimed == 0) { tx.setRollbackOnly(); return null; }
            return selected;
        });
    }

    private void ensureBudget(LocalDate day) {
        try {
            jdbc.update("INSERT INTO summary_daily_budget(budget_date,attempts) SELECT ?,0 "
                + "WHERE NOT EXISTS (SELECT 1 FROM summary_daily_budget WHERE budget_date=?)", day, day);
        } catch (DuplicateKeyException concurrentWorker) {
            // Insert outside the reservation transaction so PostgreSQL does not leave it aborted.
        }
    }

    public int attemptsToday(LocalDate day) {
        return jdbc.query("SELECT attempts FROM summary_daily_budget WHERE budget_date=?",
            (rs, row) -> rs.getInt(1), day).stream().findFirst().orElse(0);
    }

    private DayBudget dayBudget(LocalDate day, boolean lock) {
        return jdbc.query("SELECT attempts,model_accounted,legacy_attempts,blocked_until FROM summary_daily_budget WHERE budget_date=?"
            + (lock ? " FOR UPDATE" : ""), (rs, row) -> new DayBudget(rs.getInt("attempts"), rs.getBoolean("model_accounted"),
                rs.getInt("legacy_attempts"), instant(rs, "blocked_until")), day).stream().findFirst()
            .orElse(new DayBudget(0, false, 0, null));
    }

    /** Called only while holding the corresponding global day row lock. */
    private void ensureModelBudget(LocalDate day, String model, int legacy) {
        jdbc.update("INSERT INTO summary_model_daily_budget(budget_date,model,attempts) SELECT ?,?,? "
            + "WHERE NOT EXISTS (SELECT 1 FROM summary_model_daily_budget WHERE budget_date=? AND model=?)",
            day, model, legacy, day, model);
    }

    private ModelBudget rawModelBudget(LocalDate day, String model) {
        return jdbc.query("SELECT attempts,blocked_until FROM summary_model_daily_budget WHERE budget_date=? AND model=?",
            (rs, row) -> new ModelBudget(model, rs.getInt("attempts"), instant(rs, "blocked_until")), day, model)
            .stream().findFirst().orElse(null);
    }

    /** Read-only projection keeps GET free of migration writes while retaining conservative legacy usage. */
    public ModelBudget modelBudget(LocalDate day, String model) {
        ModelBudget recorded = rawModelBudget(day, model);
        if (recorded != null) return recorded;
        DayBudget budget = dayBudget(day, false);
        return new ModelBudget(model, budget.modelAccounted() ? budget.legacyAttempts() : budget.attempts(), null);
    }

    public void blockModelUntil(LocalDate day, String model, Instant until) {
        ensureBudget(day);
        transactions.executeWithoutResult(tx -> {
            DayBudget budget = dayBudget(day, true);
            int legacy = budget.modelAccounted() ? budget.legacyAttempts() : budget.attempts();
            ensureModelBudget(day, model, legacy);
            jdbc.update("UPDATE summary_model_daily_budget SET blocked_until=CASE WHEN blocked_until IS NULL OR blocked_until<? THEN ? ELSE blocked_until END "
                + "WHERE budget_date=? AND model=?", stamp(until), stamp(until), day, model);
        });
    }

    public boolean blocked(LocalDate day, Instant now) {
        return !jdbc.queryForList("SELECT budget_date FROM summary_daily_budget WHERE budget_date=? "
            + "AND blocked_until>?", LocalDate.class, day, stamp(now)).isEmpty();
    }

    public void blockUntil(LocalDate day, Instant until) {
        ensureBudget(day);
        jdbc.update("UPDATE summary_daily_budget SET blocked_until=CASE WHEN blocked_until IS NULL OR blocked_until<? THEN ? ELSE blocked_until END WHERE budget_date=?",
            stamp(until), stamp(until), day);
    }

    public void defer(Stored work, Instant next, String message, Instant now) {
        jdbc.update("UPDATE news_summary SET status='QUEUED',claim_token=NULL,next_attempt_at=?,error=?,updated_at=? "
            + "WHERE news_id=? AND status='PROCESSING' AND claim_token=?",
            stamp(next), message, stamp(now), work.newsId(), work.claimToken());
    }

    public void deferForFallback(Stored work, Instant next, String message, Instant now) {
        jdbc.update("UPDATE news_summary SET status='QUEUED',claim_token=NULL,retry_model=NULL,retry_model_day=NULL,next_attempt_at=?,error=?,updated_at=? "
            + "WHERE news_id=? AND status='PROCESSING' AND claim_token=?",
            stamp(next), message, stamp(now), work.newsId(), work.claimToken());
    }

    public void fail(Stored work, String message, Instant now) {
        jdbc.update("UPDATE news_summary SET status='FAILED',claim_token=NULL,error=?,updated_at=? "
            + "WHERE news_id=? AND status='PROCESSING' AND claim_token=?",
            message, stamp(now), work.newsId(), work.claimToken());
    }

    public void complete(Stored work, String json, String model, String promptVersion, Instant now) {
        jdbc.update("UPDATE news_summary SET status='SUCCEEDED',result_json=?,model=?,prompt_version=?,generated_at=?,updated_at=?,error=NULL,claim_token=NULL,retry_model=NULL,retry_model_day=NULL "
            + "WHERE news_id=? AND status='PROCESSING' AND claim_token=?",
            json, model, promptVersion, stamp(now), stamp(now), work.newsId(), work.claimToken());
    }

    /** Lease fencing prevents a late response from replacing a later worker's result. */
    public void recoverStale(Instant before, Instant now, int maxAttempts) {
        jdbc.update("UPDATE news_summary SET status=CASE WHEN attempts>=? OR (source_attempts>=? AND source_content IS NULL) THEN 'FAILED' ELSE 'QUEUED' END,"
            + "claim_token=NULL,next_attempt_at=?,updated_at=?,error=? WHERE status='PROCESSING' AND updated_at<?",
            maxAttempts, maxAttempts, stamp(now), stamp(now), "이전 처리 중 연결이 끊겼습니다. 제한 내에서 다시 처리합니다.", stamp(before));
    }

    private static Stored map(ResultSet rs, int row) throws SQLException {
        return new Stored(rs.getLong("news_id"), rs.getString("status"), rs.getString("claim_token"),
            rs.getString("source_scope"), rs.getString("source_url"), rs.getString("source_content"),
            rs.getString("source_warning"), rs.getString("input_hash"), rs.getString("result_json"),
            rs.getString("model"), rs.getString("prompt_version"), rs.getInt("attempts"),
            rs.getInt("source_attempts"), instant(rs, "next_attempt_at"), instant(rs, "generated_at"), rs.getString("error"), rs.getString("retry_model"), rs.getObject("retry_model_day", LocalDate.class));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime stamp(Instant value) { return value.atOffset(ZoneOffset.UTC); }
}
