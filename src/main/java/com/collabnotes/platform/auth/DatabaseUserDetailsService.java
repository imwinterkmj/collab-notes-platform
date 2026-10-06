package com.collabnotes.platform.auth;

import com.collabnotes.platform.user.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class DatabaseUserDetailsService implements UserDetailsService {
    private final UserRepository users;

    public DatabaseUserDetailsService(UserRepository users) { this.users = users; }

    @Override
    public UserDetails loadUserByUsername(String username) {
        return users.findByUsername(username).map(AuthenticatedUser::new)
                .orElseThrow(() -> new UsernameNotFoundException("凭据无效"));
    }
}
