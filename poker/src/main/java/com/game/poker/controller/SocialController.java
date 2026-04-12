package com.game.poker.controller;

import com.game.poker.auth.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import com.game.poker.service.AuthService;
import com.game.poker.service.SocialService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/social")
public class SocialController {
    private final AuthService authService;
    private final SocialService socialService;

    public SocialController(AuthService authService, SocialService socialService) {
        this.authService = authService;
        this.socialService = socialService;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(HttpServletRequest request, HttpSession session) {
        return socialService.getOverview(currentUser(request, session));
    }

    @GetMapping("/profile")
    public Map<String, Object> profile(HttpServletRequest request, HttpSession session) {
        return socialService.getProfile(currentUser(request, session));
    }

    @GetMapping("/profile/{targetUserId}")
    public Map<String, Object> friendProfile(HttpServletRequest request,
                                             HttpSession session,
                                             @PathVariable String targetUserId) {
        return socialService.getProfile(currentUser(request, session), targetUserId);
    }

    @GetMapping("/search")
    public List<Map<String, Object>> search(HttpServletRequest request, HttpSession session, @RequestParam String keyword) {
        return socialService.searchUsers(currentUser(request, session), keyword);
    }

    @GetMapping("/messages/{friendUserId}")
    public List<Map<String, Object>> conversation(HttpServletRequest request,
                                                  HttpSession session,
                                                  @PathVariable String friendUserId) {
        String currentUserId = currentUser(request, session);
        socialService.markConversationAsRead(currentUserId, friendUserId);
        return socialService.getConversation(currentUserId, friendUserId);
    }

    @PostMapping("/friend-requests")
    public void sendFriendRequest(HttpServletRequest request, HttpSession session, @RequestBody Map<String, Object> payload) {
        socialService.sendFriendRequest(currentUser(request, session), stringValue(payload, "targetUserId"));
    }

    @PostMapping("/friend-requests/{requestId}/respond")
    public void respondFriendRequest(HttpServletRequest request,
                                     HttpSession session,
                                     @PathVariable Long requestId,
                                     @RequestBody Map<String, Object> payload) {
        socialService.respondFriendRequest(currentUser(request, session), requestId, booleanValue(payload, "accept"));
    }

    @PostMapping("/friends/remove")
    public void removeFriend(HttpServletRequest request, HttpSession session, @RequestBody Map<String, Object> payload) {
        socialService.removeFriend(currentUser(request, session), stringValue(payload, "targetUserId"));
    }

    @PostMapping("/messages")
    public void sendMessage(HttpServletRequest request, HttpSession session, @RequestBody Map<String, Object> payload) {
        socialService.sendDirectMessage(
                currentUser(request, session),
                stringValue(payload, "toUserId"),
                stringValue(payload, "content")
        );
    }

    @PostMapping("/invites")
    public void sendInvite(HttpServletRequest request, HttpSession session, @RequestBody Map<String, Object> payload) {
        socialService.sendRoomInvite(
                currentUser(request, session),
                stringValue(payload, "targetUserId"),
                stringValue(payload, "roomId")
        );
    }

    @PostMapping("/invites/{inviteId}/respond")
    public Map<String, Object> respondInvite(HttpServletRequest request,
                                             HttpSession session,
                                             @PathVariable Long inviteId,
                                             @RequestBody Map<String, Object> payload) {
        return socialService.respondRoomInvite(currentUser(request, session), inviteId, booleanValue(payload, "accept"));
    }

    private String currentUser(HttpServletRequest request, HttpSession session) {
        SessionUser sessionUser = authService.requireAuthenticatedUser(request, session, false);
        return sessionUser.getUsername();
    }

    private String stringValue(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private boolean booleanValue(Map<String, Object> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
