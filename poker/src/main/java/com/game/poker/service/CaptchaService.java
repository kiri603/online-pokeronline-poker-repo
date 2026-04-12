package com.game.poker.service;

import com.game.poker.auth.AuthException;
import com.game.poker.dto.auth.CaptchaResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class CaptchaService {
    private static final long CAPTCHA_TTL_SECONDS = 300L;
    private static final String CAPTCHA_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final boolean redisEnabled;
    private final StringRedisTemplate redisTemplate;
    private final Map<String, LocalCaptchaValue> localStore = new ConcurrentHashMap<>();

    public CaptchaService(@Value("${app.auth.redis-enabled:false}") boolean redisEnabled,
                          StringRedisTemplate redisTemplate) {
        this.redisEnabled = redisEnabled;
        this.redisTemplate = redisTemplate;
    }

    public CaptchaResponse generateCaptcha(HttpSession session) {
        String code = randomCaptcha(4);
        String key = captchaKey(session.getId());
        if (redisEnabled && redisTemplate != null) {
            redisTemplate.opsForValue().set(key, code, Duration.ofSeconds(CAPTCHA_TTL_SECONDS));
        } else {
            localStore.put(key, new LocalCaptchaValue(code, System.currentTimeMillis() + CAPTCHA_TTL_SECONDS * 1000));
        }
        return new CaptchaResponse(renderBase64(code), CAPTCHA_TTL_SECONDS);
    }

    public void validateCaptcha(HttpSession session, String inputCode) {
        String key = captchaKey(session.getId());
        String actual;
        if (redisEnabled && redisTemplate != null) {
            actual = redisTemplate.opsForValue().get(key);
            redisTemplate.delete(key);
        } else {
            LocalCaptchaValue value = localStore.remove(key);
            actual = value == null || value.expireAt < System.currentTimeMillis() ? null : value.code;
        }

        if (actual == null || inputCode == null || !actual.equalsIgnoreCase(inputCode.trim())) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "验证码错误或已过期");
        }
    }

    private String captchaKey(String sessionId) {
        return "captcha:auth:" + sessionId;
    }

    public String peekCaptchaCodeForTesting(String sessionId) {
        String key = captchaKey(sessionId);
        if (redisEnabled && redisTemplate != null) {
            return redisTemplate.opsForValue().get(key);
        }
        LocalCaptchaValue value = localStore.get(key);
        return value == null ? null : value.code;
    }

    private String randomCaptcha(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(CAPTCHA_CHARS.charAt(ThreadLocalRandom.current().nextInt(CAPTCHA_CHARS.length())));
        }
        return builder.toString();
    }

    private String renderBase64(String code) {
        BufferedImage image = new BufferedImage(120, 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(243, 247, 251));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setFont(new Font("Arial", Font.BOLD, 24));
        graphics.setColor(new Color(44, 62, 80));
        graphics.drawString(code, 18, 28);
        graphics.setColor(new Color(140, 160, 180));
        for (int i = 0; i < 6; i++) {
            graphics.drawLine(ThreadLocalRandom.current().nextInt(120), ThreadLocalRandom.current().nextInt(40),
                    ThreadLocalRandom.current().nextInt(120), ThreadLocalRandom.current().nextInt(40));
        }
        graphics.dispose();

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", outputStream);
            String base64 = Base64.getEncoder().encodeToString(outputStream.toByteArray());
            return "data:image/png;base64," + base64;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render captcha", e);
        }
    }

    private record LocalCaptchaValue(String code, long expireAt) {
    }
}
