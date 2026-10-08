//bridge between database and Spring Security during login.
package com.sliit.eventsphere.security;

import com.sliit.eventsphere.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    //user tries to log in using their email.
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        //Search the database
        return userRepository.findByEmail(email)
                //If the user exists
                .map(UserPrincipal::new)
                //If the user doesn't exist
                .orElseThrow(() -> new UsernameNotFoundException("No account found for email: " + email));
    }
}
