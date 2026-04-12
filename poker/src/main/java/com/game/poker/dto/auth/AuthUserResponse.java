package com.game.poker.dto.auth;

import com.game.poker.auth.SessionUser;
import com.game.poker.entity.UserAccount;

public class AuthUserResponse {
    private Long id;
    private String username;
    private String nickname;
    private boolean guest;
    private String tabToken;
    private boolean dailySignInAvailable;

    public AuthUserResponse() {
    }

    public AuthUserResponse(Long id, String username, String nickname, boolean guest, String tabToken, boolean dailySignInAvailable) {
        this.id = id;
        this.username = username;
        this.nickname = nickname;
        this.guest = guest;
        this.tabToken = tabToken;
        this.dailySignInAvailable = dailySignInAvailable;
    }

    public static AuthUserResponse from(UserAccount user, String tabToken, boolean dailySignInAvailable) {
        return new AuthUserResponse(user.getId(), user.getUsername(), user.getNickname(), false, tabToken, dailySignInAvailable);
    }

    public static AuthUserResponse from(SessionUser user, String tabToken, boolean dailySignInAvailable) {
        return new AuthUserResponse(user.getId(), user.getUsername(), user.getNickname(), user.isGuest(), tabToken, dailySignInAvailable);
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getNickname() {
        return nickname;
    }

    public boolean isGuest() {
        return guest;
    }

    public String getTabToken() {
        return tabToken;
    }

    public boolean isDailySignInAvailable() {
        return dailySignInAvailable;
    }
}
