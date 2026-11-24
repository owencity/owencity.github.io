package com.devdesk.summary;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SummarySettings.class)
public class SummaryAiConfiguration {
    @Bean(destroyMethod = "close")
    SummaryGenerator summaryGenerator(SummarySettings settings) {
        if (!settings.enabled() || !settings.configured()) {
            return source -> { throw new SummaryGenerationException(
                SummaryGenerationException.Category.PERMANENT, "AI 연결 설정이 필요합니다."); };
        }
        return new GeminiSummaryGenerator(settings);
    }
}
