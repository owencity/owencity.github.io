package com.devdesk.summary;

import com.devdesk.summary.source.SourceContent;
import com.google.genai.errors.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeminiSummaryGeneratorTest {
    private static SummarySettings settings() {
        return new SummarySettings(true, "not-a-real-key", "test-model",20,3,3,120000,12000,90);
    }
    private static GeminiSummaryGenerator generator() {
        return new GeminiSummaryGenerator(settings(), ChatClient.create(mock(ChatModel.class)));
    }

    @Test void preservesManySectionsConditionsAndSeparateInterpretation() {
        var result = generator().parse("""
            {"sections":[
              {"heading":"배경","paragraphs":["기존 처리의 병목을 줄이려는 변경이다."]},
              {"heading":"변경","paragraphs":["묶음 조회를 적용한다."]},
              {"heading":"측정","paragraphs":["같은 데이터 10만 행을 사용했을 때 23초였다."]},
              {"heading":"조건","paragraphs":["예시는 Java 21에서 실행했다."]},
              {"heading":"한계","paragraphs":["동시 요청이 많은 환경의 결과는 제시하지 않았다."]}],
             "developerNotes":["AI 해석: 자신의 쿼리 횟수부터 측정해 볼 만하다."],
             "caveats":["원문은 CPU와 메모리 조건을 밝히지 않았다."]}
            """);
        assertThat(result.sections()).hasSize(5);
        assertThat(result.sections().get(2).paragraphs()).containsExactly("같은 데이터 10만 행을 사용했을 때 23초였다.");
        assertThat(result.developerNotes()).hasSize(1);
        assertThat(result.caveats()).hasSize(1);
    }

    @Test void rejectsBrokenEmptyOrUnexpectedOutputWithoutInventingFallbackSummary() {
        for (String invalid : List.of("not JSON", "{\"sections\":[]}",
                "{\"sections\":[{\"heading\":\"x\",\"paragraphs\":[]}],\"developerNotes\":[],\"caveats\":[]}",
                "{\"sections\":[{\"heading\":\"x\",\"paragraphs\":[\"ok\"]}],\"developerNotes\":[],\"caveats\":[],\"fake\":1}")) {
            assertThatThrownBy(() -> generator().parse(invalid)).isInstanceOf(SummaryGenerationException.class);
        }
    }

    @Test void neverAcceptsTokenTruncatedResponseEvenIfItHappensToContainValidJson() {
        ChatModel model = mock(ChatModel.class);
        var result = new Generation(new AssistantMessage("{\"sections\":[{\"heading\":\"내용\",\"paragraphs\":[\"잘린 내용\"]}],\"developerNotes\":[],\"caveats\":[]}"),
            ChatGenerationMetadata.builder().finishReason("MAX_TOKENS").build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(result)));
        var generator = new GeminiSummaryGenerator(settings(), ChatClient.create(model));
        assertThatThrownBy(() -> generator.generate(new SourceContent("제목","https://example.com/story","충분한 입력 내용","ARTICLE_BODY",null)))
            .isInstanceOf(SummaryGenerationException.class)
            .hasMessageContaining("완전히 끝나지");
        verify(model, times(1)).call(any(Prompt.class));
    }

    @Test void rejectsOversizeBeforeCallingModelAndDoesNotLeakKeyInSettings() {
        ChatClient chat = mock(ChatClient.class);
        var generator = new GeminiSummaryGenerator(settings(), chat);
        assertThatThrownBy(() -> generator.generate(new SourceContent("제목","https://example.com/story","x".repeat(120001),"ARTICLE_BODY",null)))
            .isInstanceOf(SummaryGenerationException.class)
            .hasMessageContaining("입력 본문이 비었거나 처리 한도를 넘었습니다");
        verifyNoInteractions(chat);
        assertThat(settings().toString()).doesNotContain("not-a-real-key");
    }

    @Test void sendsSelectedFallbackModelWithStructuredOutputAndNoUnsupportedCandidateCount() {
        var settings = new SummarySettings(true, "not-a-real-key", "gemini-3.5-flash-lite",
            20, 3, 1, 120000, 12000, 90, "gemini-3.1-flash-lite", 500);
        ChatModel model = mock(ChatModel.class);
        var response = new Generation(new AssistantMessage("""
            {"sections":[{"heading":"조건","paragraphs":["Java 21 이상이 필요하다."]}],
             "developerNotes":[],"caveats":[]}
            """), ChatGenerationMetadata.builder().finishReason("STOP").build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(response)));
        var generator = new GeminiSummaryGenerator(settings, ChatClient.create(model));

        var result = generator.generateForModel(source(), "gemini-3.1-flash-lite");

        assertThat(result.sections().getFirst().paragraphs()).containsExactly("Java 21 이상이 필요하다.");
        var request = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(1)).call(request.capture());
        assertThat(request.getValue().getOptions()).isInstanceOf(GoogleGenAiChatOptions.class);
        var options = (GoogleGenAiChatOptions) request.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("gemini-3.1-flash-lite");
        assertThat(options.getResponseMimeType()).isEqualTo("application/json");
        assertThat(options.getMaxOutputTokens()).isEqualTo(12000);
        assertThat(options.getCandidateCount()).isNull();
    }

    @Test void rejectsUnconfiguredModelBeforeCreatingProviderRequest() {
        ChatClient chat = mock(ChatClient.class);
        var generator = new GeminiSummaryGenerator(settings(), chat);
        assertThatThrownBy(() -> generator.generateForModel(source(), "unconfigured-model"))
            .isInstanceOfSatisfying(SummaryGenerationException.class,
                failure -> assertThat(failure.category()).isEqualTo(SummaryGenerationException.Category.PERMANENT));
        verifyNoInteractions(chat);
    }

    @Test void authenticationFailureDoesNotRetryOrExposeUpstreamMessage() {
        ChatModel model = mock(ChatModel.class);
        ApiException providerFailure = mock(ApiException.class);
        when(providerFailure.code()).thenReturn(403);
        when(providerFailure.getMessage()).thenReturn("upstream-key-and-body-must-remain-private");
        when(model.call(any(Prompt.class))).thenThrow(providerFailure);
        var generator = new GeminiSummaryGenerator(settings(), ChatClient.create(model));

        assertThatThrownBy(() -> generator.generate(source()))
            .isInstanceOfSatisfying(SummaryGenerationException.class,
                failure -> assertThat(failure.category()).isEqualTo(SummaryGenerationException.Category.PERMANENT))
            .hasMessageNotContaining("upstream-key-and-body-must-remain-private")
            .hasNoCause();
        verify(model, times(1)).call(any(Prompt.class));
    }

    private static SourceContent source() {
        return new SourceContent("제목", "https://example.com/story", "Java 21 이상이 필요하다.", "ARTICLE_BODY", null);
    }
}
