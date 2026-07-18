package project.demojwt.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import javax.xml.stream.XMLInputFactory;

@Data
public class RegisterRequest {
    @NotBlank
    @Size(min = 3, max = 16)
    private String username;
    @NotBlank
    @Email
    @Size(max = 255)
    private String email;
    @NotBlank
    @Size(min = 8, max = 64)
    private String password;
    @NotBlank
    @Size(min = 8, max = 64)
    private String confirmPassword;
}
