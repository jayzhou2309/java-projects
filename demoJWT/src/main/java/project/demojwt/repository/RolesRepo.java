package project.demojwt.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.demojwt.entity.Role;

public interface RolesRepo extends JpaRepository<Role, Long> {
}
