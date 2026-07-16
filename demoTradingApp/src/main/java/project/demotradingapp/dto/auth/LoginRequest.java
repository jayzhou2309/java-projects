package project.demotradingapp.dto.auth;

import lombok.Data;

@Data
public class LoginRequest {
    private String email;
    private String password;
    private String username;
}
