package com.game.poker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.poker.model.Card;
import com.game.poker.model.GameRoom;
import com.game.poker.model.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillMatchupSimulationTest {

    private static final String BLANK_SKILL = "BLANK";
    private static final List<String> SKILLS = List.of(
            "ZHIHENG",
            "LUANJIAN",
            "GUANXING",
            "GUSHOU",
            "KUROU",
            "TIEQI",
            "GUIXIN"
    );

    private static final int GAMES_PER_MATCHUP = Integer.getInteger("skill.matchup.games", 50);
    private static final int GAMES_PER_SKILL_VS_BLANKS = Integer.getInteger("skill.vs.blanks.games", 150);
    private static final int FREE_FOR_ALL_GAMES = Integer.getInteger("skill.ffa.games", 1_000);
    private static final int FREE_FOR_ALL_PLAYERS = 4;
    private static final int MAX_ACTIONS_PER_GAME = Integer.getInteger("skill.max.actions", 10_000);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void generateSkillMatchupReport() throws Exception {
        Report report = generateReport(false, "skill-matchup-report.json");
        assertReport(report);
    }

    @Test
    void generateSkillMatchupReportWithScrollCards() throws Exception {
        Report report = generateReport(true, "skill-matchup-report-scrolls.json");
        assertReport(report);
    }

    @Test
    void generateBlankVsGushouOneOnOneReportWithScrollCards() throws Exception {
        MatchupResult matchup = simulateMatchup(BLANK_SKILL, "GUSHOU", true);

        SingleMatchupReport report = new SingleMatchupReport();
        report.configuration = new Configuration();
        report.configuration.gamesPerMatchup = GAMES_PER_MATCHUP;
        report.configuration.enableSkills = true;
        report.configuration.enableScrollCards = true;
        report.configuration.maxActionsPerGame = MAX_ACTIONS_PER_GAME;
        report.configuration.skillOrder = new ArrayList<>(List.of(BLANK_SKILL, "GUSHOU"));
        report.notes = List.of(
                "BLANK is a test-only no-skill sentinel.",
                "It still uses the existing AI for normal card play and scroll-card decisions.",
                "No skill-specific branch in ScriptedAiService or GameService should trigger for BLANK."
        );
        report.matchup = matchup;

        Path output = Path.of("target", "blank-vs-gushou-scrolls.json");
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);

        assertEquals(GAMES_PER_MATCHUP, matchup.games);
        assertEquals(0, matchup.unresolvedGames);
        assertEquals(GAMES_PER_MATCHUP, matchup.winsA + matchup.winsB);
    }

    @Test
    void generateBlankVsAllOneOnOneReportWithScrollCards() throws Exception {
        BlankVsAllReport report = new BlankVsAllReport();
        report.configuration = new Configuration();
        report.configuration.gamesPerMatchup = GAMES_PER_MATCHUP;
        report.configuration.enableSkills = true;
        report.configuration.enableScrollCards = true;
        report.configuration.maxActionsPerGame = MAX_ACTIONS_PER_GAME;
        report.configuration.skillOrder = new ArrayList<>();
        report.configuration.skillOrder.add(BLANK_SKILL);
        report.configuration.skillOrder.addAll(SKILLS);
        report.notes = List.of(
                "BLANK is a test-only no-skill sentinel.",
                "It still uses the existing AI for normal card play and scroll-card decisions.",
                "No skill-specific branch in ScriptedAiService or GameService should trigger for BLANK."
        );

        report.matchups = new ArrayList<>();
        BlankAggregateSummary blankSummary = new BlankAggregateSummary();
        blankSummary.skill = BLANK_SKILL;
        for (String skill : SKILLS) {
            MatchupResult matchup = simulateMatchup(BLANK_SKILL, skill, true);
            report.matchups.add(matchup);

            blankSummary.totalGames += matchup.games;
            blankSummary.wins += matchup.winsA;
            blankSummary.losses += matchup.winsB;
            blankSummary.unresolvedGames += matchup.unresolvedGames;
        }
        blankSummary.winRate = round2(blankSummary.wins * 100.0 / blankSummary.totalGames);
        report.blankSummary = blankSummary;

        Path output = Path.of("target", "blank-vs-all-skills-scrolls.json");
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);

        assertEquals(SKILLS.size(), report.matchups.size());
        assertEquals(SKILLS.size() * GAMES_PER_MATCHUP, blankSummary.totalGames);
        assertEquals(0, blankSummary.unresolvedGames);
    }

    @Test
    void generateSkillVersusThreeBlanksFreeForAllReportWithScrollCards() throws Exception {
        SkillVsBlanksReport report = generateSkillVersusBlanksReport(true, "skill-vs-three-blanks-ffa-scrolls.json");

        assertEquals(SKILLS.size(), report.summaries.size());
        assertTrue(report.summaries.stream().allMatch(summary -> summary.games == GAMES_PER_SKILL_VS_BLANKS));
        assertTrue(report.summaries.stream().allMatch(summary -> summary.unresolvedGames == 0));
        assertTrue(report.summaries.stream().allMatch(summary -> summary.wins + summary.losses == GAMES_PER_SKILL_VS_BLANKS));
    }

    @Test
    void generateFourPlayerRandomUniqueSkillReportWithScrollCards() throws Exception {
        FreeForAllReport report = generateFreeForAllReport(true, "skill-ffa-report-scrolls.json");
        assertFreeForAllReport(report);
    }

    private Report generateReport(boolean enableScrollCards, String outputFileName) throws Exception {
        Report report = new Report();
        report.configuration = new Configuration();
        report.configuration.gamesPerMatchup = GAMES_PER_MATCHUP;
        report.configuration.enableSkills = true;
        report.configuration.enableScrollCards = enableScrollCards;
        report.configuration.maxActionsPerGame = MAX_ACTIONS_PER_GAME;
        report.configuration.skillOrder = new ArrayList<>(SKILLS);

        report.matchups = new ArrayList<>();
        for (int i = 0; i < SKILLS.size(); i++) {
            for (int j = i + 1; j < SKILLS.size(); j++) {
                report.matchups.add(simulateMatchup(SKILLS.get(i), SKILLS.get(j), enableScrollCards));
            }
        }

        report.matrix = buildMatrix(report.matchups);
        report.summaries = buildSummaries(report.matchups);

        Path output = Path.of("target", outputFileName);
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        return report;
    }

    private void assertReport(Report report) {
        assertEquals(SKILLS.size() * (SKILLS.size() - 1) / 2, report.matchups.size());
        assertTrue(report.matchups.stream().allMatch(matchup -> matchup.games == GAMES_PER_MATCHUP));
        assertTrue(report.matchups.stream().allMatch(matchup -> matchup.unresolvedGames == 0));
    }

    private FreeForAllReport generateFreeForAllReport(boolean enableScrollCards, String outputFileName) throws Exception {
        FreeForAllReport report = new FreeForAllReport();
        report.configuration = new FreeForAllConfiguration();
        report.configuration.totalGames = FREE_FOR_ALL_GAMES;
        report.configuration.playersPerGame = FREE_FOR_ALL_PLAYERS;
        report.configuration.enableSkills = true;
        report.configuration.enableScrollCards = enableScrollCards;
        report.configuration.randomUniqueSkillsPerGame = true;
        report.configuration.maxActionsPerGame = MAX_ACTIONS_PER_GAME;
        report.configuration.skillPool = new ArrayList<>(SKILLS);

        Map<String, FreeForAllSkillSummary> summaryBySkill = new LinkedHashMap<>();
        for (String skill : SKILLS) {
            FreeForAllSkillSummary summary = new FreeForAllSkillSummary();
            summary.skill = skill;
            summaryBySkill.put(skill, summary);
        }

        Random random = new Random();
        long totalActions = 0L;
        int unresolvedGames = 0;
        for (int gameIndex = 0; gameIndex < FREE_FOR_ALL_GAMES; gameIndex++) {
            List<String> selectedSkills = drawUniqueSkills(random, FREE_FOR_ALL_PLAYERS);
            SingleGameResult gameResult = simulateSingleGame(selectedSkills, gameIndex, enableScrollCards, "ffa");
            totalActions += gameResult.actions;

            if (gameResult.winnerSkill == null) {
                unresolvedGames++;
            }

            for (String skill : selectedSkills) {
                FreeForAllSkillSummary summary = summaryBySkill.get(skill);
                summary.appearances++;
                if (skill.equals(gameResult.winnerSkill)) {
                    summary.wins++;
                } else if (gameResult.winnerSkill == null) {
                    summary.unresolvedGames++;
                } else {
                    summary.losses++;
                }
            }
        }

        report.totalGames = FREE_FOR_ALL_GAMES;
        report.unresolvedGames = unresolvedGames;
        report.averageActionsPerGame = round2(totalActions * 1.0 / FREE_FOR_ALL_GAMES);
        report.summaries = summaryBySkill.values().stream()
                .peek(summary -> summary.winRate = round2(summary.wins * 100.0 / summary.appearances))
                .sorted(Comparator.comparingDouble((FreeForAllSkillSummary summary) -> summary.winRate).reversed()
                        .thenComparing(summary -> summary.skill))
                .toList();

        Path output = Path.of("target", outputFileName);
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        return report;
    }

    private void assertFreeForAllReport(FreeForAllReport report) {
        assertEquals(FREE_FOR_ALL_GAMES, report.totalGames);
        assertEquals(0, report.unresolvedGames);
        assertEquals(SKILLS.size(), report.summaries.size());
        assertTrue(report.summaries.stream()
                .allMatch(summary -> summary.appearances == summary.wins + summary.losses + summary.unresolvedGames));
    }

    private SkillVsBlanksReport generateSkillVersusBlanksReport(boolean enableScrollCards, String outputFileName) throws Exception {
        SkillVsBlanksReport report = new SkillVsBlanksReport();
        report.configuration = new SkillVsBlanksConfiguration();
        report.configuration.gamesPerSkill = GAMES_PER_SKILL_VS_BLANKS;
        report.configuration.playersPerGame = FREE_FOR_ALL_PLAYERS;
        report.configuration.blankOpponents = FREE_FOR_ALL_PLAYERS - 1;
        report.configuration.enableSkills = true;
        report.configuration.enableScrollCards = enableScrollCards;
        report.configuration.rotateSkillSeat = true;
        report.configuration.maxActionsPerGame = MAX_ACTIONS_PER_GAME;
        report.configuration.testedSkills = new ArrayList<>(SKILLS);
        report.notes = List.of(
                "Each game contains exactly one tested skill and three BLANK bots.",
                "BLANK is a test-only no-skill sentinel that still uses normal play and scroll-card logic.",
                "The tested skill rotates through all four seats to reduce seat-order bias."
        );

        List<SkillVsBlanksSummary> summaries = new ArrayList<>();
        for (String skill : SKILLS) {
            SkillVsBlanksSummary summary = new SkillVsBlanksSummary();
            summary.skill = skill;

            long totalActions = 0L;
            for (int gameIndex = 0; gameIndex < GAMES_PER_SKILL_VS_BLANKS; gameIndex++) {
                List<String> lineup = buildSkillVersusBlanksLineup(skill, gameIndex);
                SingleGameResult gameResult = simulateSingleGame(lineup, gameIndex, enableScrollCards, "skill-vs-blanks-" + skill);
                totalActions += gameResult.actions;

                summary.games++;
                if (skill.equals(gameResult.winnerSkill)) {
                    summary.wins++;
                } else if (gameResult.winnerSkill == null) {
                    summary.unresolvedGames++;
                } else {
                    summary.losses++;
                }
            }

            summary.winRate = round2(summary.wins * 100.0 / summary.games);
            summary.averageActions = round2(totalActions * 1.0 / summary.games);
            summaries.add(summary);
        }

        report.summaries = summaries.stream()
                .sorted(Comparator.comparingDouble((SkillVsBlanksSummary summary) -> summary.winRate).reversed()
                        .thenComparing(summary -> summary.skill))
                .toList();

        Path output = Path.of("target", outputFileName);
        Files.createDirectories(output.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        return report;
    }

    private MatchupResult simulateMatchup(String skillA, String skillB, boolean enableScrollCards) throws Exception {
        MatchupResult result = new MatchupResult();
        result.skillA = skillA;
        result.skillB = skillB;
        result.games = GAMES_PER_MATCHUP;

        long totalActions = 0L;
        for (int gameIndex = 0; gameIndex < GAMES_PER_MATCHUP; gameIndex++) {
            List<String> lineup = gameIndex % 2 == 0
                    ? List.of(skillA, skillB)
                    : List.of(skillB, skillA);
            SingleGameResult gameResult = simulateSingleGame(lineup, gameIndex, enableScrollCards, "matchup");
            totalActions += gameResult.actions;

            if (skillA.equals(gameResult.winnerSkill)) {
                result.winsA++;
            } else if (skillB.equals(gameResult.winnerSkill)) {
                result.winsB++;
            } else {
                result.unresolvedGames++;
            }
        }

        result.winRateA = round2(result.winsA * 100.0 / result.games);
        result.winRateB = round2(result.winsB * 100.0 / result.games);
        result.averageActions = round2(totalActions * 1.0 / result.games);
        return result;
    }

    private SingleGameResult simulateSingleGame(String skillA, String skillB, int gameIndex, boolean enableScrollCards) throws Exception {
        return simulateSingleGame(List.of(skillA, skillB), gameIndex, enableScrollCards, "matchup");
    }

    private SingleGameResult simulateSingleGame(List<String> skills, int gameIndex, boolean enableScrollCards, String roomTag) throws Exception {
        GameService gameService = createGameServiceWithRuleEngine();
        ScriptedAiService aiService = createAiServiceWithRuleEngine();

        String roomId = "sim-" + roomTag + "-" + (enableScrollCards ? "scrolls-" : "base-") + gameIndex;
        GameRoom room = new GameRoom(roomId);
        room.setOwnerId(botIdAt(0));
        room.getSettings().put("enableSkills", true);
        room.getSettings().put("enableScrollCards", enableScrollCards);

        Map<String, String> skillByUserId = new LinkedHashMap<>();
        List<Player> players = new ArrayList<>();
        for (int index = 0; index < skills.size(); index++) {
            String userId = botIdAt(index);
            String skill = skills.get(index);

            Player bot = new Player(userId, true);
            bot.setSkill(skill);
            players.add(bot);
            skillByUserId.put(userId, skill);
        }

        room.setPlayers(players);
        gameService.getRoomMap().put(roomId, room);
        gameService.doStartGame(room);

        for (int action = 0; action < MAX_ACTIONS_PER_GAME; action++) {
            Player winner = findWinner(room);
            if (winner != null) {
                return new SingleGameResult(skillByUserId.get(winner.getUserId()), action);
            }

            if (room.getCurrentAoeType() != null) {
                runPendingEffect(gameService, aiService, room);
            } else {
                runTurn(gameService, aiService, room);
            }
        }

        return new SingleGameResult(null, MAX_ACTIONS_PER_GAME);
    }

    private List<String> drawUniqueSkills(Random random, int count) {
        List<String> pool = new ArrayList<>(SKILLS);
        Collections.shuffle(pool, random);
        return new ArrayList<>(pool.subList(0, count));
    }

    private List<String> buildSkillVersusBlanksLineup(String skill, int gameIndex) {
        List<String> lineup = new ArrayList<>(Collections.nCopies(FREE_FOR_ALL_PLAYERS, BLANK_SKILL));
        lineup.set(gameIndex % FREE_FOR_ALL_PLAYERS, skill);
        return lineup;
    }

    private String botIdAt(int index) {
        return "bot-" + (char) ('a' + index);
    }

    private void runPendingEffect(GameService gameService, ScriptedAiService aiService, GameRoom room) {
        Player bot = room.getPlayers().stream()
                .filter(Player::isBot)
                .filter(player -> room.getPendingAoePlayers().contains(player.getUserId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No pending bot for effect " + room.getCurrentAoeType()));

        switch (room.getCurrentAoeType()) {
            case "GUANXING" -> {
                List<Card> options = readCardListSetting(room, "guanxingCards");
                if (options.isEmpty()) {
                    throw new IllegalStateException("GUANXING started without candidate cards");
                }
                int expected = Math.min(2, options.size());
                List<Card> selected = aiService.chooseGuanxingCards(bot, options);
                if (selected.size() != expected) {
                    selected = new ArrayList<>(options.subList(0, expected));
                }
                gameService.resolveGuanxing(room.getRoomId(), bot.getUserId(), selected);
            }
            case "WGFD" -> {
                List<Card> options = readCardListSetting(room, "wgfdCards");
                if (options.isEmpty()) {
                    throw new IllegalStateException("WGFD started without candidate cards");
                }
                Card selected = aiService.chooseWgfdCard(bot, options);
                if (selected == null) {
                    selected = options.get(0);
                }
                gameService.resolveWgfd(room.getRoomId(), bot.getUserId(), selected);
            }
            case "GUSHOU_DISCARD" -> {
                int discardCount = Math.min(2, bot.getHandCards().size());
                List<Card> cards = aiService.chooseGushouDiscards(bot, discardCount);
                if (cards.size() != discardCount) {
                    cards = new ArrayList<>(bot.getHandCards().subList(0, discardCount));
                }
                gameService.discardGushou(room.getRoomId(), bot.getUserId(), cards);
            }
            case "KUROU_AWAKEN_DISCARD" -> {
                Card discard = aiService.chooseKurouAwakenDiscardCard(bot);
                gameService.kurouAwakenDiscard(room.getRoomId(), bot.getUserId(), discard);
            }
            case GameService.GUIXIN_DECISION -> {
                String passerId = gameService.getPendingGuixinPasser(room.getRoomId());
                Player passer = room.getPlayers().stream()
                        .filter(player -> player.getUserId().equals(passerId))
                        .findFirst()
                        .orElse(null);
                boolean accept = aiService.chooseGuixinProtection(room, bot, passer);
                gameService.resolveGuixinDecision(room.getRoomId(), bot.getUserId(), accept);
            }
            default -> {
                Card responseCard = aiService.chooseAoeResponseCard(room, bot);
                gameService.respondAoe(room.getRoomId(), bot.getUserId(), responseCard);
            }
        }
    }

    private void runTurn(GameService gameService, ScriptedAiService aiService, GameRoom room) {
        if (room.getPlayers().isEmpty()) {
            throw new IllegalStateException("Room has no players");
        }
        int turnIndex = room.getCurrentTurnIndex();
        if (turnIndex < 0 || turnIndex >= room.getPlayers().size()) {
            throw new IllegalStateException("Invalid turn index " + turnIndex);
        }

        Player bot = room.getPlayers().get(turnIndex);
        if (!bot.isBot() || !"PLAYING".equals(bot.getStatus())) {
            throw new IllegalStateException("Current turn is not owned by an active bot: " + bot.getUserId());
        }

        ScriptedAiService.TurnDecision decision = aiService.decideTurn(room, bot);
        if (decision == null) {
            decision = ScriptedAiService.TurnDecision.pass();
        }

        switch (decision.getType()) {
            case PLAY -> playWithRecovery(gameService, aiService, room, bot, decision.getCards());
            case PASS -> passWithRecovery(gameService, aiService, room, bot);
            case REPLACE -> {
                if (decision.getCards().isEmpty()) {
                    throw new IllegalStateException("Replace decision did not include a card");
                }
                replaceWithRecovery(gameService, room, bot, decision.getCards().get(0));
            }
            case USE_SKILL -> {
                if ("LUANJIAN".equals(decision.getSkill())) {
                    luanjianWithRecovery(gameService, room, bot, decision.getCards());
                } else if ("GUIXIN".equals(decision.getSkill())) {
                    guixinWithRecovery(gameService, room, bot);
                } else {
                    throw new IllegalStateException("Unsupported scripted skill action: " + decision.getSkill());
                }
            }
            case USE_GUSHOU -> gushouWithRecovery(gameService, room, bot);
            case USE_KUROU -> kurouWithRecovery(gameService, room, bot, decision.getCards());
        }
    }

    private void playWithRecovery(GameService gameService, ScriptedAiService aiService, GameRoom room, Player bot, List<Card> cards) {
        try {
            if (gameService.playCards(room.getRoomId(), bot.getUserId(), cards)) {
                return;
            }
        } catch (RuntimeException ignored) {
            // Fall through to the same recovery path used for rejected plays.
        }

        if (tryEmergencyFreeTurnPlay(gameService, aiService, room, bot)) {
            return;
        }
        if (tryFallbackPass(gameService, room, bot)) {
            return;
        }
        throw new IllegalStateException("Bot play failed without a valid recovery path: " + bot.getSkill());
    }

    private void passWithRecovery(GameService gameService, ScriptedAiService aiService, GameRoom room, Player bot) {
        try {
            gameService.passTurn(room.getRoomId(), bot.getUserId());
            return;
        } catch (RuntimeException ignored) {
            // Try to recover the same way the websocket handler does.
        }

        if (tryEmergencyFreeTurnPlay(gameService, aiService, room, bot)) {
            return;
        }
        if (tryFallbackPass(gameService, room, bot)) {
            return;
        }
        throw new IllegalStateException("Bot pass failed without a valid recovery path: " + bot.getSkill());
    }

    private void replaceWithRecovery(GameService gameService, GameRoom room, Player bot, Card discardCard) {
        try {
            if (gameService.replaceCard(room.getRoomId(), bot.getUserId(), discardCard)) {
                return;
            }
        } catch (RuntimeException ignored) {
            // Fall through to fallback logic.
        }

        if (tryFallbackPass(gameService, room, bot)) {
            return;
        }
        throw new IllegalStateException("Bot replace failed without a valid fallback: " + bot.getSkill());
    }

    private void luanjianWithRecovery(GameService gameService, GameRoom room, Player bot, List<Card> cards) {
        try {
            gameService.useLuanjian(room.getRoomId(), bot.getUserId(), cards);
            return;
        } catch (RuntimeException ignored) {
            // Fall through to fallback logic.
        }

        if (tryFallbackPass(gameService, room, bot)) {
            return;
        }
        throw new IllegalStateException("Bot LUANJIAN failed without a valid fallback");
    }

    private void gushouWithRecovery(GameService gameService, GameRoom room, Player bot) {
        try {
            gameService.useGushou(room.getRoomId(), bot.getUserId());
            return;
        } catch (RuntimeException ignored) {
            // Fall through to fallback logic.
        }

        if (tryFallbackPass(gameService, room, bot)) {
            return;
        }
        throw new IllegalStateException("Bot GUSHOU failed without a valid fallback");
    }

    private void guixinWithRecovery(GameService gameService, GameRoom room, Player bot) {
        try {
            gameService.useGuixin(room.getRoomId(), bot.getUserId());
            return;
        } catch (RuntimeException ignored) {
            // Fall through to fallback logic.
        }

        if (tryFallbackPass(gameService, room, bot)) {
            return;
        }
        throw new IllegalStateException("Bot GUIXIN failed without a valid fallback");
    }

    private void kurouWithRecovery(GameService gameService, GameRoom room, Player bot, List<Card> cards) {
        try {
            gameService.useKurou(room.getRoomId(), bot.getUserId(), cards);
            return;
        } catch (RuntimeException ignored) {
            // Fall through to fallback logic.
        }

        if (tryFallbackPass(gameService, room, bot)) {
            return;
        }
        throw new IllegalStateException("Bot KUROU failed without a valid fallback");
    }

    private boolean tryEmergencyFreeTurnPlay(GameService gameService, ScriptedAiService aiService, GameRoom room, Player bot) {
        if (room.getCurrentAoeType() != null || room.getPlayers().isEmpty()) {
            return false;
        }

        int turnIndex = room.getCurrentTurnIndex();
        if (turnIndex < 0 || turnIndex >= room.getPlayers().size()) {
            return false;
        }

        Player currentPlayer = room.getPlayers().get(turnIndex);
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

        List<Card> emergencyPlay = aiService.chooseEmergencyFreeTurnPlay(bot);
        if (emergencyPlay.isEmpty()) {
            return false;
        }

        try {
            return gameService.playCards(room.getRoomId(), bot.getUserId(), emergencyPlay);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean tryFallbackPass(GameService gameService, GameRoom room, Player bot) {
        boolean jdsrTarget = room.getSettings().containsKey("jdsr_target")
                && bot.getUserId().equals(room.getSettings().get("jdsr_target"));
        boolean responseTurn = jdsrTarget
                || (!room.getLastPlayedCards().isEmpty() && !bot.getUserId().equals(room.getLastPlayPlayerId()));
        boolean onlyHasScrolls = bot.getHandCards().stream().allMatch(card -> "SCROLL".equals(card.getSuit()));

        if (!responseTurn && !onlyHasScrolls) {
            return false;
        }

        try {
            gameService.passTurn(room.getRoomId(), bot.getUserId());
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private Player findWinner(GameRoom room) {
        List<Player> winners = room.getPlayers().stream()
                .filter(player -> "WON".equals(player.getStatus()))
                .toList();
        if (winners.size() > 1) {
            throw new IllegalStateException("Multiple winners detected in room " + room.getRoomId());
        }
        if (!winners.isEmpty()) {
            return winners.get(0);
        }

        long activePlayers = room.getPlayers().stream()
                .filter(player -> "PLAYING".equals(player.getStatus()))
                .count();
        if (activePlayers == 0) {
            throw new IllegalStateException("No active players and no winner in room " + room.getRoomId());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Card> readCardListSetting(GameRoom room, String key) {
        Object raw = room.getSettings().get(key);
        if (raw instanceof List<?> cards) {
            return (List<Card>) cards;
        }
        return List.of();
    }

    private Map<String, Map<String, Double>> buildMatrix(List<MatchupResult> matchups) {
        Map<String, Map<String, Double>> matrix = new LinkedHashMap<>();
        for (String skill : SKILLS) {
            Map<String, Double> row = new LinkedHashMap<>();
            for (String opponent : SKILLS) {
                row.put(opponent, skill.equals(opponent) ? null : 0.0);
            }
            matrix.put(skill, row);
        }

        for (MatchupResult matchup : matchups) {
            matrix.get(matchup.skillA).put(matchup.skillB, matchup.winRateA);
            matrix.get(matchup.skillB).put(matchup.skillA, matchup.winRateB);
        }
        return matrix;
    }

    private List<SkillSummary> buildSummaries(List<MatchupResult> matchups) {
        Map<String, SkillSummary> totals = new LinkedHashMap<>();
        for (String skill : SKILLS) {
            SkillSummary summary = new SkillSummary();
            summary.skill = skill;
            totals.put(skill, summary);
        }

        for (MatchupResult matchup : matchups) {
            applyMatchup(totals.get(matchup.skillA), matchup.games, matchup.winsA, matchup.winsB, matchup.unresolvedGames);
            applyMatchup(totals.get(matchup.skillB), matchup.games, matchup.winsB, matchup.winsA, matchup.unresolvedGames);
        }

        return totals.values().stream()
                .peek(summary -> summary.winRate = round2(summary.wins * 100.0 / summary.totalGames))
                .sorted(Comparator.comparingDouble((SkillSummary summary) -> summary.winRate).reversed()
                        .thenComparing(summary -> summary.skill))
                .toList();
    }

    private void applyMatchup(SkillSummary summary, int games, int wins, int losses, int unresolvedGames) {
        summary.totalGames += games;
        summary.wins += wins;
        summary.losses += losses;
        summary.unresolvedGames += unresolvedGames;
    }

    private GameService createGameServiceWithRuleEngine() throws Exception {
        GameService service = new GameService();
        Field field = GameService.class.getDeclaredField("ruleEngine");
        field.setAccessible(true);
        field.set(service, new RuleEngine());
        return service;
    }

    private ScriptedAiService createAiServiceWithRuleEngine() throws Exception {
        ScriptedAiService service = new ScriptedAiService();
        Field field = ScriptedAiService.class.getDeclaredField("ruleEngine");
        field.setAccessible(true);
        field.set(service, new RuleEngine());
        return service;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static class SingleGameResult {
        private final String winnerSkill;
        private final int actions;

        private SingleGameResult(String winnerSkill, int actions) {
            this.winnerSkill = winnerSkill;
            this.actions = actions;
        }
    }

    private static class Configuration {
        public int gamesPerMatchup;
        public boolean enableSkills;
        public boolean enableScrollCards;
        public int maxActionsPerGame;
        public List<String> skillOrder;
    }

    private static class MatchupResult {
        public String skillA;
        public String skillB;
        public int games;
        public int winsA;
        public int winsB;
        public int unresolvedGames;
        public double winRateA;
        public double winRateB;
        public double averageActions;
    }

    private static class SkillSummary {
        public String skill;
        public int totalGames;
        public int wins;
        public int losses;
        public int unresolvedGames;
        public double winRate;
    }

    private static class Report {
        public Configuration configuration;
        public List<MatchupResult> matchups;
        public Map<String, Map<String, Double>> matrix;
        public List<SkillSummary> summaries;
    }

    private static class SingleMatchupReport {
        public Configuration configuration;
        public List<String> notes;
        public MatchupResult matchup;
    }

    private static class BlankAggregateSummary {
        public String skill;
        public int totalGames;
        public int wins;
        public int losses;
        public int unresolvedGames;
        public double winRate;
    }

    private static class BlankVsAllReport {
        public Configuration configuration;
        public List<String> notes;
        public List<MatchupResult> matchups;
        public BlankAggregateSummary blankSummary;
    }

    private static class SkillVsBlanksConfiguration {
        public int gamesPerSkill;
        public int playersPerGame;
        public int blankOpponents;
        public boolean enableSkills;
        public boolean enableScrollCards;
        public boolean rotateSkillSeat;
        public int maxActionsPerGame;
        public List<String> testedSkills;
    }

    private static class SkillVsBlanksSummary {
        public String skill;
        public int games;
        public int wins;
        public int losses;
        public int unresolvedGames;
        public double winRate;
        public double averageActions;
    }

    private static class SkillVsBlanksReport {
        public SkillVsBlanksConfiguration configuration;
        public List<String> notes;
        public List<SkillVsBlanksSummary> summaries;
    }

    private static class FreeForAllConfiguration {
        public int totalGames;
        public int playersPerGame;
        public boolean enableSkills;
        public boolean enableScrollCards;
        public boolean randomUniqueSkillsPerGame;
        public int maxActionsPerGame;
        public List<String> skillPool;
    }

    private static class FreeForAllSkillSummary {
        public String skill;
        public int appearances;
        public int wins;
        public int losses;
        public int unresolvedGames;
        public double winRate;
    }

    private static class FreeForAllReport {
        public FreeForAllConfiguration configuration;
        public int totalGames;
        public int unresolvedGames;
        public double averageActionsPerGame;
        public List<FreeForAllSkillSummary> summaries;
    }
}
