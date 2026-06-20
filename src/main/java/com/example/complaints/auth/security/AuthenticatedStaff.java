package com.example.complaints.auth.security;

import com.example.complaints.auth.model.UserRole;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/**
 * Principal stored in the {@link org.springframework.security.core.context.SecurityContext}
 * after a successful JWT auth. A {@code UserDetails} so Spring Security's standard helpers
 * (e.g. {@code @AuthenticationPrincipal}) work.
 *
 * <p>Intentionally lightweight — DB look-ups go through services on demand, not on every request.</p>
 */
public record AuthenticatedStaff(
        Long userId,
        String employeeId,
        UserRole role,
        Long subdivisionId,
        Long distributionCenterId,
        boolean passwordResetRequired
) implements UserDetails, Serializable {

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }
    @Override public String getPassword() { return ""; }      // never used post-auth
    @Override public String getUsername() { return employeeId; }
    @Override public boolean isAccountNonExpired()    { return true; }
    @Override public boolean isAccountNonLocked()     { return true; }
    @Override public boolean isCredentialsNonExpired(){ return true; }
    @Override public boolean isEnabled()              { return true; }
}

