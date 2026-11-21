package com.iitsm.infrastructure.security;

import com.iitsm.domain.repository.RefreshTokenRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final UserDetailsService userDetailsService;
    private final RefreshTokenRepository refreshTokens;
    private final Clock wallClock;

    public JwtAuthenticationFilter(
            JwtTokenProvider tokenProvider,
            UserDetailsService userDetailsService,
            RefreshTokenRepository refreshTokens,
            @Qualifier("wallClock") Clock wallClock) {
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider cannot be null");
        this.userDetailsService = Objects.requireNonNull(userDetailsService, "userDetailsService cannot be null");
        this.refreshTokens = Objects.requireNonNull(refreshTokens, "refreshTokens cannot be null");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock cannot be null");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String jwt = resolveToken(request);

        if (StringUtils.hasText(jwt)) {
            try {
                if (tokenProvider.validateToken(jwt)) {
                    // ADR-048: the token that proves the password step of a two-step sign-in is no credential
                    if (tokenProvider.isMfaChallenge(jwt)) {
                        SecurityContextHolder.clearContext();
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"errorCode\":\"ERR_UNAUTHORIZED\",\"message\":\"Invalid or expired token\"}");
                        return;
                    }
                    String email = tokenProvider.getEmailFromToken(jwt);
                    UserDetails userDetails = userDetailsService.loadUserByUsername(email);

                    if (!userDetails.isEnabled() || !userDetails.isAccountNonLocked()) {
                        SecurityContextHolder.clearContext();
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"errorCode\":\"ERR_UNAUTHORIZED\",\"message\":\"Account is locked or deactivated\"}");
                        return;
                    }

                    if (userDetails instanceof IitsmUserDetails customDetails) {
                        int tokenVersion = tokenProvider.getTokenVersionFromToken(jwt);
                        if (customDetails.getTokenVersion() != tokenVersion) {
                            SecurityContextHolder.clearContext();
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json");
                            response.getWriter().write("{\"errorCode\":\"ERR_UNAUTHORIZED\",\"message\":\"Session revoked or expired\"}");
                            return;
                        }
                    }

                    // ADR-047: a token issued for a session dies with it (sign-out, replay, expiry of the session).
                    String sessionId = tokenProvider.getSessionId(jwt);
                    if (sessionId != null && !refreshTokens.isFamilyActive(sessionId, wallClock.instant())) {
                        SecurityContextHolder.clearContext();
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"errorCode\":\"ERR_UNAUTHORIZED\",\"message\":\"Session revoked or expired\"}");
                        return;
                    }

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (Exception e) {
                SecurityContextHolder.clearContext();
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"errorCode\":\"ERR_UNAUTHORIZED\",\"message\":\"Invalid or expired token\"}");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}

