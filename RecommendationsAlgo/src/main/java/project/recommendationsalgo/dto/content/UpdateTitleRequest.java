package project.recommendationsalgo.dto.content;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateTitleRequest {
    @NotBlank
    private String title;
}
