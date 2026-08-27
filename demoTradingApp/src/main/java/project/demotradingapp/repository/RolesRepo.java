package project.demotradingapp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.demotradingapp.entity.Roles;

import java.util.Optional;

@Repository
public interface RolesRepo extends JpaRepository<Roles, Long> {
    Optional<Roles> findByName(String name);
}
