package com.devdesk.notification;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/digest")
public class DigestController {
    private final DigestService digest;
    public DigestController(DigestService digest) { this.digest = digest; }

    @GetMapping("/preview")
    public DigestService.Preview preview() { return digest.preview(); }

    @PostMapping("/send")
    public DigestService.Delivery send(@RequestBody SendRequest request) {
        if (request.channel() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "채널을 선택하세요.");
        return digest.send(request.channel());
    }

    public record SendRequest(String channel) {}
}
