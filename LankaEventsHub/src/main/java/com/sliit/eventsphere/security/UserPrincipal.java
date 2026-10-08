//adapter between your database entity and Spring Security.
package com.sliit.eventsphere.security;

import com.sliit.eventsphere.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

//JPA User entity doesn't need to directly depend on Spring Security.
public class UserPrincipal implements UserDetails {

    //User is stored inside UserPrincipal
    private final User user;

    public UserPrincipal(User user) {
        this.user = user;
    }

    public User getUser() {
        return user;
    }

    public Long getId() {
        return user.getId();
    }

    //user's role
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    //Returns the hashed password stored in the database.
    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    //email acts as the username
    @Override
    public String getUsername() {
        return user.getEmail();
    }

    //indicate that the account and credentials haven't expired
    //user can authenticate.
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return user.isActive();
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return user.isActive();
    }
}
