package com.iitsm.infrastructure.security;

import com.iitsm.domain.model.User;
import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;

// OOP (Inheritance / Realization): IitsmUserDetails extends or implements a framework or port type, so it plugs into the framework without changing callers.
/**
 * Custom Spring Security UserDetails carrying the domain User's tokenVersion and internal ID.
 * Enables immediate token revocation on tokenVersion bumps (logout, lock, password change).
 */
public class IitsmUserDetails extends org.springframework.security.core.userdetails.User {

    private final String userId;
    private final int tokenVersion;

    public IitsmUserDetails(User user, Collection<? extends GrantedAuthority> authorities, java.time.Instant now) {
        // OOP (super call): passes the values to the parent class constructor.
        super(
                user.getEmail().value(),
                user.getPasswordHash(),
                user.isActive(),
                true,
                true,
                !user.isCurrentlyLocked(now),
                authorities
        );
        this.userId = user.getId();
        this.tokenVersion = user.getTokenVersion();
    }

    public String getUserId() {
        return userId;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }
}

