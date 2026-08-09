package project.recommendationsalgo.dto.JWT;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.Getter;

@Data
@Getter
public class RegisterRequest {
    @NotBlank
    private String username;
    @NotBlank
    @Size(min = 8)
    private String password;
    @NotBlank
    @Email
    private String email;
}
