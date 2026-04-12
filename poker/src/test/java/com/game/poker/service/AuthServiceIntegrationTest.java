package com.game.poker.service;

import com.game.poker.auth.AuthException;
import com.game.poker.dto.auth.AuthRequest;
import com.game.poker.dto.auth.AuthUserResponse;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class AuthServiceIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private CaptchaService captchaService;

    @Test
    void rememberCookieRestoresLoginUntilLogout() {
        String username = "test" + System.currentTimeMillis();

        MockHttpSession registerSession = new MockHttpSession();
        authService.createCaptcha(registerSession);
        String captchaCode = captchaService.peekCaptchaCodeForTesting(registerSession.getId());
        assertThat(captchaCode).isNotBlank();

        AuthRequest registerRequest = new AuthRequest();
        registerRequest.setUsername(username);
        registerRequest.setPassword("abc12345");
        registerRequest.setCaptchaCode(captchaCode);

        MockHttpServletRequest registerServletRequest = new MockHttpServletRequest();
        MockHttpServletResponse registerServletResponse = new MockHttpServletResponse();
        AuthUserResponse registerResponse = authService.register(
                registerRequest,
                registerSession,
                registerServletRequest,
                registerServletResponse,
                "127.0.0.1",
                "JUnit"
        );
        assertThat(registerResponse.getUsername()).isEqualTo(username);

        Cookie rememberCookie = parseCookie(registerServletResponse.getHeaders("Set-Cookie"), "POKER_REMEMBER_TOKEN");
        assertThat(rememberCookie).isNotNull();

        MockHttpSession freshSession = new MockHttpSession();
        MockHttpServletRequest meRequest = new MockHttpServletRequest();
        meRequest.setCookies(rememberCookie);
        MockHttpServletResponse meResponse = new MockHttpServletResponse();
        AuthUserResponse currentUser = authService.getCurrentUser(freshSession, meRequest, meResponse);
        assertThat(currentUser.getUsername()).isEqualTo(username);

        MockHttpServletRequest logoutRequest = new MockHttpServletRequest();
        logoutRequest.setCookies(rememberCookie);
        MockHttpServletResponse logoutResponse = new MockHttpServletResponse();
        authService.logout(freshSession, logoutRequest, logoutResponse);

        MockHttpSession thirdSession = new MockHttpSession();
        MockHttpServletRequest thirdRequest = new MockHttpServletRequest();
        thirdRequest.setCookies(rememberCookie);
        MockHttpServletResponse thirdResponse = new MockHttpServletResponse();
        assertThrows(AuthException.class,
                () -> authService.getCurrentUser(thirdSession, thirdRequest, thirdResponse));
    }

    @Test
    void secondLoginInvalidatesPreviousSessionAndRememberCookie() {
        String username = "multi" + System.currentTimeMillis();

        MockHttpSession registerSession = new MockHttpSession();
        authService.createCaptcha(registerSession);
        String registerCaptcha = captchaService.peekCaptchaCodeForTesting(registerSession.getId());

        AuthRequest registerRequest = new AuthRequest();
        registerRequest.setUsername(username);
        registerRequest.setPassword("abc12345");
        registerRequest.setCaptchaCode(registerCaptcha);

        MockHttpServletResponse registerResponse = new MockHttpServletResponse();
        authService.register(
                registerRequest,
                registerSession,
                new MockHttpServletRequest(),
                registerResponse,
                "127.0.0.1",
                "JUnit"
        );

        Cookie oldRememberCookie = parseCookie(registerResponse.getHeaders("Set-Cookie"), "POKER_REMEMBER_TOKEN");
        assertThat(oldRememberCookie).isNotNull();

        MockHttpSession secondLoginSession = new MockHttpSession();
        authService.createCaptcha(secondLoginSession);
        String loginCaptcha = captchaService.peekCaptchaCodeForTesting(secondLoginSession.getId());

        AuthRequest loginRequest = new AuthRequest();
        loginRequest.setUsername(username);
        loginRequest.setPassword("abc12345");
        loginRequest.setCaptchaCode(loginCaptcha);

        MockHttpServletResponse secondLoginResponse = new MockHttpServletResponse();
        authService.login(
                loginRequest,
                secondLoginSession,
                new MockHttpServletRequest(),
                secondLoginResponse,
                "127.0.0.2",
                "JUnit"
        );

        MockHttpServletResponse staleSessionResponse = new MockHttpServletResponse();
        assertThrows(AuthException.class,
                () -> authService.getCurrentUser(registerSession, new MockHttpServletRequest(), staleSessionResponse));

        MockHttpSession staleRememberSession = new MockHttpSession();
        MockHttpServletRequest staleRememberRequest = new MockHttpServletRequest();
        staleRememberRequest.setCookies(oldRememberCookie);
        MockHttpServletResponse staleRememberResponse = new MockHttpServletResponse();
        assertThrows(AuthException.class,
                () -> authService.getCurrentUser(staleRememberSession, staleRememberRequest, staleRememberResponse));
    }

    @Test
    void dailySignInCanOnlyBeClaimedOncePerDay() {
        String username = "signin" + System.currentTimeMillis();

        MockHttpSession registerSession = new MockHttpSession();
        authService.createCaptcha(registerSession);
        String captchaCode = captchaService.peekCaptchaCodeForTesting(registerSession.getId());

        AuthRequest registerRequest = new AuthRequest();
        registerRequest.setUsername(username);
        registerRequest.setPassword("abc12345");
        registerRequest.setCaptchaCode(captchaCode);

        AuthUserResponse registerResponse = authService.register(
                registerRequest,
                registerSession,
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                "127.0.0.1",
                "JUnit"
        );

        assertThat(registerResponse.isDailySignInAvailable()).isTrue();

        MockHttpServletRequest signInRequest = new MockHttpServletRequest();
        Map<String, Object> firstClaim = authService.claimDailySignIn(registerSession, signInRequest);
        assertThat(firstClaim.get("gainedExperience")).isEqualTo(3);
        assertThat(firstClaim.get("experience")).isEqualTo(3);
        assertThat(firstClaim.get("level")).isEqualTo(1);

        assertThrows(AuthException.class, () -> authService.claimDailySignIn(registerSession, signInRequest));
    }

    private Cookie parseCookie(Iterable<String> setCookieHeaders, String cookieName) {
        String setCookieHeader = null;
        for (String header : setCookieHeaders) {
            if (header.startsWith(cookieName + "=")) {
                setCookieHeader = header;
                break;
            }
        }
        assertThat(setCookieHeader).isNotBlank();
        String[] parts = setCookieHeader.split(";", 2);
        String[] nameValue = parts[0].split("=", 2);
        return new Cookie(nameValue[0], nameValue.length > 1 ? nameValue[1] : "");
    }
}
