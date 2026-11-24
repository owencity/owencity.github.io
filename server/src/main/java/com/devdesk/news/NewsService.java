package com.devdesk.news;

import com.devdesk.HttpFetch;
import java.net.URI;
import java.net.http.HttpRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
public class NewsService {
    public static final Set<String> FEEDBACK = Set.of("NONE","INTERESTED","NOT_INTERESTED","KNOWN","LATER","READ");
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    public NewsService(JdbcTemplate jdbc,@Value("${devdesk.feed.enabled:true}") boolean enabled) { this.jdbc=jdbc; this.enabled=enabled; }

    public List<NewsItem> list(String filter,String query) {
        String condition = switch(filter) {
            case "all" -> "feedback NOT IN ('NOT_INTERESTED','KNOWN')";
            case "saved" -> "feedback = 'LATER'";
            case "hidden" -> "feedback IN ('NOT_INTERESTED','KNOWN')";
            case "recommended" -> "feedback NOT IN ('NOT_INTERESTED','KNOWN','READ')";
            default -> throw new ResponseStatusException(BAD_REQUEST,"Unknown news filter");
        };
        if (query.length()>200) throw new ResponseStatusException(BAD_REQUEST,"Query too long");
        // Literal substring matching; escape LIKE wildcard characters from user input.
        String pattern = "%"+query.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        String order=filter.equals("recommended")?"CASE WHEN feedback='INTERESTED' THEN 0 WHEN feedback='LATER' THEN 1 ELSE 2 END, ":"";
        return jdbc.query("SELECT * FROM news_item WHERE "+condition+" AND (LOWER(title) LIKE ? ESCAPE '!' OR LOWER(description) LIKE ? ESCAPE '!') ORDER BY "+order+"COALESCE(published_at,collected_at) DESC, id DESC LIMIT 300",
            NewsService::map,pattern,pattern);
    }

    @Transactional
    public NewsItem feedback(long id,String value) {
        if (!FEEDBACK.contains(value)) throw new ResponseStatusException(BAD_REQUEST,"Invalid feedback");
        if (jdbc.update("UPDATE news_item SET feedback=? WHERE id=?",value,id)==0) throw new ResponseStatusException(NOT_FOUND,"News not found");
        jdbc.update("INSERT INTO news_feedback_event(news_id,feedback) VALUES (?,?)",id,value);
        return jdbc.queryForObject("SELECT * FROM news_item WHERE id=?",NewsService::map,id);
    }

    public FeedState state() {
        return jdbc.queryForObject("SELECT * FROM feed_state WHERE id=1",(rs,row)->new FeedState(instant(rs,"last_collected_at"),rs.getString("error")));
    }
    public record RefreshResult(int imported,long total,Instant lastCollectedAt) {}

    public synchronized RefreshResult refresh() {
        FeedState current=state();
        if(current.lastCollectedAt()!=null && current.lastCollectedAt().isAfter(Instant.now().minusSeconds(60))) {
            return new RefreshResult(0,jdbc.queryForObject("SELECT COUNT(*) FROM news_item",Long.class),current.lastCollectedAt());
        }
        try {
            byte[] body=HttpFetch.send(HttpRequest.newBuilder(URI.create("https://feeds.feedburner.com/geeknews-feed"))
                .header("User-Agent","DevDesk/0.1 personal RSS reader").header("Accept","application/atom+xml, application/rss+xml, application/xml").GET().build(),4_194_304);
            int count=ingest(FeedParser.parse(body),Instant.now());
            Instant now=Instant.now();
            jdbc.update("UPDATE feed_state SET last_collected_at=?, error=NULL WHERE id=1",now.atOffset(ZoneOffset.UTC));
            return new RefreshResult(count,jdbc.queryForObject("SELECT COUNT(*) FROM news_item",Long.class),now);
        } catch(Exception failure) {
            jdbc.update("UPDATE feed_state SET error=? WHERE id=1","뉴스 수집에 실패했습니다. 기존 뉴스는 유지됩니다.");
            throw new ResponseStatusException(BAD_GATEWAY,"Feed unavailable");
        }
    }

    int ingest(List<FeedParser.Entry> entries,Instant now) {
        int inserted=0;
        for(var entry:entries) {
            try {
                inserted+=jdbc.update("INSERT INTO news_item(source_id,title,url,description,published_at,collected_at,topic,feedback) SELECT ?,?,?,?,?,?,?,'NONE' WHERE NOT EXISTS (SELECT 1 FROM news_item WHERE source_id=?)",
                    entry.sourceId(),entry.title(),entry.url(),entry.description(),entry.publishedAt()==null?null:entry.publishedAt().atOffset(ZoneOffset.UTC),now.atOffset(ZoneOffset.UTC),topic(entry.title()+" "+entry.description()),entry.sourceId());
            } catch(DuplicateKeyException duplicate) { /* Concurrent insert: preserve the existing feedback. */ }
        }
        return inserted;
    }

    @Scheduled(initialDelay=10000,fixedDelayString="${devdesk.feed.interval-ms:900000}")
    void scheduledCollect() { if(enabled) { try { refresh(); } catch(ResponseStatusException ignored) {} } }

    private static String topic(String text) {
        String lower=text.toLowerCase(Locale.ROOT);
        if(lower.matches("(?s).*\\b(ai|llm|agent|claude|gpt|jev)\\b.*")||lower.contains("인공지능")||lower.contains("에이전트"))return "AI";
        if(lower.matches("(?s).*\\b(java|spring|jvm|kotlin)\\b.*"))return "Java · Spring";
        if(lower.matches("(?s).*\\b(postgres|postgresql|mysql|redis|database|sql)\\b.*")||lower.contains("데이터베이스"))return "데이터";
        return "개발 동향";
    }
    private static NewsItem map(ResultSet rs,int row) throws SQLException {
        return new NewsItem(rs.getLong("id"),rs.getString("source_id"),rs.getString("title"),rs.getString("url"),rs.getString("description"),instant(rs,"published_at"),instant(rs,"collected_at"),rs.getString("topic"),rs.getString("feedback"));
    }
    private static Instant instant(ResultSet rs,String field)throws SQLException {
        OffsetDateTime value=rs.getObject(field,OffsetDateTime.class); return value==null?null:value.toInstant();
    }
}
