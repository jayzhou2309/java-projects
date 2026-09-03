package project.ragdemo;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ChatController {
    private final ChatClient chatClient;

    @GetMapping("/")
    public String chat(){
        return chatClient.prompt()
                .user("How does this affect stock price?")
                .call()
                .content();
    }
}
