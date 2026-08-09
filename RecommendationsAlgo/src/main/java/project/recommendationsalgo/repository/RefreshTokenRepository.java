package project.recommendationsalgo.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.recommendationsalgo.entities.RefreshToken;
import project.recommendationsalgo.entities.User;

import java.lang.ScopedValue;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByToken(String refreshToken);

    List<RefreshToken> findByRevokedAtIsNullAndUser(User user);

    Optional<RefreshToken> findByTokenHash(String token);

    void deleteByExpiresAtBefore(LocalDateTime expiresAtBefore);
}
