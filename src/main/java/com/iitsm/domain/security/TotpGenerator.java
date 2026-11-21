package com.iitsm.domain.security;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords, RFC 6238 with HMAC-SHA-1, 6 digits and 30-second steps (what authenticator apps
 * implement), on {@code javax.crypto.Mac}; no library (ADR-048). Pure: the caller passes the time, so the real wall
 * clock is the caller's choice (rule R1).
 *
 * <p>{@link #verify} accepts the step of {@code now} and {@code windowSteps} steps either side (clock drift of the
 * phone), compares in constant time, and refuses any step that is not newer than the last accepted one, so a code
 * cannot be used twice.
 */
public final class TotpGenerator {

    public static final int STEP_SECONDS = 30;
    public static final int DIGITS = 6;
    public static final int SECRET_BYTES = 20;
    private static final String HMAC = "HmacSHA1";
    private static final int[] POWERS_OF_TEN = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000};
    private static final int DYNAMIC_OFFSET_MASK = 0x0F;
    private static final int POSITIVE_INT_MASK = 0x7FFFFFFF;

    private TotpGenerator() {}

    /** A new random secret of {@value #SECRET_BYTES} bytes (160 bits), as RFC 4226 recommends. */
    public static byte[] newSecret(SecureRandom random) {
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        return secret;
    }

    /** The time step the instant falls in. */
    public static long stepAt(Instant instant) {
        return Math.floorDiv(Objects.requireNonNull(instant, "instant cannot be null").getEpochSecond(), STEP_SECONDS);
    }

    public static String codeAt(byte[] secret, long step) {
        return codeAt(secret, step, DIGITS);
    }

    /** The code for a step, left-padded with zeros; {@code digits} 6 for the application, 8 in the RFC test vectors. */
    public static String codeAt(byte[] secret, long step, int digits) {
        Objects.requireNonNull(secret, "secret cannot be null");
        if (digits < 1 || digits >= POWERS_OF_TEN.length) {
            throw new IllegalArgumentException("digits must be between 1 and " + (POWERS_OF_TEN.length - 1));
        }
        byte[] hash = hmac(secret, ByteBuffer.allocate(Long.BYTES).putLong(step).array());
        int offset = hash[hash.length - 1] & DYNAMIC_OFFSET_MASK;
        int binary = ByteBuffer.wrap(hash, offset, Integer.BYTES).getInt() & POSITIVE_INT_MASK;
        return String.format("%0" + digits + "d", binary % POWERS_OF_TEN[digits]);
    }

    /**
     * The step the code is valid for, or empty when no step in the window matches or the matching step is not newer than
     * {@code lastUsedStep} (pass {@code Long.MIN_VALUE} when no code was accepted yet). Every candidate step is
     * computed and compared whatever the earlier results, so the time taken does not show which digit was wrong.
     */
    public static OptionalLong verify(byte[] secret, String code, Instant now, int windowSteps, long lastUsedStep) {
        if (windowSteps < 0) {
            throw new IllegalArgumentException("windowSteps must not be negative");
        }
        byte[] presented = (code == null ? "" : code).getBytes(StandardCharsets.UTF_8);
        long current = stepAt(now);
        long matched = Long.MIN_VALUE;
        boolean found = false;
        for (long step = current - windowSteps; step <= current + windowSteps; step++) {
            byte[] expected = codeAt(secret, step).getBytes(StandardCharsets.UTF_8);
            boolean equal = MessageDigest.isEqual(expected, presented);
            if (equal && step > lastUsedStep && (!found || step > matched)) {
                matched = step;
                found = true;
            }
        }
        return found ? OptionalLong.of(matched) : OptionalLong.empty();
    }

    /** The {@code otpauth://} URI an authenticator app reads (shown as text; there is no QR image, ADR-048). */
    public static String otpauthUri(String issuer, String account, String secretBase32) {
        String label = encode(issuer) + ":" + encode(account);
        return "otpauth://totp/" + label + "?secret=" + secretBase32 + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static byte[] hmac(byte[] secret, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(HMAC + " is required on every Java platform", e);
        }
    }
}
