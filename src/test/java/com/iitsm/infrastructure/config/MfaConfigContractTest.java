package com.iitsm.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.iitsm.infrastructure.security.AesGcmSecretCipher;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * Authentication hardening, Phase S4 (ADR-048): what the shipped {@code application.yml} says about two-step verification.
 * The prod profile has no default encryption key (a start without {@code MFA_ENCRYPTION_KEY} fails, like a start without
 * {@code JWT_SECRET}), the development and test keys are valid AES-256 keys, and the cipher itself refuses a missing or
 * short key. The files are read directly; nothing here starts an application.
 */
class MfaConfigContractTest {

    private static final String MAIN_YML = "src/main/resources/application.yml";
    private static final String TEST_YML = "src/test/resources/application.yml";
    private static final String KEY = "iitsm.security.mfa.encryption-key";
    private static final String MFA = "iitsm.security.mfa.";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[A-Z_]+:(.*)}");

    private static List<PropertySource<?>> documents(String file) throws IOException {
        return new YamlPropertySourceLoader().load(file, new FileSystemResource(file));
    }

    private static Optional<PropertySource<?>> profile(String name) throws IOException {
        return documents(MAIN_YML).stream()
                .filter(source -> name.equals(source.getProperty("spring.config.activate.on-profile")))
                .findFirst();
    }

    private static PropertySource<?> base() throws IOException {
        return documents(MAIN_YML).stream()
                .filter(source -> source.getProperty("spring.config.activate.on-profile") == null)
                .findFirst().orElseThrow();
    }

    private static String defaultOf(Object value) {
        String text = String.valueOf(value);
        Matcher placeholder = PLACEHOLDER.matcher(text);
        return placeholder.matches() ? placeholder.group(1) : text;
    }

    private static String effective(String profile, String property) throws IOException {
        Object own = profile(profile).map(source -> source.getProperty(property)).orElse(null);
        return defaultOf(own != null ? own : base().getProperty(property));
    }

    @Test
    @DisplayName("S4: the prod profile has no default encryption key, so a start without MFA_ENCRYPTION_KEY fails")
    void prodHasNoDefaultKey() throws IOException {
        assertThat(effective("prod", KEY)).isEmpty();
        assertThatThrownBy(() -> new AesGcmSecretCipher(effective("prod", KEY))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(KEY);
    }

    @Test
    @DisplayName("S4: the development default (dev and demo) and the test key are valid 256-bit keys, and not the same key")
    void developmentAndTestKeysAreValid() throws IOException {
        String development = effective("dev", KEY);
        String demo = effective("demo", KEY);
        String test = String.valueOf(documents(TEST_YML).get(0).getProperty(KEY));

        assertThat(development).isEqualTo(demo);
        for (String key : List.of(development, test)) {
            assertThat(Base64.getDecoder().decode(key)).hasSize(32);
            new AesGcmSecretCipher(key);
        }
        assertThat(test).isNotEqualTo(development);
    }

    @Test
    @DisplayName("S4: the settings are the specified ones: 5-minute challenge, window of 1 step, 8 recovery codes, an issuer")
    void settings() throws IOException {
        assertThat(defaultOf(base().getProperty(MFA + "challenge-minutes"))).isEqualTo("5");
        assertThat(defaultOf(base().getProperty(MFA + "window-steps"))).isEqualTo("1");
        assertThat(defaultOf(base().getProperty(MFA + "recovery-code-count"))).isEqualTo("8");
        assertThat(defaultOf(base().getProperty(MFA + "issuer"))).isEqualTo("Nimbus FinServe I-ITSM");
        PropertySource<?> test = documents(TEST_YML).get(0);
        assertThat(String.valueOf(test.getProperty(MFA + "challenge-minutes"))).isEqualTo("5");
    }

    @Test
    @DisplayName("S4: the cipher refuses a missing, blank or short key")
    void cipherFailsFast() {
        assertThatThrownBy(() -> new AesGcmSecretCipher(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AesGcmSecretCipher("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AesGcmSecretCipher(Base64.getEncoder().encodeToString(new byte[31])))
                .isInstanceOf(IllegalStateException.class);
    }
}
