package project.ragdemo;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatConfig {
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, VectorStore vectorStore){
        return builder
                .defaultSystem("Reply only one word answer to save API Usage. This is only for testing")
                .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore).build())
                .build();
    }
}
