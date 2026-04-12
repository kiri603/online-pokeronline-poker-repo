package com.game.poker.service;

import com.game.poker.auth.AuthException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthRateLimitService {
    private final boolean redisEnabled;
    private final StringRedisTemplate redisTemplate;
    private final Map<String, LocalCounter> localCounters = new ConcurrentHashMap<>();

    public AuthRateLimitService(@Value("${app.auth.redis-enabled:false}") boolean redisEnabled,
                                StringRedisTemplate redisTemplate) {
        this.redisEnabled = redisEnabled;
        this.redisTemplate = redisTemplate;
    }

    public void assertRegisterAllowed(String ip, String username) {
        checkLimit("register:ip:" + safe(ip), 5, Duration.ofMinutes(10), "注册过于频繁，请稍后再试");
        checkLimit("register:user:" + safe(username), 5, Duration.ofMinutes(10), "该用户名尝试过于频繁，请稍后再试");
    }

    public void assertRegisterDeviceAllowed(String deviceId) {
        checkLimit("register:device:" + safe(deviceId), 3, Duration.ofDays(1), "同一设备今日注册次数过多，请稍后再试");
    }

    public void assertLoginAllowed(String ip, String username) {
        checkLimit("login:ip:" + safe(ip), 20, Duration.ofMinutes(15), "登录过于频繁，请稍后再试");
        if (currentCount("login:fail:" + safe(username), Duration.ofMinutes(30)) >= 8) {
            throw new AuthException(HttpStatus.TOO_MANY_REQUESTS, "登录失败次数过多，请30分钟后再试");
        }
    }

    public void recordLoginFailure(String username) {
        increment("login:fail:" + safe(username), Duration.ofMinutes(30));
    }

    public void clearLoginFailures(String username) {
        delete("login:fail:" + safe(username));
    }

    private void checkLimit(String key, int max, Duration window, String message) {
        long count = increment(key, window);
        if (count > max) {
            throw new AuthException(HttpStatus.TOO_MANY_REQUESTS, message);
        }
    }

    private long currentCount(String key, Duration window) {
        if (redisEnabled && redisTemplate != null) {
            String value = redisTemplate.opsForValue().get(key);
            return value == null ? 0L : Long.parseLong(value);
        }
        LocalCounter counter = localCounters.get(key);
        if (counter == null || counter.expireAt < System.currentTimeMillis()) {
            localCounters.remove(key);
            return 0L;
        }
        return counter.count;
    }

    private long increment(String key, Duration window) {
        if (redisEnabled && redisTemplate != null) {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, window);
            }
            return count == null ? 0L : count;
        }

        long now = System.currentTimeMillis();
        LocalCounter counter = localCounters.compute(key, (ignored, existing) -> {
            if (existing == null || existing.expireAt < now) {
                return new LocalCounter(1, now + window.toMillis());
            }
            existing.count += 1;
            return existing;
        });
        return counter.count;
    }

    private void delete(String key) {
        if (redisEnabled && redisTemplate != null) {
            redisTemplate.delete(key);
            return;
        }
        localCounters.remove(key);
    }

    private String safe(String source) {
        return source == null || source.isBlank() ? "unknown" : source.trim().toLowerCase();
    }

    private static final class LocalCounter {
        private long count;
        private final long expireAt;

        private LocalCounter(long count, long expireAt) {
            this.count = count;
            this.expireAt = expireAt;
        }
    }
}
