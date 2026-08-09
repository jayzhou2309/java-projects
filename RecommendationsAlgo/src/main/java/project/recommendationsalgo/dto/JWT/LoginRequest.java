package project.recommendationsalgo.dto.JWT;

import lombok.Data;

@Data
public class LoginRequest {
    private String email;
    private String password;
    private String username;
}
