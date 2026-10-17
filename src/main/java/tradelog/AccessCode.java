package tradelog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

// The code every device (the iPhone, other browsers) has to enter once. It is created on first start
// and kept in access-code.txt, so it stays the same between restarts. The file also keeps the
// key the login cookie holds: a long random one, not the code, so trying codes in a cookie
// gets nowhere (the 10 tries limit is only on the login page).
@Component
public class AccessCode {
    // No 0/o, 1/l/i, so it's easy to read off the screen and type on a phone
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final int LENGTH = 8;
    private static final int MAX_FAILURES = 10;
    private static final long LOCK_MILLIS = 10 * 60 * 1000;

    private final String code;
    private final String cookieKey;
    private int failures = 0;
    private long lockedUntil = 0;

    public AccessCode(@Value("${tradelog.access-code-file}") String file) {
        String[] saved = loadOrCreate(Paths.get(file));
        this.code = saved[0];
        this.cookieKey = saved[1];
    }

    public String getCode() {
        return code;
    }

    public boolean matches(String candidate) {
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
                candidate.trim().toLowerCase().getBytes(StandardCharsets.UTF_8),
                code.getBytes(StandardCharsets.UTF_8));
    }

    // What the login cookie holds
    public String getCookieKey() {
        return cookieKey;
    }

    public boolean matchesCookie(String candidate) {
        return candidate != null && MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8), cookieKey.getBytes(StandardCharsets.UTF_8));
    }

    // Like matches, but stops accepting codes for 10 minutes after 10 wrong ones,
    // so nobody on the network can just try them all
    public synchronized boolean tryLogin(String candidate) {
        long now = System.currentTimeMillis();
        if (now < lockedUntil) {
            return false;
        }
        if (matches(candidate)) {
            failures = 0;
            return true;
        }
        failures++;
        if (failures >= MAX_FAILURES) {
            failures = 0;
            lockedUntil = now + LOCK_MILLIS;
        }
        return false;
    }

    // {code, cookie key}: line 1 and 2 of the file. A file from before cookie keys gets one added;
    // a missing or empty file gets both new.
    private static String[] loadOrCreate(Path path) {
        try {
            List<String> lines = Files.exists(path) ? Files.readAllLines(path) : List.of();
            String saved = lines.isEmpty() ? "" : lines.get(0).trim().toLowerCase();
            String key = lines.size() < 2 ? "" : lines.get(1).trim();
            if (!saved.isEmpty() && !key.isEmpty()) {
                return new String[]{saved, key};
            }
            SecureRandom random = new SecureRandom();
            if (saved.isEmpty()) {
                StringBuilder newCode = new StringBuilder();
                for (int i = 0; i < LENGTH; i++) {
                    newCode.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
                }
                saved = newCode.toString();
            }
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            // written in place, so it also works for a file that Docker mounts on its own
            Files.writeString(path, saved + System.lineSeparator() + key + System.lineSeparator());
            return new String[]{saved, key};
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read or create the access code file " + path, e);
        }
    }
}
