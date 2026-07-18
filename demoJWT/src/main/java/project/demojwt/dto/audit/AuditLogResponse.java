package project.demojwt.dto.audit;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AuditLogResponse {
    private Long id;
    private String username;
    private String action;
    private String ipAddress;
    private LocalDateTime createdAt;
}
