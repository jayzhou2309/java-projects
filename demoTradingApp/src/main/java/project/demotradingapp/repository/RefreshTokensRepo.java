package project.demotradingapp.repository;

import org.springframework.cglib.core.Local;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import project.demotradingapp.entity.RefreshToken;
import project.demotradingapp.entity.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokensRepo extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByToken(String refreshToken);

    void deleteByExpiryBefore(LocalDateTime time);

    List<RefreshToken> findByRevokedAndUser(boolean b, User user);
}
