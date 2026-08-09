package project.recommendationsalgo.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import project.recommendationsalgo.entities.User;
import project.recommendationsalgo.repository.UserRepository;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    // Lookups
    public User getById(Long id){
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("ID not found"));
    }

    public User getByUsername(String username){
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Username not found"));
    }
    public Optional<User> findByUsername(String username){
        return userRepository.findByUsername(username);
    }
    public Optional<User> findByEmail(String email){
        return userRepository.findByEmail(email);
    }

    // Existence checks — for registration validation
    public boolean existsByUsername(String username){
        return userRepository.existsByUsername(username);
    }
    public boolean existsByEmail(String email){
        return userRepository.existsByEmail(email);
    }

    // Creation — called by AuthService.register()
    public User create(String username, String email, String passwordHash){
        User user = User.builder()
                .username(username)
                .passwordHash(passwordHash)
                .email(email)
                .build();
        return userRepository.save(user);
    }

    // Account state
    public void enable(User user){
        user.setEnabled(true);
        userRepository.save(user);
    }
    public void disable(User user){
        user.setEnabled(false);
        userRepository.save(user);
    }
}
