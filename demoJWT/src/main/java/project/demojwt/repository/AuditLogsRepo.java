package project.demojwt.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.demojwt.entity.AuditLog;

public interface AuditLogsRepo extends JpaRepository<AuditLog, Long> {
}
