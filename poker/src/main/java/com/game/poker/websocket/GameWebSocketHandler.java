package com.game.poker.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.poker.model.Card;
import com.game.poker.model.GameMessage;
import com.game.poker.model.GameRoom;
import com.game.poker.service.GameService;
import com.game.poker.service.ScriptedAiService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class GameWebSocketHandler extends TextWebSocketHandler {
    private static final long ROOM_EMOJI_COOLDOWN_MS = 8_000L;
    private static final long ROOM_EMOJI_COOLDOWN_JITTER_MS = 3_000L;
    private static final long BOT_EMOJI_COOLDOWN_MS = 18_000L;
    private static final long BOT_EMOJI_COOLDOWN_JITTER_MS = 7_000L;
    private static final long BOT_EMOJI_DELAY_MS = 500L;
    private static final long BOT_EMOJI_DELAY_JITTER_MS = 1_200L;

    private static final String EMOJI_SMILE = "image_emoticon.png";
    private static final String EMOJI_LOVING = "image_emoticon2.png";
    private static final String EMOJI_PLAYFUL = "image_emoticon3.png";
    private static final String EMOJI_ANGRY = "image_emoticon6.png";
    private static final String EMOJI_CRYING = "image_emoticon9.png";
    private static final String EMOJI_WRY = "image_emoticon10.png";
    private static final String EMOJI_CONFUSED = "image_emoticon15.png";
    private static final String EMOJI_SMUG = "image_emoticon16.png";
    private static final String EMOJI_SICK = "image_emoticon17.png";
    private static final String EMOJI_LAUGHING = "image_emoticon22.png";
    private static final String EMOJI_SLY = "image_emoticon23.png";
    private static final String EMOJI_HUGGING = "image_emoticon24.png";
    private static final String EMOJI_STRESSED = "image_emoticon27.png";
    private static final String EMOJI_PLEADING = "image_emoticon28.png";
    private static final String EMOJI_HUFFING = "image_emoticon33.png";
    private static final String EMOJI_HEART = "image_emoticon34.png";
    private static final String EMOJI_BROKEN_HEART = "image_emoticon35.png";
    private static final String EMOJI_ROSE = "image_emoticon36.png";
    private static final String EMOJI_THINKING = "image_emoticon73.png";
    private static final String EMOJI_FINGER_HEART = "image_emoticon88.png";

    @Autowired
    private GameService gameService;

    @Autowired
    private ScriptedAiService scriptedAiService;

    // JSON 转换工具
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 记录 房间ID -> 该房间内所有的 WebSocketSession
    private final Map<String, CopyOnWriteArraySet<WebSocketSession>> roomSessions = new ConcurrentHashMap<>();
    // 记录 SessionID -> 用户ID (用于断开连接时清理)
    private final Map<String, String> sessionUserMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService botExecutor = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Long> botScheduleVersion = new ConcurrentHashMap<>();
    private final Map<String, Long> roomEmojiCooldownUntil = new ConcurrentHashMap<>();
    private final Map<String, Long> botEmojiCooldownUntil = new ConcurrentHashMap<>();
    private final Map<String, String> botLastEmoji = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("新的 WebSocket 连接建立: {}", session.getId());
    }

    @PreDestroy
    public void shutdownBotExecutor() {
        botExecutor.shutdownNow();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        GameMessage gameMsg;
        try {
            gameMsg = objectMapper.readValue(payload, GameMessage.class);
        } catch (Exception e) {
            log.warn("收到非法 JSON 数据，解析失败。内容: {}", payload);
            // 主动向客户端发送错误提示
            if (session.isOpen()) {
                session.sendMessage(new TextMessage("{\"event\": \"ERROR\", \"msg\": \"指令发送失败：JSON 格式不正确，请检查是否有多余的逗号或缺少的括号。\"}"));
            }
            // 直接 return 结束本次处理。由于异常被捕获且没有向外抛出，WebSocket 连接将保持畅通
            return;
        }
        String roomId = gameMsg.getRoomId();
        String userId = gameMsg.getUserId();
        String type = gameMsg.getType();

        sessionUserMap.put(session.getId(), userId);

        try {
            switch (type) {
                case "JOIN_ROOM": {
                    // 解析 payload 为 Map
                    Map<String, Object> data = null;
                    if (gameMsg.getData() instanceof Map) {
                        data = (Map<String, Object>) gameMsg.getData();
                    }

                    // ====== 【核心修复】：使用 Map 标准方法提取参数，安全转换类型 ======
                    boolean isCreating = data != null && Boolean.TRUE.equals(data.get("isCreating"));
                    boolean isPrivate = data != null && Boolean.TRUE.equals(data.get("isPrivate"));
                    String password = (data != null && data.get("password") != null) ? String.valueOf(data.get("password")) : "";

                    com.game.poker.model.GameRoom currentRoom = gameService.getRoomMap().get(roomId);

                    // ====== 严格区分创建与加入逻辑 ======
                    if (isCreating) {
                        // 1. 如果尝试创建，但房间已被别人抢先创建，报错拦截
                        if (currentRoom != null) {
                            session.sendMessage(new TextMessage("{\"event\": \"ERROR\", \"msg\": \"该房间已被他人创建\"}"));
                            return;
                        }
                        // 2. 正常创建房间并应用前端传来的高级设置
                        currentRoom = new com.game.poker.model.GameRoom(roomId);
                        currentRoom.setOwnerId(userId);
                        currentRoom.setPrivateRoom(isPrivate);
                        currentRoom.setPassword(password);

                        // ====== 【核心修复】：处理嵌套的 settings Map ======
                        if (data != null && data.containsKey("settings") && data.get("settings") instanceof Map) {
                            Map<String, Object> settings = (Map<String, Object>) data.get("settings");

                            if (settings.containsKey("enableScrollCards")) {
                                currentRoom.getSettings().put("enableScrollCards", Boolean.TRUE.equals(settings.get("enableScrollCards")));
                            }
                            if (settings.containsKey("enableWildcard")) {
                                currentRoom.getSettings().put("enableWildcard", Boolean.TRUE.equals(settings.get("enableWildcard")));
                            }
                            if (settings.containsKey("enableSkills")) {
                                currentRoom.getSettings().put("enableSkills", Boolean.TRUE.equals(settings.get("enableSkills")));
                            }
                        }
                        gameService.getRoomMap().put(roomId, currentRoom);
                    } else {
                        // 1. 如果是加入已有房间，但房间不存在
                        if (currentRoom == null) {
                            session.sendMessage(new TextMessage("{\"event\": \"ERROR\", \"msg\": \"房间不存在，请返回大厅重新创建\"}"));
                            return;
                        }
                        // 2. 校验私密房间密码
                        if (currentRoom.isPrivateRoom()) {
                            if (!currentRoom.getPassword().equals(password)) {
                                session.sendMessage(new TextMessage("{\"event\": \"ERROR\", \"msg\": \"密码错误\"}"));
                                return;
                            }
                        }
                    }

                    // ====== 正常执行加入与广播 ======
                    try {
                        // 此时房间必定已经存在（要么刚创建，要么已校验），正常执行加入
                        gameService.joinRoom(roomId, userId, isPrivate, password);

                        addSessionToRoom(roomId, session);
                        broadcastToRoom(roomId, new TextMessage("{\"event\": \"USER_JOINED\", \"userId\": \"" + userId + "\"}"));
                        broadcastGameState(roomId);
                    } catch (Exception e) {
                        session.sendMessage(new TextMessage("{\"event\": \"ERROR\", \"msg\": \"" + e.getMessage() + "\"}"));
                        try {
                            session.close();
                        } catch (java.io.IOException ignored) {}
                    }

                    break;
                }

                case "START_GAME":
                    try {
                        gameService.startGame(roomId, userId);
                        GameRoom room = gameService.getRoom(roomId);

                        // ====== 【核心修复】：分流下发指令 ======
                        if (Boolean.TRUE.equals(room.getSettings().get("enableSkills"))) {
                            // 技能模式：只发选将指令！此时绝对不能发 GAME_STARTED 和空手牌！
                            broadcastToRoom(roomId, new TextMessage("{\"event\": \"START_SKILL_SELECTION\"}"));
                        } else {
                            // 经典模式：正常下发开始指令和手牌
                            broadcastToRoom(roomId, new TextMessage("{\"event\": \"GAME_STARTED\"}"));
                            if (room != null) {
                                for (com.game.poker.model.Player p : room.getPlayers()) {
                                    String cardsJson = objectMapper.writeValueAsString(p.getHandCards());
                                    sendToUser(roomId, p.getUserId(), new TextMessage("{\"event\": \"SYNC_HAND\", \"cards\": " + cardsJson + "}"));
                                }
                            }
                        }
                    } catch (Exception e) {
                        session.sendMessage(new TextMessage("{\"event\": \"ERROR\", \"msg\": \"" + e.getMessage() + "\"}"));
                    }
                    broadcastGameState(roomId);
                    break;

                case "REPLACE_CARD": {
                    if (gameMsg.getData() == null) return;
                    Card discardCard = objectMapper.convertValue(gameMsg.getData(), Card.class);
                    boolean replaceSuccess = gameService.replaceCard(roomId, userId, discardCard);
                    if (replaceSuccess) {
                        broadcastToRoom(roomId, new TextMessage("{\"event\": \"PLAYER_REPLACED\", \"userId\": \"" + userId + "\"}"));
                        // 【数据连接核心】：换牌成功后，将最新的手牌同步给该玩家
                        com.game.poker.model.Player p = gameService.getRoom(roomId).getPlayers().stream().filter(u -> u.getUserId().equals(userId)).findFirst().orElse(null);
                        if (p != null) {
                            String cardsJson = objectMapper.writeValueAsString(p.getHandCards());
                            sendToUser(roomId, userId, new TextMessage("{\"event\": \"SYNC_HAND\", \"cards\": " + cardsJson + "}"));
                        }
                    } else {
                        session.sendMessage(new TextMessage("{\"event\": \"ERROR\", \"msg\": \"换牌失败(可能已换过或非你的回合)\"}"));
                    }
                    broadcastGameState(roomId);
                    break;
                }
                // ====== 【新增：处理固守弃牌】 ======
                case "GUSHOU_DISCARD":
                    try {
                        List<Card> cards = objectMapper.convertValue(gameMsg.getData(), new com.fasterxml.jackson.core.type.TypeReference<List<Card>>(){});
                        for (Card c : cards) {
                            String animJson = objectMapper.writeValueAsString(c);
                            broadcastToRoom(roomId, new TextMessage("{\"event\": \"AOE_ANIMATION\", \"userId\": \"" + userId + "\", \"card\": " + animJson + "}"));
                        }
                        gameService.discardGushou(roomId, userId, cards);
                        syncPlayerHand(roomId, userId);

                        // ====== 【核心修复】：补上遗漏的游戏结束广播 ======
                        com.game.poker.model.Player gushouDiscardWinner = gameService.getRoom(roomId).getPlayers().stream()
                                .filter(p -> "WON".equals(p.getStatus())).findFirst().orElse(null);
                        if (gushouDiscardWinner != null) {
                            broadcastToRoom(roomId, new TextMessage("{\"event\": \"GAME_OVER\", \"winner\": \"" + gushouDiscardWinner.getUserId() + "\", \"winningCards\": []}"));
                            GameRoom endRoom = gameService.getRoom(roomId);
                            if (endRoom != null) {
                                endRoom.setStarted(false); // 解锁准备按钮
                                endRoom.getPlayers().forEach(player -> player.setReady(false)); // 强行把所有人打回未准备状态
                            }
                        }

                        broadcastGameState(roomId);
                    } catch (Exception e) {
                        session.sendMessage(new TextMessage("{\"event\": \"ERROR\", \"msg\": \"" + e.getMessage() + "\"}"));
                    }
                    break;

                // ====== 【新增：主动使用固守】 ======
                case "USE_GUSHOU":
                    try {
                        gameService.useGushou(roomId, userId);
                        syncPlayerHand(roomId, userId);
                        broadcastToRoom(roomId, new TextMessage("{\"event\": \"SKILL_USED\", \"userId\": \"" + userId + "\", \"skillName\": \"GUSHOU\"}"));
                        // ====== 【核心修复】：补上遗漏的游戏结束广播 ======
                        com.game.poker.model.Player gushouWinner = gameService.getRoom(roomId).getPlayers().stream()
                                .filter(p -> "WON".equals(p.getStatus())).findFirst().orElse(null);
                        if (gushouWinner != null) {
                            broadcastToRoom(roomId, new TextMessage("{\"event\": \"GAME_OVER\", \"winner\": \"" + gushouWinner.getUserId() + "\", \"winningCards\": []}"));
                            GameRoom endRoom = gameService.getRoom(roomId);
                            if (endRoom != null) {
                                endRoom.setStarted(false); // 解锁准备按钮
                                endRoom.getPlayers().forEach(player -> player.setReady(false)); // 强行把所有人打回未准备状态
                            }
                        }

                        broadcastGameState(roomId);
                    } catch (Exception e) {
                        session.sendMessage(new TextMessage("{\"event\": \"ERROR\", \"msg\": \"" + e.getMessage() + "\"}"));
                    }
                    break;
                case "PLAY_CARD":

                    if (gameMsg.getData() == null) {
                        session.sendMessage(new TextMessage("{\"event\": \"ERROR\", \"msg\": \"缺少出牌数据\"}"));
                        return;
                    }
                    List<Card> playedCards = objectMapper.convertValue(
                            gameMsg.getData(),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, Card.class)
                    );

                    boolean isAoe = playedCards.size() == 1 && "SCROLL".equals(playedCards.get(0).getSuit());

                    try {
                        boolean playSuccess = gameService.playCards(roomId, userId, playedCards);
                        if (playSuccess) {
                            // 【核心表现】：如果打出的是锦囊，不下发 CARDS_PLAYED，这样桌面的牌就不会变，依然保留上一手牌的模样！
                            if (!isAoe) {
                                String cardsJson = objectMapper.writeValueAsString(playedCards);
                                broadcastToRoom(roomId, new TextMessage("{\"event\": \"CARDS_PLAYED\", \"userId\": \"" + userId + "\", \"cards\": " + cardsJson + "}"));
                            } else {
                                String aoeRank = playedCards.get(0).getRank();

                                // ====== 【核心修复】：剥离借刀杀人，让其走 CARDS_PLAYED 通道供前端拦截 ======
                                if ("JDSR".equals(aoeRank)) {
                                    String cardsJson = objectMapper.writeValueAsString(playedCards);
                                    broadcastToRoom(roomId, new TextMessage("{\"event\": \"CARDS_PLAYED\", \"userId\": \"" + userId + "\", \"cards\": " + cardsJson + "}"));
                                } else {
                                    // 其他锦囊正常走 AOE 广播
                                    String aoeName = "NMRQ".equals(aoeRank) ? "南蛮入侵" : "WJQF".equals(aoeRank) ? "万箭齐发" : "五谷丰登";
                                    broadcastToRoom(roomId, new TextMessage("{\"event\": \"AOE_PLAYED\", \"userId\": \"" + userId + "\", \"aoeName\": \"" + aoeName + "\"}"));
                                }
                            }

                            com.game.poker.model.Player p = gameService.getRoom(roomId).getPlayers().stream().filter(u -> u.getUserId().equals(userId)).findFirst().orElse(null);
                            if (p != null) {
                                String handJson = objectMapper.writeValueAsString(p.getHandCards());
                                sendToUser(roomId, userId, new TextMessage("{\"event\": \"SYNC_HAND\", \"cards\": " + handJson + "}"));
                            }
                            if (p != null && "WON".equals(p.getStatus())) {
                                // 如果凭借最后一张锦囊赢了，底牌就下发一个空数组
                                String winCardsJson = objectMapper.writeValueAsString(isAoe ? new ArrayList<>() : playedCards);
                                broadcastToRoom(roomId, new TextMessage("{\"event\": \"GAME_OVER\", \"winner\": \"" + userId + "\", \"winningCards\": " + winCardsJson + "}"));
                                GameRoom endRoom = gameService.getRoom(roomId);
                                if (endRoom != null) {
                                    endRoom.setStarted(false); // 解锁准备按钮
                                    endRoom.getPlayers().forEach(player -> player.setReady(false)); // 强行把所有人打回未准备状态
                                }
                            }
                        } else {
                            session.sendMessage(new TextMessage("{\"event\": \"ERROR\", \"msg\": \"出牌不符合规则或未大过上一手\"}"));
                        }
                    } catch (Exception e) {
                        // 将后端抛出的“只能使用一次”、“只能自由出牌回合使用”的异常直接发给前端弹窗
                        session.sendMessage(new TextMessage("{\"event\": \"ERROR\", \"msg\": \"" + e.getMessage() + "\"}"));
                    }
                    broadcastGameState(roomId);
                    break;

                case "PASS":
                    // 【新增修改 4】：加上 try-catch，并下发 ROUND_RESET 指令
                    try {
                        gameService.passTurn(roomId, userId);
                        GameRoom currentRoom = gameService.getRoom(roomId);

                        if ("GUANXING".equals(currentRoom.getCurrentAoeType()) && currentRoom.getPendingAoePlayers().contains(userId)) {
                            broadcastToRoom(roomId, new TextMessage("{\"event\": \"SKILL_USED\", \"userId\": \"" + userId + "\", \"skillName\": \"GUANXING\"}"));
                            List<Card> fourCards = (List<Card>) currentRoom.getSettings().get("guanxingCards");
                            String cardsJson = objectMapper.writeValueAsString(fourCards);
                            sendToUser(roomId, userId, new TextMessage("{\"event\": \"GUANXING_SHOW\", \"cards\": " + cardsJson + "}"));
                            broadcastGameState(roomId);
                            break;
                        }
                        broadcastToRoom(roomId, new TextMessage("{\"event\": \"PLAYER_PASSED\", \"userId\": \"" + userId + "\"}"));
                        // 如果没有观星，正常执行过牌播报
                        // 摸了两张惩罚牌，需要把最新手牌同步给该玩家
                        syncPlayerHand(roomId, userId);

                        // 检查桌面是否已被清空（说明转了一圈都没人要）
                        if (gameService.getRoom(roomId).getLastPlayedCards().isEmpty()) {
                            broadcastToRoom(roomId, new TextMessage("{\"event\": \"ROUND_RESET\"}"));
                        }
                        com.game.poker.model.Player winner = gameService.getRoom(roomId).getPlayers().stream()
                                .filter(p -> "WON".equals(p.getStatus())).findFirst().orElse(null);
                        if (winner != null) {
                            broadcastToRoom(roomId, new TextMessage("{\"event\": \"GAME_OVER\", \"winner\": \"" + winner.getUserId() + "\", \"winningCards\": []}"));
                            GameRoom endRoom = gameService.getRoom(roomId);
                            if (endRoom != null) {
                                endRoom.setStarted(false); // 解锁准备按钮
                                endRoom.getPlayers().forEach(player -> player.setReady(false)); // 强行把所有人打回未准备状态
                            }
                        }
                    } catch (Exception e) {
                        // 如果违反规则（比如自由出牌回合强行不出），给该玩家弹窗报错
                        session.sendMessage(new TextMessage("{\"event\": \"ERROR\", \"msg\": \"" + e.getMessage() + "\"}"));
                    }
                    broadcastGameState(roomId);
                    break;
                case "SEND_EMOJI":
                    if (gameMsg.getData() != null) {
                        String emojiFileName = gameMsg.getData().toString();
                        // 收到表情指令后，直接原样广播给房间里的所有人（包括发送者自己）
                        broadcastToRoom(roomId, new TextMessage("{\"event\": \"EMOJI_RECEIVED\", \"userId\": \"" + userId + "\", \"emoji\": \"" + emojiFileName + "\"}"));
                    }
                    break;

                case "READY":
                    gameService.toggleReady(roomId, userId);
                    broadcastGameState(roomId);
                    break;
                case "ADD_SCRIPT_AI":
                    try {
                        gameService.addScriptedBot(roomId, userId);
                    } catch (Exception e) {
                        session.sendMessage(new TextMessage("{\"event\": \"ERROR\", \"msg\": \"" + e.getMessage() + "\"}"));
                    }
                    broadcastGameState(roomId);
                    break;
                // 【新增】：接收房主的高级设置更新
                case "UPDATE_SETTINGS":
                    GameRoom sRoom = gameService.getRoom(roomId);
                    if (sRoom != null && sRoom.getOwnerId().equals(userId) && gameMsg.getData() != null) {
                        Map<String, Object> newSettings = (Map<String, Object>) gameMsg.getData();
                        sRoom.getSettings().putAll(newSettings);
                        broadcastGameState(roomId); // 广播给房间所有人
                    }
                    break;
                case "RETURN_TO_ROOM": {
                    GameRoom r = gameService.getRoom(roomId);
                    if (r != null) {
                        // 1. 把点击返回的玩家状态设为“大厅等待”
                        com.game.poker.model.Player p = r.getPlayers().stream().filter(u -> u.getUserId().equals(userId)).findFirst().orElse(null);
                        if (p != null) {
                            p.setStatus("WAITING");
                            p.setReady(false); // 自己返回大厅，重置为未准备
                        }

                        // 2. 检查是否所有还在房间里的活人玩家，都已经返回了等待大厅
                        r.getPlayers().stream()
                                .filter(com.game.poker.model.Player::isBot)
                                .filter(player -> !player.isDisconnected())
                                .forEach(bot -> {
                                    bot.setStatus("WAITING");
                                    bot.setReady(true);
                                });
                        boolean allWaiting = r.getPlayers().stream()
                                .filter(player -> !player.isDisconnected())
                                .allMatch(player -> "WAITING".equals(player.getStatus()));

                        if (allWaiting) {
                            // 大家都回去了，彻底打扫战场清理卡牌
                            gameService.returnToWaitingRoom(roomId);
                            broadcastToRoom(roomId, new TextMessage("{\"event\": \"ROOM_RESET\"}"));
                        } else {
                            // 还有人没回去，只给点击返回的这个人定向发送重置指令！
                            sendToUser(roomId, userId, new TextMessage("{\"event\": \"ROOM_RESET\"}"));
                        }
                        broadcastGameState(roomId);
                    }
                    break;
                }
                case "SELECT_SKILL":{
                    String selectedSkill = gameMsg.getData().toString();
                    GameRoom r = gameService.getRoom(roomId);
                    Map<String, String> skills = (Map<String, String>) r.getSettings().get("skillsSelected");
                    skills.put(userId, selectedSkill);

                    com.game.poker.model.Player pl = r.getPlayers().stream().filter(p -> p.getUserId().equals(userId)).findFirst().orElse(null);
                    if (pl != null) pl.setSkill(selectedSkill);

                    // 如果所有人都选完了，直接开始！
                    if (skills.size() == r.getPlayers().size()) {
                        gameService.doStartGame(r);
                        broadcastToRoom(roomId, new TextMessage("{\"event\": \"GAME_STARTED\"}"));
                        for (com.game.poker.model.Player p : r.getPlayers()) {
                            String cardsJson = objectMapper.writeValueAsString(p.getHandCards());
                            sendToUser(roomId, p.getUserId(), new TextMessage("{\"event\": \"SYNC_HAND\", \"cards\": " + cardsJson + "}"));
                        }
                    }
                    broadcastGameState(roomId);
                    break;}

                // ====== 【新增：使用新技能】 ======
                case "USE_SKILL":{
                    Map<String, Object> skillData = (Map<String, Object>) gameMsg.getData();
                    String skillName = (String) skillData.get("skill");
                    try {
                        if ("LUANJIAN".equals(skillName)) {
                            List<Card> cards = objectMapper.convertValue(skillData.get("cards"), new com.fasterxml.jackson.core.type.TypeReference<List<Card>>(){});
                            gameService.useLuanjian(roomId, userId, cards);
                            broadcastToRoom(roomId, new TextMessage("{\"event\": \"SKILL_USED\", \"userId\": \"" + userId + "\", \"skillName\": \"LUANJIAN\"}"));
                        } else if ("GUANXING".equals(skillName)) {
                            // 【新增】：拦截明面的观星按钮点击，直接引流给 passTurn
                            gameService.passTurn(roomId, userId);
                            GameRoom currentRoom = gameService.getRoom(roomId);
                            if ("GUANXING".equals(currentRoom.getCurrentAoeType()) && currentRoom.getPendingAoePlayers().contains(userId)) {
                                broadcastToRoom(roomId, new TextMessage("{\"event\": \"SKILL_USED\", \"userId\": \"" + userId + "\", \"skillName\": \"GUANXING\"}"));
                                List<Card> fourCards = (List<Card>) currentRoom.getSettings().get("guanxingCards");
                                String cardsJson = objectMapper.writeValueAsString(fourCards);
                                sendToUser(roomId, userId, new TextMessage("{\"event\": \"GUANXING_SHOW\", \"cards\": " + cardsJson + "}"));
                            }
                        }
                        syncPlayerHand(roomId, userId);// 【核心修复】：无论放什么技能，强刷全场手牌！
                        broadcastGameState(roomId);
                    } catch (Exception e) {
                        session.sendMessage(new TextMessage("{\"event\": \"ERROR\", \"msg\": \"" + e.getMessage() + "\"}"));
                    }
                    break;
                }
                // ====== 【新增：五谷丰登选牌确认】 ======
                case "WGFD_SELECT":
                    try {
                        Card selectedCard = objectMapper.convertValue(gameMsg.getData(), Card.class);
                        gameService.resolveWgfd(roomId, userId, selectedCard);
                        syncPlayerHand(roomId, userId);

                        // 检查拿完牌后有没有人爆仓直接结束游戏
                        GameRoom rAfter = gameService.getRoom(roomId);
                        com.game.poker.model.Player wgfdWinner = rAfter.getPlayers().stream().filter(p -> "WON".equals(p.getStatus())).findFirst().orElse(null);
                        if (wgfdWinner != null) {
                            broadcastToRoom(roomId, new TextMessage("{\"event\": \"GAME_OVER\", \"winner\": \"" + wgfdWinner.getUserId() + "\", \"winningCards\": []}"));
                            rAfter.setStarted(false);
                            rAfter.getPlayers().forEach(player -> player.setReady(false));
                        }
                        broadcastGameState(roomId);
                    } catch (Exception e) {
                        session.sendMessage(new TextMessage("{\"event\": \"ERROR\", \"msg\": \"" + e.getMessage() + "\"}"));
                    }
                    break;
                // ====== 【新增：观星选牌确认】 ======

                case "GUANXING_SELECT":
                    try {
                        List<Card> selectedCards = objectMapper.convertValue(gameMsg.getData(), new com.fasterxml.jackson.core.type.TypeReference<List<Card>>(){});
                        gameService.resolveGuanxing(roomId, userId, selectedCards);
                        GameRoom roomAfter = gameService.getRoom(roomId);

                        // 观星选完牌后，告诉全场你相当于执行了一次“要不起”
                        broadcastToRoom(roomId, new TextMessage("{\"event\": \"PLAYER_PASSED\", \"userId\": \"" + userId + "\"}"));
                        syncPlayerHand(roomId, userId);
                        if (roomAfter.getLastPlayedCards().isEmpty() && roomAfter.getCurrentAoeType() == null) {
                            broadcastToRoom(roomId, new TextMessage("{\"event\": \"ROUND_RESET\"}"));
                        }
                        com.game.poker.model.Player pWinner = roomAfter.getPlayers().stream().filter(p -> "WON".equals(p.getStatus())).findFirst().orElse(null);
                        if (pWinner != null) {
                            broadcastToRoom(roomId, new TextMessage("{\"event\": \"GAME_OVER\", \"winner\": \"" + pWinner.getUserId() + "\", \"winningCards\": []}"));
                            roomAfter.setStarted(false);
                            roomAfter.getPlayers().forEach(player -> player.setReady(false));
                        }
                        broadcastGameState(roomId);
                    } catch (Exception e) {
                        session.sendMessage(new TextMessage("{\"event\": \"ERROR\", \"msg\": \"" + e.getMessage() + "\"}"));
                    }
                    break;
                case "KICK_PLAYER":
                    String targetId = gameMsg.getData().toString();
                    gameService.kickPlayer(roomId, userId, targetId);
                    // 单独给房间所有人广播踢人事件，前端匹配到了 targetId 就会自己断开
                    broadcastToRoom(roomId, new TextMessage("{\"event\": \"KICKED\", \"targetId\": \"" + targetId + "\"}"));
                    broadcastGameState(roomId);
                    break;
                // 【新增】：处理锦囊牌响应
                // 【修改】：处理锦囊牌响应，增加异常拦截与特效播报
                case "RESPOND_AOE":{
                    Card discardCard = null;
                    if (gameMsg.getData() != null) {
                        discardCard = objectMapper.convertValue(gameMsg.getData(), Card.class);
                    }
                    try {
                        gameService.respondAoe(roomId, userId, discardCard);
                        GameRoom currentRoom = gameService.getRoom(roomId);

                        // ====== 【核心修复】：删除了冗余的 GUANXING 判断，直接走正常的出牌播报 ======

                        if (discardCard != null) {
                            // 如果成功弃牌，广播飞牌动画事件
                            String animJson = objectMapper.writeValueAsString(discardCard);
                            broadcastToRoom(roomId, new TextMessage("{\"event\": \"AOE_ANIMATION\", \"userId\": \"" + userId + "\", \"card\": " + animJson + "}"));
                        } else {
                            // 如果不弃牌（被罚摸两张），全服广播“要不起”动画特效！
                            broadcastToRoom(roomId, new TextMessage("{\"event\": \"PLAYER_PASSED\", \"userId\": \"" + userId + "\"}"));
                        }

                        // 刷新自己手牌
                        syncPlayerHand(roomId, userId);
                        // 检测是否有人因为爆仓产生赢家
                        com.game.poker.model.Player aoeWinner = gameService.getRoom(roomId).getPlayers().stream()
                                .filter(p -> "WON".equals(p.getStatus())).findFirst().orElse(null);
                        if (aoeWinner != null) {
                            broadcastToRoom(roomId, new TextMessage("{\"event\": \"GAME_OVER\", \"winner\": \"" + aoeWinner.getUserId() + "\", \"winningCards\": []}"));
                            GameRoom endRoom = gameService.getRoom(roomId);
                            if (endRoom != null) {
                                endRoom.setStarted(false); // 解锁准备按钮
                                endRoom.getPlayers().forEach(player -> player.setReady(false)); // 强行把所有人打回未准备状态
                            }
                        }
                        broadcastGameState(roomId);

                    } catch (Exception e) {
                        session.sendMessage(new TextMessage("{\"event\": \"ERROR\", \"msg\": \"" + e.getMessage() + "\"}"));
                    }
                    break;
                }
                // ====== 【新增】：超级管理员强制解散指令 ======
                case "DISBAND_ROOM":
                    if ("room_manager".equals(userId)) {
                        GameRoom roomToDisband = gameService.getRoomMap().remove(roomId);
                        if (roomToDisband != null) {
                            // 遍历房间里的所有人（包括玩家和旁观者），给他们群发 KICKED 指令强行清退！
                            for (com.game.poker.model.Player p : roomToDisband.getPlayers()) {
                                sendToUser(roomId, p.getUserId(), new TextMessage("{\"event\": \"KICKED\", \"targetId\": \"" + p.getUserId() + "\"}"));
                            }
                            for (String spec : roomToDisband.getSpectators()) {
                                sendToUser(roomId, spec, new TextMessage("{\"event\": \"KICKED\", \"targetId\": \"" + spec + "\"}"));
                            }
                            log.info("🚨 房间 [{}] 已被超级管理员强制解散并清空！", roomId);
                        }
                    }
                    break;

                default:
                    session.sendMessage(new TextMessage("{\"event\": \"ERROR\", \"msg\": \"未知的指令类型: " + type + "\"}"));
            }
        } catch (Exception e) {
            log.error("处理玩家 [{}] 的请求时发生服务器内部错误", userId, e);
            if (session.isOpen()) {
                session.sendMessage(new TextMessage("{\"event\": \"ERROR\", \"msg\": \"服务器内部错误，请检查请求参数是否完整\"}"));
            }
        }
    }

    // --- 新增：处理游戏强行中止 ---
//    private void handleGameAbort(String roomId, String causeUser) throws Exception {
//        // 重置游戏，踢出掉线玩家
//        gameService.resetGame(roomId, causeUser);
//        // 通知剩下的玩家：游戏结束，回到大厅
//        broadcastToRoom(roomId, new TextMessage("{\"event\": \"GAME_ABORTED\", \"msg\": \"玩家 [" + causeUser + "] 断开连接/退出了房间。当前对局已中止，已返回等待大厅。\"}"));
//        broadcastGameState(roomId); // 同步最新的大厅状态
//    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = sessionUserMap.remove(session.getId());
        if (userId != null) {
            for (Map.Entry<String, CopyOnWriteArraySet<WebSocketSession>> entry : roomSessions.entrySet()) {
                if (entry.getValue().contains(session)) {
                    String roomId = entry.getKey();
                    entry.getValue().remove(session);

                    try {
                        GameRoom room = gameService.getRoom(roomId);
                        if (room != null) {
                            com.game.poker.model.Player p = room.getPlayers().stream().filter(u -> u.getUserId().equals(userId)).findFirst().orElse(null);
                            boolean isPlaying = room.isStarted() && p != null && "PLAYING".equals(p.getStatus());

                            gameService.safeLeaveRoom(roomId, userId);
                            if (gameService.getRoom(roomId) == null) {
                                roomSessions.remove(roomId);
                                break;
                            }
                            if (isPlaying) {
                                broadcastToRoom(roomId, new TextMessage("{\"event\": \"ERROR\", \"msg\": \"玩家 [" + userId + "] 中途逃跑，已被自动淘汰！\"}"));

                                com.game.poker.model.Player winner = room.getPlayers().stream()
                                        .filter(player -> "WON".equals(player.getStatus())).findFirst().orElse(null);
                                if (winner != null) {
                                    broadcastToRoom(roomId, new TextMessage("{\"event\": \"GAME_OVER\", \"winner\": \"" + winner.getUserId() + "\", \"winningCards\": []}"));
                                    GameRoom endRoom = gameService.getRoom(roomId);
                                    if (endRoom != null) {
                                        endRoom.setStarted(false); // 解锁准备按钮
                                        endRoom.getPlayers().forEach(player -> player.setReady(false)); // 强行把所有人打回未准备状态
                                    }
                                } else if (room.getLastPlayedCards().isEmpty()) {
                                    // 桌面被清空，通知下一个人自由出牌
                                    broadcastToRoom(roomId, new TextMessage("{\"event\": \"ROUND_RESET\"}"));
                                }
                            }

                            // 恢复旧版，只做基础的状态广播
                            broadcastGameState(roomId);
                        }
                    } catch (Exception e) {
                        log.error("处理退出失败", e);
                    }
                    break;
                }
            }
        }
    }

    // --- 广播辅助方法 ---
    private void addSessionToRoom(String roomId, WebSocketSession session) {
        roomSessions.computeIfAbsent(roomId, k -> new CopyOnWriteArraySet<>()).add(session);
    }

    private void broadcastToRoom(String roomId, TextMessage message) throws Exception {
        CopyOnWriteArraySet<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions != null) {
            for (WebSocketSession s : sessions) {
                if (s.isOpen()) {
                    s.sendMessage(message);
                }
            }
        }
    }
    // --- 新增：仅向指定玩家定向发送消息（保护手牌隐私） ---
    private void sendToUser(String roomId, String userId, TextMessage message) throws Exception {
        CopyOnWriteArraySet<WebSocketSession> sessions = roomSessions.get(roomId);
        if (sessions != null) {
            for (WebSocketSession s : sessions) {
                // 找到该玩家对应的专属连接
                if (userId.equals(sessionUserMap.get(s.getId())) && s.isOpen()) {
                    s.sendMessage(message);
                }
            }
        }
    }
    private void broadcastGameState(String roomId) throws Exception {
        GameRoom room = gameService.getRoom(roomId);
        if (room == null) return;
        String warningUserId = null;
        Integer warningCount = null;

        String jsonPayload; // 将 JSON 字符串的组装提取出来

        // ====== 【极限并发优化：房间级原子锁，彻底杜绝 50 人在线时的遍历闪退】 ======
        synchronized (room) {
            if (Boolean.TRUE.equals(room.getSettings().get("justShuffled"))) {
                broadcastToRoom(roomId, new TextMessage("{\"event\": \"DECK_SHUFFLED\"}"));
                room.getSettings().remove("justShuffled");
            }

            if (room.getSettings().containsKey("cardWarningUserId")) {
                warningUserId = (String) room.getSettings().get("cardWarningUserId");
                warningCount = (Integer) room.getSettings().get("cardWarningCount");
                broadcastToRoom(roomId, new TextMessage(
                        "{\"event\": \"CARD_WARNING\", \"userId\": \"" + warningUserId + "\", \"count\": " + warningCount + "}"
                ));
                room.getSettings().remove("cardWarningUserId");
                room.getSettings().remove("cardWarningCount");
            }

            List<Map<String, Object>> playersInfo = new ArrayList<>();
            for (com.game.poker.model.Player p : room.getPlayers()) {
                Map<String, Object> pInfo = new java.util.HashMap<>();
                pInfo.put("userId", p.getUserId());
                pInfo.put("cardCount", p.getHandCards().size());
                pInfo.put("status", p.getStatus());
                pInfo.put("isReady", p.isReady());
                pInfo.put("isBot", p.isBot());
                pInfo.put("skill", p.getSkill());
                playersInfo.add(pInfo);
            }

            Map<String, Object> state = new java.util.HashMap<>();
            state.put("event", "SYNC_STATE");
            state.put("ownerId", room.getOwnerId());
            state.put("serverTime", System.currentTimeMillis());
            state.put("currentTurnStartTime", room.getCurrentTurnStartTime());

            Map<String, Object> safeSettings = new java.util.HashMap<>();
            safeSettings.put("enableScrollCards", room.getSettings().get("enableScrollCards"));
            safeSettings.put("enableWildcard", room.getSettings().get("enableWildcard"));
            safeSettings.put("enableSkills", room.getSettings().get("enableSkills"));
            safeSettings.put("skillsSelected", room.getSettings().get("skillsSelected"));

            if ("WGFD".equals(room.getCurrentAoeType())) {
                safeSettings.put("wgfdCards", room.getSettings().get("wgfdCards"));
                safeSettings.put("wgfdQueue", room.getSettings().get("wgfdQueue"));
            }
            if (room.getSettings().containsKey("jdsr_target")) {
                safeSettings.put("jdsr_target", room.getSettings().get("jdsr_target"));
                safeSettings.put("jdsr_initiator", room.getSettings().get("jdsr_initiator"));
            }

            state.put("settings", safeSettings);

            String currentTurnUser = "";
            if (!room.getPlayers().isEmpty()) {
                int safeIndex = room.getCurrentTurnIndex();
                if (safeIndex >= 0 && safeIndex < room.getPlayers().size()) {
                    currentTurnUser = room.getPlayers().get(safeIndex).getUserId();
                } else {
                    currentTurnUser = room.getPlayers().get(0).getUserId();
                }
            }

            state.put("currentTurn", currentTurnUser);
            state.put("players", playersInfo);
            state.put("spectators", room.getSpectators());
            state.put("isStarted", room.isStarted());
            state.put("tableCards", room.getLastPlayedCards());
            state.put("lastPlayPlayer", room.getLastPlayPlayerId());
            state.put("currentAoeType", room.getCurrentAoeType());
            state.put("pendingAoePlayers", room.getPendingAoePlayers());
            state.put("aoeStartTime", room.getAoeStartTime());
            state.put("aoeInitiator", room.getAoeInitiator());
            state.put("luanjianInitiator", room.getSettings().get("luanjian_initiator"));

            // 在锁内部进行安全的序列化
            jsonPayload = objectMapper.writeValueAsString(state);
        }

        // 【网络优化】：把发送数据的动作放在锁外面，防止阻塞业务逻辑！
        broadcastToRoom(roomId, new TextMessage(jsonPayload));
        if (warningUserId != null && warningCount != null) {
            maybeReactToCardWarning(roomId, warningUserId, warningCount);
        }
        scheduleBotAction(roomId);
    }
    // ====== 【新增：全场手牌强制同步器】 ======
    private void scheduleBotAction(String roomId) {
        GameRoom room = gameService.getRoom(roomId);
        if (room == null) {
            botScheduleVersion.remove(roomId);
            return;
        }
        boolean hasBot = room.getPlayers().stream().anyMatch(com.game.poker.model.Player::isBot);
        if (!hasBot || !room.isStarted()) {
            return;
        }

        long version = botScheduleVersion.merge(roomId, 1L, Long::sum);
        botExecutor.schedule(() -> {
            Long latestVersion = botScheduleVersion.get(roomId);
            if (latestVersion == null || latestVersion.longValue() != version) {
                return;
            }
            try {
                runBotAction(roomId);
            } catch (Exception e) {
                log.error("scripted bot action failed for room {}", roomId, e);
            }
        }, 2, TimeUnit.SECONDS);
    }

    private void runBotAction(String roomId) throws Exception {
        GameRoom room = gameService.getRoom(roomId);
        if (room == null || !room.isStarted()) {
            botScheduleVersion.remove(roomId);
            return;
        }

        if ("SKILL_SELECTION".equals(room.getPhase())) {
            handleBotSkillSelection(roomId);
            return;
        }

        if (room.getCurrentAoeType() != null) {
            handleBotAoeAction(roomId, room.getCurrentAoeType());
            return;
        }

        handleBotTurn(roomId);
    }

    @SuppressWarnings("unchecked")
    private void handleBotSkillSelection(String roomId) throws Exception {
        GameRoom room = gameService.getRoom(roomId);
        if (room == null || !"SKILL_SELECTION".equals(room.getPhase())) {
            return;
        }

        Map<String, String> skillsSelected;
        Object rawSkills = room.getSettings().get("skillsSelected");
        if (rawSkills instanceof Map<?, ?> rawMap) {
            skillsSelected = (Map<String, String>) rawMap;
        } else {
            skillsSelected = new ConcurrentHashMap<>();
            room.getSettings().put("skillsSelected", skillsSelected);
        }

        com.game.poker.model.Player bot = room.getPlayers().stream()
                .filter(com.game.poker.model.Player::isBot)
                .filter(player -> !skillsSelected.containsKey(player.getUserId()))
                .findFirst()
                .orElse(null);
        if (bot == null) {
            return;
        }

        String skill = scriptedAiService.chooseSkill(room, bot);
        if (skill == null || skill.isBlank()) {
            skill = "ZHIHENG";
        }

        skillsSelected.put(bot.getUserId(), skill);
        bot.setSkill(skill);

        if (skillsSelected.size() == room.getPlayers().size()) {
            gameService.doStartGame(room);
            broadcastToRoom(roomId, new TextMessage("{\"event\": \"GAME_STARTED\"}"));
            syncAllHands(roomId);
        }

        broadcastGameState(roomId);
    }

    private void handleBotAoeAction(String roomId, String aoeType) throws Exception {
        GameRoom room = gameService.getRoom(roomId);
        if (room == null || aoeType == null) {
            return;
        }

        com.game.poker.model.Player bot = room.getPlayers().stream()
                .filter(com.game.poker.model.Player::isBot)
                .filter(player -> room.getPendingAoePlayers().contains(player.getUserId()))
                .findFirst()
                .orElse(null);
        if (bot == null) {
            return;
        }

        switch (aoeType) {
            case "GUANXING":
                handleBotGuanxing(roomId, bot);
                break;
            case "WGFD":
                handleBotWgfd(roomId, bot);
                break;
            case "GUSHOU_DISCARD":
                handleBotGushouDiscard(roomId, bot);
                break;
            default:
                handleBotRespondAoe(roomId, bot);
                break;
        }
    }

    private void handleBotTurn(String roomId) throws Exception {
        GameRoom room = gameService.getRoom(roomId);
        if (room == null || room.getCurrentAoeType() != null || room.getPlayers().isEmpty()) {
            return;
        }

        int turnIndex = room.getCurrentTurnIndex();
        if (turnIndex < 0 || turnIndex >= room.getPlayers().size()) {
            return;
        }

        com.game.poker.model.Player bot = room.getPlayers().get(turnIndex);
        if (!bot.isBot() || !"PLAYING".equals(bot.getStatus())) {
            return;
        }

        ScriptedAiService.TurnDecision decision = scriptedAiService.decideTurn(room, bot);
        if (decision == null) {
            decision = ScriptedAiService.TurnDecision.pass();
        }

        switch (decision.getType()) {
            case PLAY:
                if (!decision.getCards().isEmpty()) {
                    handleBotPlay(roomId, bot, decision.getCards());
                }
                break;
            case PASS:
                handleBotPass(roomId, bot);
                break;
            case REPLACE:
                if (!decision.getCards().isEmpty()) {
                    handleBotReplace(roomId, bot, decision.getCards().get(0));
                }
                break;
            case USE_SKILL:
                if ("LUANJIAN".equals(decision.getSkill())) {
                    handleBotLuanjian(roomId, bot, decision.getCards());
                }
                break;
            case USE_GUSHOU:
                handleBotUseGushou(roomId, bot);
                break;
            default:
                break;
        }
    }

    private enum BotEmojiScenario {
        TRICK_ATTACK,
        AGGRESSIVE_PLAY,
        TACTICAL_PLAY,
        PRESSURED_PASS,
        DEFENSE_SUCCESS,
        DEFENSE_FAIL,
        CLOSE_TO_WIN,
        OPPONENT_IN_DANGER,
        VICTORY
    }

    private void maybeSendBotEmoji(String roomId, com.game.poker.model.Player bot, BotEmojiScenario scenario) {
        if (bot == null || !bot.isBot()) {
            return;
        }

        String emoji = chooseBotEmoji(roomId, bot, scenario);
        if (emoji == null) {
            return;
        }

        long delayMs = BOT_EMOJI_DELAY_MS + ThreadLocalRandom.current().nextLong(BOT_EMOJI_DELAY_JITTER_MS + 1);
        botExecutor.schedule(() -> {
            try {
                GameRoom room = gameService.getRoom(roomId);
                if (room == null) {
                    return;
                }

                boolean stillPresent = room.getPlayers().stream()
                        .anyMatch(player -> bot.getUserId().equals(player.getUserId()));
                if (!stillPresent) {
                    return;
                }

                broadcastToRoom(roomId, new TextMessage(
                        "{\"event\": \"EMOJI_RECEIVED\", \"userId\": \"" + bot.getUserId() + "\", \"emoji\": \"" + emoji + "\"}"
                ));
            } catch (Exception e) {
                log.warn("failed to send bot emoji for room {} and user {}", roomId, bot.getUserId(), e);
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private String chooseBotEmoji(String roomId, com.game.poker.model.Player bot, BotEmojiScenario scenario) {
        String botKey = botEmojiKey(roomId, bot.getUserId());
        long now = System.currentTimeMillis();
        if (now < roomEmojiCooldownUntil.getOrDefault(roomId, 0L)
                || now < botEmojiCooldownUntil.getOrDefault(botKey, 0L)) {
            return null;
        }

        double chance = switch (scenario) {
            case TRICK_ATTACK -> 0.55;
            case AGGRESSIVE_PLAY -> 0.38;
            case TACTICAL_PLAY -> 0.24;
            case PRESSURED_PASS -> 0.32;
            case DEFENSE_SUCCESS -> 0.18;
            case DEFENSE_FAIL -> 0.26;
            case CLOSE_TO_WIN -> bot.getHandCards().size() <= 1 ? 0.6 : 0.42;
            case OPPONENT_IN_DANGER -> 0.28;
            case VICTORY -> 0.9;
        };
        if (ThreadLocalRandom.current().nextDouble() > chance) {
            return null;
        }

        List<String> pool = switch (scenario) {
            case TRICK_ATTACK -> List.of(EMOJI_SMUG, EMOJI_SLY, EMOJI_PLAYFUL, EMOJI_CONFUSED, EMOJI_ANGRY);
            case AGGRESSIVE_PLAY -> List.of(EMOJI_ANGRY, EMOJI_SMUG, EMOJI_SLY, EMOJI_HUFFING, EMOJI_PLAYFUL);
            case TACTICAL_PLAY -> List.of(EMOJI_THINKING, EMOJI_WRY, EMOJI_SMUG, EMOJI_LOVING, EMOJI_FINGER_HEART);
            case PRESSURED_PASS -> List.of(EMOJI_STRESSED, EMOJI_CRYING, EMOJI_CONFUSED, EMOJI_THINKING, EMOJI_SICK, EMOJI_PLEADING);
            case DEFENSE_SUCCESS -> List.of(EMOJI_SMUG, EMOJI_WRY, EMOJI_THINKING, EMOJI_HUFFING);
            case DEFENSE_FAIL -> List.of(EMOJI_STRESSED, EMOJI_CRYING, EMOJI_SICK, EMOJI_BROKEN_HEART, EMOJI_PLEADING);
            case CLOSE_TO_WIN -> List.of(EMOJI_LAUGHING, EMOJI_SMILE, EMOJI_SMUG, EMOJI_PLAYFUL, EMOJI_HUGGING, EMOJI_FINGER_HEART);
            case OPPONENT_IN_DANGER -> List.of(EMOJI_SMUG, EMOJI_SLY, EMOJI_LAUGHING, EMOJI_HUFFING, EMOJI_THINKING);
            case VICTORY -> List.of(EMOJI_LAUGHING, EMOJI_SMILE, EMOJI_LOVING, EMOJI_HUGGING, EMOJI_HEART, EMOJI_ROSE, EMOJI_FINGER_HEART);
        };

        String lastEmoji = botLastEmoji.get(botKey);
        List<String> availablePool = pool.stream()
                .filter(emoji -> !emoji.equals(lastEmoji))
                .toList();
        List<String> finalPool = availablePool.isEmpty() ? pool : availablePool;
        String chosen = finalPool.get(ThreadLocalRandom.current().nextInt(finalPool.size()));

        roomEmojiCooldownUntil.put(
                roomId,
                now + ROOM_EMOJI_COOLDOWN_MS + ThreadLocalRandom.current().nextLong(ROOM_EMOJI_COOLDOWN_JITTER_MS + 1)
        );
        botEmojiCooldownUntil.put(
                botKey,
                now + BOT_EMOJI_COOLDOWN_MS + ThreadLocalRandom.current().nextLong(BOT_EMOJI_COOLDOWN_JITTER_MS + 1)
        );
        botLastEmoji.put(botKey, chosen);
        return chosen;
    }

    private void maybeReactToCardWarning(String roomId, String warningUserId, int warningCount) {
        GameRoom room = gameService.getRoom(roomId);
        if (room == null || warningCount <= 0) {
            return;
        }

        com.game.poker.model.Player warnedPlayer = room.getPlayers().stream()
                .filter(player -> warningUserId.equals(player.getUserId()))
                .findFirst()
                .orElse(null);
        if (warnedPlayer != null && warnedPlayer.isBot()) {
            maybeSendBotEmoji(roomId, warnedPlayer, BotEmojiScenario.CLOSE_TO_WIN);
            return;
        }

        List<com.game.poker.model.Player> botReactors = room.getPlayers().stream()
                .filter(com.game.poker.model.Player::isBot)
                .filter(player -> "PLAYING".equals(player.getStatus()))
                .filter(player -> !warningUserId.equals(player.getUserId()))
                .toList();
        if (botReactors.isEmpty()) {
            return;
        }

        com.game.poker.model.Player reactor = botReactors.get(ThreadLocalRandom.current().nextInt(botReactors.size()));
        maybeSendBotEmoji(roomId, reactor, BotEmojiScenario.OPPONENT_IN_DANGER);
    }

    private String botEmojiKey(String roomId, String userId) {
        return roomId + "::" + userId;
    }

    private BotEmojiScenario determineBotPlayEmojiScenario(List<Card> playedCards, com.game.poker.model.Player bot) {
        if (playedCards == null || playedCards.isEmpty()) {
            return null;
        }
        if (bot.getHandCards().size() <= 2) {
            return BotEmojiScenario.CLOSE_TO_WIN;
        }
        if (playedCards.size() == 1 && "SCROLL".equals(playedCards.get(0).getSuit())) {
            String rank = playedCards.get(0).getRank();
            if ("JDSR".equals(rank)) {
                return BotEmojiScenario.TRICK_ATTACK;
            }
            return "WGFD".equals(rank) ? BotEmojiScenario.TACTICAL_PLAY : BotEmojiScenario.AGGRESSIVE_PLAY;
        }
        if (playedCards.size() >= 4 || isRocket(playedCards)) {
            return BotEmojiScenario.AGGRESSIVE_PLAY;
        }
        return null;
    }

    private boolean isRocket(List<Card> cards) {
        return cards.size() == 2
                && cards.stream().anyMatch(card -> "JOKER".equals(card.getSuit()) && "小王".equals(card.getRank()))
                && cards.stream().anyMatch(card -> "JOKER".equals(card.getSuit()) && "大王".equals(card.getRank()));
    }

    private boolean tryEmergencyFreeTurnPlay(String roomId, com.game.poker.model.Player bot, String reason) throws Exception {
        GameRoom room = gameService.getRoom(roomId);
        if (room == null || room.getCurrentAoeType() != null || room.getPlayers().isEmpty()) {
            return false;
        }

        int turnIndex = room.getCurrentTurnIndex();
        if (turnIndex < 0 || turnIndex >= room.getPlayers().size()) {
            return false;
        }

        com.game.poker.model.Player currentPlayer = room.getPlayers().get(turnIndex);
        if (!currentPlayer.getUserId().equals(bot.getUserId())) {
            return false;
        }

        boolean jdsrTarget = room.getSettings().containsKey("jdsr_target")
                && bot.getUserId().equals(room.getSettings().get("jdsr_target"));
        boolean freeTurn = room.getLastPlayedCards().isEmpty()
                || (!jdsrTarget && bot.getUserId().equals(room.getLastPlayPlayerId()));
        if (!freeTurn) {
            return false;
        }

        List<Card> emergencyPlay = scriptedAiService.chooseEmergencyFreeTurnPlay(bot);
        if (emergencyPlay.isEmpty()) {
            return false;
        }

        log.warn("scripted bot [{}] uses emergency free-turn play after {} in room {}",
                bot.getUserId(), reason, roomId);
        if (!gameService.playCards(roomId, bot.getUserId(), emergencyPlay)) {
            return false;
        }

        String cardsJson = objectMapper.writeValueAsString(emergencyPlay);
        broadcastToRoom(roomId, new TextMessage(
                "{\"event\": \"CARDS_PLAYED\", \"userId\": \"" + bot.getUserId() + "\", \"cards\": " + cardsJson + "}"
        ));
        syncPlayerHand(roomId, bot.getUserId());
        publishWinnerIfNeeded(roomId, emergencyPlay);
        broadcastGameState(roomId);

        BotEmojiScenario emojiScenario = determineBotPlayEmojiScenario(emergencyPlay, bot);
        if (emojiScenario != null) {
            maybeSendBotEmoji(roomId, bot, emojiScenario);
        }
        return true;
    }

    private void recoverBotAction(String roomId, com.game.poker.model.Player bot, String action, RuntimeException error) throws Exception {
        log.warn("scripted bot [{}] failed to {} in room {}: {}", bot.getUserId(), action, roomId, error.getMessage());
        fallbackBotAction(roomId, bot, action);
    }

    private void fallbackBotAction(String roomId, com.game.poker.model.Player bot, String action) throws Exception {
        GameRoom room = gameService.getRoom(roomId);
        if (room == null || room.getCurrentAoeType() != null || room.getPlayers().isEmpty()) {
            return;
        }

        int turnIndex = room.getCurrentTurnIndex();
        if (turnIndex < 0 || turnIndex >= room.getPlayers().size()) {
            return;
        }

        com.game.poker.model.Player currentPlayer = room.getPlayers().get(turnIndex);
        if (!currentPlayer.getUserId().equals(bot.getUserId())) {
            return;
        }

        boolean jdsrTarget = room.getSettings().containsKey("jdsr_target")
                && bot.getUserId().equals(room.getSettings().get("jdsr_target"));
        boolean responseTurn = jdsrTarget
                || (!room.getLastPlayedCards().isEmpty() && !bot.getUserId().equals(room.getLastPlayPlayerId()));
        boolean onlyHasScrolls = bot.getHandCards().stream().allMatch(card -> "SCROLL".equals(card.getSuit()));

        if (!responseTurn && !onlyHasScrolls) {
            log.warn("scripted bot [{}] has no safe fallback after {} failed in room {}", bot.getUserId(), action, roomId);
            return;
        }

        log.warn("scripted bot [{}] falls back to PASS after {} failed in room {}", bot.getUserId(), action, roomId);
        handleBotPass(roomId, bot);
    }

    private void handleBotReplace(String roomId, com.game.poker.model.Player bot, Card discardCard) throws Exception {
        try {
            if (!gameService.replaceCard(roomId, bot.getUserId(), discardCard)) {
                fallbackBotAction(roomId, bot, "replace");
                return;
            }
        } catch (RuntimeException e) {
            recoverBotAction(roomId, bot, "replace", e);
            return;
        }
        broadcastToRoom(roomId, new TextMessage("{\"event\": \"PLAYER_REPLACED\", \"userId\": \"" + bot.getUserId() + "\"}"));
        syncPlayerHand(roomId, bot.getUserId());
        broadcastGameState(roomId);
    }

    private void handleBotLuanjian(String roomId, com.game.poker.model.Player bot, List<Card> cards) throws Exception {
        if (cards == null || cards.size() != 2) {
            return;
        }
        try {
            gameService.useLuanjian(roomId, bot.getUserId(), cards);
        } catch (RuntimeException e) {
            recoverBotAction(roomId, bot, "use LUANJIAN", e);
            return;
        }
        broadcastToRoom(roomId, new TextMessage("{\"event\": \"SKILL_USED\", \"userId\": \"" + bot.getUserId() + "\", \"skillName\": \"LUANJIAN\"}"));
        syncPlayerHand(roomId, bot.getUserId());
        broadcastGameState(roomId);
        maybeSendBotEmoji(roomId, bot, BotEmojiScenario.AGGRESSIVE_PLAY);
    }

    private void handleBotUseGushou(String roomId, com.game.poker.model.Player bot) throws Exception {
        try {
            gameService.useGushou(roomId, bot.getUserId());
        } catch (RuntimeException e) {
            recoverBotAction(roomId, bot, "use GUSHOU", e);
            return;
        }
        syncPlayerHand(roomId, bot.getUserId());
        broadcastToRoom(roomId, new TextMessage("{\"event\": \"SKILL_USED\", \"userId\": \"" + bot.getUserId() + "\", \"skillName\": \"GUSHOU\"}"));
        publishWinnerIfNeeded(roomId, List.of());
        broadcastGameState(roomId);
        maybeSendBotEmoji(roomId, bot, BotEmojiScenario.TACTICAL_PLAY);
    }

    private void handleBotPlay(String roomId, com.game.poker.model.Player bot, List<Card> playedCards) throws Exception {
        boolean isAoe = playedCards.size() == 1 && "SCROLL".equals(playedCards.get(0).getSuit());
        try {
            if (!gameService.playCards(roomId, bot.getUserId(), playedCards)) {
                if (!tryEmergencyFreeTurnPlay(roomId, bot, "rejected play")) {
                    fallbackBotAction(roomId, bot, "play cards");
                }
                return;
            }
        } catch (RuntimeException e) {
            if (!tryEmergencyFreeTurnPlay(roomId, bot, e.getMessage())) {
                recoverBotAction(roomId, bot, "play cards", e);
            }
            return;
        }

        if (!isAoe) {
            String cardsJson = objectMapper.writeValueAsString(playedCards);
            broadcastToRoom(roomId, new TextMessage("{\"event\": \"CARDS_PLAYED\", \"userId\": \"" + bot.getUserId() + "\", \"cards\": " + cardsJson + "}"));
        } else {
            String aoeRank = playedCards.get(0).getRank();
            if ("JDSR".equals(aoeRank)) {
                String cardsJson = objectMapper.writeValueAsString(playedCards);
                broadcastToRoom(roomId, new TextMessage("{\"event\": \"CARDS_PLAYED\", \"userId\": \"" + bot.getUserId() + "\", \"cards\": " + cardsJson + "}"));
            } else {
                String aoeName = "NMRQ".equals(aoeRank) ? "南蛮入侵" : "WJQF".equals(aoeRank) ? "万箭齐发" : "五谷丰登";
                broadcastToRoom(roomId, new TextMessage("{\"event\": \"AOE_PLAYED\", \"userId\": \"" + bot.getUserId() + "\", \"aoeName\": \"" + aoeName + "\"}"));
            }
        }

        BotEmojiScenario emojiScenario = determineBotPlayEmojiScenario(playedCards, bot);
        syncPlayerHand(roomId, bot.getUserId());
        publishWinnerIfNeeded(roomId, isAoe ? List.of() : playedCards);
        broadcastGameState(roomId);
        if (emojiScenario != null) {
            maybeSendBotEmoji(roomId, bot, emojiScenario);
        }
    }

    private void handleBotPass(String roomId, com.game.poker.model.Player bot) throws Exception {
        GameRoom roomBefore = gameService.getRoom(roomId);
        boolean wasJdsrTarget = roomBefore != null
                && roomBefore.getSettings().containsKey("jdsr_target")
                && bot.getUserId().equals(roomBefore.getSettings().get("jdsr_target"));
        boolean pressuredPass = roomBefore != null
                && !roomBefore.getLastPlayedCards().isEmpty()
                && !bot.getUserId().equals(roomBefore.getLastPlayPlayerId());

        try {
            gameService.passTurn(roomId, bot.getUserId());
        } catch (RuntimeException e) {
            if (!tryEmergencyFreeTurnPlay(roomId, bot, e.getMessage())) {
                recoverBotAction(roomId, bot, "pass", e);
            }
            return;
        }
        GameRoom room = gameService.getRoom(roomId);
        if (room == null) {
            return;
        }

        if ("GUANXING".equals(room.getCurrentAoeType()) && room.getPendingAoePlayers().contains(bot.getUserId())) {
            broadcastToRoom(roomId, new TextMessage("{\"event\": \"SKILL_USED\", \"userId\": \"" + bot.getUserId() + "\", \"skillName\": \"GUANXING\"}"));
            broadcastGameState(roomId);
            return;
        }

        broadcastToRoom(roomId, new TextMessage("{\"event\": \"PLAYER_PASSED\", \"userId\": \"" + bot.getUserId() + "\"}"));
        syncPlayerHand(roomId, bot.getUserId());

        if (room.getLastPlayedCards().isEmpty()) {
            broadcastToRoom(roomId, new TextMessage("{\"event\": \"ROUND_RESET\"}"));
        }

        publishWinnerIfNeeded(roomId, List.of());
        broadcastGameState(roomId);
        if (wasJdsrTarget || pressuredPass) {
            maybeSendBotEmoji(roomId, bot, BotEmojiScenario.PRESSURED_PASS);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleBotGuanxing(String roomId, com.game.poker.model.Player bot) throws Exception {
        GameRoom room = gameService.getRoom(roomId);
        if (room == null) {
            return;
        }

        List<Card> options = room.getSettings().get("guanxingCards") instanceof List<?> rawCards
                ? (List<Card>) rawCards
                : List.of();
        if (options.isEmpty()) {
            return;
        }

        List<Card> selectedCards = scriptedAiService.chooseGuanxingCards(bot, options);
        if (selectedCards.size() != Math.min(2, options.size())) {
            selectedCards = new ArrayList<>(options.subList(0, Math.min(2, options.size())));
        }

        gameService.resolveGuanxing(roomId, bot.getUserId(), selectedCards);
        GameRoom roomAfter = gameService.getRoom(roomId);

        broadcastToRoom(roomId, new TextMessage("{\"event\": \"PLAYER_PASSED\", \"userId\": \"" + bot.getUserId() + "\"}"));
        syncPlayerHand(roomId, bot.getUserId());

        if (roomAfter != null && roomAfter.getLastPlayedCards().isEmpty() && roomAfter.getCurrentAoeType() == null) {
            broadcastToRoom(roomId, new TextMessage("{\"event\": \"ROUND_RESET\"}"));
        }

        publishWinnerIfNeeded(roomId, List.of());
        broadcastGameState(roomId);
        maybeSendBotEmoji(roomId, bot, BotEmojiScenario.TACTICAL_PLAY);
    }

    @SuppressWarnings("unchecked")
    private void handleBotWgfd(String roomId, com.game.poker.model.Player bot) throws Exception {
        GameRoom room = gameService.getRoom(roomId);
        if (room == null) {
            return;
        }

        List<Card> options = room.getSettings().get("wgfdCards") instanceof List<?> rawCards
                ? (List<Card>) rawCards
                : List.of();
        if (options.isEmpty()) {
            return;
        }

        Card selectedCard = scriptedAiService.chooseWgfdCard(bot, options);
        if (selectedCard == null) {
            selectedCard = options.get(0);
        }

        gameService.resolveWgfd(roomId, bot.getUserId(), selectedCard);
        syncPlayerHand(roomId, bot.getUserId());
        publishWinnerIfNeeded(roomId, List.of());
        broadcastGameState(roomId);
        maybeSendBotEmoji(roomId, bot, BotEmojiScenario.TACTICAL_PLAY);
    }

    private void handleBotRespondAoe(String roomId, com.game.poker.model.Player bot) throws Exception {
        GameRoom room = gameService.getRoom(roomId);
        if (room == null) {
            return;
        }

        Card responseCard = scriptedAiService.chooseAoeResponseCard(room, bot);
        gameService.respondAoe(roomId, bot.getUserId(), responseCard);

        if (responseCard != null) {
            String animJson = objectMapper.writeValueAsString(responseCard);
            broadcastToRoom(roomId, new TextMessage("{\"event\": \"AOE_ANIMATION\", \"userId\": \"" + bot.getUserId() + "\", \"card\": " + animJson + "}"));
        } else {
            broadcastToRoom(roomId, new TextMessage("{\"event\": \"PLAYER_PASSED\", \"userId\": \"" + bot.getUserId() + "\"}"));
        }

        syncPlayerHand(roomId, bot.getUserId());
        publishWinnerIfNeeded(roomId, List.of());
        broadcastGameState(roomId);
        maybeSendBotEmoji(roomId, bot, responseCard == null ? BotEmojiScenario.DEFENSE_FAIL : BotEmojiScenario.DEFENSE_SUCCESS);
    }

    private void handleBotGushouDiscard(String roomId, com.game.poker.model.Player bot) throws Exception {
        int discardCount = Math.min(2, bot.getHandCards().size());
        if (discardCount <= 0) {
            return;
        }

        List<Card> cards = scriptedAiService.chooseGushouDiscards(bot, discardCount);
        if (cards.size() != discardCount) {
            cards = new ArrayList<>(bot.getHandCards().subList(0, discardCount));
        }

        for (Card card : cards) {
            String animJson = objectMapper.writeValueAsString(card);
            broadcastToRoom(roomId, new TextMessage("{\"event\": \"AOE_ANIMATION\", \"userId\": \"" + bot.getUserId() + "\", \"card\": " + animJson + "}"));
        }

        gameService.discardGushou(roomId, bot.getUserId(), cards);
        syncPlayerHand(roomId, bot.getUserId());
        publishWinnerIfNeeded(roomId, List.of());
        broadcastGameState(roomId);
    }

    private boolean publishWinnerIfNeeded(String roomId, List<Card> winningCards) throws Exception {
        GameRoom room = gameService.getRoom(roomId);
        if (room == null) {
            return false;
        }

        com.game.poker.model.Player winner = room.getPlayers().stream()
                .filter(player -> "WON".equals(player.getStatus()))
                .findFirst()
                .orElse(null);
        if (winner == null) {
            return false;
        }

        String winningCardsJson = objectMapper.writeValueAsString(winningCards == null ? List.of() : winningCards);
        broadcastToRoom(roomId, new TextMessage("{\"event\": \"GAME_OVER\", \"winner\": \"" + winner.getUserId() + "\", \"winningCards\": " + winningCardsJson + "}"));
        room.setStarted(false);
        room.getPlayers().forEach(player -> player.setReady(player.isBot()));
        if (winner.isBot()) {
            maybeSendBotEmoji(roomId, winner, BotEmojiScenario.VICTORY);
        }
        return true;
    }

    private void syncAllHands(String roomId) throws Exception {
        GameRoom room = gameService.getRoom(roomId);
        if (room != null) {
            for (com.game.poker.model.Player p : room.getPlayers()) {
                String handJson = objectMapper.writeValueAsString(p.getHandCards());
                sendToUser(roomId, p.getUserId(), new TextMessage("{\"event\": \"SYNC_HAND\", \"cards\": " + handJson + "}"));
            }
        }
    }
    // ====== 【性能优化：精准定向手牌同步，拒绝全局发包】 ======
    private void syncPlayerHand(String roomId, String userId) throws Exception {
        GameRoom room = gameService.getRoom(roomId);
        if (room != null) {
            String handJson = "";
            // ====== 对单人手牌提取也加锁 ======
            synchronized (room) {
                com.game.poker.model.Player p = room.getPlayers().stream().filter(u -> u.getUserId().equals(userId)).findFirst().orElse(null);
                if (p != null) {
                    handJson = objectMapper.writeValueAsString(p.getHandCards());
                }
            }
            if (!handJson.isEmpty()) {
                sendToUser(roomId, userId, new TextMessage("{\"event\": \"SYNC_HAND\", \"cards\": " + handJson + "}"));
            }
        }
    }
}
