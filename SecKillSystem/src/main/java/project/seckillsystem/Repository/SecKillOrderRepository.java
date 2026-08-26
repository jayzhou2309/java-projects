package project.seckillsystem.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.seckillsystem.Entity.SecKillOrder;

public interface SecKillOrderRepository extends JpaRepository<SecKillOrder, Long> {
}
