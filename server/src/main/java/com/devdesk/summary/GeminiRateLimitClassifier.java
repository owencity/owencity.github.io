package com.devdesk.summary;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/** Classifies an already identified HTTP 429 without logging or retaining its provider payload. */
public final class GeminiRateLimitClassifier {
    private static final int MAX_BYTES = 65_536;
    private static final String QUOTA_FAILURE = "type.googleapis.com/google.rpc.QuotaFailure";
    private static final String DETAILS_MARKER = "Details:";
    private static final String METRIC_PREFIX = "generativelanguage.googleapis.com/";
    private static final Pattern REQUEST_METRIC = Pattern.compile("generate_content_(?:(?:free|paid)_tier_)?requests");
    private static final Pattern TOKEN_METRIC = Pattern.compile(
        "generate_content_(?:(?:free|paid)_tier_)?(?:input_token_count|output_token_count|token_count|input_tokens|output_tokens|tokens)");
    private static final Pattern DAILY_MODEL_REQUEST_ID = Pattern.compile(
        "GenerateRequestsPerDayPerProjectPerModel(?:-(?:Free|Paid)Tier)?");
    private static final Pattern RATE_REQUEST_ID = Pattern.compile(
        "GenerateRequests(?:Per(?:Minute|Second|Project|Model|User|Region)){1,6}(?:-(?:Free|Paid)Tier)?");
    private static final Pattern RATE_TOKEN_ID = Pattern.compile(
        "GenerateContent(?:Input|Output)?Tokens(?:Per(?:Minute|Second|Project|Model|User|Region)){1,6}(?:-(?:Free|Paid)Tier)?");
    private static final ObjectReader JSON = new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder()
            .maxNestingDepth(20).maxStringLength(MAX_BYTES).maxNumberLength(100).build())
        .build()).reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private GeminiRateLimitClassifier() {}

    public static SummaryGenerationException.Category classify(String providerMessage, String requestedModel) {
        if (providerMessage == null || providerMessage.length() > MAX_BYTES
                || providerMessage.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            return SummaryGenerationException.Category.RATE_LIMIT;
        String model = normalizeModel(requestedModel);
        try {
            String payload = jsonPayload(providerMessage);
            if (payload == null) return SummaryGenerationException.Category.RATE_LIMIT;
            JsonNode root = JSON.readTree(payload);
            Iterable<JsonNode> details = details(root);
            if (details == null) return SummaryGenerationException.Category.RATE_LIMIT;

            boolean foundQuota = false;
            boolean foundDaily = false;
            boolean foundTransient = false;
            int inspected = 0;
            for (JsonNode detail : details) {
                if (!detail.isObject() || !detail.path("@type").isTextual())
                    return SummaryGenerationException.Category.RATE_LIMIT;
                if (!QUOTA_FAILURE.equals(detail.path("@type").asText())) {
                    if (detail.has("violations")) return SummaryGenerationException.Category.RATE_LIMIT;
                    continue;
                }
                foundQuota = true;
                JsonNode violations = detail.get("violations");
                if (violations == null || !violations.isArray() || violations.isEmpty())
                    return SummaryGenerationException.Category.RATE_LIMIT;
                for (JsonNode violation : violations) {
                    if (++inspected > 128 || !violation.isObject())
                        return SummaryGenerationException.Category.RATE_LIMIT;
                    Kind kind = classifyViolation(violation, model);
                    if (kind == Kind.UNKNOWN) return SummaryGenerationException.Category.RATE_LIMIT;
                    foundDaily |= kind == Kind.DAILY_MODEL;
                    foundTransient |= kind == Kind.TRANSIENT;
                }
            }
            // A short-window violation always prevents a model switch, even alongside a daily one.
            if (foundQuota && foundTransient) return SummaryGenerationException.Category.RATE_LIMIT_TRANSIENT;
            if (foundQuota && foundDaily) return SummaryGenerationException.Category.DAILY_MODEL_LIMIT;
            return SummaryGenerationException.Category.RATE_LIMIT;
        } catch (Exception invalidOrUnsupported) {
            // Deliberately omit exception causes/messages: upstream data may contain credentials.
            return SummaryGenerationException.Category.RATE_LIMIT;
        }
    }

    private static Kind classifyViolation(JsonNode violation, String requestedModel) {
        String metric = stringField(violation, "quotaMetric", "quota_metric");
        String id = stringField(violation, "quotaId", "quota_id");
        if (metric == null || id == null || !metric.startsWith(METRIC_PREFIX)) return Kind.UNKNOWN;
        String name = metric.substring(METRIC_PREFIX.length());
        boolean requests = REQUEST_METRIC.matcher(name).matches();
        boolean tokens = TOKEN_METRIC.matcher(name).matches();
        if (!requests && !tokens) return Kind.UNKNOWN;

        if (requests && DAILY_MODEL_REQUEST_ID.matcher(id).matches()) {
            JsonNode dimensions = field(violation, "quotaDimensions", "quota_dimensions");
            if (requestedModel == null || dimensions == null || !dimensions.isObject()) return Kind.UNKNOWN;
            JsonNode actualModel = dimensions.get("model");
            if (actualModel == null || !actualModel.isTextual()) return Kind.UNKNOWN;
            return requestedModel.equals(normalizeModel(actualModel.textValue())) ? Kind.DAILY_MODEL : Kind.UNKNOWN;
        }
        boolean rateId = requests ? RATE_REQUEST_ID.matcher(id).matches() : RATE_TOKEN_ID.matcher(id).matches();
        if (rateId && (id.contains("PerMinute") || id.contains("PerSecond"))) return Kind.TRANSIENT;
        return Kind.UNKNOWN;
    }

    private static String jsonPayload(String message) {
        int marker = message.indexOf(DETAILS_MARKER);
        String candidate;
        if (marker >= 0) {
            candidate = message.substring(marker + DETAILS_MARKER.length()).strip();
        } else {
            candidate = message.strip();
            if (!candidate.startsWith("{") && !candidate.startsWith("[")) return null;
        }
        return candidate.startsWith("{") || candidate.startsWith("[") ? candidate : null;
    }

    private static Iterable<JsonNode> details(JsonNode root) {
        if (root == null) return null;
        if (root.isArray()) return root;
        if (!root.isObject()) return null;
        if (QUOTA_FAILURE.equals(root.path("@type").asText())) return List.of(root);
        JsonNode error = root.path("error");
        JsonNode detailArray = error.isObject() ? error.get("details") : root.get("details");
        return detailArray != null && detailArray.isArray() ? detailArray : null;
    }

    private static JsonNode field(JsonNode parent, String camelCase, String snakeCase) {
        JsonNode camel = parent.get(camelCase), snake = parent.get(snakeCase);
        if (camel != null && snake != null && !camel.equals(snake)) return null;
        return camel != null ? camel : snake;
    }

    private static String stringField(JsonNode parent, String camelCase, String snakeCase) {
        JsonNode value = field(parent, camelCase, snakeCase);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static String normalizeModel(String value) {
        if (value == null) return null;
        String model = value.strip();
        if (model.startsWith("models/")) model = model.substring("models/".length());
        if (model.isBlank() || model.length() > 256 || !model.matches("[A-Za-z0-9._-]+")) return null;
        return model;
    }

    private enum Kind { DAILY_MODEL, TRANSIENT, UNKNOWN }
}
