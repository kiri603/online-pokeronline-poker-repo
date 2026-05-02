package com.game.poker.service;

import com.game.poker.model.Card;
import com.game.poker.model.GameRoom;
import com.game.poker.model.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ScriptedAiServiceTest {

    @Test
    void jdsrTargetPassesInsteadOfUsingSkillOrReplaceWhenNoValidResponseExists() throws Exception {
        ScriptedAiService service = createServiceWithRuleEngine();
        GameRoom room = new GameRoom("room-1");

        Player initiator = new Player("p1");
        Player bot = new Player("bot", true);
        bot.setSkill("ZHIHENG");
        bot.getHandCards().add(card("\u2665", "4", 2));
        bot.getHandCards().add(card("\u2663", "5", 3));

        room.setPlayers(new ArrayList<>(List.of(initiator, bot)));
        room.setCurrentTurnIndex(1);
        room.setLastPlayPlayerId(initiator.getUserId());
        room.setLastPlayedCards(new ArrayList<>(List.of(card("\u2660", "10", 8))));
        room.getSettings().put("jdsr_initiator", initiator.getUserId());
        room.getSettings().put("jdsr_target", bot.getUserId());

        ScriptedAiService.TurnDecision decision = service.decideTurn(room, bot);

        assertEquals(ScriptedAiService.TurnDecisionType.PASS, decision.getType());
    }

    @Test
    void jdsrTargetStillPlaysWhenItHasAValidResponse() throws Exception {
        ScriptedAiService service = createServiceWithRuleEngine();
        GameRoom room = new GameRoom("room-2");

        Player initiator = new Player("p1");
        Player bot = new Player("bot", true);
        bot.setSkill("GUSHOU");
        bot.getHandCards().add(card("\u2660", "J", 9));
        bot.getHandCards().add(card("\u2665", "4", 2));

        room.setPlayers(new ArrayList<>(List.of(initiator, bot)));
        room.setCurrentTurnIndex(1);
        room.setLastPlayPlayerId(initiator.getUserId());
        room.setLastPlayedCards(new ArrayList<>(List.of(card("\u2663", "10", 8))));
        room.getSettings().put("jdsr_initiator", initiator.getUserId());
        room.getSettings().put("jdsr_target", bot.getUserId());

        ScriptedAiService.TurnDecision decision = service.decideTurn(room, bot);

        assertEquals(ScriptedAiService.TurnDecisionType.PLAY, decision.getType());
        assertEquals(List.of(card("\u2660", "J", 9)), decision.getCards());
    }

    @Test
    void botDoesNotAttemptSecondScrollAfterAlreadyUsingOneThisTurn() throws Exception {
        ScriptedAiService service = createServiceWithRuleEngine();
        GameRoom room = new GameRoom("room-3");

        Player bot = new Player("bot", true);
        Player other = new Player("other");
        bot.setHasUsedAoeThisTurn(true);
        bot.getHandCards().add(card("SCROLL", "NMRQ", 17));
        bot.getHandCards().add(card("\u2660", "7", 5));

        room.setPlayers(new ArrayList<>(List.of(bot, other)));
        room.setCurrentTurnIndex(0);
        room.setLastPlayedCards(new ArrayList<>());
        room.setLastPlayPlayerId("");

        ScriptedAiService.TurnDecision decision = service.decideTurn(room, bot);

        assertNotEquals(ScriptedAiService.TurnDecisionType.USE_SKILL, decision.getType());
        if (decision.getType() == ScriptedAiService.TurnDecisionType.PLAY) {
            assertNotEquals("SCROLL", decision.getCards().get(0).getSuit());
        }
    }

    @Test
    void kurouBotWithNearBustHandRefusesToUseKurou() throws Exception {
        // 手牌 11 张，未觉醒 —— 用后将变 13 张，紧贴爆牌 14 的红线。
        // 新策略要求 未觉醒 handAfter ≤ 10，故应拒绝使用苦肉。
        ScriptedAiService service = createServiceWithRuleEngine();
        GameRoom room = new GameRoom("room-kurou-bust");
        Player bot = new Player("bot", true);
        bot.setSkill("KUROU");
        Player other = new Player("other");
        other.getHandCards().add(card("\u2660", "5", 3));
        other.getHandCards().add(card("\u2665", "5", 3));

        String[] ranks = {"3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
        for (int i = 0; i < ranks.length; i++) {
            bot.getHandCards().add(card("\u2660", ranks[i], i + 1));
        }

        room.setPlayers(new ArrayList<>(List.of(bot, other)));
        room.setCurrentTurnIndex(0);
        room.setLastPlayedCards(new ArrayList<>());
        room.setLastPlayPlayerId("");

        ScriptedAiService.TurnDecision decision = service.decideTurn(room, bot);

        assertNotEquals(ScriptedAiService.TurnDecisionType.USE_KUROU, decision.getType());
    }

    @Test
    void awakenedKurouBotSkipsKurouWhenHandIsTidy() throws Exception {
        // 已觉醒 + 牌型整齐（两对 + 一对三连对基础），
        // 苦肉再用没有额外价值，应跳过；改打普通牌路径。
        ScriptedAiService service = createServiceWithRuleEngine();
        GameRoom room = new GameRoom("room-kurou-awaken-tidy");
        Player bot = new Player("bot", true);
        bot.setSkill("KUROU");
        bot.setKurouAwakened(true);
        bot.setKurouUseCount(3);
        Player other = new Player("other");
        other.getHandCards().add(card("\u2660", "5", 3));
        other.getHandCards().add(card("\u2665", "5", 3));

        // 一对 4 + 一对 6 + 一张 8 —— 散牌只有 1，新策略不应再刷
        bot.getHandCards().add(card("\u2660", "4", 2));
        bot.getHandCards().add(card("\u2665", "4", 2));
        bot.getHandCards().add(card("\u2660", "6", 4));
        bot.getHandCards().add(card("\u2665", "6", 4));
        bot.getHandCards().add(card("\u2660", "8", 6));

        room.setPlayers(new ArrayList<>(List.of(bot, other)));
        room.setCurrentTurnIndex(0);
        room.setLastPlayedCards(new ArrayList<>());
        room.setLastPlayPlayerId("");

        ScriptedAiService.TurnDecision decision = service.decideTurn(room, bot);

        assertNotEquals(ScriptedAiService.TurnDecisionType.USE_KUROU, decision.getType());
    }

    @Test
    void kurouBotSkipsKurouWhenOpponentNearWinning() throws Exception {
        // 威胁场景：对手只剩 2 张，应该放弃刷牌集中资源应对
        ScriptedAiService service = createServiceWithRuleEngine();
        GameRoom room = new GameRoom("room-kurou-threat");
        Player bot = new Player("bot", true);
        bot.setSkill("KUROU");
        Player threat = new Player("threat");
        threat.setStatus("PLAYING");
        threat.getHandCards().add(card("\u2660", "5", 3));
        threat.getHandCards().add(card("\u2665", "5", 3));

        // 全散牌，本来是苦肉"控牌"的理想场景
        String[] ranks = {"3", "4", "5", "6", "7"};
        for (int i = 0; i < ranks.length; i++) {
            bot.getHandCards().add(card("\u2663", ranks[i], i + 1));
        }
        bot.setStatus("PLAYING");

        room.setPlayers(new ArrayList<>(List.of(bot, threat)));
        room.setCurrentTurnIndex(0);
        room.setLastPlayedCards(new ArrayList<>());
        room.setLastPlayPlayerId("");

        ScriptedAiService.TurnDecision decision = service.decideTurn(room, bot);

        assertNotEquals(ScriptedAiService.TurnDecisionType.USE_KUROU, decision.getType());
    }

    @Test
    void kurouBotUsesKurouForControlOnMessyHand() throws Exception {
        // 未觉醒 + 手牌碎（5 张全散） + 对手手牌正常 —— 应该主动刷一次控牌
        ScriptedAiService service = createServiceWithRuleEngine();
        GameRoom room = new GameRoom("room-kurou-messy");
        Player bot = new Player("bot", true);
        bot.setSkill("KUROU");
        bot.setStatus("PLAYING");
        Player other = new Player("other");
        other.setStatus("PLAYING");
        // 故意让对手保持 5 张以上，避免触发 threat
        String[] otherRanks = {"3", "4", "5", "6", "7"};
        for (int i = 0; i < otherRanks.length; i++) {
            other.getHandCards().add(card("\u2666", otherRanks[i], i + 1));
        }

        // 5 张全散（3/5/7/9/J），不同权重无对子、无连续
        bot.getHandCards().add(card("\u2663", "3", 1));
        bot.getHandCards().add(card("\u2663", "5", 3));
        bot.getHandCards().add(card("\u2663", "7", 5));
        bot.getHandCards().add(card("\u2663", "9", 7));
        bot.getHandCards().add(card("\u2663", "J", 9));

        room.setPlayers(new ArrayList<>(List.of(bot, other)));
        room.setCurrentTurnIndex(0);
        room.setLastPlayedCards(new ArrayList<>());
        room.setLastPlayPlayerId("");

        ScriptedAiService.TurnDecision decision = service.decideTurn(room, bot);

        assertEquals(ScriptedAiService.TurnDecisionType.USE_KUROU, decision.getType());
        assertEquals(2, decision.getCards().size());
    }

    @Test
    void kurouBotDoesNotUseKurouAfterTwoUsesThisTurn() throws Exception {
        ScriptedAiService service = createServiceWithRuleEngine();
        GameRoom room = new GameRoom("room-kurou-turn-limit");
        Player bot = new Player("bot", true);
        bot.setSkill("KUROU");
        bot.setStatus("PLAYING");
        bot.setKurouUsesThisTurn(2);
        Player other = new Player("other");
        other.setStatus("PLAYING");
        String[] otherRanks = {"3", "4", "5", "6", "7"};
        for (int i = 0; i < otherRanks.length; i++) {
            other.getHandCards().add(card("\u2666", otherRanks[i], i + 1));
        }

        bot.getHandCards().add(card("\u2663", "3", 1));
        bot.getHandCards().add(card("\u2663", "5", 3));
        bot.getHandCards().add(card("\u2663", "7", 5));
        bot.getHandCards().add(card("\u2663", "9", 7));
        bot.getHandCards().add(card("\u2663", "J", 9));

        room.setPlayers(new ArrayList<>(List.of(bot, other)));
        room.setCurrentTurnIndex(0);
        room.setLastPlayedCards(new ArrayList<>());
        room.setLastPlayPlayerId("");

        ScriptedAiService.TurnDecision decision = service.decideTurn(room, bot);

        assertNotEquals(ScriptedAiService.TurnDecisionType.USE_KUROU, decision.getType());
    }

    @Test
    void emergencyFreeTurnPlayIgnoresScrollCards() throws Exception {
        ScriptedAiService service = createServiceWithRuleEngine();
        Player bot = new Player("bot", true);
        bot.getHandCards().add(card("SCROLL", "WGFD", 18));
        bot.getHandCards().add(card("\u2663", "9", 7));
        bot.getHandCards().add(card("\u2665", "9", 7));

        List<Card> emergencyPlay = service.chooseEmergencyFreeTurnPlay(bot);

        assertEquals(List.of(card("\u2663", "9", 7), card("\u2665", "9", 7)), emergencyPlay);
    }

    @Test
    void botPlaysRocketAsOneHandWhenItFinishesTheGame() throws Exception {
        // 回归：只剩小王 + 大王时，AI 必须整手打王炸赢下这一局，
        // 而不是拆成两张单牌分开出。
        ScriptedAiService service = createServiceWithRuleEngine();
        GameRoom room = new GameRoom("room-rocket-finish");

        Player bot = new Player("bot", true);
        bot.setStatus("PLAYING");
        bot.getHandCards().add(card("JOKER", "\u5c0f\u738b", 14));
        bot.getHandCards().add(card("JOKER", "\u5927\u738b", 15));

        Player other = new Player("other");
        other.setStatus("PLAYING");
        other.getHandCards().add(card("\u2660", "3", 1));
        other.getHandCards().add(card("\u2665", "3", 1));
        other.getHandCards().add(card("\u2663", "3", 1));
        other.getHandCards().add(card("\u2666", "3", 1));
        other.getHandCards().add(card("\u2660", "4", 2));

        room.setPlayers(new ArrayList<>(List.of(bot, other)));
        room.setCurrentTurnIndex(0);
        room.setLastPlayedCards(new ArrayList<>());
        room.setLastPlayPlayerId("");

        ScriptedAiService.TurnDecision decision = service.decideTurn(room, bot);

        assertEquals(ScriptedAiService.TurnDecisionType.PLAY, decision.getType());
        assertEquals(2, decision.getCards().size(),
                "\u53ea\u5269\u738b\u70b8\u65f6\u5e94\u4e00\u624b\u6253\u51fa\u4e24\u5f20\u7687\uff0c\u4e0d\u5e94\u62c6\u5206");
    }

    @Test
    void botPlaysRocketToFinishEvenWhenRespondingToOpponent() throws Exception {
        // 回归：对手出单牌、AI 手里只剩小王 + 大王时，
        // 虽然单王也能压得住，但整手打王炸才能即刻获胜。
        ScriptedAiService service = createServiceWithRuleEngine();
        GameRoom room = new GameRoom("room-rocket-response");

        Player opponent = new Player("p1");
        opponent.setStatus("PLAYING");
        Player bot = new Player("bot", true);
        bot.setStatus("PLAYING");
        bot.getHandCards().add(card("JOKER", "\u5c0f\u738b", 14));
        bot.getHandCards().add(card("JOKER", "\u5927\u738b", 15));

        room.setPlayers(new ArrayList<>(List.of(opponent, bot)));
        room.setCurrentTurnIndex(1);
        room.setLastPlayPlayerId(opponent.getUserId());
        room.setLastPlayedCards(new ArrayList<>(List.of(card("\u2660", "5", 3))));

        ScriptedAiService.TurnDecision decision = service.decideTurn(room, bot);

        assertEquals(ScriptedAiService.TurnDecisionType.PLAY, decision.getType());
        assertEquals(2, decision.getCards().size(),
                "\u6700\u540e\u4e00\u624b\u738b\u70b8\u5e94\u76f4\u63a5\u6253\u51fa\u62ff\u4e0b\u80dc\u5229");
    }

    @Test
    void gushouBotPlaysRocketToWinInsteadOfSkippingTurn() throws Exception {
        // GUSHOU（固守）在 bestNormal 是炸弹/王炸时会选择跳过保留资源，
        // 但只剩王炸能直接获胜时，必须打出，不能跳过。
        ScriptedAiService service = createServiceWithRuleEngine();
        GameRoom room = new GameRoom("room-gushou-rocket-finish");

        Player opponent = new Player("p1");
        opponent.setStatus("PLAYING");
        Player bot = new Player("bot", true);
        bot.setStatus("PLAYING");
        bot.setSkill("GUSHOU");
        bot.getHandCards().add(card("JOKER", "\u5c0f\u738b", 14));
        bot.getHandCards().add(card("JOKER", "\u5927\u738b", 15));

        room.setPlayers(new ArrayList<>(List.of(opponent, bot)));
        room.setCurrentTurnIndex(1);
        room.setLastPlayPlayerId(opponent.getUserId());
        room.setLastPlayedCards(new ArrayList<>(List.of(card("\u2660", "5", 3))));

        ScriptedAiService.TurnDecision decision = service.decideTurn(room, bot);

        assertEquals(ScriptedAiService.TurnDecisionType.PLAY, decision.getType());
        assertEquals(2, decision.getCards().size());
    }

    @Test
    void gushouBotKeepsCheapSingleResponseInsteadOfUsingSkill() throws Exception {
        // 回归：之前固守会在“明明有很自然的单牌应对”时也抢先发动，
        // 导致白白把回合让掉。这种低破坏度回应应直接打出。
        ScriptedAiService service = createServiceWithRuleEngine();
        GameRoom room = new GameRoom("room-gushou-cheap-response");

        Player opponent = new Player("p1");
        opponent.setStatus("PLAYING");
        Player bot = new Player("bot", true);
        bot.setStatus("PLAYING");
        bot.setSkill("GUSHOU");
        bot.getHandCards().add(card("\u2660", "J", 9));
        bot.getHandCards().add(card("\u2665", "9", 7));
        bot.getHandCards().add(card("\u2663", "Q", 10));
        bot.getHandCards().add(card("JOKER", "\u5c0f\u738b", 14));
        bot.getHandCards().add(card("\u2666", "10", 8));
        bot.getHandCards().add(card("\u2663", "6", 4));
        bot.getHandCards().add(card("\u2660", "7", 5));
        bot.getHandCards().add(card("\u2665", "A", 12));

        room.setPlayers(new ArrayList<>(List.of(opponent, bot)));
        room.setCurrentTurnIndex(1);
        room.setLastPlayPlayerId(opponent.getUserId());
        room.setLastPlayedCards(new ArrayList<>(List.of(card("\u2663", "7", 5))));

        ScriptedAiService.TurnDecision decision = service.decideTurn(room, bot);

        assertEquals(ScriptedAiService.TurnDecisionType.PLAY, decision.getType());
        assertEquals(1, decision.getCards().size());
    }

    @Test
    void gushouBotUsesSkillWhenNoNormalResponseExists() throws Exception {
        ScriptedAiService service = createServiceWithRuleEngine();
        GameRoom room = new GameRoom("room-gushou-no-response");

        Player opponent = new Player("p1");
        opponent.setStatus("PLAYING");
        Player bot = new Player("bot", true);
        bot.setStatus("PLAYING");
        bot.setSkill("GUSHOU");
        bot.getHandCards().add(card("\u2660", "3", 1));
        bot.getHandCards().add(card("\u2665", "5", 3));
        bot.getHandCards().add(card("\u2663", "7", 5));
        bot.getHandCards().add(card("\u2666", "9", 7));

        room.setPlayers(new ArrayList<>(List.of(opponent, bot)));
        room.setCurrentTurnIndex(1);
        room.setLastPlayPlayerId(opponent.getUserId());
        room.setLastPlayedCards(new ArrayList<>(List.of(
                card("\u2660", "10", 8),
                card("\u2665", "10", 8)
        )));

        ScriptedAiService.TurnDecision decision = service.decideTurn(room, bot);

        assertEquals(ScriptedAiService.TurnDecisionType.USE_GUSHOU, decision.getType());
    }

    @Test
    void gushouBotPrefersScrollOverUsingSkillWhenScrollPlayIsAvailable() throws Exception {
        ScriptedAiService service = createServiceWithRuleEngine();
        GameRoom room = new GameRoom("room-gushou-scroll-over-skill");

        Player opponent = new Player("p1");
        opponent.setStatus("PLAYING");
        Player bot = new Player("bot", true);
        bot.setStatus("PLAYING");
        bot.setSkill("GUSHOU");
        bot.getHandCards().add(card("SCROLL", "JDSR", 19));
        bot.getHandCards().add(card("\u2660", "3", 1));
        bot.getHandCards().add(card("\u2665", "5", 3));
        bot.getHandCards().add(card("\u2663", "7", 5));

        room.setPlayers(new ArrayList<>(List.of(opponent, bot)));
        room.setCurrentTurnIndex(1);
        room.setLastPlayPlayerId(opponent.getUserId());
        room.setLastPlayedCards(new ArrayList<>(List.of(card("\u2666", "8", 6))));

        ScriptedAiService.TurnDecision decision = service.decideTurn(room, bot);

        assertEquals(ScriptedAiService.TurnDecisionType.PLAY, decision.getType());
        assertEquals("SCROLL", decision.getCards().get(0).getSuit());
        assertEquals("JDSR", decision.getCards().get(0).getRank());
    }

    @Test
    void luanjianBotPlaysBombToWinInsteadOfUsingSkill() throws Exception {
        // LUANJIAN（乱箭）平时会用 2 张黑牌触发 AoE 而留住炸弹，
        // 但只剩一手炸弹能直接获胜时，必须整手打出，不能消耗掉收官的炸弹。
        ScriptedAiService service = createServiceWithRuleEngine();
        GameRoom room = new GameRoom("room-luanjian-bomb-finish");

        Player bot = new Player("bot", true);
        bot.setStatus("PLAYING");
        bot.setSkill("LUANJIAN");
        bot.getHandCards().add(card("\u2660", "K", 11));
        bot.getHandCards().add(card("\u2665", "K", 11));
        bot.getHandCards().add(card("\u2663", "K", 11));
        bot.getHandCards().add(card("\u2666", "K", 11));

        Player a = new Player("a");
        a.setStatus("PLAYING");
        Player b = new Player("b");
        b.setStatus("PLAYING");
        a.getHandCards().add(card("\u2660", "3", 1));
        a.getHandCards().add(card("\u2660", "4", 2));
        a.getHandCards().add(card("\u2660", "5", 3));
        b.getHandCards().add(card("\u2665", "3", 1));
        b.getHandCards().add(card("\u2665", "4", 2));
        b.getHandCards().add(card("\u2665", "5", 3));

        room.setPlayers(new ArrayList<>(List.of(bot, a, b)));
        room.setCurrentTurnIndex(0);
        room.setLastPlayedCards(new ArrayList<>());
        room.setLastPlayPlayerId("");

        ScriptedAiService.TurnDecision decision = service.decideTurn(room, bot);

        assertEquals(ScriptedAiService.TurnDecisionType.PLAY, decision.getType());
        assertEquals(4, decision.getCards().size());
    }

    @Test
    void botPrefersWinningBombOverSplittingQuads() throws Exception {
        // 回归：手牌正好是四张相同 + 一张散牌，先打散牌再打炸弹是 2 回合，
        // 过程中不应把炸弹拆成 单/对/三带 等小牌。
        ScriptedAiService service = createServiceWithRuleEngine();
        GameRoom room = new GameRoom("room-bomb-finish");

        Player bot = new Player("bot", true);
        bot.setStatus("PLAYING");
        bot.getHandCards().add(card("\u2660", "K", 11));
        bot.getHandCards().add(card("\u2665", "K", 11));
        bot.getHandCards().add(card("\u2663", "K", 11));
        bot.getHandCards().add(card("\u2666", "K", 11));

        Player other = new Player("other");
        other.setStatus("PLAYING");
        other.getHandCards().add(card("\u2660", "3", 1));
        other.getHandCards().add(card("\u2665", "3", 1));
        other.getHandCards().add(card("\u2663", "3", 1));
        other.getHandCards().add(card("\u2666", "3", 1));
        other.getHandCards().add(card("\u2660", "4", 2));

        room.setPlayers(new ArrayList<>(List.of(bot, other)));
        room.setCurrentTurnIndex(0);
        room.setLastPlayedCards(new ArrayList<>());
        room.setLastPlayPlayerId("");

        ScriptedAiService.TurnDecision decision = service.decideTurn(room, bot);

        assertEquals(ScriptedAiService.TurnDecisionType.PLAY, decision.getType());
        assertEquals(4, decision.getCards().size(),
                "\u6700\u540e\u4e00\u624b\u70b8\u5f39\u5e94\u6574\u624b\u6253\u51fa\u8d62\u4e0b");
    }

    private ScriptedAiService createServiceWithRuleEngine() throws Exception {
        ScriptedAiService service = new ScriptedAiService();
        Field field = ScriptedAiService.class.getDeclaredField("ruleEngine");
        field.setAccessible(true);
        field.set(service, new RuleEngine());
        return service;
    }

    private Card card(String suit, String rank, int weight) {
        return Card.getCard(suit, rank, weight);
    }
}
