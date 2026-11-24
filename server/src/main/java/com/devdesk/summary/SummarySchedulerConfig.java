package com.devdesk.summary;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SummarySchedulerConfig {
    @Bean(name = "summaryScheduler")
    ThreadPoolTaskScheduler summaryScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("news-summary-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    /** Keep existing RSS and digest jobs separate from slower AI calls. */
    @Bean(name = "taskScheduler")
    ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("devdesk-scheduled-");
        return scheduler;
    }
}
