package project.demotradingapp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.demotradingapp.entity.RolesEntity;

@Repository
public interface RolesRepo extends JpaRepository<RolesEntity, Long> {
}
