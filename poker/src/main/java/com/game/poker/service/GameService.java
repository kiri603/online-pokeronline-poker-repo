package com.game.poker.service;

import com.game.poker.model.Card;
import com.game.poker.model.GameRoom;
import com.game.poker.model.Player;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j // 引入 Lombok 的日志注解
@Service
public class GameService {

    @Autowired(required = false) // 如果你还没有写 RuleEngine，可以先加上 required=false 防止报错
    private RuleEngine ruleEngine;

    private static final List<String> SCRIPTED_AI_NAMES = List.of(
            "AI\u73a9\u5bb6-\u8bf8\u845b\u4eae",
            "AI\u73a9\u5bb6-\u5218\u5907",
            "AI\u73a9\u5bb6-\u66f9\u64cd",
            "AI\u73a9\u5bb6-\u5b59\u6743",
            "AI\u73a9\u5bb6-\u5173\u7fbd",
            "AI\u73a9\u5bb6-\u5f20\u98de",
            "AI\u73a9\u5bb6-\u8d75\u4e91",
            "AI\u73a9\u5bb6-\u9a6c\u8d85",
            "AI\u73a9\u5bb6-\u9ec4\u5fe0",
            "AI\u73a9\u5bb6-\u5468\u745c",
            "AI\u73a9\u5bb6-\u53f8\u9a6c\u61ff",
            "AI\u73a9\u5bb6-\u5415\u5e03",
            "AI\u73a9\u5bb6-\u8c82\u8749",
            "AI\u73a9\u5bb6-\u5b59\u5c1a\u9999",
            "AI\u73a9\u5bb6-\u9c81\u8083",
            "AI\u73a9\u5bb6-\u9646\u900a",
            "AI\u73a9\u5bb6-\u8bb8\u891a",
            "AI\u73a9\u5bb6-\u590f\u4faf\u60c7",
            "AI\u73a9\u5bb6-\u5f20\u8fbd",
            "AI\u73a9\u5bb6-\u90ed\u5609",
            "AI\u73a9\u5bb6-\u8340\u5f67",
            "AI\u73a9\u5bb6-\u5e9e\u7edf",
            "AI\u73a9\u5bb6-\u9ec4\u6708\u82f1",
            "AI\u73a9\u5bb6-\u59dc\u7ef4",
            "AI\u73a9\u5bb6-\u9093\u827e",
            "AI\u73a9\u5bb6-\u592a\u53f2\u6148",
            "AI\u73a9\u5bb6-\u7518\u5b81",
            "AI\u73a9\u5bb6-\u5415\u8499",
            "AI\u73a9\u5bb6-\u534e\u4f57",
            "AI\u73a9\u5bb6-\u5178\u97e6"
    );

    // 内存中存储所有活跃的房间（使用线程安全的 Map）
    private final Map<String, GameRoom> roomMap = new ConcurrentHashMap<>();
    public java.util.Map<String, com.game.poker.model.GameRoom> getRoomMap() {
        return roomMap;
    }
    /**
     * 加入或创建房间
     */
    public GameRoom joinRoom(String roomId, String userId, boolean isPrivate, String password) {
        if ("room_manager".equals(userId)) {
            if (!"czq".equals(password)) {
                throw new RuntimeException("REQUIRE_PASSWORD"); // 借用现有的密码框机制
            }
            GameRoom room = roomMap.get(roomId);
            if (room == null) throw new RuntimeException("该房间不存在！");

            // 管理员直接以旁观者身份强行进入，无视任何限制！
            if (!room.getSpectators().contains(userId)) {
                room.getSpectators().add(userId);
            }
            log.warn("🚨 超级管理员已强行进入房间 [{}]", roomId);
            return room;
        }
        // 先尝试获取房间
        GameRoom room = roomMap.get(roomId);

        if (room == null) {
            // ===== 【情况1：房间不存在，我是来创建房间的】 =====
            if (isPrivate && (password == null || password.length() != 4)) {
                throw new RuntimeException("创建私密房间必须设置 4 位密码！");
            }
            room = new GameRoom(roomId);
            room.setPrivateRoom(isPrivate);
            room.setPassword(password);
            roomMap.put(roomId, room);
        } else {
            // ===== 【情况2：房间已存在，我是来加入房间的】 =====
            if (room.isPrivateRoom()) {
                if (password == null || password.isEmpty()) {
                    // 【核心机制】：如果没传密码，抛出特定的魔法口令，前端借此弹出密码框
                    throw new RuntimeException("REQUIRE_PASSWORD");
                } else if (!room.getPassword().equals(password)) {
                    throw new RuntimeException("房间密码错误，请重新输入！");
                }
            }
        }

        // ===== 以下是原有的玩家加入与查重逻辑，保持不变 =====
        // 【新增拦截】：检查名字是否已经被使用（不管是参战者还是旁观者）
        boolean isPlayer = room.getPlayers().stream().anyMatch(p -> p.getUserId().equals(userId));
        boolean isSpectator = room.getSpectators().contains(userId);

        if (isPlayer || isSpectator) {
            // 直接抛出异常拒绝加入
            throw new RuntimeException("昵称 [" + userId + "] 已被占用，请换一个名字！");
        }
        // 检查玩家是否已经在房间内
        if (room.isStarted()) {
            if (!room.getSpectators().contains(userId) && room.getPlayers().stream().noneMatch(p -> p.getUserId().equals(userId))) {
                room.getSpectators().add(userId);
                log.info("游戏已开始，玩家 [{}] 作为旁观者加入房间 [{}]", userId, roomId);
            }
            return room;
        }
        boolean exists = room.getPlayers().stream().anyMatch(p -> p.getUserId().equals(userId));
        if (!exists) {
            if (room.getPlayers().size() >= 4) {
                throw new RuntimeException("房间已满，最多允许4人加入");
            }
            if (room.getPlayers().isEmpty()) {
                room.setOwnerId(userId);
            }
            room.getPlayers().add(new Player(userId));
            log.info("玩家 [{}] 加入了房间 [{}]。当前房间人数: {}", userId, roomId, room.getPlayers().size());
        }
        return room;
    }

    public Player addScriptedBot(String roomId, String ownerId) {
        GameRoom room = roomMap.get(roomId);
        if (room == null) {
            throw new RuntimeException("房间不存在！");
        }
        if (room.isStarted()) {
            throw new RuntimeException("游戏已经开始，无法继续添加脚本AI！");
        }
        if (!ownerId.equals(room.getOwnerId())) {
            throw new RuntimeException("只有房主可以添加脚本AI！");
        }
        if (room.getPlayers().size() >= 4) {
            throw new RuntimeException("房间已满，无法继续添加脚本AI！");
        }

        String botId = nextBotId(room);
        Player bot = new Player(botId, true);
        bot.setStatus("WAITING");
        bot.setReady(true);
        room.getPlayers().add(bot);
        return bot;
    }

    /**
     * 开始游戏，给每人发8张牌
     */
    /**
     * 开始游戏，给每人发8张牌（新增房主和准备状态校验）
     */
    public void startGame(String roomId, String userId) {
        GameRoom room = roomMap.get(roomId);
        if (room == null || room.getPlayers().size() < 2) throw new RuntimeException("人数不足，无法开始游戏");
        if (!userId.equals(room.getOwnerId())) throw new RuntimeException("只有房主可以开始游戏！");
        for (Player p : room.getPlayers()) {
            if (!p.getUserId().equals(room.getOwnerId()) && !p.isReady()) throw new RuntimeException("还有玩家未准备！");
        }

        // ====== 【核心修复】：进入选将阶段时，必须把房间标记为“已开始”！ ======
        if (Boolean.TRUE.equals(room.getSettings().get("enableSkills"))) {
            room.setStarted(true); // <--- 致命遗漏点！不加这句前端会被瞬间闪退回大厅！
            room.setPhase("SKILL_SELECTION");
            room.getSettings().put("skillsSelected", new ConcurrentHashMap<String, String>());
            return;
        }

        doStartGame(room); // 经典模式直接发牌
    }
    public void doStartGame(GameRoom room) {
        room.getSettings().entrySet().removeIf(entry -> entry.getKey().startsWith("gushou_active_"));
        room.getSettings().remove("luanjian_initiator");
        room.getSettings().remove("jdsr_target");
        room.getSettings().remove("jdsr_initiator");
        room.getSettings().remove("game_recorded");
        room.getSettings().put("game_start_time", System.currentTimeMillis());
        room.setStarted(true);
        room.setPhase("PLAYING");
        // 【修改】：加入锦囊牌的发牌逻辑
        room.setCurrentAoeType(null);
        room.getPendingAoePlayers().clear();
        room.getDeck().clear();
        room.getDiscardPile().clear();
        room.getLastPlayedCards().clear();
        room.setLastPlayPlayerId("");
        room.setAoeInitiator("");
        room.initDeck(); // 默认 54 张
        // 如果勾选了锦囊牌选项，额外加入四张牌
        if (Boolean.TRUE.equals(room.getSettings().get("enableScrollCards"))) {
            // ====== 【性能优化：使用享元模式获取锦囊牌】 ======
            room.getDeck().add(Card.getCard("SCROLL", "WJQF", 16)); // 万箭齐发
            room.getDeck().add(Card.getCard("SCROLL", "NMRQ", 17)); // 南蛮入侵
            room.getDeck().add(Card.getCard("SCROLL", "WGFD", 18)); // 五谷丰登
            room.getDeck().add(Card.getCard("SCROLL", "JDSR", 19)); // 借刀杀人
            java.util.Collections.shuffle(room.getDeck());
        }



        // 发牌，每人8张
        for (Player player : room.getPlayers()) {
            List<Card> initialCards = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                Card c = drawCard(room);
                if (c != null) initialCards.add(c);
            }
            player.setHandCards(initialCards);
            player.setStatus("PLAYING");
            player.setHasReplacedCardThisTurn(false);
            player.setHasUsedAoeThisTurn(false);
            player.setHasUsedSkillThisTurn(false);
            // 【苦肉技能】：本局开时重置计数/觉醒/挂起态；同局内跨回合保留
            player.setKurouUseCount(0);
            player.setKurouAwakened(false);
            player.setKurouPendingAwakenDiscard(false);
            // 打印每个玩家的初始手牌
            log.info("玩家 [{}] 获得初始手牌 (共{}张): {}", player.getUserId(), initialCards.size(), initialCards);
        }

        int firstIndex = new java.util.Random().nextInt(room.getPlayers().size());
        room.setCurrentTurnIndex(firstIndex);
        room.setCurrentTurnStartTime(System.currentTimeMillis());
        Player firstPlayer = room.getPlayers().get(firstIndex);
        log.info(">>> 回合开始: 当前轮到玩家 [{}] 行动", firstPlayer.getUserId());
    }
    public void useLuanjian(String roomId, String userId, List<Card> cards) {
        GameRoom room = getRoom(roomId);
        Player p = getPlayer(room, userId);
        if (room == null || p == null || !isPlayerTurn(room, userId)) return;
        if (room.getSettings().containsKey("jdsr_target") && userId.equals(room.getSettings().get("jdsr_target"))) {
            throw new RuntimeException("借刀期间不能使用技能！");
        }
        if (p.isHasUsedSkillThisTurn()) throw new RuntimeException("本回合已使用过技能！");

        // 【修改：变成消耗 2 张黑色牌】
        if (cards.size() != 2) throw new RuntimeException("乱箭只能弃置 2 张牌！");
        for (Card c : cards) {
            if (!c.getSuit().equals("♠") && !c.getSuit().equals("♣")) throw new RuntimeException("乱箭必须使用黑色牌！");
        }

        for (Card playedCard : cards) {
            java.util.Iterator<Card> iterator = p.getHandCards().iterator();
            boolean found = false;
            while (iterator.hasNext()) {
                Card handCard = iterator.next();
                if (handCard.getSuit().equals(playedCard.getSuit()) && handCard.getRank().equals(playedCard.getRank())) {
                    iterator.remove(); found = true; break;
                }
            }
            if (!found) throw new RuntimeException("手牌不足！");
        }

        room.getDiscardPile().addAll(cards);
        p.setHasUsedSkillThisTurn(true);

        room.setCurrentAoeType("WJQF");
        room.setAoeStartTime(System.currentTimeMillis());
        room.setAoeInitiator(userId);

        room.getSettings().put("luanjian_initiator", userId); // 标记乱箭发动者

        for (Player other : room.getPlayers()) {
            if ("PLAYING".equals(other.getStatus())) {
                room.getPendingAoePlayers().add(other.getUserId());
            }
        }

        if (room.getPendingAoePlayers().isEmpty()) {
            endAoePhase(room);
        } else {
            // ====== 【核心修复】：解决空手牌玩家卡死等 10 秒的 Bug ======
            List<String> autoPassPlayers = new ArrayList<>();
            for (String uid : room.getPendingAoePlayers()) {
                Player ap = getPlayer(room, uid);
                if (ap != null && ap.getHandCards().isEmpty()) {
                    autoPassPlayers.add(uid);
                }
            }
            // 自动帮没牌的人点“要不起”
            for (String uid : autoPassPlayers) {
                respondAoe(roomId, uid, null);
            }
        }
    }


    private void checkOverloadAndWin(GameRoom room, Player player) {
        if (player.getHandCards().size() > 14) {
            player.setStatus("LOST");
            room.getDiscardPile().addAll(player.getHandCards());
            player.getHandCards().clear();

            long aliveCount = room.getPlayers().stream().filter(p -> "PLAYING".equals(p.getStatus())).count();
            if (aliveCount == 1) {
                Player winner = room.getPlayers().stream().filter(p -> "PLAYING".equals(p.getStatus())).findFirst().orElse(null);
                if (winner != null) winner.setStatus("WON");
            }
        }
    }
    private void handleNextTurnAfterPass(GameRoom room) {
        if (room.getPlayers().stream().filter(p -> "PLAYING".equals(p.getStatus())).count() <= 1) return;
        nextTurn(room);
        String nextUserId = room.getPlayers().get(room.getCurrentTurnIndex()).getUserId();
        if (nextUserId.equals(room.getLastPlayPlayerId())) {
            if (!room.getLastPlayedCards().isEmpty()) room.getDiscardPile().addAll(room.getLastPlayedCards());
            room.getLastPlayedCards().clear();
            room.setLastPlayPlayerId("");
        }
    }
    private void finishAoeResponse(GameRoom room, String userId) {
        room.getPendingAoePlayers().remove(userId);
        if (room.getPendingAoePlayers().isEmpty()) {
            endAoePhase(room);
        }
    }
    /**
     * 回合前：弃置1张，摸1张
     */
    public boolean replaceCard(String roomId, String userId, Card discardCard) {
        GameRoom room = roomMap.get(roomId);
        Player player = getPlayer(room, userId);

        if (room != null && room.getSettings().containsKey("jdsr_target")
                && userId.equals(room.getSettings().get("jdsr_target"))) {
            throw new RuntimeException("借刀期间不能使用技能！");
        }
        if (room == null || player == null || !isPlayerTurn(room, userId) || player.isHasReplacedCardThisTurn()) {
            log.warn("玩家 [{}] 换牌失败：可能非当前回合，或本回合已经换过牌", userId);
            return false;
        }

        // 移除手牌并加入弃牌堆（增强容错版）
        boolean removed = false;
        Iterator<Card> iterator = player.getHandCards().iterator();
        while (iterator.hasNext()) {
            Card handCard = iterator.next();
            // ====== 【核心修复 2】：制衡时必须同时校验“点数”和“花色”！ ======
            if (handCard.getSuit().equals(discardCard.getSuit()) &&
                    handCard.getRank().trim().equalsIgnoreCase(discardCard.getRank().trim())) {
                iterator.remove();
                removed = true;
                break;
            }
        }

        if (!removed) {
            log.warn("玩家 [{}] 换牌失败：手牌中未找到待弃置的牌 {}", userId, discardCard);
            return false;
        }

        room.getDiscardPile().add(discardCard);

        // 摸一张新牌
        // 摸一张新牌
        Card newCard = drawCard(room);
        if (newCard != null) {
            player.getHandCards().add(newCard);
        }

        player.setHasReplacedCardThisTurn(true);
        log.info("玩家 [{}] 执行了【弃一摸一】。当前手牌 (共{}张): {}", userId, player.getHandCards().size(), player.getHandCards());
        return true;
    }

    /**
     * 玩家不出牌 (Pass) -> 惩罚摸两张
     */
    public void passTurn(String roomId, String userId) {
        GameRoom room = roomMap.get(roomId);
        if (room == null) return;

        // ====== 【性能优化】：加锁处理超时自动弃权并发 ======
        synchronized (room) {
            Player player = getPlayer(room, userId);
            if (player == null || !isPlayerTurn(room, userId)) return;

            // 【防御】：若此刻玩家已经 LOST/WON（例如苦肉爆牌瞬间，前端倒计时滞后触发 PASS），
            // 不能再给他发惩罚牌，直接把回合让给下家。
            if (!"PLAYING".equals(player.getStatus())) {
                log.info("玩家 [{}] 已非 PLAYING（{}），忽略本次 PASS 并推进回合", userId, player.getStatus());
                long aliveCount = room.getPlayers().stream().filter(p -> "PLAYING".equals(p.getStatus())).count();
                if (aliveCount >= 2) {
                    handleNextTurnAfterPass(room);
                }
                return;
            }

            if (room.getSettings().containsKey("jdsr_target") && userId.equals(room.getSettings().get("jdsr_target"))) {
                log.info("玩家 [{}] 被借刀时要不起，摸1张牌", userId);
                Card c = drawCard(room); // ====== 【修改】：仅惩罚 1 张 ======
                if (c != null) player.getHandCards().add(c);
                checkOverloadAndWin(room, player);

                // 检查对方是否被撑死，如果游戏结束直接阻断
                long aliveCount = room.getPlayers().stream().filter(p -> "PLAYING".equals(p.getStatus())).count();
                if (aliveCount <= 1) {
                    for (Player p : room.getPlayers()) {
                        if ("PLAYING".equals(p.getStatus())) p.setStatus("WON");
                    }
                }
                boolean gameEnded = room.getPlayers().stream().anyMatch(p -> "WON".equals(p.getStatus()));
                if (gameEnded) return;

                // 回合时光倒流，回到发起人手里
                String initiatorId = (String) room.getSettings().get("jdsr_initiator");
                room.getSettings().remove("jdsr_target");
                room.getSettings().remove("jdsr_initiator");

                Player initiator = getPlayer(room, initiatorId);
                if (initiator != null && "PLAYING".equals(initiator.getStatus())) {
                    room.setCurrentTurnIndex(room.getPlayers().indexOf(initiator));
                    room.setCurrentTurnStartTime(System.currentTimeMillis()); // 重置20秒
                } else {
                    nextTurn(room); // 发起人离奇掉线了就正常流转
                }
                return; // 拦截成功，结束
            }

            if (room.getLastPlayedCards().isEmpty() || userId.equals(room.getLastPlayPlayerId())) {
                boolean onlyHasScrolls = player.getHandCards().stream().allMatch(c -> "SCROLL".equals(c.getSuit()));
                if (!onlyHasScrolls ) {
                    throw new RuntimeException("自由出牌回合必须出牌！");
                }
            }

            log.info("玩家 [{}] 选择不出 (Pass)，触发惩罚摸 2 张牌...", userId);

            if ("GUANXING".equals(player.getSkill()) && !player.isHasUsedSkillThisTurn()) {
                player.setHasUsedSkillThisTurn(true);
                startGuanxing(room, userId, "PASS");
                return;
            }

            if (room.getSettings().containsKey("gushou_active_" + userId)) {
                room.getSettings().remove("gushou_active_" + userId);
                log.info("玩家 [{}] 固守生效，免除要不起的摸牌惩罚", userId);

                nextTurn(room);

                Player nextPlayer = room.getPlayers().get(room.getCurrentTurnIndex());
                if (nextPlayer.getUserId().equals(room.getLastPlayPlayerId())) {
                    if (room.getLastPlayedCards() != null) room.getLastPlayedCards().clear();
                    room.setLastPlayPlayerId("");
                }
                return;
            }

            for (int i = 0; i < 2; i++) {
                Card c = drawCard(room);
                if (c != null) player.getHandCards().add(c);
            }
            checkOverloadAndWin(room, player);

            long aliveCount = room.getPlayers().stream().filter(p -> "PLAYING".equals(p.getStatus())).count();
            if (aliveCount <= 1) {
                for (Player p : room.getPlayers()) {
                    if ("PLAYING".equals(p.getStatus())) p.setStatus("WON");
                }
            }

            boolean gameEnded = room.getPlayers().stream().anyMatch(p -> "WON".equals(p.getStatus()));
            if (gameEnded) return;

            handleNextTurnAfterPass(room);
        }
    }

    private void startGuanxing(GameRoom room, String userId, String context) {
        List<Card> fourCards = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Card c = drawCard(room);
            if (c != null) fourCards.add(c);
        }
        room.getSettings().put("guanxingCards", fourCards);
        room.getSettings().put("guanxingContext", context);

        // ====== 【核心修复 2：防止 ConcurrentHashMap 存 null 导致崩溃】 ======
        if (room.getCurrentAoeType() != null) {
            room.getSettings().put("savedAoeType", room.getCurrentAoeType());
        } else {
            room.getSettings().remove("savedAoeType");
        }

        if (room.getPendingAoePlayers() != null && !room.getPendingAoePlayers().isEmpty()) {
            room.getSettings().put("savedPendingAoePlayers", new java.util.concurrent.CopyOnWriteArraySet<>(room.getPendingAoePlayers()));
        } else {
            room.getSettings().remove("savedPendingAoePlayers");
        }
        // ==============================================================

        room.setCurrentAoeType("GUANXING");
        room.setAoeStartTime(System.currentTimeMillis());
        room.getPendingAoePlayers().clear();
        room.getPendingAoePlayers().add(userId);
    }

    public void resolveGuanxing(String roomId, String userId, List<Card> selected) {
        GameRoom room = getRoom(roomId);
        if (room == null) return;

        // ====== 【加锁：防止观星阶段的高频点击并发异常】 ======
        synchronized (room) {
            Player p = getPlayer(room, userId);
            if (!room.getPendingAoePlayers().contains(userId) || !"GUANXING".equals(room.getCurrentAoeType())) return;

            // ====== 【核心修复：防止网络卡顿双击导致的 NullPointerException 闪退】 ======
            List<Card> fourCards = (List<Card>) room.getSettings().get("guanxingCards");
            if (fourCards == null) {
                log.warn("观星请求已失效或已被处理，丢弃重复请求。");
                return;
            }

            if (selected.size() != 2) throw new RuntimeException("观星必须选择 2 张牌！");

            List<Card> discardCards = new ArrayList<>(fourCards);
            for (Card sel : selected) {
                p.getHandCards().add(sel);
                discardCards.removeIf(c -> c.getSuit().equals(sel.getSuit()) && c.getRank().equals(sel.getRank()));
            }
            room.getDiscardPile().addAll(discardCards);
            room.getSettings().remove("guanxingCards");

            String context = (String) room.getSettings().remove("guanxingContext");
            String savedAoeType = (String) room.getSettings().remove("savedAoeType");
            room.setCurrentAoeType(savedAoeType);

            java.util.Set<String> savedPending = (java.util.Set<String>) room.getSettings().remove("savedPendingAoePlayers");
            room.getPendingAoePlayers().clear();
            if (savedPending != null) room.getPendingAoePlayers().addAll(savedPending);

            checkOverloadAndWin(room, p);

            if ("PASS".equals(context)) {
                handleNextTurnAfterPass(room);
            } else if ("AOE".equals(context)) {
                finishAoeResponse(room, userId);
            }
            room.setCurrentTurnStartTime(System.currentTimeMillis());
        }
    }
    /**
     * 玩家出牌
     */
    public boolean playCards(String roomId, String userId, List<Card> playedCards) {
        GameRoom room = roomMap.get(roomId);
        if (room == null) return false;

        // ====== 【性能优化】：给房间操作加锁，杜绝并发修改导致 ArrayList 死循环引发的 CPU 满载 ======
        synchronized (room) {
            Player player = getPlayer(room, userId);
            if (player == null || !isPlayerTurn(room, userId)) return false;

            // ====== 【防御】：清除上一次铁骑判定可能残留的 settings ======
            // 若上一次出牌来自 Bot 通道但未能广播/消费 tieqi 字段，会被本次出牌误读。
            room.getSettings().remove("tieqiJudgeCard");
            room.getSettings().remove("tieqiJudgeUserId");
            room.getSettings().remove("tieqiJudgeSuccess");
            room.getSettings().remove("tieqiMaxRedWeight");
            room.getSettings().remove("tieqiSuppressed");

            // ====== 提取当前玩家是否处于被借刀状态 ======
            boolean isJdsrTarget = room.getSettings().containsKey("jdsr_target") && userId.equals(room.getSettings().get("jdsr_target"));

            // 被借刀人绝对禁止使用锦囊牌
            if (isJdsrTarget) {
                for (Card c : playedCards) {
                    if ("SCROLL".equals(c.getSuit())) {
                        throw new RuntimeException("被借刀期间不能使用锦囊牌！");
                    }
                }
            }

            List<Card> cardsToCompare = room.getLastPlayedCards();

            // ====== 【核心修复】：被借刀人必须强制压制桌面牌，绝对不能算作自由出牌回合！ ======
            if (cardsToCompare.isEmpty() || (!isJdsrTarget && userId.equals(room.getLastPlayPlayerId()))) {
                cardsToCompare = null; // 只有在非借刀状态下，轮回自己的牌才算自由出牌
            }

            // ====== 锦囊牌专项处理 ======
            if (playedCards.size() == 1 && "SCROLL".equals(playedCards.get(0).getSuit())) {
                if (player.isHasUsedAoeThisTurn()) {
                    throw new RuntimeException("每回合只能使用一次锦囊牌！请继续打出普通牌或选择要不起。");
                }
                Card aoeCard = playedCards.get(0);

                // ====== 【新增】：借刀杀人的苛刻发动条件 ======
                if ("JDSR".equals(aoeCard.getRank())) {
                    if (cardsToCompare == null || cardsToCompare.isEmpty() || userId.equals(room.getLastPlayPlayerId())) {
                        throw new RuntimeException("借刀杀人仅限桌面上有其他人打出的牌时使用！");
                    }
                }

                // 严谨移除手牌
                boolean removed = false;
                java.util.Iterator<Card> iterator = player.getHandCards().iterator();
                while (iterator.hasNext()) {
                    Card c = iterator.next();
                    if (c.getSuit().equals("SCROLL") && c.getRank().equals(aoeCard.getRank())) {
                        iterator.remove();
                        removed = true; break;
                    }
                }
                if (!removed) throw new RuntimeException("手牌中没有该锦囊牌！");

                // 锦囊牌直接进入弃牌堆，不覆盖桌面
                room.getDiscardPile().add(aoeCard);
                player.setHasUsedAoeThisTurn(true); // 锁定本回合锦囊

                // ====== 【新增】：借刀杀人结算逻辑 ======
                if ("JDSR".equals(aoeCard.getRank())) {
                    Player nextPlayer = null;
                    int currIndex = room.getPlayers().indexOf(player);
                    for (int i = 1; i < room.getPlayers().size(); i++) {
                        Player p = room.getPlayers().get((currIndex + i) % room.getPlayers().size());
                        if ("PLAYING".equals(p.getStatus())) {
                            nextPlayer = p;
                            break;
                        }
                    }
                    if (nextPlayer == null || nextPlayer.getUserId().equals(userId)) {
                        throw new RuntimeException("没有存活的其他玩家可以借刀！");
                    }

                    // 记录主使与替身
                    room.getSettings().put("jdsr_initiator", userId);
                    room.getSettings().put("jdsr_target", nextPlayer.getUserId());

                    // 回合强行移交给“刀”，开始 10 秒出牌倒计时
                    room.setCurrentTurnIndex(room.getPlayers().indexOf(nextPlayer));
                    room.setCurrentTurnStartTime(System.currentTimeMillis());

                    // ====== 【核心修复】：借刀作为最后一张牌打出时，立即获胜 ======
                    // 【进一步修复】：不再把"剩余 PLAYING 玩家"一并提升为 WON。
                    // 发起人本人已经获胜，真正还在场上的对手（= 被借刀的替身）依然是 PLAYING，
                    // 否则 2 人局会让替身也误标成 WON，结算时可能挑错赢家。
                    if (player.getHandCards().isEmpty()) {
                        player.setStatus("WON");
                    }
                    return true;
                }

                room.setCurrentAoeType(aoeCard.getRank());
                room.setAoeStartTime(System.currentTimeMillis());
                room.setAoeInitiator(userId); // 记录发起人

                // 将全场依然存活的玩家加入待响应列表
                if ("WGFD".equals(aoeCard.getRank())) {
                    // ====== 【新增：五谷丰登结算逻辑】 ======
                    List<String> alivePlayers = room.getPlayers().stream()
                            .filter(p -> "PLAYING".equals(p.getStatus()))
                            .map(Player::getUserId)
                            .collect(java.util.stream.Collectors.toList());

                    int initIndex = alivePlayers.indexOf(userId);
                    if (initIndex == -1) initIndex = 0;

                    List<String> wgfdQueue = new ArrayList<>();
                    for (int i = 0; i < alivePlayers.size(); i++) {
                        wgfdQueue.add(alivePlayers.get((initIndex + i) % alivePlayers.size()));
                    }
                    room.getSettings().put("wgfdQueue", wgfdQueue);

                    // 2. 根据存活人数摸牌展示在桌面
                    List<Card> wgfdCards = new ArrayList<>();
                    for (int i = 0; i < alivePlayers.size(); i++) {
                        Card c = drawCard(room);
                        if (c != null) wgfdCards.add(c);
                    }
                    room.getSettings().put("wgfdCards", wgfdCards);

                    // 3. 把第一名玩家加入等待响应队列，并启动 10 秒倒计时
                    room.getPendingAoePlayers().clear();
                    room.getPendingAoePlayers().add(wgfdQueue.get(0));
                } else {
                    // 原本的南蛮万箭：全场存活玩家同时进入队列
                    for (Player p : room.getPlayers()) {
                        if ("PLAYING".equals(p.getStatus())) {
                            room.getPendingAoePlayers().add(p.getUserId());
                        }
                    }
                }

                if (room.getPendingAoePlayers().isEmpty()) {
                    endAoePhase(room);
                } else {
                    // ====== 【核心修复】：如果你打出万箭/南蛮后手牌为0，自动帮你“要不起”摸2张，防止卡死倒计时 ======
                    List<String> autoPassPlayers = new ArrayList<>();
                    for (String uid : room.getPendingAoePlayers()) {
                        Player ap = getPlayer(room, uid);
                        if (ap != null && ap.getHandCards().isEmpty()) {
                            autoPassPlayers.add(uid);
                        }
                    }
                    for (String uid : autoPassPlayers) {
                        respondAoe(roomId, uid, null);
                    }
                }
                return true;
            } else {
                // ====== 【核心保留】：严禁把多张锦囊牌当作对子/顺子等普通牌型打出 ======
                // （这个 else 绝对不能删，否则玩家可以双重出牌作弊）
                for (Card c : playedCards) {
                    if ("SCROLL".equals(c.getSuit())) {
                        throw new RuntimeException("锦囊牌只能单张使用，不可与其他牌组合出牌！");
                    }
                }
            }

            // ====== 普通出牌逻辑 ======
            if (ruleEngine != null && !ruleEngine.isValidPlay(playedCards, cardsToCompare)) {
                return false;
            }

            for (Card playedCard : playedCards) {
                java.util.Iterator<Card> iterator = player.getHandCards().iterator();
                while (iterator.hasNext()) {
                    Card handCard = iterator.next();
                    if (handCard.getSuit().equals(playedCard.getSuit()) &&
                            handCard.getRank().trim().equalsIgnoreCase(playedCard.getRank().trim())) {
                        iterator.remove(); break;
                    }
                }
            }

            if (room.getLastPlayedCards() != null && !room.getLastPlayedCards().isEmpty()) {
                room.getDiscardPile().addAll(room.getLastPlayedCards());
            }

            room.setLastPlayedCards(playedCards);

            // ====== 【新增】：移花接木，借刀成功！修改卡牌所有权 ======
            boolean isJdsrSuccess = false;
            if (room.getSettings().containsKey("jdsr_target") && userId.equals(room.getSettings().get("jdsr_target"))) {
                String initiatorId = (String) room.getSettings().get("jdsr_initiator");
                room.setLastPlayPlayerId(initiatorId); // 强行把牌算在主使头上！

                room.getSettings().remove("jdsr_target");
                room.getSettings().remove("jdsr_initiator");
                isJdsrSuccess = true;
            } else {
                room.setLastPlayPlayerId(userId); // 正常出牌
            }

            // 【只有普通牌】打空手牌，才是真正的直接获胜！
            if (player.getHandCards().isEmpty()) {
                player.setStatus("WON");
                return true;
            }
            int remainCount = player.getHandCards().size();
            if (remainCount == 1 || remainCount == 2) {
                try {
                    room.getSettings().put("cardWarningUserId", userId);
                    room.getSettings().put("cardWarningCount", remainCount);
                } catch (Exception e) {}
            }
            if (room.getSettings().containsKey("gushou_active_" + userId)) {
                if (!isJdsrSuccess) {
                    room.getSettings().remove("gushou_active_" + userId);
                    room.setCurrentAoeType("GUSHOU_DISCARD"); // 借用锦囊倒计时系统挂起
                    room.setAoeStartTime(System.currentTimeMillis());
                    room.getPendingAoePlayers().clear();
                    room.getPendingAoePlayers().add(userId);
                    return true; // 拦截 nextTurn，等待玩家弃牌
                }
            }
            if (isJdsrSuccess) {
                room.setCurrentTurnStartTime(System.currentTimeMillis());
                return true;
            }

            // ====== 【苦肉·觉醒】：已觉醒的玩家，主动打出普通牌型后可选额外弃 1 张黑色牌 ======
            // 天然不触发：锦囊（已在上方 return）、AOE 响应（走 respondAoe）、JDSR 接牌成功（已在上方 return）
            if ("KUROU".equals(player.getSkill()) && player.isKurouAwakened()) {
                boolean hasBlack = player.getHandCards().stream()
                        .anyMatch(c -> "\u2660".equals(c.getSuit())
                                || "\u2663".equals(c.getSuit())
                                || ("JOKER".equals(c.getSuit()) && "\u5c0f\u738b".equals(c.getRank())));
                if (hasBlack) {
                    player.setKurouPendingAwakenDiscard(true);
                    room.setCurrentAoeType("KUROU_AWAKEN_DISCARD");
                    room.setAoeStartTime(System.currentTimeMillis());
                    room.getPendingAoePlayers().clear();
                    room.getPendingAoePlayers().add(userId);
                    log.info("玩家 [{}] 苦肉已觉醒，挂起等待选择额外弃置黑色牌", userId);
                    return true;
                }
            }

            // ====== 【铁骑】：出牌含红色牌时自动判定 ======
            log.info("[TIEQI-DEBUG] 玩家 [{}] 出牌完成, skill={}, 牌={}",
                    userId, player.getSkill(),
                    playedCards.stream()
                            .map(c -> c.getSuit() + c.getRank())
                            .toList());
            if ("TIEQI".equals(player.getSkill())) {
                triggerTieqi(room, player, playedCards);
            }

            nextTurn(room);
            return true;
        }
    }

    /**
     * 铁骑「红色」判定辅助：♥、♦ 以及大王（JOKER·大王）均视为红色。
     * 小王不算红色，不会触发判定也不会作为成功判定牌。
     */
    private static boolean isRedForTieqi(Card c) {
        if (c == null || c.getSuit() == null) return false;
        if ("\u2665".equals(c.getSuit()) || "\u2666".equals(c.getSuit())) return true;
        return "JOKER".equals(c.getSuit()) && "\u5927\u738b".equals(c.getRank());
    }

    /**
     * 【铁骑】被动判定：当出牌包含「红色」牌时，从牌堆顶抽一张判定牌。
     * 红色定义：♥ (U+2665) / ♦ (U+2666) / 大王（JOKER·大王，视为红色）。
     * 判定条件：判定牌为红色且 weight 严格小于本次出牌中最大红色 weight → 成功。
     * 无论成败，判定牌一律进入弃牌堆；成功则在 settings 中写入 tieqiSkipRound 标记，
     * 后续 nextTurn 会清空桌面、跳回发动者；其他玩家视为被「压制」，各罚摸 2 张。
     */
    private void triggerTieqi(GameRoom room, Player player, List<Card> playedCards) {
        List<Card> redCards = playedCards.stream()
                .filter(GameService::isRedForTieqi)
                .toList();
        if (redCards.isEmpty()) {
            // 纯黑色 / 小王 / 锦囊出牌：不触发铁骑
            return;
        }
        int maxRedWeight = redCards.stream().mapToInt(Card::getWeight).max().getAsInt();

        Card judgeCard = drawCard(room);
        if (judgeCard == null) {
            log.warn("房间 [{}] 铁骑判定时牌堆与弃牌堆均空，技能视为失败", room.getRoomId());
            return;
        }
        room.getDiscardPile().add(judgeCard);

        // 判定牌为「红色」(♥ / ♦ / 大王) 且 weight 严格小于本次出牌中最大红色 weight → 成功
        boolean success = isRedForTieqi(judgeCard) && judgeCard.getWeight() < maxRedWeight;

        room.getSettings().put("tieqiJudgeCard", judgeCard);
        room.getSettings().put("tieqiJudgeUserId", player.getUserId());
        room.getSettings().put("tieqiJudgeSuccess", success);
        room.getSettings().put("tieqiMaxRedWeight", maxRedWeight);

        if (success) {
            room.getSettings().put("tieqiSkipRound", player.getUserId());
            log.info("🐴 玩家 [{}] 铁骑判定成功！出牌={} 红牌={} 判定牌={}{} (w={}) < 最大红色 w={}",
                    player.getUserId(),
                    playedCards.stream().map(c -> c.getSuit() + c.getRank()).toList(),
                    redCards.stream().map(c -> c.getSuit() + c.getRank()).toList(),
                    judgeCard.getSuit(), judgeCard.getRank(),
                    judgeCard.getWeight(), maxRedWeight);
        } else {
            log.info("🐴 玩家 [{}] 铁骑判定失败：出牌={} 红牌={} 判定牌={}{} (w={}) 最大红色 w={}",
                    player.getUserId(),
                    playedCards.stream().map(c -> c.getSuit() + c.getRank()).toList(),
                    redCards.stream().map(c -> c.getSuit() + c.getRank()).toList(),
                    judgeCard.getSuit(), judgeCard.getRank(),
                    judgeCard.getWeight(), maxRedWeight);
        }
    }

    // --- 内部辅助方法 ---

    private void nextTurn(GameRoom room) {
        // ====== 【铁骑 · 压制】：判定成功 → 清桌面、所有其他 PLAYING 玩家各罚摸 2 张、轮回发动者 ======
        if (room.getSettings().containsKey("tieqiSkipRound")) {
            String initiatorUid = (String) room.getSettings().remove("tieqiSkipRound");
            if (room.getLastPlayedCards() != null && !room.getLastPlayedCards().isEmpty()) {
                room.getDiscardPile().addAll(room.getLastPlayedCards());
                room.setLastPlayedCards(new ArrayList<>());
            }
            room.setLastPlayPlayerId(null);
            for (int i = 0; i < room.getPlayers().size(); i++) {
                if (initiatorUid.equals(room.getPlayers().get(i).getUserId())) {
                    room.setCurrentTurnIndex(i);
                    break;
                }
            }
            room.setCurrentTurnStartTime(System.currentTimeMillis());
            Player initiator = getPlayer(room, initiatorUid);
            if (initiator != null) {
                initiator.setHasReplacedCardThisTurn(false);
                initiator.setHasUsedAoeThisTurn(false);
                initiator.setHasUsedSkillThisTurn(false);
            }

            // —— 对其他 PLAYING 玩家执行「压制」：各罚摸 2 张 ——
            List<String> suppressed = new ArrayList<>();
            for (Player p : room.getPlayers()) {
                if (p.getUserId().equals(initiatorUid)) continue;
                if (!"PLAYING".equals(p.getStatus())) continue;
                for (int i = 0; i < 2; i++) {
                    Card c = drawCard(room);
                    if (c != null) p.getHandCards().add(c);
                }
                checkOverloadAndWin(room, p);
                suppressed.add(p.getUserId());
            }
            if (!suppressed.isEmpty()) {
                room.getSettings().put("tieqiSuppressed", suppressed);
            }

            log.info("🐴 铁骑·压制：其他 {} 名玩家各摸 2 张，轮回发动者 [{}]，受压制玩家={}",
                    suppressed.size(), initiatorUid, suppressed);
            return;
        }

        int nextIndex = (room.getCurrentTurnIndex() + 1) % room.getPlayers().size();

        // 跳过已经淘汰或获胜的玩家
        int loopCount = 0; // 防止死循环
        while (!"PLAYING".equals(room.getPlayers().get(nextIndex).getStatus()) && loopCount < room.getPlayers().size()) {
            nextIndex = (nextIndex + 1) % room.getPlayers().size();
            loopCount++;
        }

        room.setCurrentTurnIndex(nextIndex);
        room.setCurrentTurnStartTime(System.currentTimeMillis());
        Player nextPlayer = room.getPlayers().get(nextIndex);

        // 允许下一位玩家在新回合换牌
        nextPlayer.setHasReplacedCardThisTurn(false);
        nextPlayer.setHasUsedAoeThisTurn(false); // 【新增】
        nextPlayer.setHasUsedSkillThisTurn(false);
        log.info(">>> 回合流转: 当前轮到玩家 [{}] 行动", nextPlayer.getUserId());
    }

    private Player getPlayer(GameRoom room, String userId) {
        if (room == null) return null;
        return room.getPlayers().stream().filter(p -> p.getUserId().equals(userId)).findFirst().orElse(null);
    }

    private boolean isPlayerTurn(GameRoom room, String userId) {
        return room.getPlayers().get(room.getCurrentTurnIndex()).getUserId().equals(userId);
    }
    public GameRoom getRoom(String roomId) {
        return roomMap.get(roomId);
    }
    // --- 新增：智能抽牌（牌堆为空时自动洗牌） ---
    // --- 新增：智能抽牌（牌堆为空时自动洗牌） ---
    private Card drawCard(GameRoom room) {
        // ====== 【核心修复 1】：加上对象锁，防止并发请求导致弃牌堆被双倍复制 ======
        synchronized (room) {
            if (room.getDeck().isEmpty()) {
                if (room.getDiscardPile().isEmpty()) {
                    log.warn("房间 [{}] 牌堆和弃牌堆均为空，无法继续摸牌！", room.getRoomId());
                    return null;
                }
                log.info("🔄 房间 [{}] 牌堆已空，正在将弃牌堆重新洗牌加入牌堆...", room.getRoomId());

                // 加锁后，这三步操作变成了绝对安全的原子操作
                room.getDeck().addAll(room.getDiscardPile());
                room.getDiscardPile().clear();
                // 洗牌
                java.util.Collections.shuffle(room.getDeck());
                room.getSettings().put("justShuffled", true);
            }
            if (room.getDeck().isEmpty()) return null; // 双重保险
            return room.getDeck().remove(0);
        }
    }

/*    private String nextBotId(GameRoom room) {
        int index = 1;
        while (true) {
            String candidate = "脚本AI-" + index;
            boolean exists = room.getPlayers().stream().anyMatch(player -> candidate.equals(player.getUserId()))
                    || room.getSpectators().contains(candidate);
            if (!exists) {
                return candidate;
            }
            index++;
        }
    }

*/

    private String nextBotId(GameRoom room) {
        List<String> availableNames = new ArrayList<>(SCRIPTED_AI_NAMES);
        availableNames.removeIf(candidate -> room.getPlayers().stream().anyMatch(player -> candidate.equals(player.getUserId()))
                || room.getSpectators().contains(candidate));

        if (!availableNames.isEmpty()) {
            return availableNames.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(availableNames.size()));
        }

        int index = 1;
        while (true) {
            String candidate = "AI\u73a9\u5bb6-\u5019\u8865" + index;
            boolean exists = room.getPlayers().stream().anyMatch(player -> candidate.equals(player.getUserId()))
                    || room.getSpectators().contains(candidate);
            if (!exists) {
                return candidate;
            }
            index++;
        }
    }

    private Player chooseNextOwner(GameRoom room) {
        Player nextHumanOwner = room.getPlayers().stream()
                .filter(player -> !player.isDisconnected() && !player.isBot())
                .findFirst()
                .orElse(null);
        if (nextHumanOwner != null) {
            return nextHumanOwner;
        }
        return room.getPlayers().stream()
                .filter(player -> !player.isDisconnected())
                .findFirst()
                .orElse(null);
    }

    private boolean hasActiveHumanPlayers(GameRoom room) {
        return room.getPlayers().stream().anyMatch(player -> !player.isBot() && !player.isDisconnected());
    }

    // --- 新增：切换准备状态 ---
    public void toggleReady(String roomId, String userId) {
        GameRoom room = roomMap.get(roomId);
        if (room == null || room.isStarted()) return;
        Player p = getPlayer(room, userId);
        if (p != null) p.setReady(!p.isReady());
    }

    // --- 新增：游戏结束后返回等待大厅，重置房间状态 ---
    public void returnToWaitingRoom(String roomId) {

        GameRoom room = roomMap.get(roomId);
        room.getSettings().entrySet().removeIf(entry -> entry.getKey().startsWith("gushou_active_"));
        room.getSettings().remove("luanjian_initiator");
        if (room != null) {
            room.getSettings().entrySet().removeIf(entry -> entry.getKey().startsWith("gushou_active_"));
            room.getSettings().remove("luanjian_initiator");
            room.getSettings().remove("jdsr_target");
            room.getSettings().remove("jdsr_initiator");
            room.getSettings().remove("game_recorded");
            room.getSettings().remove("game_start_time");
            room.setStarted(false);
            room.getTableCards().clear();
            room.setLastPlayUserId("");
            room.getLastPlayedCards().clear();
            room.setLastPlayPlayerId("");
            room.getDiscardPile().clear();
            room.getDeck().clear();
            room.setCurrentTurnIndex(0);
            room.setPhase("WAITING");

            room.getPlayers().removeIf(Player::isDisconnected);
            if (!room.getPlayers().isEmpty() && room.getPlayers().stream().noneMatch(p -> p.getUserId().equals(room.getOwnerId()))) {
                Player nextOwner = chooseNextOwner(room);
                if (nextOwner != null) {
                    room.setOwnerId(nextOwner.getUserId());
                }
            }

            for (Player p : room.getPlayers()) {
                p.getHandCards().clear();
                p.setStatus("WAITING");
                p.setReady(p.isBot());
                p.setHasReplacedCardThisTurn(false);
                p.setHasUsedAoeThisTurn(false);
                p.setHasUsedSkillThisTurn(false);
            }
            java.util.List<String> toRemove = new java.util.ArrayList<>();
            for (String specId : room.getSpectators()) {
                if (room.getPlayers().size() < 4) {
                    room.getPlayers().add(new Player(specId));
                    toRemove.add(specId);
                }
            }
            room.getSpectators().removeAll(toRemove);
            // 重新洗牌
            room.initDeck();
            if (!hasActiveHumanPlayers(room) && room.getSpectators().isEmpty()) {
                roomMap.remove(roomId);
            }
        }
    }
    public java.util.Collection<GameRoom> getAllRooms() {
        return roomMap.values();
    }

    public java.util.Set<String> getAllActiveUserIds() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (GameRoom room : roomMap.values()) {
            for (Player player : room.getPlayers()) {
                ids.add(player.getUserId());
            }
            ids.addAll(room.getSpectators());
        }
        return ids;
    }
    // 【新增】：踢出玩家
/*    public void kickPlayer(String roomId, String ownerId, String targetId) {
        GameRoom room = roomMap.get(roomId);
        if (room != null) {
            if ("room_manager".equals(targetId)) {
                log.warn("⚠️ 拦截操作：玩家 [{}] 试图踢出系统管理员！", ownerId);
                return;
            }
            // 只有房主，或者超级管理员有权限踢人
            if (room.getOwnerId().equals(ownerId) || "room_manager".equals(ownerId)) {
                room.getPlayers().removeIf(p -> p.getUserId().equals(targetId));
                room.getSpectators().remove(targetId);
                log.info("玩家 [{}] 已被踢出房间 [{}]，执行人: {}", targetId, roomId, ownerId);

                // 如果是管理员把最后一个人踢了，直接销毁房间
                if (room.getPlayers().isEmpty() && room.getSpectators().isEmpty()) {
                    roomMap.remove(roomId);
                    log.info("🚨 房间 [{}] 已被管理员清空并销毁！", roomId);
                }
            }
        }
    }
*/

    public void kickPlayer(String roomId, String ownerId, String targetId) {
        GameRoom room = roomMap.get(roomId);
        if (room == null) {
            return;
        }
        if ("room_manager".equals(targetId)) {
            log.warn("owner [{}] attempted to kick the room manager", ownerId);
            return;
        }
        if (!room.getOwnerId().equals(ownerId) && !"room_manager".equals(ownerId)) {
            return;
        }

        room.getPlayers().removeIf(player -> player.getUserId().equals(targetId));
        room.getSpectators().remove(targetId);

        if (!room.getPlayers().isEmpty() && targetId.equals(room.getOwnerId())) {
            Player nextOwner = chooseNextOwner(room);
            if (nextOwner != null) {
                room.setOwnerId(nextOwner.getUserId());
            }
        }

        if (room.getPlayers().isEmpty() && room.getSpectators().isEmpty()) {
            roomMap.remove(roomId);
            return;
        }

        if (!hasActiveHumanPlayers(room) && room.getSpectators().isEmpty()) {
            roomMap.remove(roomId);
        }
    }

    public void safeLeaveRoom(String roomId, String userId) {
        GameRoom room = roomMap.get(roomId);
        if (room == null) return;
        Player p = getPlayer(room, userId);

        // ====== 【核心修复 1：加锁防止玩家退出时与其他操作并发冲突】 ======
        synchronized (room) {
            if (room.isStarted()) {
                if (p != null) {
                    p.setDisconnected(true); // 无论死活，标记为幽灵
                    if ("PLAYING".equals(p.getStatus())) {
                        p.setStatus("LOST");
                        log.info("❌ 玩家 [{}] 游戏中途断线，已被自动淘汰！", userId);

                        if (p.getHandCards() != null) {
                            room.getDiscardPile().addAll(p.getHandCards());
                            p.getHandCards().clear();
                        }
                        room.getPendingAoePlayers().remove(userId);
                        if (room.getCurrentAoeType() != null && room.getPendingAoePlayers().isEmpty()) {
                            endAoePhase(room);
                        }

                        long aliveCount = room.getPlayers().stream().filter(player -> "PLAYING".equals(player.getStatus())).count();
                        if (aliveCount <= 1) {
                            Player lastPlayer = room.getPlayers().stream().filter(player -> "PLAYING".equals(player.getStatus())).findFirst().orElse(null);
                            if (lastPlayer != null) lastPlayer.setStatus("WON");
                        } else {
                            boolean isCurrentTurn = room.getPlayers().get(room.getCurrentTurnIndex()).getUserId().equals(userId);
                            if (userId.equals(room.getLastPlayPlayerId())) {
                                if (!room.getLastPlayedCards().isEmpty()) room.getDiscardPile().addAll(room.getLastPlayedCards());
                                room.getLastPlayedCards().clear();
                                room.setLastPlayPlayerId("");
                            }
                            if (isCurrentTurn && room.getCurrentAoeType() == null) {
                                nextTurn(room);
                                String nextUserId = room.getPlayers().get(room.getCurrentTurnIndex()).getUserId();
                                if (nextUserId.equals(room.getLastPlayPlayerId())) {
                                    if (!room.getLastPlayedCards().isEmpty()) room.getDiscardPile().addAll(room.getLastPlayedCards());
                                    room.getLastPlayedCards().clear();
                                    room.setLastPlayPlayerId("");
                                }
                            }
                        }
                    }
                } else {
                    room.getSpectators().remove(userId);
                }
            } else {
                if (p != null) room.getPlayers().remove(p);
                room.getSpectators().remove(userId);
                if (room.getOwnerId() != null && room.getOwnerId().equals(userId)) {
                    Player nextOwner = chooseNextOwner(room);
                    if (nextOwner != null) room.setOwnerId(nextOwner.getUserId());
                }
            }

            // ====== 【核心修复 2：彻底消灭幽灵房间】 ======
            // 只要房间里没有任何存活状态的人（全部是 disconnected），立刻销毁房间释放内存！
            boolean hasAlivePlayer = room.getPlayers().stream().anyMatch(player -> !player.isDisconnected());
            if (hasAlivePlayer && !hasActiveHumanPlayers(room) && room.getSpectators().isEmpty()) {
                roomMap.remove(roomId);
                return;
            }
            if (!hasAlivePlayer && room.getSpectators().isEmpty()) {
                log.info("♻️ 房间 [{}] 已无真实活人，系统已彻底清空并销毁该幽灵房间！", roomId);
                roomMap.remove(roomId);
            }
        }
    }
    // 【新增】：响应锦囊牌（弃牌或摸牌）
    public void respondAoe(String roomId, String userId, Card discardCard) {
        GameRoom room = roomMap.get(roomId);
        if (room == null) return;

        // ====== 【性能优化】：加锁处理锦囊倒计时超时的并发操作 ======
        synchronized (room) {
            Player player = getPlayer(room, userId);
            if (player == null || !room.getPendingAoePlayers().contains(userId)) return;

            // ====== 检查是否为乱箭发动者响应 ======
            boolean isLuanjianInitiator = "WJQF".equals(room.getCurrentAoeType()) && userId.equals(room.getSettings().get("luanjian_initiator"));

            if (discardCard == null) {
                // 拒绝弃牌 -> 惩罚摸2张
                for (int i = 0; i < 2; i++) {
                    Card c = drawCard(room);
                    if (c != null) player.getHandCards().add(c);
                }
            } else {
                String aoeType = room.getCurrentAoeType();
                boolean isValid = false;

                if (isLuanjianInitiator) {
                    isValid = true; // ====== 【修改】：乱箭发动者可以出任意牌！ ======
                } else if ("NMRQ".equals(aoeType)) {
                    isValid = "♥".equals(discardCard.getSuit()) || "♦".equals(discardCard.getSuit()) || ("JOKER".equals(discardCard.getSuit()) && "大王".equals(discardCard.getRank()));
                } else if ("WJQF".equals(aoeType)) {
                    isValid = "♠".equals(discardCard.getSuit()) || "♣".equals(discardCard.getSuit()) || ("JOKER".equals(discardCard.getSuit()) && "小王".equals(discardCard.getRank()));
                }
                if (!isValid) throw new RuntimeException("弃置的卡牌不符合锦囊要求！");

                java.util.Iterator<Card> iterator = player.getHandCards().iterator();
                while (iterator.hasNext()) {
                    Card c = iterator.next();
                    if (c.getSuit().equals(discardCard.getSuit()) && c.getRank().equals(discardCard.getRank())) {
                        iterator.remove();
                        break;
                    }
                }
                room.getDiscardPile().add(discardCard);
            }

            // ====== 【修改】：乱箭发动者响应结束后，摸 2 张牌 ======
            if (isLuanjianInitiator) {
                for(int i=0; i<2; i++){
                    Card c = drawCard(room);
                    if (c != null) player.getHandCards().add(c);
                }
                room.getSettings().remove("luanjian_initiator"); // 清除标记
            }

            checkOverloadAndWin(room, player);

            // 只有不爆牌的时候才判断胜利和预警（防止提前判断胜利）
            if ("PLAYING".equals(player.getStatus())) {
                if (player.getHandCards().isEmpty()) {
                    player.setStatus("WON");
                } else {
                    int remainCount = player.getHandCards().size();
                    if (remainCount == 1 || remainCount == 2) {
                        try {
                            room.getSettings().put("cardWarningUserId", userId);
                            room.getSettings().put("cardWarningCount", remainCount);
                        } catch (Exception e) {}
                    }
                }
            }
            finishAoeResponse(room, userId);
        }
    }

    private void endAoePhase(GameRoom room) {
        room.setCurrentAoeType(null);
        room.getPendingAoePlayers().clear();

        // 【核心】：重置发起人的 20 秒倒计时！不执行 nextTurn！
        room.setCurrentTurnStartTime(System.currentTimeMillis());

        Player initiator = null;
        if (room.getAoeInitiator() != null && !room.getAoeInitiator().isEmpty()) {
            initiator = getPlayer(room, room.getAoeInitiator());
        }
        room.setAoeInitiator("");

        // 只有当发起锦囊的人凭借这张锦囊打空手牌赢了，或者中途掉线了，才把出牌权移交给下家
        if (initiator != null && !"PLAYING".equals(initiator.getStatus())) {
            nextTurn(room);
        }
    }
    // ====== 【新增：处理五谷丰登选牌与队列传递】 ======
    public void resolveWgfd(String roomId, String userId, Card selectedCard) {
        GameRoom room = getRoom(roomId);
        Player p = getPlayer(room, userId);
        if (!room.getPendingAoePlayers().contains(userId) || !"WGFD".equals(room.getCurrentAoeType())) return;

        List<Card> wgfdCards = (List<Card>) room.getSettings().get("wgfdCards");
        List<String> wgfdQueue = (List<String>) room.getSettings().get("wgfdQueue");
        if (wgfdCards == null || wgfdQueue == null) return;

        // 1. 拿走选择的牌
        boolean found = false;
        java.util.Iterator<Card> it = wgfdCards.iterator();
        while (it.hasNext()) {
            Card c = it.next();
            if (c.getSuit().equals(selectedCard.getSuit()) && c.getRank().equals(selectedCard.getRank())) {
                it.remove();
                p.getHandCards().add(c);
                found = true;
                break;
            }
        }
        if (!found) return; // 非法选择直接忽略

        checkOverloadAndWin(room, p);

        // 2. 传递给下一个人
        if (!wgfdQueue.isEmpty()) wgfdQueue.remove(0);

        while (!wgfdQueue.isEmpty()) {
            Player nextP = getPlayer(room, wgfdQueue.get(0));
            if (nextP == null || !"PLAYING".equals(nextP.getStatus())) {
                wgfdQueue.remove(0); // 跳过中途掉线或死亡的玩家
            } else {
                break;
            }
        }

        // 3. 判断是否结束
        if (wgfdQueue.isEmpty() || wgfdCards.isEmpty()) {
            if (!wgfdCards.isEmpty()) room.getDiscardPile().addAll(wgfdCards); // 多余的牌进弃牌堆
            room.getSettings().remove("wgfdCards");
            room.getSettings().remove("wgfdQueue");
            endAoePhase(room);
        } else {
            room.getPendingAoePlayers().clear();
            room.getPendingAoePlayers().add(wgfdQueue.get(0));
            room.setAoeStartTime(System.currentTimeMillis()); // 重置下一个人的 10 秒倒计时！
        }
    }
    public void discardGushou(String roomId, String userId, List<Card> cards) {
        GameRoom room = getRoom(roomId);
        Player p = getPlayer(room, userId);
        if (!"GUSHOU_DISCARD".equals(room.getCurrentAoeType()) || !room.getPendingAoePlayers().contains(userId)) return;

        for (Card discardCard : cards) {
            java.util.Iterator<Card> it = p.getHandCards().iterator();
            while (it.hasNext()) {
                Card c = it.next();
                if (c.getSuit().equals(discardCard.getSuit()) && c.getRank().equals(discardCard.getRank())) {
                    it.remove();
                    room.getDiscardPile().add(c);
                    break;
                }
            }
        }

        room.setCurrentAoeType(null);
        room.getPendingAoePlayers().clear();

        checkOverloadAndWin(room, p);

        // ====== 【核心修复】：弃牌后检查游戏是否结束 ======
        long aliveCount = room.getPlayers().stream().filter(p1 -> "PLAYING".equals(p1.getStatus())).count();
        if (aliveCount <= 1) {
            for (Player p1 : room.getPlayers()) {
                if ("PLAYING".equals(p1.getStatus())) p1.setStatus("WON");
            }
        }

        boolean gameEnded = room.getPlayers().stream().anyMatch(p1 -> "WON".equals(p1.getStatus()));
        if (gameEnded) return;

        nextTurn(room);

        Player nextPlayer = room.getPlayers().get(room.getCurrentTurnIndex());
        if (nextPlayer.getUserId().equals(room.getLastPlayPlayerId())) {
            if (room.getLastPlayedCards() != null) room.getLastPlayedCards().clear();
            room.setLastPlayPlayerId("");
        }
    }
    public void useGushou(String roomId, String userId) {

        GameRoom room = getRoom(roomId);
        Player player = getPlayer(room, userId);
        if (room == null || player == null || !isPlayerTurn(room, userId)) return;
        if (room.getSettings().containsKey("jdsr_target") && userId.equals(room.getSettings().get("jdsr_target"))) throw new RuntimeException("借刀期间不能使用技能！");

        if (player.isHasUsedSkillThisTurn()) {
            throw new RuntimeException("本回合已使用过技能！");
        }

        player.setHasUsedSkillThisTurn(true);
        log.info("玩家 [{}] 主动发动固守，摸 4 张牌", userId);

        for (int i = 0; i < 4; i++) {
            Card c = drawCard(room);
            if (c != null) player.getHandCards().add(c);
        }
        room.getSettings().put("gushou_active_" + userId, true);

        // 检查是否被撑死淘汰
        if (player.getHandCards().size() > 14) {
            player.setStatus("LOST");
            checkOverloadAndWin(room, player);

            // ====== 【核心修复】：爆牌后，立刻清点存活人数，触发直接躺赢 ======
            long aliveCount = room.getPlayers().stream().filter(p -> "PLAYING".equals(p.getStatus())).count();
            if (aliveCount <= 1) {
                for (Player p : room.getPlayers()) {
                    if ("PLAYING".equals(p.getStatus())) p.setStatus("WON");
                }
            }
        }

        // 如果游戏已经结束，立刻阻断，绝不交出出牌权！
        boolean gameEnded = room.getPlayers().stream().anyMatch(p -> "WON".equals(p.getStatus()));
        if (gameEnded) return;

        nextTurn(room);

        // 手动清空桌面
        Player nextPlayer = room.getPlayers().get(room.getCurrentTurnIndex());
        if (nextPlayer.getUserId().equals(room.getLastPlayPlayerId())) {
            if (room.getLastPlayedCards() != null) room.getLastPlayedCards().clear();
            room.setLastPlayPlayerId("");
        }
    }

    /**
     * 【苦肉】：回合内无限次使用，弃 2 张手牌 + 摸 4 张。
     * 跨回合累计 >= 3 次即永久觉醒（整局保留）。
     * 不消耗 hasUsedSkillThisTurn；借刀目标期间禁用；爆牌（>14）即输。
     * 返回布尔：是否由本次使用触发了觉醒（供 WS 层判断是否广播 SKILL_AWAKEN）。
     */
    public boolean useKurou(String roomId, String userId, List<Card> cards) {
        GameRoom room = getRoom(roomId);
        Player player = getPlayer(room, userId);
        if (room == null || player == null || !isPlayerTurn(room, userId)) {
            throw new RuntimeException("非本人回合，无法使用苦肉！");
        }
        if (room.getSettings().containsKey("jdsr_target") && userId.equals(room.getSettings().get("jdsr_target"))) {
            throw new RuntimeException("借刀期间不能使用技能！");
        }
        if (!"KUROU".equals(player.getSkill())) {
            throw new RuntimeException("你没有选择苦肉技能！");
        }
        // 觉醒后弃黑牌挂起期间禁止再发动苦肉，避免状态错乱
        if (player.isKurouPendingAwakenDiscard()) {
            throw new RuntimeException("请先处理觉醒后的弃置选择！");
        }
        if (cards == null || cards.size() != 2) {
            throw new RuntimeException("苦肉必须弃置 2 张牌！");
        }

        // 严谨地校验并移除手牌（参照 useLuanjian）
        for (Card playedCard : cards) {
            java.util.Iterator<Card> iterator = player.getHandCards().iterator();
            boolean found = false;
            while (iterator.hasNext()) {
                Card handCard = iterator.next();
                if (handCard.getSuit().equals(playedCard.getSuit())
                        && handCard.getRank().trim().equalsIgnoreCase(playedCard.getRank().trim())) {
                    iterator.remove();
                    found = true;
                    break;
                }
            }
            if (!found) throw new RuntimeException("手牌中不存在待弃置的牌！");
        }

        room.getDiscardPile().addAll(cards);

        // 摸 4 张
        for (int i = 0; i < 4; i++) {
            Card c = drawCard(room);
            if (c != null) player.getHandCards().add(c);
        }

        // 累计计数 + 觉醒判定
        boolean awakenTriggered = false;
        player.setKurouUseCount(player.getKurouUseCount() + 1);
        if (player.getKurouUseCount() >= 3 && !player.isKurouAwakened()) {
            player.setKurouAwakened(true);
            awakenTriggered = true;
            log.info("玩家 [{}] 苦肉累计使用 {} 次，触发永久觉醒！",
                    userId, player.getKurouUseCount());
        }
        log.info("玩家 [{}] 发动苦肉：弃 2 摸 4（手牌 {} 张，累计 {} 次{}）",
                userId, player.getHandCards().size(), player.getKurouUseCount(),
                player.isKurouAwakened() ? "，已觉醒" : "");

        // 爆牌（>14）即输，参照固守逻辑
        if (player.getHandCards().size() > 14) {
            checkOverloadAndWin(room, player);
            long aliveCount = room.getPlayers().stream()
                    .filter(p -> "PLAYING".equals(p.getStatus())).count();
            if (aliveCount <= 1) {
                for (Player p : room.getPlayers()) {
                    if ("PLAYING".equals(p.getStatus())) p.setStatus("WON");
                }
            }
            // 【关键修复】：自爆后立刻把回合交给下家，否则当前回合仍挂在死者身上，
            // 倒计时到期会触发前端自动 PASS，把 2 张惩罚牌塞回已经 LOST 的玩家。
            boolean gameEnded = room.getPlayers().stream().anyMatch(p -> "WON".equals(p.getStatus()));
            if (!gameEnded && !"PLAYING".equals(player.getStatus())) {
                // 苦肉阶段本人尚未对当前trick做出反应，复用 pass 的收尾逻辑即可
                // （nextTurn + 若新回合对应 lastPlayPlayerId 则清空桌面）
                handleNextTurnAfterPass(room);
            }
            return awakenTriggered;
        }

        // 注意：回合不流转，允许同一回合再用、再出牌
        return awakenTriggered;
    }

    /**
     * 【苦肉·觉醒】：普通出牌挂起时，玩家选 1 张黑色牌弃置，或传 null 跳过。
     * 结束后 nextTurn。
     */
    public void kurouAwakenDiscard(String roomId, String userId, Card card) {
        GameRoom room = getRoom(roomId);
        Player player = getPlayer(room, userId);
        if (room == null || player == null) return;

        if (!"KUROU_AWAKEN_DISCARD".equals(room.getCurrentAoeType())
                || !room.getPendingAoePlayers().contains(userId)
                || !player.isKurouPendingAwakenDiscard()) {
            throw new RuntimeException("当前不是苦肉觉醒弃牌阶段！");
        }

        if (card != null) {
            String suit = card.getSuit();
            String rank = card.getRank();
            boolean isBlackSuit = "\u2660".equals(suit) || "\u2663".equals(suit);
            boolean isSmallJoker = "JOKER".equals(suit) && "\u5c0f\u738b".equals(rank);
            if (!isBlackSuit && !isSmallJoker) {
                throw new RuntimeException("觉醒弃置必须为黑色牌（♠ / ♣ / 小王）！");
            }
            boolean removed = false;
            java.util.Iterator<Card> iterator = player.getHandCards().iterator();
            while (iterator.hasNext()) {
                Card handCard = iterator.next();
                if (handCard.getSuit().equals(card.getSuit())
                        && handCard.getRank().trim().equalsIgnoreCase(card.getRank().trim())) {
                    iterator.remove();
                    removed = true;
                    break;
                }
            }
            if (!removed) throw new RuntimeException("手牌中不存在该黑色牌！");
            room.getDiscardPile().add(card);
            log.info("玩家 [{}] 苦肉觉醒：额外弃置黑色牌 {}", userId, card);
        } else {
            log.info("玩家 [{}] 苦肉觉醒：跳过额外弃置", userId);
        }

        // 清挂起态
        room.getPendingAoePlayers().remove(userId);
        room.setCurrentAoeType(null);
        room.setAoeStartTime(0);
        player.setKurouPendingAwakenDiscard(false);

        // 胜负兜底（极端情况下打空手牌本已走胜利分支，这里额外再校验一次）
        if (player.getHandCards().isEmpty() && "PLAYING".equals(player.getStatus())) {
            player.setStatus("WON");
        }
        // 【修复】：仅在"尚无胜者"时才做最后存活者兜底；
        // 否则 2 人局中，发动者因觉醒弃黑清空手牌获胜后，对手也会被错误升级为 WON，
        // 导致 publishWinnerIfNeeded / recordCompletedGame 用 findFirst() 选出错误的赢家。
        boolean hasWinner = room.getPlayers().stream().anyMatch(p -> "WON".equals(p.getStatus()));
        if (!hasWinner) {
            long aliveCount = room.getPlayers().stream()
                    .filter(p -> "PLAYING".equals(p.getStatus())).count();
            if (aliveCount <= 1) {
                for (Player p : room.getPlayers()) {
                    if ("PLAYING".equals(p.getStatus())) p.setStatus("WON");
                }
            }
        }
        boolean gameEnded = room.getPlayers().stream().anyMatch(p -> "WON".equals(p.getStatus()));
        if (gameEnded) return;

        nextTurn(room);

        // 复用固守的回合回旋清理：若下一位是桌面牌拥有者，清空桌面
        Player nextPlayer = room.getPlayers().get(room.getCurrentTurnIndex());
        if (nextPlayer.getUserId().equals(room.getLastPlayPlayerId())) {
            if (room.getLastPlayedCards() != null) room.getLastPlayedCards().clear();
            room.setLastPlayPlayerId("");
        }
    }

}
