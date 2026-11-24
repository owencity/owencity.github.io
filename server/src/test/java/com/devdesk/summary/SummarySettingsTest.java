package com.devdesk.summary;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import static org.assertj.core.api.Assertions.*;

class SummarySettingsTest {
    @Test void fallbackOrderPreservesPriorityAndRemovesDuplicatesAndWhitespace() {
        var settings = new SummarySettings(true, "private-key", "gemini-3.5-flash-lite", 20, 3, 1,
            120000, 12000, 90, " gemini-3.1-flash-lite,gemini-3.5-flash-lite, gemini-3.1-flash-lite ,", 500);
        assertThat(settings.modelOrder()).containsExactly("gemini-3.5-flash-lite", "gemini-3.1-flash-lite");
        assertThat(settings.toString()).doesNotContain("private-key");
        assertThat(settings.dailyLimit()).isEqualTo(20);
        assertThat(settings.modelDailyLimit()).isEqualTo(500);
    }

    @Test void oldConstructorKeepsSingleModelCompatibility() {
        var settings = new SummarySettings(true, "key", "single-model", 20, 3, 1, 120000, 12000, 90);
        assertThat(settings.modelOrder()).containsExactly("single-model");
        assertThat(settings.modelDailyLimit()).isEqualTo(500);
        assertThat(settings.configured()).isTrue();
    }

    @Test void modelLimitsAndIdsAreValidatedWithoutPrintingSecrets() {
        assertThatThrownBy(() -> new SummarySettings(true, "private-key", "bad model", 20, 3, 1, 120000, 12000, 90))
            .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-key");
        assertThatThrownBy(() -> new SummarySettings(true, "private-key", "valid", 20, 3, 1, 120000, 12000, 90, "fallback", 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SummarySettings(true, "private-key", "valid", 20, 3, 1, 120000, 12000, 90, "https://invalid/model", 500))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void emptyModelDoesNotBecomeConfiguredOnlyBecauseFallbackExists() {
        var settings = new SummarySettings(true, "key", "", 20, 3, 1, 120000, 12000, 90, "fallback", 500);
        assertThat(settings.configured()).isFalse();
    }

    @Test void realBootBinderUsesCanonicalConstructorWithFallbackAndDefaults() {
        var source = new MapConfigurationPropertySource(Map.of(
            "devdesk.summary.enabled", "true",
            "devdesk.summary.api-key", "test-only-not-a-real-key",
            "devdesk.summary.model", "gemini-3.5-flash-lite",
            "devdesk.summary.fallback-models", "gemini-3.1-flash-lite",
            "devdesk.summary.model-daily-limit", "7",
            "devdesk.summary.daily-limit", "1"));
        var settings = new Binder(source).bind("devdesk.summary", Bindable.of(SummarySettings.class)).get();
        assertThat(settings.enabled()).isTrue();
        assertThat(settings.configured()).isTrue();
        assertThat(settings.dailyLimit()).isEqualTo(1);
        assertThat(settings.modelDailyLimit()).isEqualTo(7);
        assertThat(settings.maxAttempts()).isEqualTo(3);
        assertThat(settings.modelOrder()).containsExactly("gemini-3.5-flash-lite", "gemini-3.1-flash-lite");
    }

    @Test void unspecifiedOptionalPropertiesKeepConservativeDefaults() {
        var source = new MapConfigurationPropertySource(Map.of(
            "devdesk.summary.model", "gemini-3.5-flash-lite"));
        var settings = new Binder(source).bind("devdesk.summary", Bindable.of(SummarySettings.class)).get();
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.configured()).isFalse();
        assertThat(settings.dailyLimit()).isEqualTo(20);
        assertThat(settings.modelDailyLimit()).isEqualTo(500);
        assertThat(settings.modelOrder()).containsExactly("gemini-3.5-flash-lite");
    }
}
