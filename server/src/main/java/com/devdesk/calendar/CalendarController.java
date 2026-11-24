package com.devdesk.calendar;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/calendar")
public class CalendarController {
    private final CalendarService calendar;
    public CalendarController(CalendarService calendar) { this.calendar = calendar; }

    @GetMapping("/events")
    public CalendarDay events(@RequestParam(required = false) String date) {
        try { return calendar.day(date == null ? LocalDate.now(ZoneId.of("Asia/Seoul")) : LocalDate.parse(date)); }
        catch (DateTimeParseException invalid) { throw CalendarPolicy.bad("날짜는 YYYY-MM-DD 형식으로 입력하세요."); }
    }

    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return Map.of("configured", calendar.configured(), "writeEnabled", calendar.writeEnabled());
    }

    @PostMapping("/events")
    public CalendarEvent create(@RequestBody CalendarWriteRequest request) { return calendar.create(request); }

    @PutMapping("/events/{id}")
    public CalendarEvent update(@PathVariable String id, @RequestBody CalendarWriteRequest request,
            @RequestHeader(value = "If-Match", required = false) String etag,
            @RequestParam(required = false) String scope) {
        return calendar.update(id, request, etag, scope);
    }

    @DeleteMapping("/events/{id}")
    public Map<String, Boolean> delete(@PathVariable String id,
            @RequestHeader(value = "If-Match", required = false) String etag,
            @RequestParam(required = false) String scope) {
        calendar.delete(id, etag, scope); return Map.of("deleted", true);
    }

    @ExceptionHandler(CalendarException.class)
    public ResponseEntity<Map<String, String>> failure(CalendarException failure) {
        return ResponseEntity.status(failure.status()).body(Map.of("message", failure.getMessage(), "code", failure.code()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> invalidBody() {
        return ResponseEntity.badRequest().body(Map.of("message", "일정 입력 형식을 확인해 주세요.", "code", "INVALID_EVENT"));
    }
}
