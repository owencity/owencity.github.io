package com.devdesk.summary;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static com.devdesk.summary.SummaryGenerationException.Category.*;

class GeminiRateLimitClassifierTest {
    private static final String MODEL = "gemini-3.5-flash-lite";
    private static final String REQUEST_METRIC = "generativelanguage.googleapis.com/generate_content_free_tier_requests";
    private static final String TOKEN_METRIC = "generativelanguage.googleapis.com/generate_content_free_tier_input_token_count";
    private static final String DAILY = "GenerateRequestsPerDayPerProjectPerModel-FreeTier";

    @Test void classifiesMatchingModelDailyRequestQuotaFromSdkDetails() {
        assertThat(classify(sdkDetails(violation(REQUEST_METRIC, DAILY, MODEL))))
            .isEqualTo(DAILY_MODEL_LIMIT);
    }

    @Test void acceptsAnErrorEnvelopeAndCanonicalModelsPrefix() {
        String payload = "{\"error\":{\"code\":429,\"details\":" + quotaArray(violation(REQUEST_METRIC, DAILY, "models/" + MODEL)) + "}}";
        assertThat(GeminiRateLimitClassifier.classify(payload, "models/" + MODEL)).isEqualTo(DAILY_MODEL_LIMIT);
    }

    @Test void differentModelOrMissingModelCannotEnableFallback() {
        assertThat(classify(sdkDetails(violation(REQUEST_METRIC, DAILY, "gemini-other")))).isEqualTo(RATE_LIMIT);
        assertThat(classify(sdkDetails(violation(REQUEST_METRIC, DAILY, null)))).isEqualTo(RATE_LIMIT);
        assertThat(GeminiRateLimitClassifier.classify(sdkDetails(violation(REQUEST_METRIC, DAILY, MODEL)), null))
            .isEqualTo(RATE_LIMIT);
    }

    @Test void projectSharedDailyQuotaCannotEnableFallbackEvenWithAModelDimension() {
        assertThat(classify(sdkDetails(violation(REQUEST_METRIC, "GenerateRequestsPerDayPerProject-FreeTier", MODEL))))
            .isEqualTo(RATE_LIMIT);
    }

    @Test void minuteRequestsAndMinuteTokensAreTransientWithoutSwitchingModels() {
        assertThat(classify(sdkDetails(violation(REQUEST_METRIC, "GenerateRequestsPerMinutePerProjectPerModel-FreeTier", MODEL))))
            .isEqualTo(RATE_LIMIT_TRANSIENT);
        assertThat(classify(sdkDetails(violation(TOKEN_METRIC, "GenerateContentInputTokensPerModelPerMinute-FreeTier", MODEL))))
            .isEqualTo(RATE_LIMIT_TRANSIENT);
    }

    @Test void confirmedProjectSecondRateIsTransientEvenWithoutModelDimension() {
        assertThat(classify(sdkDetails(violation(REQUEST_METRIC, "GenerateRequestsPerSecondPerProject", null))))
            .isEqualTo(RATE_LIMIT_TRANSIENT);
    }

    @Test void dailyModelPlusMinuteQuotaDoesNotTriggerFallback() {
        assertThat(classify(sdkDetails(violation(REQUEST_METRIC, DAILY, MODEL),
            violation(REQUEST_METRIC, "GenerateRequestsPerMinutePerProjectPerModel-FreeTier", MODEL))))
            .isEqualTo(RATE_LIMIT_TRANSIENT);
    }

    @Test void mixedSharedAndModelDailyQuotasBlockGloballyRegardlessOfOrder() {
        String model = violation(REQUEST_METRIC, DAILY, MODEL);
        String shared = violation(REQUEST_METRIC, "GenerateRequestsPerDayPerProject-FreeTier", null);
        assertThat(classify(sdkDetails(model, shared))).isEqualTo(RATE_LIMIT);
        assertThat(classify(sdkDetails(shared, model))).isEqualTo(RATE_LIMIT);
    }

    @Test void unknownViolationAlongsideTransientIsConservative() {
        assertThat(classify(sdkDetails(violation(REQUEST_METRIC, "GenerateRequestsPerMinutePerProjectPerModel-FreeTier", MODEL),
            "{\"description\":\"Daily quota exceeded\"}"))).isEqualTo(RATE_LIMIT);
    }

    @Test void dailyTokenQuotaIsNotAModelDailyRequestLimit() {
        assertThat(classify(sdkDetails(violation(TOKEN_METRIC, "GenerateContentInputTokensPerDayPerProjectPerModel-FreeTier", MODEL))))
            .isEqualTo(RATE_LIMIT);
        assertThat(classify(sdkDetails(violation(TOKEN_METRIC, DAILY, MODEL)))).isEqualTo(RATE_LIMIT);
    }

    @Test void missingMetricOrUnknownProviderMetricCannotEnableFallback() {
        assertThat(classify(sdkDetails("{\"quotaId\":\"" + DAILY + "\",\"quotaDimensions\":{\"model\":\"" + MODEL + "\"}}")))
            .isEqualTo(RATE_LIMIT);
        assertThat(classify(sdkDetails(violation("other.googleapis.com/generate_content_free_tier_requests", DAILY, MODEL))))
            .isEqualTo(RATE_LIMIT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"429 RESOURCE_EXHAUSTED", "quota exceeded: 500 requests per day per model", "429 retry in 30s",
        "Details: []", "Details: null", "Details: [{", "Details: {\"violations\":[]}",
        "Details: [{\"@type\":\"type.googleapis.com/google.rpc.QuotaFailure\",\"violations\":[]}]",
        "Details: [{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"30s\"}]"})
    void unknownOrMalformed429NeverEnablesModelFallback(String payload) {
        assertThat(classify(payload)).isEqualTo(RATE_LIMIT);
    }

    @Test void rejectsOversizedUtf8InputAndExcessiveNesting() {
        assertThat(classify("x".repeat(65_537) + sdkDetails(violation(REQUEST_METRIC, DAILY, MODEL)))).isEqualTo(RATE_LIMIT);
        assertThat(classify("한".repeat(22_000) + sdkDetails(violation(REQUEST_METRIC, DAILY, MODEL)))).isEqualTo(RATE_LIMIT);
        assertThat(classify("Details: " + "[".repeat(50) + "{}" + "]".repeat(50))).isEqualTo(RATE_LIMIT);
    }

    @Test void trailingGarbageIsNotSilentlyIgnored() {
        assertThat(classify(sdkDetails(violation(REQUEST_METRIC, DAILY, MODEL)) + " {broken"))
            .isEqualTo(RATE_LIMIT);
    }

    @Test void duplicateModelDimensionsCannotHideAMismatchedModel() {
        String violation = violation(REQUEST_METRIC, DAILY, MODEL)
            .replace("\"model\":\"" + MODEL + "\"", "\"model\":\"other-model\",\"model\":\"" + MODEL + "\"");
        assertThat(classify(sdkDetails(violation))).isEqualTo(RATE_LIMIT);
    }

    @Test void malformedAdditionalQuotaDetailPreventsFallback() {
        String message = "Details: [" + quotaArray(violation(REQUEST_METRIC, DAILY, MODEL)).substring(1).replaceFirst("\\]$", "")
            + ",{\"violations\":[{\"description\":\"Shared daily limit\"}]}]";
        assertThat(classify(message)).isEqualTo(RATE_LIMIT);
    }

    @Test void neverPrintsCredentialsOrReturnsProviderPayload() throws Exception {
        String key = "AIza-secret-test-key-never-log";
        var captured = new ByteArrayOutputStream();
        PrintStream originalOut = System.out, originalErr = System.err;
        try (var sink = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(sink);
            System.setErr(sink);
            assertThat(classify("429 key=" + key + " Details: [{broken"))
                .isEqualTo(RATE_LIMIT).hasToString("RATE_LIMIT");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        assertThat(captured.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    private static SummaryGenerationException.Category classify(String message) {
        return GeminiRateLimitClassifier.classify(message, MODEL);
    }

    private static String violation(String metric, String id, String model) {
        String dimensions = model == null ? "{\"location\":\"global\"}" : "{\"model\":\"" + model + "\",\"location\":\"global\"}";
        return "{\"quotaMetric\":\"" + metric + "\",\"quotaId\":\"" + id + "\",\"quotaDimensions\":" + dimensions + ",\"quotaValue\":\"500\"}";
    }

    private static String quotaArray(String... violations) {
        return "[{\"@type\":\"type.googleapis.com/google.rpc.QuotaFailure\",\"violations\":[" + String.join(",", violations) + "]}]";
    }

    private static String sdkDetails(String... violations) {
        return "429 RESOURCE_EXHAUSTED. Quota exceeded. Details: " + quotaArray(violations);
    }
}
