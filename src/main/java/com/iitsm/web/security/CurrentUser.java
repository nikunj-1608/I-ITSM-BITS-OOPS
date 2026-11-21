package com.iitsm.web.security;

import com.iitsm.application.service.UserService;
import com.iitsm.domain.exception.ValidationException;
import com.iitsm.domain.model.User;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * The one place that answers "which user is signed in" for the controllers (ADR-037, decision D20).
 *
 * <p>Spring Security names the signed-in user by login e-mail ({@code Authentication.getName()}). Everything the system
 * stores about who did something (assignment, override, note author, resolver, audit entry) must be the user's id, not
 * the e-mail, so every controller asks this class instead of reading the name itself. {@code ArchitectureRegressionTest}
 * fails the build when a controller calls {@code Authentication.getName()} directly.
 *
 * <p>OOP: Single Responsibility. Lives in the web layer and reaches users only through the application service.
 */
@Component
public class CurrentUser {

    private final UserService userService;

    public CurrentUser(UserService userService) {
        this.userService = Objects.requireNonNull(userService, "userService cannot be null");
    }

    /** The id of the signed-in user. An e-mail that matches no user is refused rather than stored as an actor. */
    public String idOf(Authentication authentication) {
        Objects.requireNonNull(authentication, "authentication cannot be null");
        String name = authentication.getName();
        if (name != null && name.contains("@")) {
            return userService.findByEmail(name)
                    .map(User::getId)
                    .orElseThrow(() -> new ValidationException("userId", "User not found with email: " + name));
        }
        return name;
    }
}
