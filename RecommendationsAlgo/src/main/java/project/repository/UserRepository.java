package project.repository;

import org.springframework.stereotype.Repository;
import project.entities.User;

import java.util.Optional;

@Repository
public interface UserRepository {
    Optional<User> findByUsername(String username);
}
