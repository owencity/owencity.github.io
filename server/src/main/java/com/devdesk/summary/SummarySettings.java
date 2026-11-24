package com.devdesk.summary;

import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("devdesk.summary")
public record SummarySettings(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String apiKey,
        @DefaultValue("") String model,
        @DefaultValue("20") int dailyLimit,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("1") int batchSize,
        @DefaultValue("120000") int maxInputChars,
        @DefaultValue("12000") int maxOutputTokens,
        @DefaultValue("90") int requestTimeoutSeconds,
        @DefaultValue("") String fallbackModels,
        @DefaultValue("500") int modelDailyLimit) {
    @ConstructorBinding
    public SummarySettings {
        apiKey = apiKey == null ? "" : apiKey.strip();
        model = model == null ? "" : model.strip();
        fallbackModels = fallbackModels == null ? "" : fallbackModels.strip();
        if (dailyLimit < 0 || dailyLimit > 10000 || maxAttempts < 1 || maxAttempts > 5
                || batchSize < 1 || batchSize > 10 || maxInputChars < 200 || maxInputChars > 500000
                || maxOutputTokens < 1024 || maxOutputTokens > 65536
                || requestTimeoutSeconds < 10 || requestTimeoutSeconds > 180
                || modelDailyLimit < 1 || modelDailyLimit > 10000)
            throw new IllegalArgumentException("Invalid DevDesk summary limits");
        if ((!model.isBlank() && !validModel(model)) || fallbackModels.length() > 1000)
            throw new IllegalArgumentException("Invalid Gemini model ID");
        for (String candidate : fallbackModels.split(","))
            if (!candidate.isBlank() && !validModel(candidate.strip())) throw new IllegalArgumentException("Invalid Gemini fallback model ID");
    }
    /** Preserve existing callers and tests that configure a single model. */
    public SummarySettings(boolean enabled, String apiKey, String model, int dailyLimit, int maxAttempts,
            int batchSize, int maxInputChars, int maxOutputTokens, int requestTimeoutSeconds) {
        this(enabled, apiKey, model, dailyLimit, maxAttempts, batchSize, maxInputChars, maxOutputTokens,
            requestTimeoutSeconds, "", 500);
    }
    public List<String> modelOrder() {
        var models = new LinkedHashSet<String>();
        if (!model.isBlank()) models.add(model);
        for (String candidate : fallbackModels.split(",")) if (!candidate.isBlank()) models.add(candidate.strip());
        return List.copyOf(models);
    }
    private static boolean validModel(String model) { return model.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,149}"); }
    public boolean configured() { return !apiKey.isBlank() && !model.isBlank(); }
    @Override public String toString() {
        return "SummarySettings[enabled=" + enabled + ", configured=" + configured() + ", models=" + modelOrder() + "]";
    }
}
