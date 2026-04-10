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
    void emergencyFreeTurnPlayIgnoresScrollCards() throws Exception {
        ScriptedAiService service = createServiceWithRuleEngine();
        Player bot = new Player("bot", true);
        bot.getHandCards().add(card("SCROLL", "WGFD", 18));
        bot.getHandCards().add(card("\u2663", "9", 7));
        bot.getHandCards().add(card("\u2665", "9", 7));

        List<Card> emergencyPlay = service.chooseEmergencyFreeTurnPlay(bot);

        assertEquals(List.of(card("\u2663", "9", 7), card("\u2665", "9", 7)), emergencyPlay);
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
