package com.game.poker.service;

import com.game.poker.model.Card;
import com.game.poker.model.GameRoom;
import com.game.poker.model.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖铁骑（TIEQI）被动技能的核心规则：
 *  - 红色定义：♥ / ♦ / 大王；黑色与小王不算红色
 *  - 出牌不含红色不触发判定
 *  - 判定牌为红色且 weight 严格小于 max(红色 weight) → 成功·压制：桌面清空、其他 PLAYING 玩家各罚摸 2 张、轮回发动者
 *  - 判定牌为黑色 / 小王 / 红色但 weight >= 最大红色 weight → 失败：流程正常走下家
 *  - 判定牌无论成败均进入弃牌堆
 *  - 牌堆+弃牌堆全空：不崩溃，视作无判定
 */
class TieqiSkillServiceTest {

    private static final String ROOM_ID = "room-tieqi";
    private static final String ME = "general";
    private static final String FOE = "rival";
    private static final String THIRD = "ally";

    private static final String HEART = "\u2665";   // ♥
    private static final String DIAMOND = "\u2666"; // ♦
    private static final String SPADE = "\u2660";   // ♠
    private static final String CLUB = "\u2663";    // ♣

    @Test
    void allBlackPlayDoesNotTriggerTieqi() {
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);

        Card spade5 = Card.getCard(SPADE, "5", 3);
        me.getHandCards().add(spade5);
        room.getDeck().add(Card.getCard(HEART, "6", 4)); // 即使顶牌是红桃也不应被抽

        assertTrue(service.playCards(ROOM_ID, ME, new ArrayList<>(List.of(spade5))));

        assertFalse(room.getSettings().containsKey("tieqiJudgeCard"),
                "出牌全是黑色时不应触发铁骑判定");
        assertFalse(room.getSettings().containsKey("tieqiSkipRound"));
        // 回合推进到下家
        assertEquals(1, room.getCurrentTurnIndex());
        // 桌面牌保留
        assertEquals(List.of(spade5), room.getLastPlayedCards());
    }

    @Test
    void heartJudgeCardBelowMaxRedSucceedsAndSkipsOthers() {
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);
        Player foe = room.getPlayers().get(1);
        int foeBefore = foe.getHandCards().size();

        Card heart7 = Card.getCard(HEART, "7", 5);   // 出的牌：♥7 (w=5)
        Card heart5 = Card.getCard(HEART, "5", 3);   // 判定顶牌：♥5 (w=3) < 5 → 命中
        me.getHandCards().add(heart7);
        room.getDeck().add(heart5);
        // 判定抽走 heart5 后，deck 至少要留 2 张供 foe 罚摸
        room.getDeck().add(Card.getCard(SPADE, "9", 7));
        room.getDeck().add(Card.getCard(SPADE, "8", 6));

        assertTrue(service.playCards(ROOM_ID, ME, new ArrayList<>(List.of(heart7))));

        assertTrue(room.getSettings().containsKey("tieqiJudgeCard"), "应触发铁骑判定");
        assertEquals(heart5, room.getSettings().get("tieqiJudgeCard"));
        assertEquals(true, room.getSettings().get("tieqiJudgeSuccess"));
        assertEquals(ME, room.getSettings().get("tieqiJudgeUserId"));
        // tieqiSkipRound 已在 nextTurn 中被 remove（说明跳圈已执行）
        assertFalse(room.getSettings().containsKey("tieqiSkipRound"));
        // 判定成功 → 桌面清空、lastPlayPlayerId 置空、轮回发动者
        assertTrue(room.getLastPlayedCards().isEmpty(), "判定成功后桌面牌应被清空");
        assertNull(room.getLastPlayPlayerId());
        assertEquals(0, room.getCurrentTurnIndex(), "判定成功应轮回发动者");
        // 判定牌进入弃牌堆
        assertTrue(room.getDiscardPile().contains(heart5));
        // 出过的 ♥7 也应在弃牌堆（因为判定成功清桌面时把它扫进了弃堆）
        assertTrue(room.getDiscardPile().contains(heart7));
        // 压制：对手被强制罚摸 2 张
        assertEquals(foeBefore + 2, foe.getHandCards().size(),
                "铁骑·压制：其他 PLAYING 玩家应各罚摸 2 张");
        @SuppressWarnings("unchecked")
        List<String> suppressed = (List<String>) room.getSettings().get("tieqiSuppressed");
        assertNotNull(suppressed);
        assertEquals(List.of(FOE), suppressed);
    }

    @Test
    void diamondJudgeCardBelowMaxRedSucceeds() {
        // 出 ♥7 (w=5)，判定顶牌 ♦5 (w=3) → 红色且 3 < 5 → 成功
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);
        Player foe = room.getPlayers().get(1);
        int foeBefore = foe.getHandCards().size();

        Card heart7 = Card.getCard(HEART, "7", 5);
        Card diamond5 = Card.getCard(DIAMOND, "5", 3);
        me.getHandCards().add(heart7);
        room.getDeck().add(diamond5);
        // 留出 foe 罚摸所需的备用牌
        room.getDeck().add(Card.getCard(SPADE, "8", 6));
        room.getDeck().add(Card.getCard(SPADE, "9", 7));

        assertTrue(service.playCards(ROOM_ID, ME, new ArrayList<>(List.of(heart7))));

        assertTrue(room.getSettings().containsKey("tieqiJudgeCard"));
        assertEquals(diamond5, room.getSettings().get("tieqiJudgeCard"));
        assertEquals(true, room.getSettings().get("tieqiJudgeSuccess"),
                "判定牌为 ♦ 且 weight 小于最大红牌时应成功（红色定义放宽）");
        assertEquals(0, room.getCurrentTurnIndex(), "判定成功 → 轮回发动者");
        assertTrue(room.getDiscardPile().contains(diamond5));
        assertEquals(foeBefore + 2, foe.getHandCards().size(), "成功 → 压制对手罚摸 2 张");
    }

    @Test
    void heartJudgeCardEqualOrLargerFails() {
        // weight 等于最大红牌应失败（严格小于规则）；同时大于也失败
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);

        // ===== Case A：weight 严格大于 =====
        Card heart5 = Card.getCard(HEART, "5", 3);   // maxRed = 3
        Card heartK = Card.getCard(HEART, "K", 11);  // 判定 11 > 3 → 失败
        me.getHandCards().add(heart5);
        room.getDeck().add(heartK);
        room.getDeck().add(Card.getCard(SPADE, "4", 2));

        assertTrue(service.playCards(ROOM_ID, ME, new ArrayList<>(List.of(heart5))));
        assertEquals(heartK, room.getSettings().get("tieqiJudgeCard"));
        assertEquals(false, room.getSettings().get("tieqiJudgeSuccess"),
                "判定牌 weight > 最大红牌时应失败");
        assertEquals(1, room.getCurrentTurnIndex());
        assertTrue(room.getDiscardPile().contains(heartK));
    }

    @Test
    void heartJudgeCardEqualToMaxRedFails() {
        // weight 等于最大红牌：严格小于规则下应失败
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);

        Card heart7a = Card.getCard(HEART, "7", 5);
        Card heart7b = Card.getCard(HEART, "7", 5); // 引用同一池，weight 一致
        me.getHandCards().add(heart7a);
        room.getDeck().add(heart7b);
        room.getDeck().add(Card.getCard(SPADE, "8", 6));

        assertTrue(service.playCards(ROOM_ID, ME, new ArrayList<>(List.of(heart7a))));

        assertEquals(false, room.getSettings().get("tieqiJudgeSuccess"),
                "weight 等于最大红牌时应失败（严格 < 规则）");
        assertEquals(1, room.getCurrentTurnIndex(), "失败 → 流程走下家");
        assertTrue(room.getDiscardPile().contains(heart7b));
    }

    @Test
    void bigJokerJudgeCardFailsAgainstSmallerMaxRed() {
        // 出 ♥7 (maxRed=5)，判定牌大王 (w=15)：大王算红色，但 15 不小于 5 → 失败
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);

        Card heart7 = Card.getCard(HEART, "7", 5);
        Card bigJoker = Card.getCard("JOKER", "\u5927\u738b", 15);
        me.getHandCards().add(heart7);
        room.getDeck().add(bigJoker);
        room.getDeck().add(Card.getCard(SPADE, "4", 2));

        assertTrue(service.playCards(ROOM_ID, ME, new ArrayList<>(List.of(heart7))));

        assertEquals(bigJoker, room.getSettings().get("tieqiJudgeCard"));
        assertEquals(false, room.getSettings().get("tieqiJudgeSuccess"),
                "大王虽为红色，但 w=15 > maxRed=5 → 判定失败");
        assertEquals(1, room.getCurrentTurnIndex());
        assertTrue(room.getDiscardPile().contains(bigJoker));
    }

    @Test
    void smallJokerInPlayDoesNotTriggerTieqi() {
        // 小王不算红色：单出小王不触发铁骑判定
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);

        Card smallJoker = Card.getCard("JOKER", "\u5c0f\u738b", 14);
        me.getHandCards().add(smallJoker);
        // 就算顶牌是红桃也不应被抽到
        room.getDeck().add(Card.getCard(HEART, "3", 1));

        assertTrue(service.playCards(ROOM_ID, ME, new ArrayList<>(List.of(smallJoker))));

        assertFalse(room.getSettings().containsKey("tieqiJudgeCard"),
                "小王不视为红色 → 铁骑不应触发");
        assertEquals(1, room.getCurrentTurnIndex());
    }

    @Test
    void bigJokerInPlayTriggersTieqiAndJudgesAgainstJokerWeight() {
        // 出大王 (w=15) → 触发判定，maxRed=15；判定牌 ♥5 (w=3) < 15 → 成功
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);
        Player foe = room.getPlayers().get(1);
        int foeBefore = foe.getHandCards().size();

        Card bigJoker = Card.getCard("JOKER", "\u5927\u738b", 15);
        me.getHandCards().add(bigJoker);
        Card heart5 = Card.getCard(HEART, "5", 3);
        room.getDeck().add(heart5);
        // 留出 foe 罚摸所需的备用牌
        room.getDeck().add(Card.getCard(SPADE, "7", 5));
        room.getDeck().add(Card.getCard(SPADE, "8", 6));

        assertTrue(service.playCards(ROOM_ID, ME, new ArrayList<>(List.of(bigJoker))));

        assertTrue(room.getSettings().containsKey("tieqiJudgeCard"),
                "出大王也应触发铁骑判定");
        assertEquals(heart5, room.getSettings().get("tieqiJudgeCard"));
        assertEquals(true, room.getSettings().get("tieqiJudgeSuccess"),
                "大王 w=15 作为 maxRed，♥5 (w=3) 严格小于 → 成功");
        assertEquals(15, room.getSettings().get("tieqiMaxRedWeight"),
                "maxRedWeight 应取大王 weight=15");
        assertEquals(0, room.getCurrentTurnIndex(), "成功 → 轮回发动者");
        assertEquals(foeBefore + 2, foe.getHandCards().size(), "压制对手罚摸 2 张");
    }

    @Test
    void mixedRedPlayUsesMaxRedWeight() {
        // 出两张红色（♦7, ♥9）：maxRed = 9 (w=7)
        // 顶牌 ♥8 (w=6) < 7 → 成功
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);
        Player foe = room.getPlayers().get(1);
        int foeBefore = foe.getHandCards().size();

        Card diamond7 = Card.getCard(DIAMOND, "7", 5);
        Card heart9 = Card.getCard(HEART, "9", 7);
        Card heart8 = Card.getCard(HEART, "8", 6);
        me.getHandCards().add(diamond7);
        me.getHandCards().add(heart9);
        room.getDeck().add(heart8);
        room.getDeck().add(Card.getCard(SPADE, "4", 2));
        room.getDeck().add(Card.getCard(SPADE, "5", 3));

        assertTrue(service.playCards(ROOM_ID, ME,
                new ArrayList<>(List.of(diamond7, heart9))));

        assertEquals(true, room.getSettings().get("tieqiJudgeSuccess"));
        Object mw = room.getSettings().get("tieqiMaxRedWeight");
        assertEquals(7, mw, "应取出牌中最大红色牌的 weight 作为 maxRedWeight");
        // 判定成功 → 轮回发动者
        assertEquals(0, room.getCurrentTurnIndex());
        assertEquals(foeBefore + 2, foe.getHandCards().size());
    }

    @Test
    void judgeCardAlwaysGoesToDiscardPile() {
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);

        Card heart5 = Card.getCard(HEART, "5", 3);
        Card spade10 = Card.getCard(SPADE, "10", 8); // 黑色 → 失败但仍进弃牌堆
        me.getHandCards().add(heart5);
        room.getDeck().add(spade10);
        room.getDeck().add(Card.getCard(CLUB, "4", 2));

        int beforeDiscardSize = room.getDiscardPile().size();
        assertTrue(service.playCards(ROOM_ID, ME, new ArrayList<>(List.of(heart5))));

        assertTrue(room.getDiscardPile().contains(spade10),
                "失败的判定牌也应进入弃牌堆");
        // 判定牌被抽走 → deck 应减少 1
        assertEquals(1, room.getDeck().size(),
                "判定只抽一张牌");
        // 失败流程：桌面保留出过的牌
        assertTrue(room.getLastPlayedCards().contains(heart5));
        assertTrue(room.getDiscardPile().size() > beforeDiscardSize);
    }

    @Test
    void emptyDeckAndDiscardDoesNotCrash() {
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);

        Card heart7 = Card.getCard(HEART, "7", 5);
        me.getHandCards().add(heart7);
        // deck 与 discardPile 双空：drawCard 返回 null，triggerTieqi 静默返回
        assertTrue(room.getDeck().isEmpty());
        assertTrue(room.getDiscardPile().isEmpty());

        assertTrue(service.playCards(ROOM_ID, ME, new ArrayList<>(List.of(heart7))));

        assertFalse(room.getSettings().containsKey("tieqiJudgeCard"),
                "牌堆弃堆皆空时铁骑不触发（避免无穷等待）");
        // 正常走下家
        assertEquals(1, room.getCurrentTurnIndex());
    }

    @Test
    void successSkipsMultiplePlayersBackToInitiator() {
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);
        Player foe = room.getPlayers().get(1);
        int foeBefore = foe.getHandCards().size();
        // 再加一个玩家（3 人）
        Player third = new Player(THIRD);
        third.setStatus("PLAYING");
        third.getHandCards().add(Card.getCard(SPADE, "4", 2));
        int thirdBefore = third.getHandCards().size();
        room.getPlayers().add(third);

        Card heart7 = Card.getCard(HEART, "7", 5);
        Card heart3 = Card.getCard(HEART, "3", 1);
        me.getHandCards().add(heart7);
        room.getDeck().add(heart3);
        // 判定拿走 heart3，剩 4 张够 foe+third 各摸 2 张
        room.getDeck().add(Card.getCard(CLUB, "4", 2));
        room.getDeck().add(Card.getCard(CLUB, "5", 3));
        room.getDeck().add(Card.getCard(CLUB, "6", 4));
        room.getDeck().add(Card.getCard(CLUB, "7", 5));

        assertTrue(service.playCards(ROOM_ID, ME, new ArrayList<>(List.of(heart7))));

        // 命中：3 人场景下应直接跳回发动者而不是 index=1 或 2
        assertEquals(true, room.getSettings().get("tieqiJudgeSuccess"));
        assertEquals(0, room.getCurrentTurnIndex(),
                "判定成功：即使 3 人场景也直接轮回发动者");
        assertTrue(room.getLastPlayedCards().isEmpty());
        // 两名其他 PLAYING 玩家均被压制罚摸 2 张
        assertEquals(foeBefore + 2, foe.getHandCards().size());
        assertEquals(thirdBefore + 2, third.getHandCards().size());
        @SuppressWarnings("unchecked")
        List<String> suppressed = (List<String>) room.getSettings().get("tieqiSuppressed");
        assertNotNull(suppressed);
        assertEquals(2, suppressed.size(), "两名玩家都应被列入受压制名单");
    }

    private GameRoom makeRoom(GameService service) {
        GameRoom room = new GameRoom(ROOM_ID);
        room.getDeck().clear();
        room.getDiscardPile().clear();
        room.setLastPlayedCards(new ArrayList<>());

        Player me = new Player(ME);
        me.setSkill("TIEQI");
        me.setStatus("PLAYING");
        // 给发动者一张"占位"黑色牌，避免打出唯一一张牌后手牌为 0 触发 WON 分支、绕过铁骑判定
        me.getHandCards().add(Card.getCard(CLUB, "3", 1));

        Player foe = new Player(FOE);
        foe.setStatus("PLAYING");
        foe.getHandCards().add(Card.getCard(SPADE, "3", 1));

        room.getPlayers().add(me);
        room.getPlayers().add(foe);
        room.setCurrentTurnIndex(0);
        room.setStarted(true);

        service.getRoomMap().put(ROOM_ID, room);
        return room;
    }
}
