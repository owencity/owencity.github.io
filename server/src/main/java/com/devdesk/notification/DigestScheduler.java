package com.devdesk.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DigestScheduler {
    private static final Logger log = LoggerFactory.getLogger(DigestScheduler.class);
    private final DigestService service;
    private final boolean scheduledEnabled;
    private final boolean notificationsEnabled;

    public DigestScheduler(DigestService service,
            @Value("${devdesk.notifications.scheduled-enabled:false}") boolean scheduledEnabled,
            @Value("${devdesk.notifications.enabled:false}") boolean notificationsEnabled) {
        this.service = service;
        this.scheduledEnabled = scheduledEnabled;
        this.notificationsEnabled = notificationsEnabled;
    }

    @Scheduled(cron = "${devdesk.notifications.cron:0 0 8 * * *}", zone = "Asia/Seoul")
    public void deliverMorningDigest() {
        if (!scheduledEnabled || !notificationsEnabled) return;
        for (String channel : new String[] {"slack", "email"}) {
            try {
                if (service.channel(channel).configured()) {
                    var result = service.send(channel);
                    log.info("Daily digest channel={} status={}", channel, result.status());
                }
            } catch (RuntimeException failure) {
                // Avoid exception messages: provider errors can include credentials/addresses.
                log.warn("Daily digest channel={} needs owner inspection", channel);
            }
        }
    }
}
