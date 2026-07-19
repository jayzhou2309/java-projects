package project.demojwt.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.demojwt.entity.User;

import java.util.Optional;

public interface UsersRepo extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
}
