package project.seckillsystem.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.seckillsystem.Entity.SecKillStockLog;

public interface SecKillStockLogRepository extends JpaRepository<SecKillStockLog, Long> {
}
