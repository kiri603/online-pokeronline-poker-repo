package com.game.poker.service;

import com.game.poker.auth.SessionUser;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthTokenService {
    private static final long TOKEN_TTL_MILLIS = 12L * 60L * 60L * 1000L;

    private final Map<String, TokenEntry> tokenStore = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    public String issueToken(SessionUser sessionUser) {
        cleanupExpiredTokens();
        String token = generateRawToken();
        tokenStore.put(token, new TokenEntry(sessionUser, System.currentTimeMillis() + TOKEN_TTL_MILLIS));
        return token;
    }

    public SessionUser resolveUser(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        TokenEntry entry = tokenStore.get(token);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt < System.currentTimeMillis()) {
            tokenStore.remove(token);
            return null;
        }
        return entry.sessionUser;
    }

    public void revokeToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        tokenStore.remove(token);
    }

    private void cleanupExpiredTokens() {
        long now = System.currentTimeMillis();
        tokenStore.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + "." + Instant.now().toEpochMilli();
    }

    private record TokenEntry(SessionUser sessionUser, long expiresAt) {
    }
}
