package com.iitsm.domain.security;

import java.util.Locale;
import java.util.Objects;

/**
 * RFC 4648 Base32 (the encoding authenticator apps use for one-time-password secrets). Pure Java, no library.
 * {@link #decode} accepts lower case and missing padding, and refuses any character outside the alphabet.
 */
public final class Base32 {

    public static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int BITS_PER_CHAR = 5;
    private static final int BITS_PER_BYTE = 8;
    private static final int GROUP_CHARS = 8;
    private static final int CHAR_MASK = 0x1F;
    private static final char PADDING = '=';

    private Base32() {}

    /** Encodes with padding to a multiple of 8 characters, as in RFC 4648 section 6. */
    public static String encode(byte[] data) {
        String bare = encodeWithoutPadding(data);
        StringBuilder text = new StringBuilder(bare);
        while (text.length() % GROUP_CHARS != 0) {
            text.append(PADDING);
        }
        return text.toString();
    }

    /** Encodes without padding (the form used in {@code otpauth://} URIs and shown to people). */
    public static String encodeWithoutPadding(byte[] data) {
        Objects.requireNonNull(data, "data cannot be null");
        StringBuilder text = new StringBuilder((data.length * BITS_PER_BYTE + BITS_PER_CHAR - 1) / BITS_PER_CHAR);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << BITS_PER_BYTE) | (b & 0xFF);
            bits += BITS_PER_BYTE;
            while (bits >= BITS_PER_CHAR) {
                text.append(ALPHABET.charAt((buffer >> (bits - BITS_PER_CHAR)) & CHAR_MASK));
                bits -= BITS_PER_CHAR;
            }
            buffer &= (1 << bits) - 1;
        }
        if (bits > 0) {
            text.append(ALPHABET.charAt((buffer << (BITS_PER_CHAR - bits)) & CHAR_MASK));
        }
        return text.toString();
    }

    /** @throws IllegalArgumentException for a character that is not in the alphabet */
    public static byte[] decode(String text) {
        Objects.requireNonNull(text, "text cannot be null");
        String clean = stripPadding(text.toUpperCase(Locale.ROOT));
        byte[] out = new byte[clean.length() * BITS_PER_CHAR / BITS_PER_BYTE];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (int i = 0; i < clean.length(); i++) {
            int value = ALPHABET.indexOf(clean.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("Not a Base32 character at position " + i);
            }
            buffer = (buffer << BITS_PER_CHAR) | value;
            bits += BITS_PER_CHAR;
            if (bits >= BITS_PER_BYTE) {
                out[index++] = (byte) ((buffer >> (bits - BITS_PER_BYTE)) & 0xFF);
                bits -= BITS_PER_BYTE;
                buffer &= (1 << bits) - 1;
            }
        }
        return out;
    }

    private static String stripPadding(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == PADDING) {
            end--;
        }
        return text.substring(0, end);
    }
}
