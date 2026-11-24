package com.devdesk.tasks;

import com.devdesk.HttpFetch;
import com.devdesk.calendar.CalendarException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TasksServiceTest {
    @Test void readsExistingListNamesAndSeparatesTaskIdentityAcrossLists() {
        var requests = new ArrayList<HttpRequest>();
        var service = new TasksService(new ObjectMapper(), "fake-client", "fake-secret", "fake-token",
            (request, limit) -> {
                requests.add(request);
                return switch (request.uri().getPath()) {
                    case "/token" -> ok("{\"access_token\":\"test-access\"}");
                    case "/tasks/v1/users/@me/lists" -> ok("""
                        {"items":[{"id":"week","title":"WEEKLY"},{"id":"misc","title":"Other"},{"id":"today","title":"TODAY"}]}
                        """);
                    case "/tasks/v1/lists/today/tasks", "/tasks/v1/lists/week/tasks" -> ok("""
                        {"items":[{"id":"task","title":"JVM","status":"needsAction"}]}
                        """);
                    default -> throw new AssertionError("Unexpected route");
                };
            }, Clock.systemUTC());
        var board = service.board();
        assertThat(board.connected()).isTrue();
        assertThat(board.lists()).extracting(TasksService.ListView::horizon).containsExactly("TODAY", "WEEK");
        assertThat(board.lists().stream().flatMap(list -> list.tasks().stream()).map(TasksService.Task::planKey))
            .doesNotHaveDuplicates();
        assertThat(requests.stream().filter(request -> "POST".equals(request.method())).map(request -> request.uri().getPath()))
            .containsExactly("/token");
    }

    @Test void completedGoogleTaskStatusMapsToCheckedAndTimeCanOnlyBeAssignedInToday() {
        var methods = new ArrayList<String>();
        var service = new TasksService(new ObjectMapper(), "fake-client", "fake-secret", "fake-token",
            (request, limit) -> {
                methods.add(request.method());
                return switch (request.uri().getPath()) {
                    case "/token" -> ok("{\"access_token\":\"test-access\"}");
                    case "/tasks/v1/users/@me/lists/week" -> ok("{\"title\":\"WEEKLY\"}");
                    case "/tasks/v1/lists/week/tasks/task" -> ok("{\"id\":\"task\",\"title\":\"JVM\",\"status\":\"completed\"}");
                    default -> throw new AssertionError("Unexpected route");
                };
            }, Clock.systemUTC());
        assertThat(service.complete("week", "task", true).completed()).isTrue();
        assertThatThrownBy(() -> service.todayTask("week", "task")).isInstanceOf(CalendarException.class)
            .hasMessageContaining("TODAY");
        assertThat(methods).containsExactly("POST", "GET", "PATCH", "GET");
    }

    @Test void confirmedReadOnlyScopeKeepsTheListsReadableAndBlocksMutation() {
        var requests = new ArrayList<HttpRequest>();
        var service = new TasksService(new ObjectMapper(), "fake-client", "fake-secret", "fake-token",
            (request, limit) -> {
                requests.add(request);
                return switch (request.uri().getPath()) {
                    case "/token" -> ok("{\"access_token\":\"test-access\",\"scope\":\"https://www.googleapis.com/auth/tasks.readonly\"}");
                    case "/tasks/v1/users/@me/lists" -> ok("{\"items\":[{\"id\":\"today\",\"title\":\"TODAY\"}]}");
                    case "/tasks/v1/lists/today/tasks" -> ok("{\"items\":[]}");
                    case "/tasks/v1/users/@me/lists/today" -> ok("{\"title\":\"TODAY\"}");
                    default -> throw new AssertionError("Unexpected route");
                };
            }, Clock.systemUTC());
        var board = service.board();
        assertThat(board.connected()).isTrue();
        assertThat(board.writeEnabled()).isFalse();
        assertThat(board.lists()).hasSize(1);
        assertThatThrownBy(() -> service.complete("today", "task", true))
            .isInstanceOfSatisfying(CalendarException.class, error -> assertThat(error.code()).isEqualTo("TASKS_WRITE_SCOPE_REQUIRED"));
        assertThat(requests).noneMatch(request -> "PATCH".equals(request.method()));
    }

    private static HttpFetch.Response ok(String data) {
        return new HttpFetch.Response(200, data.getBytes(StandardCharsets.UTF_8));
    }
}
