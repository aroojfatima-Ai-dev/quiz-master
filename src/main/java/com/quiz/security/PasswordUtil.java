package com.quiz.security;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * Password hashing for QuizMaster.
 *
 * <p>Passwords used to be stored in the {@code users} table as plain text, which
 * meant anyone with read access to the database (or a leaked SQL dump) could log
 * in as any user. This class replaces that with salted PBKDF2-HMAC-SHA256.
 *
 * <p><b>Stored format</b>
 * <pre>PBKDF2:&lt;iterations&gt;:&lt;base64 salt&gt;:&lt;base64 derived key&gt;</pre>
 * The algorithm parameters travel with the hash, so the cost factor can be raised
 * later without invalidating existing rows.
 *
 * <p><b>Legacy rows</b>
 * A stored value that is not in the format above is treated as a legacy plain-text
 * password so that accounts created before this change keep working. After such a
 * login succeeds, the caller should re-hash and update the row; see
 * {@link #needsRehash(String)} and {@code UserDAO#findUserForLogin}.
 *
 * <p>The encoding is deliberately language-agnostic: the vectors in
 * {@code PasswordUtilTest} were produced by an independent implementation, so a
 * mistake in this class is caught by the test suite.
 */
public final class PasswordUtil {

    /** PBKDF2 with HMAC-SHA256, available in every supported JDK. */
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    /**
     * Iteration count for newly created hashes. 210,000 is the current OWASP
     * recommendation for PBKDF2-HMAC-SHA256.
     */
    public static final int ITERATIONS = 210_000;

    /** Length of the derived key in bits (32 bytes == SHA-256 output). */
    private static final int KEY_LENGTH_BITS = 256;

    /** Length of the per-password random salt in bytes. */
    private static final int SALT_LENGTH_BYTES = 16;

    private static final String PREFIX = "PBKDF2";
    private static final String SEPARATOR = ":";

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtil() {
        // Utility class - not instantiable.
    }

    /**
     * Hashes a password with a fresh random salt.
     *
     * @param password the plain-text password; must not be null or empty
     * @return the encoded hash, suitable for storing in {@code users.password}
     * @throws IllegalArgumentException if the password is null or empty
     */
    public static String hash(String password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("password must not be null or empty");
        }
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        RANDOM.nextBytes(salt);
        return encode(password, salt, ITERATIONS);
    }

    /**
     * Checks a candidate password against a stored hash (or legacy plain-text value).
     *
     * <p>Comparison is done in constant time so that response timing does not leak
     * how many leading characters of the hash were correct.
     *
     * @param password the plain-text password to check; null never matches
     * @param stored   the value read from {@code users.password}
     * @return true only if the password is correct
     */
    public static boolean verify(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }

        ParsedHash parsed = parse(stored);
        if (parsed == null) {
            // Legacy plain-text row: compare the raw values.
            return MessageDigest.isEqual(
                    password.getBytes(StandardCharsets.UTF_8),
                    stored.getBytes(StandardCharsets.UTF_8));
        }

        byte[] actual = deriveKey(password, parsed.salt, parsed.iterations, parsed.hash.length * 8);
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(actual, parsed.hash);
    }

    /**
     * Reports whether a stored value should be re-hashed after a successful login.
     *
     * <p>True for legacy plain-text passwords, for malformed values, and for hashes
     * created with a lower iteration count than {@link #ITERATIONS}.
     *
     * @param stored the value read from {@code users.password}
     * @return true if the row should be upgraded
     */
    public static boolean needsRehash(String stored) {
        ParsedHash parsed = parse(stored);
        return parsed == null || parsed.iterations < ITERATIONS;
    }

    /**
     * Builds the encoded representation for a known salt and iteration count.
     * Exposed (package-private semantics aside) so tests can reproduce fixed vectors.
     */
    static String encode(String password, byte[] salt, int iterations) {
        byte[] key = deriveKey(password, salt, iterations, KEY_LENGTH_BITS);
        if (key == null) {
            throw new IllegalStateException("Unable to derive password key; check JCE availability.");
        }
        Base64.Encoder encoder = Base64.getEncoder();
        return PREFIX + SEPARATOR + iterations
                + SEPARATOR + encoder.encodeToString(salt)
                + SEPARATOR + encoder.encodeToString(key);
    }

    private static byte[] deriveKey(String password, byte[] salt, int iterations, int keyLengthBits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, keyLengthBits);
            try {
                SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
                return factory.generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | RuntimeException e) {
            return null;
        }
    }

    private static ParsedHash parse(String stored) {
        if (stored == null) {
            return null;
        }
        String[] parts = stored.split(SEPARATOR);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return null;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations <= 0) {
                return null;
            }
            Base64.Decoder decoder = Base64.getDecoder();
            byte[] salt = decoder.decode(parts[2]);
            byte[] hash = decoder.decode(parts[3]);
            if (salt.length == 0 || hash.length == 0) {
                return null;
            }
            return new ParsedHash(iterations, salt, hash);
        } catch (IllegalArgumentException e) {
            // Covers NumberFormatException (a subclass) from parseInt and the
            // Base64 decoder's own IllegalArgumentException. Not a well-formed hash,
            // so let the caller treat it as legacy plain text.
            return null;
        }
    }

    private static final class ParsedHash {
        final int iterations;
        final byte[] salt;
        final byte[] hash;

        ParsedHash(int iterations, byte[] salt, byte[] hash) {
            this.iterations = iterations;
            this.salt = salt;
            this.hash = hash;
        }
    }
}
