package com.game.poker.websocket;

import com.game.poker.auth.AuthHandshakeInterceptor;
import com.game.poker.config.AllowedOriginsProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final GameWebSocketHandler gameWebSocketHandler;
    private final AuthHandshakeInterceptor authHandshakeInterceptor;
    private final AllowedOriginsProperties allowedOriginsProperties;

    public WebSocketConfig(GameWebSocketHandler gameWebSocketHandler,
                           AuthHandshakeInterceptor authHandshakeInterceptor,
                           AllowedOriginsProperties allowedOriginsProperties) {
        this.gameWebSocketHandler = gameWebSocketHandler;
        this.authHandshakeInterceptor = authHandshakeInterceptor;
        this.allowedOriginsProperties = allowedOriginsProperties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(gameWebSocketHandler, "/ws/game")
                .addInterceptors(new HttpSessionHandshakeInterceptor(), authHandshakeInterceptor)
                .setAllowedOriginPatterns(allowedOriginsProperties.toArray());
    }
}
