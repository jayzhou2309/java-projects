package project.demojwt.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.demojwt.entity.User;

public interface UsersRepo extends JpaRepository<User, Long> {
}
