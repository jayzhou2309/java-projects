package project.recommendationsalgo.dto.content;

import lombok.Builder;
import lombok.Data;
import project.recommendationsalgo.entities.User;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
public class ContentResponse {
    private Long id;
    private String title;
    private String category;
    private User creator;
    private LocalDateTime createdAt;
    private Map<String, Object> metadata;
}
