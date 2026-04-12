package com.game.poker.service;

import com.game.poker.auth.AuthException;
import com.game.poker.auth.AuthSessionKeys;
import com.game.poker.auth.SessionUser;
import com.game.poker.dto.auth.AuthRequest;
import com.game.poker.dto.auth.AuthUserResponse;
import com.game.poker.dto.auth.CaptchaResponse;
import com.game.poker.entity.RememberLoginToken;
import com.game.poker.entity.UserAccount;
import com.game.poker.repository.RememberLoginTokenRepository;
import com.game.poker.websocket.GameWebSocketHandler;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

@Service
public class AuthService {
    private static final String REMEMBER_COOKIE = "POKER_REMEMBER_TOKEN";
    private static final String DEVICE_COOKIE = "POKER_DEVICE_ID";
    private static final String TAB_AUTH_HEADER = "X-Poker-Auth-Token";
    private static final int REMEMBER_DAYS = 30;
    private static final String GUEST_PREFIX = "游客";
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[\\p{IsHan}A-Za-z0-9_]{4,20}$");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,64}$");

    private final UserService userService;
    private final CaptchaService captchaService;
    private final AuthRateLimitService authRateLimitService;
    private final PasswordEncoder passwordEncoder;
    private final RememberLoginTokenRepository rememberLoginTokenRepository;
    private final GameService gameService;
    private final GameWebSocketHandler gameWebSocketHandler;
    private final LoginSessionRegistry loginSessionRegistry;
    private final AuthTokenService authTokenService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Set<String> activeGuestUsernames = ConcurrentHashMap.newKeySet();

    public AuthService(UserService userService,
                       CaptchaService captchaService,
                       AuthRateLimitService authRateLimitService,
                       PasswordEncoder passwordEncoder,
                       RememberLoginTokenRepository rememberLoginTokenRepository,
                       GameService gameService,
                       GameWebSocketHandler gameWebSocketHandler,
                       LoginSessionRegistry loginSessionRegistry,
                       AuthTokenService authTokenService) {
        this.userService = userService;
        this.captchaService = captchaService;
        this.authRateLimitService = authRateLimitService;
        this.passwordEncoder = passwordEncoder;
        this.rememberLoginTokenRepository = rememberLoginTokenRepository;
        this.gameService = gameService;
        this.gameWebSocketHandler = gameWebSocketHandler;
        this.loginSessionRegistry = loginSessionRegistry;
        this.authTokenService = authTokenService;
    }

    public CaptchaResponse createCaptcha(HttpSession session) {
        return captchaService.generateCaptcha(session);
    }

    @Transactional
    public AuthUserResponse register(AuthRequest request, HttpSession session, HttpServletRequest httpRequest,
                                     HttpServletResponse response, String ip, String userAgent) {
        captchaService.validateCaptcha(session, request.getCaptchaCode());
        authRateLimitService.assertRegisterAllowed(ip, request.getUsername());
        authRateLimitService.assertRegisterDeviceAllowed(
                ensureDeviceId(response, readCookie(httpRequest, DEVICE_COOKIE), httpRequest.isSecure())
        );

        String username = normalizeUsername(request.getUsername());
        releaseGuestIfNeeded(session);
        validateUsername(username);
        validatePassword(request.getPassword());
        if (userService.existsByUsername(username)) {
            throw new AuthException(HttpStatus.CONFLICT, "用户名已存在");
        }

        UserAccount user = userService.createUser(username, passwordEncoder.encode(request.getPassword()));
        userService.updateLastLogin(user);
        userService.saveLoginLog(user, username, ip, userAgent, true);
        persistLogin(session, response, user, httpRequest.isSecure());
        return AuthUserResponse.from(user, authTokenService.issueToken(SessionUser.from(user)), userService.hasDailySignInAvailable(user.getId()));
    }

    @Transactional
    public AuthUserResponse login(AuthRequest request, HttpSession session, HttpServletRequest httpRequest,
                                  HttpServletResponse response, String ip, String userAgent) {
        captchaService.validateCaptcha(session, request.getCaptchaCode());
        String username = normalizeUsername(request.getUsername());
        releaseGuestIfNeeded(session);
        authRateLimitService.assertLoginAllowed(ip, username);

        UserAccount user = userService.findByUsername(username)
                .orElseThrow(() -> {
                    authRateLimitService.recordLoginFailure(username);
                    userService.saveLoginLog(null, username, ip, userAgent, false);
                    return new AuthException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
                });

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            authRateLimitService.recordLoginFailure(username);
            userService.saveLoginLog(user, username, ip, userAgent, false);
            throw new AuthException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }

        authRateLimitService.clearLoginFailures(username);
        userService.updateLastLogin(user);
        userService.saveLoginLog(user, username, ip, userAgent, true);
        persistLogin(session, response, user, httpRequest.isSecure());
        return AuthUserResponse.from(user, authTokenService.issueToken(SessionUser.from(user)), userService.hasDailySignInAvailable(user.getId()));
    }

    public AuthUserResponse guestLogin(HttpSession session, HttpServletResponse response, boolean secureCookie) {
        releaseGuestIfNeeded(session);
        loginSessionRegistry.unregister(session);
        clearRememberCookie(response, secureCookie);
        String username = generateGuestUsername();
        activeGuestUsernames.add(username);
        SessionUser guestUser = SessionUser.guest(username);
        session.setAttribute(AuthSessionKeys.LOGIN_USER, guestUser);
        return AuthUserResponse.from(guestUser, null, false);
    }

    @Transactional
    public AuthUserResponse getCurrentUser(HttpSession session, HttpServletRequest request, HttpServletResponse response) {
        SessionUser headerUser = authenticateByTabToken(readTabAuthToken(request));
        if (headerUser != null) {
            if (headerUser.isGuest()) {
                throw new AuthException(HttpStatus.UNAUTHORIZED, "游客登录仅当前访问有效，请重新进入");
            }
            return AuthUserResponse.from(headerUser, authTokenService.issueToken(headerUser), userService.hasDailySignInAvailable(headerUser.getId()));
        }

        SessionUser sessionUser = sessionUserFromSession(session);
        if (sessionUser != null) {
            if (sessionUser.isGuest()) {
                releaseGuestUsername(sessionUser.getUsername());
                session.removeAttribute(AuthSessionKeys.LOGIN_USER);
                session.invalidate();
                clearRememberCookie(response, request.isSecure());
                throw new AuthException(HttpStatus.UNAUTHORIZED, "游客登录仅当前访问有效，请重新进入");
            }
            ensureActiveSession(sessionUser, session, response, request.isSecure());
            loginSessionRegistry.register(sessionUser.getUsername(), session);
            return AuthUserResponse.from(sessionUser, authTokenService.issueToken(sessionUser), userService.hasDailySignInAvailable(sessionUser.getId()));
        }

        String rememberToken = readCookie(request, REMEMBER_COOKIE);
        if (rememberToken == null || rememberToken.isBlank()) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "未登录");
        }

        RememberLoginToken storedToken = rememberLoginTokenRepository
                .findByTokenHashAndRevokedFalse(sha256(rememberToken))
                .filter(token -> token.getExpiresAt().isAfter(LocalDateTime.now()))
                .orElseThrow(() -> {
                    clearRememberCookie(response, request.isSecure());
                    return new AuthException(HttpStatus.UNAUTHORIZED, "未登录");
                });

        UserAccount user = userService.findById(storedToken.getUserId())
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "账号不存在"));

        storedToken.setLastUsedAt(LocalDateTime.now());
        rememberLoginTokenRepository.save(storedToken);
        session.setAttribute(AuthSessionKeys.LOGIN_USER, SessionUser.from(user));
        loginSessionRegistry.register(user.getUsername(), session);
        return AuthUserResponse.from(user, authTokenService.issueToken(SessionUser.from(user)), userService.hasDailySignInAvailable(user.getId()));
    }

    @Transactional
    public void logout(HttpSession session, HttpServletRequest request, HttpServletResponse response) {
        SessionUser headerUser = authenticateByTabToken(readTabAuthToken(request));
        if (headerUser != null && headerUser.isGuest()) {
            releaseGuestUsername(headerUser.getUsername());
        }
        authTokenService.revokeToken(readTabAuthToken(request));
        releaseGuestIfNeeded(session);
        loginSessionRegistry.unregister(session);
        String rememberToken = readCookie(request, REMEMBER_COOKIE);
        if (rememberToken != null && !rememberToken.isBlank()) {
            Optional<RememberLoginToken> token = rememberLoginTokenRepository.findByTokenHashAndRevokedFalse(sha256(rememberToken));
            token.ifPresent(value -> {
                value.setRevoked(true);
                rememberLoginTokenRepository.save(value);
            });
        }
        session.removeAttribute(AuthSessionKeys.LOGIN_USER);
        session.invalidate();
        clearRememberCookie(response, request.isSecure());
    }

    @Transactional
    public Map<String, Object> claimDailySignIn(HttpSession session, HttpServletRequest request) {
        SessionUser sessionUser = requireAuthenticatedUser(request, session, false);
        UserAccount user = userService.findById(sessionUser.getId())
                .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "账号不存在"));
        if (!userService.hasDailySignInAvailable(user.getId())) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "今天已经签到过了");
        }

        int beforeExperience = userService.getOrCreateStats(user.getId()).getExperience();
        int afterExperience = userService.claimDailySignIn(user.getId()).getExperience();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("gainedExperience", Math.max(0, afterExperience - beforeExperience));
        body.put("experience", afterExperience);
        body.put("level", LevelSystem.resolveLevel(afterExperience));
        body.put("dailySignInAvailable", false);
        return body;
    }

    public SessionUser requireAuthenticatedUser(HttpSession session, boolean allowGuest) {
        SessionUser sessionUser = sessionUserFromSession(session);
        if (sessionUser == null) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        if (sessionUser.isGuest()) {
            if (allowGuest) {
                return sessionUser;
            }
            throw new AuthException(HttpStatus.FORBIDDEN, "游客账号暂不支持该功能");
        }
        if (!userService.isSessionVersionCurrent(sessionUser)) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "账号已在其他设备登录，请重新登录");
        }
        loginSessionRegistry.register(sessionUser.getUsername(), session);
        return sessionUser;
    }

    public SessionUser requireAuthenticatedUser(HttpServletRequest request, HttpSession session, boolean allowGuest) {
        SessionUser tokenUser = authenticateByTabToken(readTabAuthToken(request));
        if (tokenUser != null) {
            if (tokenUser.isGuest() && !allowGuest) {
                throw new AuthException(HttpStatus.FORBIDDEN, "游客账号暂不支持该功能");
            }
            return tokenUser;
        }
        return requireAuthenticatedUser(session, allowGuest);
    }

    public SessionUser resolveTabAuthenticatedUser(String token) {
        return authenticateByTabToken(token);
    }

    private void validateUsername(String username) {
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "用户名需为4到20位中文、字母、数字或下划线");
        }
        String lower = username.toLowerCase();
        if (lower.contains("room_manager") || username.startsWith("AI玩家-") || username.startsWith(GUEST_PREFIX)) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "该用户名为系统保留名称");
        }
    }

    private void validatePassword(String password) {
        if (!PASSWORD_PATTERN.matcher(password).matches()) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "密码至少8位，且必须包含字母和数字");
        }
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim();
    }

    private void persistLogin(HttpSession session, HttpServletResponse response, UserAccount user, boolean secureCookie) {
        String sessionVersion = generateRawToken();
        user.setSessionVersion(sessionVersion);
        userService.save(user);
        revokeRememberTokens(user.getId());
        session.setAttribute(AuthSessionKeys.LOGIN_USER, SessionUser.from(user));
        loginSessionRegistry.register(user.getUsername(), session);
        String rawToken = generateRawToken();
        RememberLoginToken token = new RememberLoginToken();
        token.setUserId(user.getId());
        token.setTokenHash(sha256(rawToken));
        token.setExpiresAt(LocalDateTime.now().plusDays(REMEMBER_DAYS));
        token.setRevoked(false);
        token.setLastUsedAt(LocalDateTime.now());
        rememberLoginTokenRepository.save(token);

        ResponseCookie cookie = ResponseCookie.from(REMEMBER_COOKIE, rawToken)
                .httpOnly(true)
                .secure(secureCookie)
                .path("/")
                .sameSite("Lax")
                .maxAge(REMEMBER_DAYS * 24L * 3600L)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        gameWebSocketHandler.forceLogoutUser(user.getUsername(), "账号已在其他设备登录，请重新登录");
    }

    private void clearRememberCookie(HttpServletResponse response, boolean secureCookie) {
        ResponseCookie cookie = ResponseCookie.from(REMEMBER_COOKIE, "")
                .httpOnly(true)
                .secure(secureCookie)
                .path("/")
                .sameSite("Lax")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private SessionUser sessionUserFromSession(HttpSession session) {
        try {
            Object raw = session.getAttribute(AuthSessionKeys.LOGIN_USER);
            return raw instanceof SessionUser sessionUser ? sessionUser : null;
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private SessionUser authenticateByTabToken(String token) {
        SessionUser tokenUser = authTokenService.resolveUser(token);
        if (tokenUser == null) {
            return null;
        }
        if (tokenUser.isGuest()) {
            return tokenUser;
        }
        if (!userService.isSessionVersionCurrent(tokenUser)) {
            authTokenService.revokeToken(token);
            return null;
        }
        return tokenUser;
    }

    private void ensureActiveSession(SessionUser sessionUser, HttpSession session, HttpServletResponse response, boolean secureCookie) {
        if (userService.isSessionVersionCurrent(sessionUser)) {
            return;
        }
        invalidateSession(session, response, secureCookie);
        throw new AuthException(HttpStatus.UNAUTHORIZED, "账号已在其他设备登录，请重新登录");
    }

    private void invalidateSession(HttpSession session, HttpServletResponse response, boolean secureCookie) {
        loginSessionRegistry.unregister(session);
        try {
            session.removeAttribute(AuthSessionKeys.LOGIN_USER);
        } catch (IllegalStateException ignored) {
            // Session was already invalidated by a newer login.
        }
        try {
            session.invalidate();
        } catch (IllegalStateException ignored) {
            // Session was already invalidated by a newer login.
        }
        clearRememberCookie(response, secureCookie);
    }

    private void revokeRememberTokens(Long userId) {
        if (userId == null) {
            return;
        }
        for (RememberLoginToken existingToken : rememberLoginTokenRepository.findAllByUserIdAndRevokedFalse(userId)) {
            existingToken.setRevoked(true);
            rememberLoginTokenRepository.save(existingToken);
        }
    }

    private void releaseGuestIfNeeded(HttpSession session) {
        SessionUser currentUser = sessionUserFromSession(session);
        if (currentUser != null && currentUser.isGuest()) {
            releaseGuestUsername(currentUser.getUsername());
        }
    }

    private void releaseGuestUsername(String username) {
        if (username != null && username.startsWith(GUEST_PREFIX)) {
            activeGuestUsernames.remove(username);
        }
    }

    private String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String readTabAuthToken(HttpServletRequest request) {
        String headerValue = request.getHeader(TAB_AUTH_HEADER);
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        return headerValue.trim();
    }

    private String ensureDeviceId(HttpServletResponse response, String existingValue, boolean secureCookie) {
        if (existingValue != null && !existingValue.isBlank()) {
            return existingValue;
        }
        String deviceId = generateRawToken();
        ResponseCookie cookie = ResponseCookie.from(DEVICE_COOKIE, deviceId)
                .httpOnly(true)
                .secure(secureCookie)
                .path("/")
                .sameSite("Lax")
                .maxAge(180L * 24L * 3600L)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return deviceId;
    }

    private String generateGuestUsername() {
        for (int attempt = 0; attempt < 2000; attempt++) {
            int suffix = ThreadLocalRandom.current().nextInt(1000, 10000);
            String candidate = GUEST_PREFIX + suffix;
            if (activeGuestUsernames.contains(candidate)) {
                continue;
            }
            if (gameService.getAllActiveUserIds().contains(candidate)) {
                continue;
            }
            if (userService.existsByUsername(candidate)) {
                continue;
            }
            return candidate;
        }
        throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "游客ID暂时分配失败，请稍后再试");
    }

    private String generateRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash remember token", e);
        }
    }
}
