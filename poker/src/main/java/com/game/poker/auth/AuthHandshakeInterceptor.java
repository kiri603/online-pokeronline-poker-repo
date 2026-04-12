package com.game.poker.auth;

import com.game.poker.service.AuthService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Component
public class AuthHandshakeInterceptor implements HandshakeInterceptor {
    private final AuthService authService;

    public AuthHandshakeInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String query = request.getURI().getQuery();
        String queryToken = extractQueryToken(query);
        if (queryToken != null) {
            SessionUser tokenUser = authService.resolveTabAuthenticatedUser(queryToken);
            if (tokenUser != null) {
                attributes.put(AuthSessionKeys.LOGIN_USER, tokenUser);
                return true;
            }
            response.getHeaders().add(HttpHeaders.WWW_AUTHENTICATE, "TabToken");
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        HttpSession httpSession = servletRequest.getServletRequest().getSession(false);
        if (httpSession == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        Object rawUser = httpSession.getAttribute(AuthSessionKeys.LOGIN_USER);
        if (!(rawUser instanceof SessionUser sessionUser)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        attributes.put(AuthSessionKeys.LOGIN_USER, sessionUser);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                               Exception exception) {
    }

    private String extractQueryToken(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && "authToken".equals(kv[0]) && !kv[1].isBlank()) {
                return kv[1];
            }
        }
        return null;
    }
}
