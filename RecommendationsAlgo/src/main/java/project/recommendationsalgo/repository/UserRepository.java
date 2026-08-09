package project.recommendationsalgo.repository;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.recommendationsalgo.entities.User;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(@NotBlank @Email String email);

    Optional<Object> getByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
