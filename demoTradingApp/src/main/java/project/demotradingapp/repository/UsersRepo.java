package project.demotradingapp.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.demotradingapp.entity.UsersEntity;

import java.util.Optional;

@Repository
public interface UsersRepo extends JpaRepository<UsersEntity, Long> {
    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    Optional<UsersEntity> findByEmail(String email);

    Optional<UsersEntity> findByUsername(String username);
}
