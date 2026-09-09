package project.ragdemo.recommendation;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import project.ragdemo.research.EvidenceItem;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecommendationGeneratorTest {
    @Test
    void structuredUnrelatedResponseParsesAndPromptContainsExactEvidence() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(org.springframework.ai.chat.prompt.ChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("""
                        {"recommendation":"N/A","confidence":0,"takeProfit":"N/A","stopLoss":"N/A",
                         "reasoning":"Question is unrelated to filing research.","sources":[],"status":"UNRELATED_TOPIC"}
                        """)))));
        var generator = new RecommendationGenerator(ChatClient.builder(model).build());
        var evidence = new EvidenceItem("fixed-id", "AAPL", "https://example.test/filing", "10-K",
                null, "Fixed synthetic supplier-risk passage.");
        var result = generator.generate("Write a cake recipe.", List.of(evidence));
        assertEquals(RecommendationStatus.UNRELATED_TOPIC, result.status());
        assertEquals("N/A", result.recommendation());
        var captured = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(captured.capture());
        String text = captured.getValue().getContents();
        assertTrue(text.contains(evidence.id()));
        assertTrue(text.contains(evidence.text()));
        assertTrue(text.contains(evidence.sourceUrl()));
        assertTrue(text.contains("never return a plain-text alert"));
    }
}
