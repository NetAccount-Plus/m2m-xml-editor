package hu.gov.nav.xsdparsertool.web.security.config;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * Az M2M szerver saját órájával létrehozott, rövid életű, egyszer használható
 * trusted-login tokenek tára. Így az Accounting és az M2M gép órája közötti
 * eltérés nem tudja érvényteleníteni az SSO-t.
 */
@Service
public class NetAccountingTrustedLoginTokenService {

    private static final long TOKEN_LIFETIME_SECONDS = 60;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();

    public String issue(String userId, String org, String redirect) {
        purgeExpired();
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        tokens.put(token, new Entry(userId, org, redirect, Instant.now().getEpochSecond() + TOKEN_LIFETIME_SECONDS));
        return token;
    }

    public Entry consume(String token) {
        if (token == null || token.isBlank()) return null;
        Entry entry = tokens.remove(token);
        if (entry == null) return null;
        if (entry.expiresAt() < Instant.now().getEpochSecond()) return null;
        return entry;
    }

    private void purgeExpired() {
        long now = Instant.now().getEpochSecond();
        tokens.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
    }

    public record Entry(String userId, String org, String redirect, long expiresAt) {}
}
