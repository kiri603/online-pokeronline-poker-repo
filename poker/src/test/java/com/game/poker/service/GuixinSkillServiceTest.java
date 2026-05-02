package com.game.poker.service;

import com.game.poker.model.Card;
import com.game.poker.model.GameRoom;
import com.game.poker.model.Player;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuixinSkillServiceTest {
    private static final String ROOM_ID = "room-guixin";
    private static final String OWNER = "owner";
    private static final String PASSER = "passer";
    private static final String THIRD = "third";

    @Test
    void activeGuixinDrawsForOthersDisablesSkillAndEndsTurn() {
        GameService service = new GameService();
        GameRoom room = makeRoom(service, true);
        Player owner = room.getPlayers().get(0);
        Player passer = room.getPlayers().get(1);
        Player third = room.getPlayers().get(2);
        fillDeck(room, 12);

        int ownerCards = owner.getHandCards().size();
        int passerCards = passer.getHandCards().size();
        int thirdCards = third.getHandCards().size();

        service.useGuixin(ROOM_ID, OWNER);

        assertEquals(ownerCards, owner.getHandCards().size());
        assertEquals(passerCards + 2, passer.getHandCards().size());
        assertEquals(thirdCards + 2, third.getHandCards().size());
        assertTrue(owner.isGuixinDisabled());
        assertEquals(PASSER, room.getPlayers().get(room.getCurrentTurnIndex()).getUserId());
    }

    @Test
    void disabledGuixinCanProtectPasserAndRecover() {
        GameService service = new GameService();
        GameRoom room = makeRoom(service, false);
        Player owner = room.getPlayers().get(0);
        Player passer = room.getPlayers().get(1);
        owner.setGuixinDisabled(true);
        fillDeck(room, 8);
        int passerCards = passer.getHandCards().size();

        service.passTurn(ROOM_ID, PASSER);

        assertEquals(GameService.GUIXIN_DECISION, room.getCurrentAoeType());
        assertEquals(passerCards, passer.getHandCards().size());

        boolean accepted = service.resolveGuixinDecision(ROOM_ID, OWNER, true);

        assertTrue(accepted);
        assertFalse(owner.isGuixinDisabled());
        assertEquals(passerCards, passer.getHandCards().size());
        assertEquals(THIRD, room.getPlayers().get(room.getCurrentTurnIndex()).getUserId());
    }

    @Test
    void rejectingGuixinKeepsGushouProtectionAndConsumesIt() {
        GameService service = new GameService();
        GameRoom room = makeRoom(service, false);
        Player owner = room.getPlayers().get(0);
        Player passer = room.getPlayers().get(1);
        owner.setGuixinDisabled(true);
        room.getSettings().put("gushou_active_" + PASSER, true);
        fillDeck(room, 8);
        int passerCards = passer.getHandCards().size();

        service.passTurn(ROOM_ID, PASSER);
        boolean accepted = service.resolveGuixinDecision(ROOM_ID, OWNER, false);

        assertFalse(accepted);
        assertTrue(owner.isGuixinDisabled());
        assertEquals(passerCards, passer.getHandCards().size());
        assertFalse(room.getSettings().containsKey("gushou_active_" + PASSER));
    }

    @Test
    void directGushouOnGuixinOwnersTableStartsDecisionAndCanRecover() {
        GameService service = new GameService();
        GameRoom room = makeRoom(service, false);
        Player owner = room.getPlayers().get(0);
        Player passer = room.getPlayers().get(1);
        passer.setSkill("GUSHOU");
        owner.setGuixinDisabled(true);
        fillDeck(room, 12);
        int passerCards = passer.getHandCards().size();

        service.useGushou(ROOM_ID, PASSER);

        assertEquals(GameService.GUIXIN_DECISION, room.getCurrentAoeType());
        assertEquals(passerCards, passer.getHandCards().size());
        assertFalse(room.getSettings().containsKey("gushou_active_" + PASSER));
        assertFalse(passer.isHasUsedSkillThisTurn());

        boolean accepted = service.resolveGuixinDecision(ROOM_ID, OWNER, true);

        assertTrue(accepted);
        assertFalse(owner.isGuixinDisabled());
        assertEquals(passerCards, passer.getHandCards().size());
        assertFalse(room.getSettings().containsKey("gushou_active_" + PASSER));
        assertEquals(THIRD, room.getPlayers().get(room.getCurrentTurnIndex()).getUserId());
    }

    @Test
    void rejectingGuixinOnGushouContinuesGushouEffect() {
        GameService service = new GameService();
        GameRoom room = makeRoom(service, false);
        Player owner = room.getPlayers().get(0);
        Player passer = room.getPlayers().get(1);
        passer.setSkill("GUSHOU");
        owner.setGuixinDisabled(true);
        fillDeck(room, 12);
        int passerCards = passer.getHandCards().size();

        service.useGushou(ROOM_ID, PASSER);
        boolean accepted = service.resolveGuixinDecision(ROOM_ID, OWNER, false);

        assertFalse(accepted);
        assertTrue(owner.isGuixinDisabled());
        assertTrue(passer.isHasUsedSkillThisTurn());
        assertEquals(passerCards + 4, passer.getHandCards().size());
        assertTrue(room.getSettings().containsKey("gushou_active_" + PASSER));
        assertEquals(THIRD, room.getPlayers().get(room.getCurrentTurnIndex()).getUserId());
    }

    @Test
    void guanxingPassOnGuixinOwnersTableStartsDecisionBeforeSelection() {
        GameService service = new GameService();
        GameRoom room = makeRoom(service, false);
        Player owner = room.getPlayers().get(0);
        Player passer = room.getPlayers().get(1);
        passer.setSkill("GUANXING");
        owner.setGuixinDisabled(true);
        fillDeck(room, 12);
        int passerCards = passer.getHandCards().size();

        service.passTurn(ROOM_ID, PASSER);

        assertEquals(GameService.GUIXIN_DECISION, room.getCurrentAoeType());
        assertTrue(room.getPendingAoePlayers().contains(OWNER));
        assertFalse(room.getSettings().containsKey("guanxingCards"));
        assertEquals(passerCards, passer.getHandCards().size());
        assertFalse(passer.isHasUsedSkillThisTurn());
        assertTrue(owner.isGuixinDisabled());

        boolean accepted = service.resolveGuixinDecision(ROOM_ID, OWNER, true);

        assertTrue(accepted);
        assertFalse(owner.isGuixinDisabled());
        assertEquals(passerCards, passer.getHandCards().size());
        assertEquals(THIRD, room.getPlayers().get(room.getCurrentTurnIndex()).getUserId());
    }

    @Test
    void rejectingGuixinOnGuanxingStartsGuanxingNormally() {
        GameService service = new GameService();
        GameRoom room = makeRoom(service, false);
        Player owner = room.getPlayers().get(0);
        Player passer = room.getPlayers().get(1);
        passer.setSkill("GUANXING");
        owner.setGuixinDisabled(true);
        fillDeck(room, 12);
        int passerCards = passer.getHandCards().size();

        service.passTurn(ROOM_ID, PASSER);
        boolean accepted = service.resolveGuixinDecision(ROOM_ID, OWNER, false);

        assertFalse(accepted);
        assertTrue(owner.isGuixinDisabled());
        assertTrue(passer.isHasUsedSkillThisTurn());
        assertEquals("GUANXING", room.getCurrentAoeType());
        assertTrue(room.getPendingAoePlayers().contains(PASSER));
        assertTrue(room.getSettings().containsKey("guanxingCards"));
        assertEquals(passerCards, passer.getHandCards().size());

        @SuppressWarnings("unchecked")
        List<Card> guanxingCards = (List<Card>) room.getSettings().get("guanxingCards");
        service.resolveGuanxing(ROOM_ID, PASSER, guanxingCards.subList(0, 2));

        assertEquals(passerCards + 2, passer.getHandCards().size());
        assertEquals(THIRD, room.getPlayers().get(room.getCurrentTurnIndex()).getUserId());
    }

    @Test
    void availableGuixinDoesNotDelayNormalPass() {
        GameService service = new GameService();
        GameRoom room = makeRoom(service, false);
        Player owner = room.getPlayers().get(0);
        Player passer = room.getPlayers().get(1);
        assertFalse(owner.isGuixinDisabled());
        fillDeck(room, 8);
        int passerCards = passer.getHandCards().size();

        service.passTurn(ROOM_ID, PASSER);

        assertEquals(passerCards + 2, passer.getHandCards().size());
        assertFalse(GameService.GUIXIN_DECISION.equals(room.getCurrentAoeType()));
    }

    private GameRoom makeRoom(GameService service, boolean ownerTurn) {
        GameRoom room = new GameRoom(ROOM_ID);
        room.getDeck().clear();

        Player owner = new Player(OWNER);
        owner.setSkill("GUIXIN");
        owner.setStatus("PLAYING");
        seedHand(owner, 4);

        Player passer = new Player(PASSER);
        passer.setStatus("PLAYING");
        seedHand(passer, 4);

        Player third = new Player(THIRD);
        third.setStatus("PLAYING");
        seedHand(third, 4);

        room.setPlayers(new ArrayList<>(List.of(owner, passer, third)));
        room.setStarted(true);
        room.setCurrentTurnIndex(ownerTurn ? 0 : 1);
        room.setLastPlayPlayerId(OWNER);
        room.setLastPlayedCards(new ArrayList<>(List.of(Card.getCard("\u2660", "9", 7))));
        service.getRoomMap().put(ROOM_ID, room);
        return room;
    }

    private void seedHand(Player player, int count) {
        for (int i = 0; i < count; i++) {
            player.getHandCards().add(Card.getCard("\u2665", String.valueOf(i + 3), i + 1));
        }
    }

    private void fillDeck(GameRoom room, int count) {
        for (int i = 0; i < count; i++) {
            room.getDeck().add(Card.getCard("\u2663", String.valueOf(i + 3), i + 1));
        }
    }
}
