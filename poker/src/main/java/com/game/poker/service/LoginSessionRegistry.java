package com.game.poker.service;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginSessionRegistry {
    private final Map<String, HttpSession> userSessions = new ConcurrentHashMap<>();
    private final Map<String, String> sessionOwners = new ConcurrentHashMap<>();

    public void register(String username, HttpSession session) {
        if (username == null || username.isBlank() || session == null) {
            return;
        }
        HttpSession previousSession = userSessions.put(username, session);
        sessionOwners.put(session.getId(), username);
        if (previousSession != null && !previousSession.getId().equals(session.getId())) {
            sessionOwners.remove(previousSession.getId(), username);
            invalidateQuietly(previousSession);
        }
    }

    public void unregister(HttpSession session) {
        if (session == null) {
            return;
        }
        String sessionId = session.getId();
        String username = sessionOwners.remove(sessionId);
        if (username != null) {
            userSessions.computeIfPresent(username, (key, currentSession) ->
                    currentSession != null && sessionId.equals(currentSession.getId()) ? null : currentSession
            );
        }
    }

    public boolean isOnline(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        return userSessions.containsKey(username);
    }

    private void invalidateQuietly(HttpSession session) {
        try {
            session.invalidate();
        } catch (IllegalStateException ignored) {
            // Session is already gone; nothing else to do here.
        }
    }
}
