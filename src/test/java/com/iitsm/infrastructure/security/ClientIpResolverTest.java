package com.iitsm.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/** Authentication hardening, Phase S2 (ADR-046): which address a rate limit counts against. */
class ClientIpResolverTest {

    private static final String PEER = "198.51.100.7";

    private static MockHttpServletRequest request(String... forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr(PEER);
        for (String header : forwardedFor) {
            request.addHeader(ClientIpResolver.FORWARDED_FOR, header);
        }
        return request;
    }

    @Test
    @DisplayName("default (no trusted proxy): the TCP peer, a forged X-Forwarded-For is ignored")
    void defaultIgnoresForwardedFor() {
        ClientIpResolver resolver = new ClientIpResolver(0);

        assertThat(resolver.resolve(request("1.1.1.1"))).isEqualTo(PEER);
        assertThat(resolver.resolve(request())).isEqualTo(PEER);
    }

    @Test
    @DisplayName("one trusted proxy: the right-most entry, never what the client wrote to its left")
    void oneHop() {
        ClientIpResolver resolver = new ClientIpResolver(1);

        assertThat(resolver.resolve(request("6.6.6.6, 203.0.113.5"))).isEqualTo("203.0.113.5");
        assertThat(resolver.resolve(request("203.0.113.5"))).isEqualTo("203.0.113.5");
    }

    @Test
    @DisplayName("two trusted proxies: the second entry from the right, across several headers")
    void twoHops() {
        ClientIpResolver resolver = new ClientIpResolver(2);

        assertThat(resolver.resolve(request("6.6.6.6, 203.0.113.5, 10.0.0.2"))).isEqualTo("203.0.113.5");
        assertThat(resolver.resolve(request("6.6.6.6, 203.0.113.5", "10.0.0.2"))).isEqualTo("203.0.113.5");
    }

    @Test
    @DisplayName("IPv6 addresses are accepted")
    void ipv6() {
        ClientIpResolver resolver = new ClientIpResolver(1);

        assertThat(resolver.resolve(request("2001:db8::42"))).isEqualTo("2001:db8::42");
    }

    @Test
    @DisplayName("a chain shorter than the number of trusted proxies falls back to the TCP peer")
    void tooShort() {
        assertThat(new ClientIpResolver(2).resolve(request("203.0.113.5"))).isEqualTo(PEER);
        assertThat(new ClientIpResolver(1).resolve(request())).isEqualTo(PEER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-an-ip", "", " , ", "1.2.3.4; DROP", "[2001:db8::1]:443", "<script>"})
    @DisplayName("a malformed entry falls back to the TCP peer")
    void malformed(String header) {
        assertThat(new ClientIpResolver(1).resolve(request(header))).isEqualTo(PEER);
    }

    @Test
    @DisplayName("a negative number of proxies is a configuration error")
    void negativeHops() {
        assertThatThrownBy(() -> new ClientIpResolver(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
