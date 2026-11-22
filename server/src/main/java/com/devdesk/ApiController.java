package com.devdesk;

import com.devdesk.calendar.CalendarService;
import com.devdesk.calendar.CalendarToday;
import com.devdesk.news.FeedState;
import com.devdesk.news.NewsItem;
import com.devdesk.news.NewsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;

@RestController
public class ApiController {
    private final NewsService news;
    private final CalendarService calendar;
    @Value("${devdesk.notifications.enabled:false}") private boolean notificationsEnabled;
    public ApiController(NewsService news,CalendarService calendar) { this.news=news;this.calendar=calendar; }

    @GetMapping("/health") public Map<String,String> health(){return Map.of("status","UP");}
    @GetMapping("/api/session")
    public Map<String,String> session(Principal principal,CsrfToken csrf) {
        return Map.of("username",principal.getName(),"csrfToken",csrf.getToken(),"csrfHeaderName",csrf.getHeaderName(),"csrfParameterName",csrf.getParameterName());
    }
    public record NewsResponse(List<NewsItem> items,Instant lastCollectedAt,String error) {}
    @GetMapping("/api/news")
    public NewsResponse news(@RequestParam(defaultValue="all")String filter,@RequestParam(defaultValue="")String q) {
        FeedState state=news.state(); return new NewsResponse(news.list(filter,q),state.lastCollectedAt(),state.error());
    }
    public record FeedbackRequest(@NotBlank String feedback) {}
    @PostMapping("/api/news/{id}/feedback")
    public NewsItem feedback(@PathVariable long id,@Valid @RequestBody FeedbackRequest request) { return news.feedback(id,request.feedback()); }
    @PostMapping("/api/news/refresh") public NewsService.RefreshResult refresh(){return news.refresh();}
    @GetMapping("/api/calendar/today") public CalendarToday today(){return calendar.today();}
    public record Status(FeedState feed,boolean calendarConfigured,boolean notificationsEnabled) {}
    @GetMapping("/api/status") public Status status(){return new Status(news.state(),calendar.configured(),notificationsEnabled);}
}
