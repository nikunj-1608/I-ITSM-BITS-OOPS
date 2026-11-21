package com.iitsm.domain.security;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One-time recovery codes for an account whose authenticator is lost (ADR-048): {@value #LENGTH} characters of the
 * Base32 alphabet (50 bits of randomness). The server keeps only a hash of each, and each works once.
 */
public final class RecoveryCodes {

    public static final int LENGTH = 10;
    private static final Pattern SHAPE = Pattern.compile("[A-Z2-7]{" + LENGTH + "}");
    private static final Pattern IGNORED = Pattern.compile("[\\s-]");

    private RecoveryCodes() {}

    public static List<String> generate(int count, SecureRandom random) {
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1");
        }
        List<String> codes = new ArrayList<>(count);
        while (codes.size() < count) {
            String code = newCode(random);
            if (!codes.contains(code)) {
                codes.add(code);
            }
        }
        return List.copyOf(codes);
    }

    /** Upper case, without spaces or hyphens, so a code typed as "abcde-fghij" matches "ABCDEFGHIJ". */
    public static String normalize(String input) {
        return input == null ? "" : IGNORED.matcher(input).replaceAll("").toUpperCase(Locale.ROOT);
    }

    /** True when the (normalised) text has the shape of a recovery code, as opposed to a 6-digit one-time code. */
    public static boolean looksLikeRecoveryCode(String input) {
        return SHAPE.matcher(normalize(input)).matches();
    }

    private static String newCode(SecureRandom random) {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            code.append(Base32.ALPHABET.charAt(random.nextInt(Base32.ALPHABET.length())));
        }
        return code.toString();
    }
}
