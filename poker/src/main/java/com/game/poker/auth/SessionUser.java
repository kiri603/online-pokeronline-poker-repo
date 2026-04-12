package com.game.poker.auth;

import com.game.poker.entity.UserAccount;

import java.io.Serial;
import java.io.Serializable;

public class SessionUser implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String username;
    private String nickname;
    private boolean guest;
    private String sessionVersion;

    public SessionUser() {
    }

    public SessionUser(Long id, String username, String nickname, boolean guest, String sessionVersion) {
        this.id = id;
        this.username = username;
        this.nickname = nickname;
        this.guest = guest;
        this.sessionVersion = sessionVersion;
    }

    public static SessionUser from(UserAccount user) {
        return new SessionUser(user.getId(), user.getUsername(), user.getNickname(), false, user.getSessionVersion());
    }

    public static SessionUser guest(String username) {
        return new SessionUser(null, username, username, true, null);
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

    public String getSessionVersion() {
        return sessionVersion;
    }
}
