package project.demotradingapp.security.user;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import project.demotradingapp.entity.UsersEntity;
import project.demotradingapp.repository.UsersRepo;

@Service
public class UserAccountDetailsService implements UserDetailsService {
    private final UsersRepo usersRepo;

    public UserAccountDetailsService(UsersRepo usersRepo){
        this.usersRepo = usersRepo;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UsersEntity user = usersRepo.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Username not Found"));
        return new UserAccountDetails(user);
    }
}
