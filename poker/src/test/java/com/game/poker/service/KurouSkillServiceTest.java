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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖苦肉技能的核心规则：
 * - 第 3 次使用触发永久觉醒
 * - 借刀杀人目标期间禁用
 * - 爆牌（> 14）即输
 * - kurouAwakenDiscard 入参合法性
 */
class KurouSkillServiceTest {

    private static final String ROOM_ID = "room-kurou";
    private static final String ME = "hero";
    private static final String FOE = "rival";

    @Test
    void thirdUsageTriggersPermanentAwaken() {
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);

        // 给一手全是普通牌，保证 2 张可弃
        seedHand(me, 6);
        // 牌堆准备足够多的牌可摸
        fillDeck(room, 20);

        assertFalse(me.isKurouAwakened());

        assertFalse(service.useKurou(ROOM_ID, ME, twoFrom(me)));
        assertEquals(1, me.getKurouUseCount());
        assertFalse(me.isKurouAwakened());

        assertFalse(service.useKurou(ROOM_ID, ME, twoFrom(me)));
        assertEquals(2, me.getKurouUseCount());
        assertFalse(me.isKurouAwakened());

        boolean awakenTriggered = service.useKurou(ROOM_ID, ME, twoFrom(me));
        assertEquals(3, me.getKurouUseCount());
        assertTrue(awakenTriggered);
        assertTrue(me.isKurouAwakened());

        // 第 4 次再用不会再次广播觉醒
        assertFalse(service.useKurou(ROOM_ID, ME, twoFrom(me)));
        assertTrue(me.isKurouAwakened());
    }

    @Test
    void jdsrTargetCannotUseKurou() {
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);

        seedHand(me, 4);
        fillDeck(room, 6);

        room.getSettings().put("jdsr_target", ME);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.useKurou(ROOM_ID, ME, twoFrom(me)));
        assertTrue(ex.getMessage().contains("\u501f\u5200"));
        assertEquals(0, me.getKurouUseCount());
    }

    @Test
    void kurouLosesIfOverloaded() {
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);

        // 手里 13 张，弃 2 摸 4 -> 15 张 >14 必爆
        seedHand(me, 13);
        fillDeck(room, 6);

        service.useKurou(ROOM_ID, ME, twoFrom(me));

        assertEquals("LOST", me.getStatus());
        // 只剩唯一存活者自动 WON
        Player foe = room.getPlayers().get(1);
        assertEquals("WON", foe.getStatus());
    }

    @Test
    void kurouOverloadAdvancesTurnWithMultiplePlayers() {
        // 3 个活人：me 自爆后，回合应立刻交给下家而不是仍挂在 me 身上
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);
        Player foe = room.getPlayers().get(1);

        Player third = new Player("ally");
        third.setStatus("PLAYING");
        room.getPlayers().add(third);
        room.setCurrentTurnIndex(0);

        seedHand(me, 13);
        fillDeck(room, 6);

        service.useKurou(ROOM_ID, ME, twoFrom(me));

        assertEquals("LOST", me.getStatus());
        // 两个存活者都仍是 PLAYING，局面没结束
        assertEquals("PLAYING", foe.getStatus());
        assertEquals("PLAYING", third.getStatus());
        // 回合指针已从 0 跳到下一个活人（foe = index 1）
        assertEquals(1, room.getCurrentTurnIndex());
    }

    @Test
    void passTurnOnLostPlayerDoesNotDrawPenaltyCards() {
        // 防御回归：即便倒计时滞后触发 PASS，已 LOST 的玩家也不应被塞 2 张牌
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);
        Player foe = room.getPlayers().get(1);

        Player third = new Player("ally");
        third.setStatus("PLAYING");
        room.getPlayers().add(third);
        room.setCurrentTurnIndex(0);

        me.setStatus("LOST");
        me.getHandCards().clear();
        fillDeck(room, 8);

        service.passTurn(ROOM_ID, ME);

        // 没有被发惩罚牌
        assertTrue(me.getHandCards().isEmpty());
        // 回合已推进到下一个活人
        assertEquals(1, room.getCurrentTurnIndex());
    }

    @Test
    void awakenDiscardRejectsNonBlackCard() {
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);

        // 模拟已经处在觉醒弃黑挂起阶段
        me.setSkill("KUROU");
        me.setKurouAwakened(true);
        me.setKurouPendingAwakenDiscard(true);
        room.setCurrentAoeType("KUROU_AWAKEN_DISCARD");
        room.getPendingAoePlayers().add(ME);

        Card redCard = Card.getCard("\u2665", "7", 5);
        me.getHandCards().add(redCard);

        assertThrows(RuntimeException.class,
                () -> service.kurouAwakenDiscard(ROOM_ID, ME, redCard));

        // 依然处在挂起态
        assertTrue(me.isKurouPendingAwakenDiscard());
        assertEquals("KUROU_AWAKEN_DISCARD", room.getCurrentAoeType());
    }

    @Test
    void awakenDiscardSkipClearsPendingAndRotatesTurn() {
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);

        me.setSkill("KUROU");
        me.setKurouAwakened(true);
        me.setKurouPendingAwakenDiscard(true);
        room.setCurrentAoeType("KUROU_AWAKEN_DISCARD");
        room.setAoeStartTime(System.currentTimeMillis());
        room.getPendingAoePlayers().add(ME);

        me.getHandCards().add(Card.getCard("\u2660", "8", 6));
        room.setCurrentTurnIndex(0);

        service.kurouAwakenDiscard(ROOM_ID, ME, null);

        assertFalse(me.isKurouPendingAwakenDiscard());
        assertNull(room.getCurrentAoeType());
        assertFalse(room.getPendingAoePlayers().contains(ME));
        assertEquals(0L, room.getAoeStartTime());
        // 回合已交给下一位
        assertEquals(1, room.getCurrentTurnIndex());
    }

    @Test
    void awakenDiscardAcceptsSmallJoker() {
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);

        me.setSkill("KUROU");
        me.setKurouAwakened(true);
        me.setKurouPendingAwakenDiscard(true);
        room.setCurrentAoeType("KUROU_AWAKEN_DISCARD");
        room.getPendingAoePlayers().add(ME);
        room.setCurrentTurnIndex(0);

        Card smallJoker = Card.getCard("JOKER", "\u5c0f\u738b", 14);
        me.getHandCards().add(smallJoker);

        service.kurouAwakenDiscard(ROOM_ID, ME, smallJoker);

        assertTrue(me.getHandCards().isEmpty() || !me.getHandCards().contains(smallJoker));
        assertTrue(room.getDiscardPile().contains(smallJoker));
        assertFalse(me.isKurouPendingAwakenDiscard());
    }

    @Test
    void awakenDiscardStillRejectsBigJoker() {
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);

        me.setSkill("KUROU");
        me.setKurouAwakened(true);
        me.setKurouPendingAwakenDiscard(true);
        room.setCurrentAoeType("KUROU_AWAKEN_DISCARD");
        room.getPendingAoePlayers().add(ME);

        Card bigJoker = Card.getCard("JOKER", "\u5927\u738b", 15);
        me.getHandCards().add(bigJoker);

        assertThrows(RuntimeException.class,
                () -> service.kurouAwakenDiscard(ROOM_ID, ME, bigJoker));
        assertTrue(me.isKurouPendingAwakenDiscard());
    }

    @Test
    void awakenDiscardEmptyingHandOnlyMarksSelfAsWinner() {
        // 回归：他人使用苦肉觉醒弃黑把最后一张牌丢掉获胜时，
        // 不应该因为"唯一存活玩家"兜底逻辑而把对手也误升为 WON。
        // 否则 publishWinnerIfNeeded / recordCompletedGame 用 findFirst()
        // 会根据 players 列表顺序挑出错误的赢家，胜利结算显示和实际不一致。
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);
        Player foe = room.getPlayers().get(1);

        me.setSkill("KUROU");
        me.setKurouAwakened(true);
        me.setKurouPendingAwakenDiscard(true);
        room.setCurrentAoeType("KUROU_AWAKEN_DISCARD");
        room.getPendingAoePlayers().add(ME);
        room.setCurrentTurnIndex(0);

        Card lastBlack = Card.getCard("\u2660", "8", 6);
        me.getHandCards().add(lastBlack);

        // 对手还有手牌、仍 PLAYING，绝不该被判负也不该被判赢
        foe.getHandCards().add(Card.getCard("\u2665", "4", 2));
        foe.getHandCards().add(Card.getCard("\u2666", "5", 3));
        assertEquals("PLAYING", foe.getStatus());

        service.kurouAwakenDiscard(ROOM_ID, ME, lastBlack);

        assertEquals("WON", me.getStatus());
        assertEquals("PLAYING", foe.getStatus());

        // 胜者唯一：任何按 "WON" 过滤的查询都只能返回发动者本人
        List<Player> winners = room.getPlayers().stream()
                .filter(p -> "WON".equals(p.getStatus()))
                .toList();
        assertEquals(1, winners.size());
        assertEquals(ME, winners.get(0).getUserId());
    }

    @Test
    void awakenDiscardEmptyingHandStillPromotesSoleSurvivor() {
        // 另一条分支：如果对手本来就因为爆牌淘汰，发动者打空手牌照常获胜，
        // 并不会影响已经 LOST 的对手状态。
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);
        Player foe = room.getPlayers().get(1);

        me.setSkill("KUROU");
        me.setKurouAwakened(true);
        me.setKurouPendingAwakenDiscard(true);
        room.setCurrentAoeType("KUROU_AWAKEN_DISCARD");
        room.getPendingAoePlayers().add(ME);
        room.setCurrentTurnIndex(0);

        Card lastBlack = Card.getCard("\u2663", "9", 7);
        me.getHandCards().add(lastBlack);
        foe.setStatus("LOST");

        service.kurouAwakenDiscard(ROOM_ID, ME, lastBlack);

        assertEquals("WON", me.getStatus());
        assertEquals("LOST", foe.getStatus());
    }

    @Test
    void awakenDiscardConsumesSelectedBlackCard() {
        GameService service = new GameService();
        GameRoom room = makeRoom(service);
        Player me = room.getPlayers().get(0);

        me.setSkill("KUROU");
        me.setKurouAwakened(true);
        me.setKurouPendingAwakenDiscard(true);
        room.setCurrentAoeType("KUROU_AWAKEN_DISCARD");
        room.getPendingAoePlayers().add(ME);
        room.setCurrentTurnIndex(0);

        Card black = Card.getCard("\u2660", "8", 6);
        Card red = Card.getCard("\u2665", "7", 5);
        me.getHandCards().add(black);
        me.getHandCards().add(red);

        service.kurouAwakenDiscard(ROOM_ID, ME, black);

        assertEquals(1, me.getHandCards().size());
        assertEquals(red, me.getHandCards().get(0));
        assertTrue(room.getDiscardPile().contains(black));
    }

    private GameRoom makeRoom(GameService service) {
        GameRoom room = new GameRoom(ROOM_ID);
        // 清掉自动初始化的 54 张，由测试精准控制牌堆
        room.getDeck().clear();

        Player me = new Player(ME);
        me.setSkill("KUROU");
        me.setStatus("PLAYING");

        Player foe = new Player(FOE);
        foe.setStatus("PLAYING");

        room.getPlayers().add(me);
        room.getPlayers().add(foe);
        room.setCurrentTurnIndex(0);
        room.setStarted(true);

        service.getRoomMap().put(ROOM_ID, room);
        return room;
    }

    private void seedHand(Player player, int count) {
        player.getHandCards().clear();
        // 用 ♠ 3,4,5... 构造 count 张黑色牌即可（不会出现锦囊）
        String[] ranks = {"3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A", "2"};
        for (int i = 0; i < count; i++) {
            String rank = ranks[i % ranks.length];
            int weight = (i % ranks.length) + 1;
            // 交替给 ♠ 和 ♥ 以覆盖觉醒后"有黑牌"场景与"无黑牌"场景切换
            String suit = (i % 2 == 0) ? "\u2660" : "\u2665";
            player.getHandCards().add(Card.getCard(suit, rank, weight));
        }
    }

    private void fillDeck(GameRoom room, int count) {
        String[] ranks = {"3", "4", "5", "6", "7"};
        for (int i = 0; i < count; i++) {
            String rank = ranks[i % ranks.length];
            int weight = (i % ranks.length) + 1;
            room.getDeck().add(Card.getCard("\u2666", rank, weight));
        }
    }

    private List<Card> twoFrom(Player player) {
        assertNotNull(player.getHandCards());
        assertTrue(player.getHandCards().size() >= 2, "hand too small for kurou");
        // 拷贝成新 List 防止被内部 remove 搅乱迭代
        return new ArrayList<>(List.of(player.getHandCards().get(0), player.getHandCards().get(1)));
    }
}
