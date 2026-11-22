package com.devdesk.calendar;

import com.devdesk.HttpFetch;
import com.devdesk.news.FeedParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CalendarService {
    public static final String CALENDAR_URL="https://calendar.google.com/calendar/u/0/r/day";
    private final ObjectMapper json;
    private final String clientId,clientSecret,refreshToken,calendarId;
    private String accessToken="";
    private Instant expiresAt=Instant.EPOCH;

    public CalendarService(ObjectMapper json,
        @Value("${devdesk.google.client-id:}") String clientId,
        @Value("${devdesk.google.client-secret:}") String clientSecret,
        @Value("${devdesk.google.refresh-token:}") String refreshToken,
        @Value("${devdesk.google.calendar-id:primary}") String calendarId) {
        this.json=json;this.clientId=clientId;this.clientSecret=clientSecret;this.refreshToken=refreshToken;this.calendarId=calendarId;
    }
    public boolean configured() { return !clientId.isBlank()&&!clientSecret.isBlank()&&!refreshToken.isBlank(); }

    public CalendarToday today() {
        if(!configured())return new CalendarToday(false,List.of(),CALENDAR_URL,null);
        try {
            var day=LocalDate.now(ZoneId.of("Asia/Seoul"));
            var start=day.atStartOfDay(ZoneId.of("Asia/Seoul"));
            var end=day.plusDays(1).atStartOfDay(ZoneId.of("Asia/Seoul"));
            List<CalendarEvent> events=new ArrayList<>();
            String pageToken="";
            int pages=0;
            do {
                String url="https://www.googleapis.com/calendar/v3/calendars/"+encode(calendarId)+"/events?singleEvents=true&orderBy=startTime&showDeleted=false&maxResults=250&timeZone=Asia%2FSeoul&timeMin="+encode(start.toOffsetDateTime().toString())+"&timeMax="+encode(end.toOffsetDateTime().toString());
                if(!pageToken.isBlank())url+="&pageToken="+encode(pageToken);
                byte[] body=HttpFetch.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization","Bearer "+token()).GET().build(),2_097_152);
                var data=json.readTree(body);
                for(JsonNode item:data.path("items")) {
                    if("cancelled".equals(item.path("status").asText()))continue;
                    events.add(event(item));
                }
                pageToken=data.path("nextPageToken").asText("");
                if(++pages>=10&&!pageToken.isBlank())throw new IllegalStateException("Too many calendar pages");
            }while(!pageToken.isBlank());
            return new CalendarToday(true,List.copyOf(events),CALENDAR_URL,null);
        }catch(Exception failure) {
            // Deliberately omit upstream payloads, OAuth tokens, URLs with credentials and stack traces.
            return new CalendarToday(false,List.of(),CALENDAR_URL,"일정을 불러오지 못했습니다. Google 연결 설정과 네트워크를 확인해 주세요.");
        }
    }

    static CalendarEvent event(JsonNode item) {
        boolean allDay=item.path("start").has("date");
        String start=item.path("start").path(allDay?"date":"dateTime").asText();
        String end=item.path("end").path(allDay?"date":"dateTime").asText();
        String url=item.path("htmlLink").asText();
        return new CalendarEvent(item.path("id").asText(),item.path("summary").asText("제목 없는 일정"),start,end,allDay,FeedParser.safeUrl(url)?url:CALENDAR_URL);
    }

    private synchronized String token()throws Exception {
        if(!accessToken.isBlank()&&Instant.now().isBefore(expiresAt))return accessToken;
        String form="grant_type=refresh_token&client_id="+encode(clientId)+"&client_secret="+encode(clientSecret)+"&refresh_token="+encode(refreshToken);
        byte[] bytes=HttpFetch.send(HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token"))
            .header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(),65536);
        var response=json.readTree(bytes);
        String value=response.path("access_token").asText("");
        if(value.isBlank())throw new IllegalStateException("Token not returned");
        accessToken=value;
        expiresAt=Instant.now().plusSeconds(Math.max(1,response.path("expires_in").asLong(3600)-60));
        return accessToken;
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
}
