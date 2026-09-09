package project.ragdemo.research;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import project.ragdemo.stock.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QueryPlanningAgentTest {
    private final QueryPlanningAgent validator = new QueryPlanningAgent(null, null);
    private final ResearchRequest request = new ResearchRequest("Analyze Apple filings", List.of(), null, null);

    @Test
    void rejectsUnknownSymbolsAndConflictingExplicitScope() {
        var unknown = new QueryPlan(QueryPlan.Intent.SEC_RESEARCH, List.of("FAKE"), null, null, false, null);
        assertEquals(QueryPlan.Intent.NEEDS_CLARIFICATION, validator.validate(unknown, request, Set.of("AAPL")).intent());
        var plan = new QueryPlan(QueryPlan.Intent.SEC_RESEARCH, List.of("AAPL"), null, null, false, null);
        var explicit = new ResearchRequest("Analyze", List.of("MSFT"), LocalDate.of(2025, 1, 1), null);
        assertEquals(QueryPlan.Intent.NEEDS_CLARIFICATION, validator.validate(plan, explicit, Set.of("AAPL", "MSFT")).intent());
        assertThrows(IllegalArgumentException.class, () -> validator.validate(null, request, Set.of()));
    }

    @Test
    void normalizesSymbolsAndDropsScopeForUnrelatedTasks() {
        var plan = new QueryPlan(QueryPlan.Intent.SEC_RESEARCH, List.of("aapl", "AAPL"), null, null, false, null);
        assertEquals(List.of("AAPL"), validator.validate(plan, request, Set.of("AAPL")).symbols());
        var unrelated = new QueryPlan(QueryPlan.Intent.UNRELATED_TOPIC, List.of("AAPL"), null, null, true, null);
        var result = validator.validate(unrelated, request, Set.of("AAPL"));
        assertTrue(result.symbols().isEmpty());
        assertFalse(result.marketDataRequired());
    }

    @Test
    void plansThroughStructuredModelWithTrackedStockCatalog() {
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(ChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("""
                {"intent":"SEC_RESEARCH","symbols":["AAPL"],"from":null,"to":null,
                 "marketDataRequired":false,"clarification":null}
                """)))));
        StockRepository stocks = mock(StockRepository.class);
        when(stocks.findAll()).thenReturn(List.of(Stock.builder().symbol("AAPL").companyName("Apple Inc.").active(true).build()));
        var planner = new QueryPlanningAgent(ChatClient.builder(model).build(), stocks);
        assertEquals(List.of("AAPL"), planner.plan(request).symbols());
        verify(stocks).findAll();
        verify(model).call(argThat((Prompt prompt) -> prompt.getContents().contains("Apple Inc.")));
    }
}
