package com.game.poker.service;

import com.game.poker.auth.AuthException;
import com.game.poker.entity.DirectMessage;
import com.game.poker.entity.FriendRelation;
import com.game.poker.entity.FriendRequest;
import com.game.poker.entity.GameRecord;
import com.game.poker.entity.GameRecordParticipant;
import com.game.poker.entity.RoomInvite;
import com.game.poker.entity.UserAccount;
import com.game.poker.entity.UserStats;
import com.game.poker.model.GameRoom;
import com.game.poker.repository.DirectMessageRepository;
import com.game.poker.repository.FriendRelationRepository;
import com.game.poker.repository.FriendRequestRepository;
import com.game.poker.repository.GameRecordRepository;
import com.game.poker.repository.RoomInviteRepository;
import com.game.poker.repository.UserAccountRepository;
import com.game.poker.repository.UserStatsRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SocialService {
    private static final int PROFILE_RECORD_LIMIT = 50;
    private static final int CHAT_MESSAGE_LIMIT = 50;

    private final UserAccountRepository userAccountRepository;
    private final UserStatsRepository userStatsRepository;
    private final GameRecordRepository gameRecordRepository;
    private final FriendRelationRepository friendRelationRepository;
    private final FriendRequestRepository friendRequestRepository;
    private final DirectMessageRepository directMessageRepository;
    private final RoomInviteRepository roomInviteRepository;
    private final LoginSessionRegistry loginSessionRegistry;
    private final GameService gameService;

    public SocialService(UserAccountRepository userAccountRepository,
                         UserStatsRepository userStatsRepository,
                         GameRecordRepository gameRecordRepository,
                         FriendRelationRepository friendRelationRepository,
                         FriendRequestRepository friendRequestRepository,
                         DirectMessageRepository directMessageRepository,
                         RoomInviteRepository roomInviteRepository,
                         LoginSessionRegistry loginSessionRegistry,
                         GameService gameService) {
        this.userAccountRepository = userAccountRepository;
        this.userStatsRepository = userStatsRepository;
        this.gameRecordRepository = gameRecordRepository;
        this.friendRelationRepository = friendRelationRepository;
        this.friendRequestRepository = friendRequestRepository;
        this.directMessageRepository = directMessageRepository;
        this.roomInviteRepository = roomInviteRepository;
        this.loginSessionRegistry = loginSessionRegistry;
        this.gameService = gameService;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getOverview(String currentUserId) {
        Map<String, Object> body = new LinkedHashMap<>();
        List<Map<String, Object>> friends = buildFriendList(currentUserId);
        List<Map<String, Object>> pendingFriendRequests = buildPendingFriendRequests(currentUserId);
        List<Map<String, Object>> pendingInvites = buildPendingInvites(currentUserId);
        long unreadMessageCount = directMessageRepository.countUnreadByUserId(currentUserId);
        long notificationCount = unreadMessageCount + pendingFriendRequests.size() + pendingInvites.size();

        body.put("notificationCount", notificationCount);
        body.put("unreadMessageCount", unreadMessageCount);
        body.put("pendingFriendRequestCount", pendingFriendRequests.size());
        body.put("pendingInviteCount", pendingInvites.size());
        body.put("friends", friends);
        body.put("pendingFriendRequests", pendingFriendRequests);
        body.put("pendingInvites", pendingInvites);
        return body;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getProfile(String currentUserId) {
        return getProfile(currentUserId, currentUserId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getProfile(String currentUserId, String targetUserId) {
        String target = normalizeUserId(targetUserId);
        boolean self = currentUserId.equals(target);
        if (!self) {
            assertFriends(currentUserId, target);
        }

        UserAccount account = userAccountRepository.findByUsername(target)
                .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "账号不存在"));
        UserStats stats = userStatsRepository.findByUserId(account.getId()).orElseGet(() -> {
            UserStats fallback = new UserStats();
            fallback.setUserId(account.getId());
            fallback.setTotalGames(0);
            fallback.setWins(0);
            fallback.setLosses(0);
            fallback.setExperience(0);
            return fallback;
        });

        List<GameRecord> recentRecords = gameRecordRepository.findRecentByUserId(
                target,
                PageRequest.of(0, PROFILE_RECORD_LIMIT)
        );
        List<Map<String, Object>> recordViews = new ArrayList<>();
        int recentGames = 0;
        int recentWins = 0;

        for (GameRecord record : recentRecords) {
            boolean won = target.equals(record.getWinnerUserId());
            recentGames += 1;
            if (won) {
                recentWins += 1;
            }
            recordViews.add(toRecordView(target, record));
        }

        int experience = LevelSystem.clampExperience(stats.getExperience());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", account.getUsername());
        body.put("nickname", account.getNickname());
        body.put("self", self);
        body.put("registeredAt", account.getCreatedAt());
        body.put("totalGames", stats.getTotalGames());
        body.put("wins", stats.getWins());
        body.put("losses", stats.getLosses());
        body.put("experience", experience);
        body.put("level", LevelSystem.resolveLevel(experience));
        body.put("levelProgressPercent", LevelSystem.progressPercent(experience));
        body.put("currentLevelExp", LevelSystem.currentLevelBaseExperience(experience));
        body.put("nextLevelExp", LevelSystem.nextLevelRequiredExperience(experience));
        body.put("maxLevel", LevelSystem.resolveLevel(experience) >= LevelSystem.maxLevel());
        body.put("recentWinRate", recentGames <= 0 ? 0D : roundToTwo(recentWins * 100D / recentGames));
        body.put("recentWinRateTip", "仅统计最近50场纯真人对局，含脚本AI的对局不计入战绩。");
        body.put("recentRecords", recordViews);
        body.put("friends", self ? buildFriendList(currentUserId) : List.of());
        body.put("pendingFriendRequests", self ? buildPendingFriendRequests(currentUserId) : List.of());
        body.put("pendingInvites", self ? buildPendingInvites(currentUserId) : List.of());
        return body;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> searchUsers(String currentUserId, String keyword) {
        String normalized = keyword == null ? "" : keyword.trim();
        if (normalized.length() < 2) {
            return List.of();
        }
        return userAccountRepository.findTop10ByUsernameContainingIgnoreCaseOrderByUsernameAsc(normalized).stream()
                .filter(user -> !user.getUsername().equals(currentUserId))
                .map(user -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("userId", user.getUsername());
                    item.put("nickname", user.getNickname());
                    item.put("online", loginSessionRegistry.isOnline(user.getUsername()));
                    item.put("alreadyFriend", areFriends(currentUserId, user.getUsername()));
                    item.put("pendingIncoming", latestPendingRequest(user.getUsername(), currentUserId).isPresent());
                    item.put("pendingOutgoing", latestPendingRequest(currentUserId, user.getUsername()).isPresent());
                    return item;
                })
                .toList();
    }

    @Transactional
    public void sendFriendRequest(String currentUserId, String targetUserId) {
        String target = normalizeUserId(targetUserId);
        if (target.equals(currentUserId)) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "不能添加自己为好友");
        }

        UserAccount targetAccount = userAccountRepository.findByUsername(target)
                .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "未找到该账号"));
        if (areFriends(currentUserId, targetAccount.getUsername())) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "你们已经是好友了");
        }

        Optional<FriendRequest> inversePending = latestPendingRequest(targetAccount.getUsername(), currentUserId);
        if (inversePending.isPresent()) {
            FriendRequest request = inversePending.get();
            request.setStatus("ACCEPTED");
            request.setRespondedAt(LocalDateTime.now());
            friendRequestRepository.save(request);
            ensureFriendRelation(currentUserId, targetAccount.getUsername());
            return;
        }

        if (latestPendingRequest(currentUserId, targetAccount.getUsername()).isPresent()) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "好友申请已发送，请等待对方处理");
        }

        FriendRequest request = new FriendRequest();
        request.setFromUserId(currentUserId);
        request.setToUserId(targetAccount.getUsername());
        request.setStatus("PENDING");
        friendRequestRepository.save(request);
    }

    @Transactional
    public void respondFriendRequest(String currentUserId, Long requestId, boolean accept) {
        FriendRequest request = friendRequestRepository.findById(requestId)
                .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "好友申请不存在"));
        if (!currentUserId.equals(request.getToUserId())) {
            throw new AuthException(HttpStatus.FORBIDDEN, "无权处理该好友申请");
        }
        if (!"PENDING".equals(request.getStatus())) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "该好友申请已处理");
        }

        request.setStatus(accept ? "ACCEPTED" : "REJECTED");
        request.setRespondedAt(LocalDateTime.now());
        friendRequestRepository.save(request);
        if (accept) {
            ensureFriendRelation(request.getFromUserId(), request.getToUserId());
        }
    }

    @Transactional
    public void removeFriend(String currentUserId, String targetUserId) {
        String target = normalizeUserId(targetUserId);
        if (target.equals(currentUserId)) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "不能删除自己");
        }
        String userAId = canonicalLeft(currentUserId, target);
        String userBId = canonicalRight(currentUserId, target);
        FriendRelation relation = friendRelationRepository.findByUserAIdAndUserBId(userAId, userBId)
                .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "你们当前还不是好友"));
        friendRelationRepository.delete(relation);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getConversation(String currentUserId, String friendUserId) {
        String friendId = normalizeUserId(friendUserId);
        assertFriends(currentUserId, friendId);
        List<DirectMessage> messages = new ArrayList<>(directMessageRepository.findConversation(
                currentUserId,
                friendId,
                PageRequest.of(0, CHAT_MESSAGE_LIMIT)
        ));
        messages.sort(Comparator.comparing(DirectMessage::getCreatedAt));

        return messages.stream().map(message -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fromUserId", message.getFromUserId());
            item.put("toUserId", message.getToUserId());
            item.put("content", message.getContent());
            item.put("createdAt", message.getCreatedAt());
            item.put("mine", currentUserId.equals(message.getFromUserId()));
            return item;
        }).toList();
    }

    @Transactional
    public void markConversationAsRead(String currentUserId, String friendUserId) {
        directMessageRepository.markConversationAsRead(currentUserId, normalizeUserId(friendUserId), LocalDateTime.now());
    }

    @Transactional
    public void sendDirectMessage(String currentUserId, String targetUserId, String rawContent) {
        String target = normalizeUserId(targetUserId);
        String content = rawContent == null ? "" : rawContent.trim();
        if (content.isEmpty()) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "私聊内容不能为空");
        }
        if (content.length() > 300) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "私聊内容不能超过300字");
        }
        assertFriends(currentUserId, target);

        DirectMessage message = new DirectMessage();
        message.setFromUserId(currentUserId);
        message.setToUserId(target);
        message.setContent(content);
        directMessageRepository.save(message);
    }

    @Transactional
    public void sendRoomInvite(String currentUserId, String targetUserId, String roomId) {
        String target = normalizeUserId(targetUserId);
        assertFriends(currentUserId, target);

        GameRoom room = gameService.getRoom(roomId);
        if (room == null) {
            throw new AuthException(HttpStatus.NOT_FOUND, "房间不存在或已失效");
        }
        boolean isMember = room.getPlayers().stream().anyMatch(player -> currentUserId.equals(player.getUserId()))
                || room.getSpectators().contains(currentUserId);
        if (!isMember) {
            throw new AuthException(HttpStatus.FORBIDDEN, "只有房间内成员才能发起邀请");
        }

        Optional<RoomInvite> latestInvite = roomInviteRepository.findFirstByFromUserIdAndToUserIdOrderByCreatedAtDesc(currentUserId, target);
        if (latestInvite.isPresent()) {
            RoomInvite invite = latestInvite.get();
            if ("PENDING".equals(invite.getStatus())) {
                throw new AuthException(HttpStatus.BAD_REQUEST, "该好友当前已有待处理邀请");
            }
            if ("REJECTED".equals(invite.getStatus())
                    && invite.getRespondedAt() != null
                    && invite.getRespondedAt().isAfter(LocalDateTime.now().minusSeconds(30))) {
                throw new AuthException(HttpStatus.BAD_REQUEST, "对方刚刚拒绝过邀请，请30秒后再试");
            }
        }

        RoomInvite invite = new RoomInvite();
        invite.setFromUserId(currentUserId);
        invite.setToUserId(target);
        invite.setRoomId(room.getRoomId());
        invite.setRoomPassword(room.getPassword());
        invite.setPrivateRoom(room.isPrivateRoom());
        invite.setStatus("PENDING");
        roomInviteRepository.save(invite);
    }

    @Transactional
    public Map<String, Object> respondRoomInvite(String currentUserId, Long inviteId, boolean accept) {
        RoomInvite invite = roomInviteRepository.findById(inviteId)
                .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "邀请不存在"));
        if (!currentUserId.equals(invite.getToUserId())) {
            throw new AuthException(HttpStatus.FORBIDDEN, "无权处理该邀请");
        }
        if (!"PENDING".equals(invite.getStatus())) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "该邀请已处理");
        }

        invite.setStatus(accept ? "ACCEPTED" : "REJECTED");
        invite.setRespondedAt(LocalDateTime.now());
        roomInviteRepository.save(invite);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", accept);
        body.put("roomId", invite.getRoomId());
        body.put("roomPassword", invite.getRoomPassword());
        body.put("privateRoom", invite.isPrivateRoom());
        body.put("fromUserId", invite.getFromUserId());
        return body;
    }

    private List<Map<String, Object>> buildFriendList(String currentUserId) {
        return friendRelationRepository.findAllByUserId(currentUserId).stream()
                .map(relation -> relation.getUserAId().equals(currentUserId) ? relation.getUserBId() : relation.getUserAId())
                .distinct()
                .sorted(Comparator.comparing((String friendId) -> !loginSessionRegistry.isOnline(friendId))
                        .thenComparing(String::compareToIgnoreCase))
                .map(friendId -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("userId", friendId);
                    item.put("online", loginSessionRegistry.isOnline(friendId));
                    item.put("unreadCount", directMessageRepository.countUnreadFromFriend(currentUserId, friendId));
                    return item;
                })
                .toList();
    }

    private List<Map<String, Object>> buildPendingFriendRequests(String currentUserId) {
        return friendRequestRepository.findByToUserIdAndStatusOrderByCreatedAtDesc(currentUserId, "PENDING").stream()
                .map(request -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", request.getId());
                    item.put("fromUserId", request.getFromUserId());
                    item.put("createdAt", request.getCreatedAt());
                    return item;
                })
                .toList();
    }

    private List<Map<String, Object>> buildPendingInvites(String currentUserId) {
        return roomInviteRepository.findByToUserIdAndStatusOrderByCreatedAtDesc(currentUserId, "PENDING").stream()
                .map(invite -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", invite.getId());
                    item.put("fromUserId", invite.getFromUserId());
                    item.put("roomId", invite.getRoomId());
                    item.put("roomPassword", invite.getRoomPassword());
                    item.put("privateRoom", invite.isPrivateRoom());
                    item.put("createdAt", invite.getCreatedAt());
                    return item;
                })
                .toList();
    }

    private Map<String, Object> toRecordView(String currentUserId, GameRecord record) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("roomId", record.getRoomId());
        item.put("mode", record.getMode());
        item.put("modeLabel", modeLabel(record.getMode()));
        item.put("playerCount", record.getPlayerCount());
        item.put("startedAt", record.getStartedAt());
        item.put("endedAt", record.getEndedAt());
        item.put("winnerUserId", record.getWinnerUserId());
        item.put("won", currentUserId.equals(record.getWinnerUserId()));
        item.put("participantIds", record.getParticipants().stream()
                .map(GameRecordParticipant::getUserId)
                .sorted()
                .toList());
        return item;
    }

    private Optional<FriendRequest> latestPendingRequest(String fromUserId, String toUserId) {
        return friendRequestRepository.findFirstByFromUserIdAndToUserIdOrderByCreatedAtDesc(fromUserId, toUserId)
                .filter(request -> "PENDING".equals(request.getStatus()));
    }

    private void assertFriends(String currentUserId, String targetUserId) {
        if (!areFriends(currentUserId, targetUserId)) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "对方还不是你的好友");
        }
    }

    private boolean areFriends(String leftUserId, String rightUserId) {
        String userAId = canonicalLeft(leftUserId, rightUserId);
        String userBId = canonicalRight(leftUserId, rightUserId);
        return friendRelationRepository.findByUserAIdAndUserBId(userAId, userBId).isPresent();
    }

    private void ensureFriendRelation(String leftUserId, String rightUserId) {
        String userAId = canonicalLeft(leftUserId, rightUserId);
        String userBId = canonicalRight(leftUserId, rightUserId);
        if (friendRelationRepository.findByUserAIdAndUserBId(userAId, userBId).isPresent()) {
            return;
        }
        FriendRelation relation = new FriendRelation();
        relation.setUserAId(userAId);
        relation.setUserBId(userBId);
        friendRelationRepository.save(relation);
    }

    private String canonicalLeft(String leftUserId, String rightUserId) {
        return leftUserId.compareToIgnoreCase(rightUserId) <= 0 ? leftUserId : rightUserId;
    }

    private String canonicalRight(String leftUserId, String rightUserId) {
        return leftUserId.compareToIgnoreCase(rightUserId) <= 0 ? rightUserId : leftUserId;
    }

    private String normalizeUserId(String userId) {
        String normalized = userId == null ? "" : userId.trim();
        if (normalized.isBlank()) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "目标账号不能为空");
        }
        return normalized;
    }

    private double roundToTwo(double value) {
        return Math.round(value * 100D) / 100D;
    }

    private String modeLabel(String mode) {
        return switch (mode) {
            case "SCROLL" -> "锦囊";
            case "SKILL" -> "技能";
            case "SCROLL_SKILL" -> "锦囊+技能";
            default -> "经典";
        };
    }
}
