package com.game.poker.dto.auth;

public class CaptchaResponse {
    private String imageBase64;
    private long expiresInSeconds;

    public CaptchaResponse() {
    }

    public CaptchaResponse(String imageBase64, long expiresInSeconds) {
        this.imageBase64 = imageBase64;
        this.expiresInSeconds = expiresInSeconds;
    }

    public String getImageBase64() {
        return imageBase64;
    }

    public long getExpiresInSeconds() {
        return expiresInSeconds;
    }
}
