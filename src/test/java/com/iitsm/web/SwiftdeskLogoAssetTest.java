package com.iitsm.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Login page redesign, Phase 1: the Swiftdesk logo shown on the sign-in card is served to a visitor who is not
 * signed in yet, and the content security policy lets the page load it from its own origin.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SwiftdeskLogoAssetTest {

    private static final String LOGO_PATH = "/assets/swiftdesk-logo.png";
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("the logo is a PNG served without a token")
    void logoIsServedWithoutSignIn() throws Exception {
        MvcResult result = mockMvc.perform(get(LOGO_PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body.length).isGreaterThan(PNG_SIGNATURE.length);
        assertThat(java.util.Arrays.copyOf(body, PNG_SIGNATURE.length)).isEqualTo(PNG_SIGNATURE);
    }

    @Test
    @DisplayName("the content security policy allows images from the page's own origin")
    void contentSecurityPolicyAllowsSameOriginImages() throws Exception {
        String csp = mockMvc.perform(get(LOGO_PATH))
                .andReturn()
                .getResponse()
                .getHeader("Content-Security-Policy");

        assertThat(csp).isNotNull();
        assertThat(csp).contains("img-src 'self'");
    }
}
