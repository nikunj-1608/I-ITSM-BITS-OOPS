package com.iitsm.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.iitsm.application.service.UserService;
import com.iitsm.domain.model.Administrator;
import com.iitsm.domain.model.User;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/** Remaining-defects plan, Phase H (ADR-037, D20): the one place that turns the sign-in name into the user's id. */
class CurrentUserTest {

    private UserService userService;
    private CurrentUser currentUser;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        currentUser = new CurrentUser(userService);
    }

    private static Authentication signedInAs(String name) {
        return new UsernamePasswordAuthenticationToken(name, "n/a");
    }

    @Test
    @DisplayName("the login e-mail is turned into the user's id")
    void emailBecomesId() {
        User admin = mock(Administrator.class);
        when(admin.getId()).thenReturn("ADMIN-SYS-01");
        when(userService.findByEmail("admin@nimbusfinserve.com")).thenReturn(Optional.of(admin));

        assertThat(currentUser.idOf(signedInAs("admin@nimbusfinserve.com"))).isEqualTo("ADMIN-SYS-01");
    }

    @Test
    @DisplayName("a name that is already an id is returned as it is, without a lookup")
    void idStaysAnId() {
        assertThat(currentUser.idOf(signedInAs("TECH-NET-2"))).isEqualTo("TECH-NET-2");

        verify(userService, never()).findByEmail("TECH-NET-2");
    }

    @Test
    @DisplayName("an e-mail that matches no user is refused, not stored as an actor")
    void unknownEmailIsRefused() {
        when(userService.findByEmail("ghost@nimbusfinserve.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> currentUser.idOf(signedInAs("ghost@nimbusfinserve.com")))
                .isInstanceOf(com.iitsm.domain.exception.ValidationException.class);
    }

    @Test
    @DisplayName("no authentication is refused")
    void noAuthenticationIsRefused() {
        assertThatThrownBy(() -> currentUser.idOf(null)).isInstanceOf(NullPointerException.class);
    }
}
