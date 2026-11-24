package com.devdesk.tasks;

import com.devdesk.calendar.CalendarEvent;
import com.devdesk.calendar.CalendarException;
import com.devdesk.calendar.CalendarService;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tasks")
public class TasksController {
    private final TasksService tasks;
    private final CalendarService calendar;

    public TasksController(TasksService tasks, CalendarService calendar) {
        this.tasks = tasks;
        this.calendar = calendar;
    }

    public record NewTask(String title) {}
    public record Completion(Boolean completed) {}
    public record Plan(LocalDate date, LocalTime start, LocalTime end, String requestId, String eventId, String etag) {}

    @GetMapping("/board")
    public TasksService.Board board() { return tasks.board(); }

    @PostMapping("/{list}")
    public TasksService.Task add(@PathVariable String list, @RequestBody NewTask input) {
        return tasks.add(list, input.title());
    }

    @PatchMapping("/{list}/{id}")
    public TasksService.Task complete(@PathVariable String list, @PathVariable String id, @RequestBody Completion input) {
        return tasks.complete(list, id, input.completed());
    }

    @PutMapping("/{list}/{id}/today-plan")
    public CalendarEvent plan(@PathVariable String list, @PathVariable String id, @RequestBody Plan input) {
        if (input.date() == null || input.start() == null || input.end() == null)
            throw new CalendarException(400, "INVALID_TASK_PLAN", "시작과 종료 시간을 입력해 주세요.");
        var task = tasks.todayTask(list, id);
        if (task.completed()) throw new CalendarException(409, "TASK_COMPLETED", "이미 완료한 할 일입니다.");
        return calendar.scheduleTodayTask(task.planKey(), task.title(), input.date(), input.start(), input.end(),
            input.requestId(), input.eventId(), input.etag());
    }

    @ExceptionHandler(CalendarException.class)
    public ResponseEntity<Map<String, String>> failure(CalendarException failure) {
        return ResponseEntity.status(failure.status()).body(Map.of("code", failure.code(), "message", failure.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> invalidBody() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_TASK", "message", "할 일 입력을 확인해 주세요."));
    }
}
