package project.recommendationsalgo.dto.content;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class CreateContentRequest {
    @NotBlank
    private String title;
    @NotBlank
    private String category;
    @NotNull
    private Map<String, Object> metadata;
}
