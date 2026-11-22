package com.devdesk.news;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:news;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","devdesk.owner.password=test-password-16-characters",
    "devdesk.feed.enabled=false","devdesk.notifications.enabled=false","devdesk.notifications.scheduled-enabled=false"})
class NewsServiceTest {
    @Autowired NewsService news;
    @Autowired JdbcTemplate jdbc;
    @Test void collectingAgainDoesNotDuplicateOrEraseFeedback() {
        jdbc.update("DELETE FROM news_feedback_event");
        jdbc.update("DELETE FROM news_item");
        var entry=new FeedParser.Entry("stable-id","Jev news","https://example.com/jev","AI example",Instant.now());
        assertThat(news.ingest(List.of(entry),Instant.now())).isEqualTo(1);
        long id=news.list("all","").getFirst().id();
        news.feedback(id,"LATER");
        assertThat(news.ingest(List.of(entry),Instant.now())).isZero();
        assertThat(news.list("saved","")).singleElement().satisfies(item->assertThat(item.feedback()).isEqualTo("LATER"));
        news.feedback(id,"NOT_INTERESTED");
        assertThat(news.list("all","")).isEmpty();
        assertThat(news.list("hidden","")).hasSize(1);
        news.feedback(id,"NONE");
        assertThat(news.list("all","")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM news_feedback_event WHERE news_id=?",Long.class,id)).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT feedback FROM news_feedback_event WHERE news_id=? ORDER BY id",String.class,id))
            .containsExactly("LATER","NOT_INTERESTED","NONE");
    }
}
