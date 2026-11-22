package com.devdesk.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DigestService {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final DigestComposer composer;
    private final ObjectProvider<JavaMailSender> mail;
    private final ObjectMapper json;
    private final boolean enabled;
    private final String slackWebhook;
    private final String emailFrom;
    private final String emailTo;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    public DigestService(JdbcTemplate jdbc, DigestComposer composer, ObjectProvider<JavaMailSender> mail,
            ObjectMapper json,
            @Value("${devdesk.notifications.enabled:false}") boolean enabled,
            @Value("${devdesk.notifications.slack-webhook:}") String slackWebhook,
            @Value("${devdesk.notifications.email-from:}") String emailFrom,
            @Value("${devdesk.notifications.email-to:}") String emailTo) {
        this.jdbc = jdbc;
        this.composer = composer;
        this.mail = mail;
        this.json = json;
        this.enabled = enabled;
        this.slackWebhook = slackWebhook;
        this.emailFrom = emailFrom;
        this.emailTo = emailTo;
    }

    public Preview preview() {
        var content = composer.compose(LocalDate.now(SEOUL));
        return new Preview(content.subject(), content.text(), enabled,
            List.of(channel("slack"), channel("email")));
    }

    public Channel channel(String name) {
        boolean configured = switch (name) {
            case "slack" -> validSlackWebhook(slackWebhook);
            case "email" -> validEmail(emailFrom) && validEmail(emailTo) && mail.getIfAvailable() != null;
            default -> false;
        };
        var rows = jdbc.query("SELECT status FROM digest_delivery WHERE digest_date = ? AND channel = ?",
            (rs, n) -> rs.getString("status"), LocalDate.now(SEOUL), name);
        return new Channel(name, configured, rows.isEmpty() ? "NOT_SENT" : rows.getFirst());
    }

    public Delivery send(String name) {
        if (!List.of("slack", "email").contains(name))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 알림 채널입니다.");
        if (!enabled)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "알림 발송이 꺼져 있습니다.");
        if (!channel(name).configured())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "채널 설정이 필요합니다.");
        LocalDate day = LocalDate.now(SEOUL);
        // Read/compose before claiming: a source failure must not leave a false delivery claim.
        var content = composer.compose(day);
        if (!claim(day, name)) {
            return new Delivery(name, channel(name).status(), false,
                "이미 발송했거나 결과를 확인해야 하는 작업입니다. 다시 보내지 않았습니다.");
        }
        String status;
        if (name.equals("slack")) status = sendSlack(content);
        else status = sendEmail(content);
        // Do not retry delivery if saving this result fails. PROCESSING requires owner inspection.
        jdbc.update("UPDATE digest_delivery SET status = ?, updated_at = CURRENT_TIMESTAMP, detail = ? "
            + "WHERE digest_date = ? AND channel = ?", status, detail(status), day, name);
        return new Delivery(name, status, true, detail(status));
    }

    boolean claim(LocalDate day, String channel) {
        try {
            jdbc.update("INSERT INTO digest_delivery(digest_date,channel,status,created_at,updated_at,detail) "
                + "VALUES (?,?,'PROCESSING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'')", day, channel);
            return true;
        } catch (DuplicateKeyException duplicate) {
            // Only definite rejections can be retried; uncertain outcomes are never resent automatically.
            return jdbc.update("UPDATE digest_delivery SET status = 'PROCESSING', updated_at = CURRENT_TIMESTAMP "
                + "WHERE digest_date = ? AND channel = ? AND status = 'FAILED'", day, channel) == 1;
        }
    }

    private String sendSlack(DigestComposer.Content content) {
        try {
            String text = (content.subject() + "\n\n" + content.text())
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            var request = HttpRequest.newBuilder(URI.create(slackWebhook))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                    Map.of("text", text, "unfurl_links", false, "unfurl_media", false))))
                .build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && response.body().trim().equals("ok")) return "SENT";
            if (response.statusCode() >= 400 && response.statusCode() < 500) return "FAILED";
            return "UNKNOWN";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "UNKNOWN";
        } catch (Exception ignored) {
            return "UNKNOWN";
        }
    }

    private String sendEmail(DigestComposer.Content content) {
        try {
            var message = new SimpleMailMessage();
            message.setFrom(emailFrom);
            message.setTo(emailTo);
            message.setSubject(content.subject());
            message.setText(content.text());
            mail.getObject().send(message);
            return "SENT";
        } catch (MailAuthenticationException | MailPreparationException rejected) {
            return "FAILED";
        } catch (Exception ignored) {
            return "UNKNOWN";
        }
    }

    static boolean validSlackWebhook(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && "hooks.slack.com".equalsIgnoreCase(uri.getHost())
                && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null
                && uri.getQuery() == null && uri.getFragment() == null
                && uri.getPath().startsWith("/services/") && uri.getPath().split("/").length == 5;
        } catch (RuntimeException invalid) { return false; }
    }

    private static boolean validEmail(String value) {
        if (value == null || value.isBlank() || value.contains("\r") || value.contains("\n")) return false;
        try { new InternetAddress(value, true).validate(); return true; }
        catch (Exception invalid) { return false; }
    }

    private String detail(String status) {
        return switch (status) {
            case "SENT" -> "발송 요청이 수락되었습니다. 실제 알림 표시는 수신 앱 설정에 따라 달라집니다.";
            case "FAILED" -> "발송이 거절되었습니다. 채널 설정을 확인하세요.";
            default -> "발송 결과를 확인할 수 없습니다. 수신함을 확인하기 전에는 재발송하지 않습니다.";
        };
    }

    public record Channel(String name, boolean configured, String status) {}
    public record Preview(String subject, String text, boolean enabled, List<Channel> channels) {}
    public record Delivery(String channel, String status, boolean attempted, String message) {}
}
