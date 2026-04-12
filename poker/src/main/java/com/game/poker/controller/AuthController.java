package com.game.poker.controller;

import com.game.poker.dto.auth.AuthRequest;
import com.game.poker.dto.auth.AuthUserResponse;
import com.game.poker.dto.auth.CaptchaResponse;
import com.game.poker.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/captcha")
    public CaptchaResponse createCaptcha(HttpSession session) {
        return authService.createCaptcha(session);
    }

    @PostMapping("/register")
    public AuthUserResponse register(@Valid @RequestBody AuthRequest request,
                                     HttpSession session,
                                     HttpServletRequest httpRequest,
                                     HttpServletResponse response) {
        return authService.register(request, session, httpRequest, response, resolveClientIp(httpRequest),
                httpRequest.getHeader("User-Agent"));
    }

    @PostMapping("/login")
    public AuthUserResponse login(@Valid @RequestBody AuthRequest request,
                                  HttpSession session,
                                  HttpServletRequest httpRequest,
                                  HttpServletResponse response) {
        return authService.login(request, session, httpRequest, response, resolveClientIp(httpRequest),
                httpRequest.getHeader("User-Agent"));
    }

    @PostMapping("/guest")
    public AuthUserResponse guestLogin(HttpSession session, HttpServletRequest request, HttpServletResponse response) {
        return authService.guestLogin(session, response, request.isSecure());
    }

    @GetMapping("/me")
    public AuthUserResponse currentUser(HttpSession session, HttpServletRequest request, HttpServletResponse response) {
        return authService.getCurrentUser(session, request, response);
    }

    @PostMapping("/logout")
    public void logout(HttpSession session, HttpServletRequest request, HttpServletResponse response) {
        authService.logout(session, request, response);
    }

    @PostMapping("/daily-signin")
    public Map<String, Object> dailySignIn(HttpSession session, HttpServletRequest request) {
        return authService.claimDailySignIn(session, request);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
