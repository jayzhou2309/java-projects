package project.demojwt.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import project.demojwt.entity.RefreshToken;

public interface RefreshTokensRepo extends JpaRepository<RefreshToken, Long> {
}
