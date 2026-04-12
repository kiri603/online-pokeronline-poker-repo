package com.game.poker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
public class AllowedOriginsProperties {
    private final String[] origins;

    public AllowedOriginsProperties(@Value("${app.auth.allowed-origins:*}") String rawOrigins) {
        this.origins = Arrays.stream(rawOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
    }

    public String[] toArray() {
        return origins;
    }
}
