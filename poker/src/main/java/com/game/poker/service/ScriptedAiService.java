package com.game.poker.service;

import com.game.poker.model.Card;
import com.game.poker.model.GameRoom;
import com.game.poker.model.Player;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ScriptedAiService {
    private static final List<String> BOT_SKILLS = List.of("ZHIHENG", "LUANJIAN", "GUANXING", "GUSHOU", "KUROU", "TIEQI", "GUIXIN");

    // 用于让 candidateScore 在「这手打完即赢」的候选上始终返回最小分数。
    // 取一个远小于普通 score 可能到达区间的常量（普通 score 通常在几十到几千）。
    private static final int FINISHING_PLAY_SCORE = -1_000_000;
/*

    private static final String SUIT_SPADE = "♠";
    private static final String SUIT_HEART = "♥";
    private static final String SUIT_CLUB = "♣";
    private static final String SUIT_DIAMOND = "♦";
    private static final String SUIT_JOKER = "JOKER";
    private static final String SUIT_SCROLL = "SCROLL";
    private static final String RANK_SMALL_JOKER = "小王";
    private static final String RANK_BIG_JOKER = "大王";

*/
    private static final String SUIT_SPADE = "\u2660";
    private static final String SUIT_HEART = "\u2665";
    private static final String SUIT_CLUB = "\u2663";
    private static final String SUIT_DIAMOND = "\u2666";
    private static final String SUIT_JOKER = "JOKER";
    private static final String SUIT_SCROLL = "SCROLL";
    private static final String RANK_SMALL_JOKER = "\u5c0f\u738b";
    private static final String RANK_BIG_JOKER = "\u5927\u738b";

    @Autowired(required = false)
    private RuleEngine ruleEngine;

    public boolean isBot(Player player) {
        return player != null && player.isBot();
    }

    public String chooseSkill(GameRoom room, Player bot) {
        int index = java.util.concurrent.ThreadLocalRandom.current().nextInt(BOT_SKILLS.size());
        return BOT_SKILLS.get(index);
    }

    public TurnDecision decideTurn(GameRoom room, Player bot) {
        List<Card> hand = safeHand(bot);
        if (hand.isEmpty()) {
            return TurnDecision.pass();
        }

        boolean jdsrTarget = isJdsrTarget(room, bot.getUserId());
        boolean freeTurn = room.getLastPlayedCards().isEmpty()
                || (!jdsrTarget && bot.getUserId().equals(room.getLastPlayPlayerId()));

        Map<String, Integer> cache = new HashMap<>();
        List<PlayCandidate> normalCandidates = buildCandidates(hand).stream()
                .filter(candidate -> candidate.kind != PatternKind.SCROLL)
                .filter(candidate -> freeTurn || isValidAgainstLast(candidate.cards, room.getLastPlayedCards()))
                .collect(Collectors.toList());

        PlayCandidate bestNormal = chooseBestCandidate(hand, normalCandidates, freeTurn, cache);
        if (jdsrTarget) {
            if (bestNormal != null && shouldPlayResponse(room, bot, bestNormal, hand, cache)) {
                return TurnDecision.play(bestNormal.cards);
            }
            return TurnDecision.pass();
        }

        int currentTurns = estimateTurnsToFinish(hand, cache);
        // 同一回合已经用过锦囊时，后续决策必须退回普通牌逻辑，
        // 否则五谷丰登等结算结束后会继续尝试打第二张锦囊，导致动作被后端拒绝。
        ScrollAction bestScroll = bot.isHasUsedAoeThisTurn()
                ? null
                : chooseBestScrollAction(room, bot, hand, freeTurn, bestNormal, cache);

        if ("GUIXIN".equals(bot.getSkill()) && !bot.isGuixinDisabled()
                && shouldUseGuixin(room, bot, hand, freeTurn, bestNormal, bestScroll, cache)) {
            return TurnDecision.useSkill("GUIXIN", List.of());
        }

        if (!freeTurn && "GUSHOU".equals(bot.getSkill()) && !bot.isHasUsedSkillThisTurn()
                && shouldUseGushou(room, bot, hand, bestNormal, bestScroll, cache)) {
            return TurnDecision.useGushou();
        }

        if ("KUROU".equals(bot.getSkill()) && !bot.isKurouPendingAwakenDiscard()
                && bot.getKurouUsesThisTurn() < 2) {
            List<Card> kurouDiscards = chooseKurouDiscards(hand);
            if (kurouDiscards.size() == 2 && shouldUseKurou(room, bot, hand, bestNormal, cache)) {
                return TurnDecision.useKurou(kurouDiscards);
            }
        }

        if ("LUANJIAN".equals(bot.getSkill()) && !bot.isHasUsedSkillThisTurn()) {
            List<Card> luanjianCards = chooseLuanjianCards(hand);
            if (luanjianCards.size() == 2 && shouldUseLuanjian(room, bot, freeTurn, bestNormal, bestScroll)) {
                return TurnDecision.useSkill("LUANJIAN", luanjianCards);
            }
        }

        if ("ZHIHENG".equals(bot.getSkill()) && !bot.isHasReplacedCardThisTurn()) {
            Card replaceCard = chooseReplaceCard(hand);
            if (replaceCard != null && shouldReplace(bot, freeTurn, bestNormal, currentTurns)) {
                return TurnDecision.replace(replaceCard);
            }
        }

        if (bestScroll != null) {
            return TurnDecision.play(bestScroll.cards);
        }

        if (bestNormal != null && (freeTurn || shouldPlayResponse(room, bot, bestNormal, hand, cache))) {
            return TurnDecision.play(bestNormal.cards);
        }

        if (freeTurn) {
            PlayCandidate fallback = chooseFallbackCandidate(hand, normalCandidates, cache);
            if (fallback != null) {
                return TurnDecision.play(fallback.cards);
            }
        }

        return TurnDecision.pass();
    }

    public Card chooseAoeResponseCard(GameRoom room, Player bot) {
        List<Card> hand = safeHand(bot);
        List<Card> legalCards = hand.stream()
                .filter(card -> isValidAoeResponse(room, bot, card))
                .sorted(Comparator.comparingInt(card -> discardCost(hand, card)))
                .collect(Collectors.toList());
        if (legalCards.isEmpty()) {
            return null;
        }

        Card best = legalCards.get(0);
        if (discardCost(hand, best) > 36 && !isThreatSituation(room, bot)) {
            return null;
        }
        return best;
    }

    public List<Card> chooseGuanxingCards(Player bot, List<Card> options) {
        List<Card> hand = safeHand(bot);
        return options.stream()
                .sorted(Comparator.comparingInt((Card card) -> guanxingGainScore(hand, card)).reversed())
                .limit(Math.min(2, options.size()))
                .collect(Collectors.toList());
    }

    public Card chooseWgfdCard(Player bot, List<Card> options) {
        List<Card> hand = safeHand(bot);
        return options.stream()
                .max(Comparator.comparingInt(card -> wgfdPickScore(hand, card)))
                .orElse(options.isEmpty() ? null : options.get(0));
    }

    public boolean chooseGuixinProtection(GameRoom room, Player owner, Player passer) {
        if (room == null || owner == null || passer == null) {
            return false;
        }
        if (!"GUIXIN".equals(owner.getSkill()) || !owner.isGuixinDisabled()) {
            return false;
        }
        if (!"PLAYING".equals(owner.getStatus()) || !"PLAYING".equals(passer.getStatus())) {
            return false;
        }

        int passerCards = safeHand(passer).size();
        if (passerCards <= 2 || passerCards >= 13) {
            return false;
        }
        return true;
    }

    public List<Card> chooseGushouDiscards(Player bot, int count) {
        List<Card> hand = safeHand(bot);
        return hand.stream()
                .sorted(Comparator.comparingInt(card -> discardCost(hand, card)))
                .limit(Math.min(count, hand.size()))
                .collect(Collectors.toList());
    }

    /**
     * 【苦肉】挑选 2 张弃置价值最低的非锦囊手牌。
     */
    public List<Card> chooseKurouDiscards(Player bot) {
        return chooseKurouDiscards(safeHand(bot));
    }

    private List<Card> chooseKurouDiscards(List<Card> hand) {
        return hand.stream()
                .filter(card -> !SUIT_SCROLL.equals(card.getSuit()))
                .sorted(Comparator.comparingInt(card -> discardCost(hand, card)))
                .limit(2)
                .collect(Collectors.toList());
    }

    /**
     * 【苦肉·觉醒】返回值：非 null = 弃置该黑色牌；null = 跳过。
     * 仅当黑色废牌价值很低且仍有黑牌时才弃置。
     */
    public Card chooseKurouAwakenDiscardCard(Player bot) {
        List<Card> hand = safeHand(bot);
        if (hand.isEmpty()) {
            return null;
        }
        Card best = hand.stream()
                .filter(card -> SUIT_SPADE.equals(card.getSuit())
                        || SUIT_CLUB.equals(card.getSuit())
                        || (SUIT_JOKER.equals(card.getSuit()) && RANK_SMALL_JOKER.equals(card.getRank())))
                .min(Comparator.comparingInt(card -> discardCost(hand, card)))
                .orElse(null);
        if (best == null) {
            return null;
        }
        int cost = discardCost(hand, best);
        // 手牌较多时（>10）更倾向弃置；价值过高的黑牌优先留着
        int threshold = hand.size() > 10 ? 40 : 24;
        if (cost <= threshold) {
            return best;
        }
        return null;
    }

    private boolean shouldUseKurou(GameRoom room, Player bot, List<Card> hand,
                                   PlayCandidate bestNormal, Map<String, Integer> cache) {
        // 苦肉核心定位：【控牌】—— 把碎牌/散牌换掉以形成更好的组合，
        // 而不是盲目追求觉醒。觉醒之后再用性价比骤降，门槛要更高。
        //
        // 机制：弃 2 摸 4，净 +2；手牌 > 14 即爆牌判负。
        // 所以必须保留足够的"爆牌安全余量"，不允许贴着 14 用。

        if (hand.size() < 2) {
            return false;
        }

        // 威胁局面（任一对手 ≤ 2 张）：放弃刷牌，保留节奏与资源应对
        if (isThreatSituation(room, bot)) {
            return false;
        }

        // 安全上限：
        //  - 未觉醒：用后手牌 ≤ 10（距爆牌 14 至少留 4 张余量）
        //  - 已觉醒：用后手牌 ≤ 8（觉醒后不再有额外收益，更保守）
        int handAfter = hand.size() + 2;
        int safetyCap = bot.isKurouAwakened() ? 8 : 10;
        if (handAfter > safetyCap) {
            return false;
        }

        // 已经有炸弹 / 王炸能打出：走普通出牌路线，不浪费节奏刷牌
        if (bestNormal != null
                && (bestNormal.kind == PatternKind.BOMB || bestNormal.kind == PatternKind.ROCKET)) {
            return false;
        }

        int currentTurns = estimateTurnsToFinish(hand, cache);
        long looseSingles = countLooseSingles(hand);

        // 【已觉醒】苦肉只剩"换牌"价值，需要牌型明显碎才值得再消耗一次
        if (bot.isKurouAwakened()) {
            if (bestNormal == null) {
                return looseSingles >= 3 && currentTurns >= 4;
            }
            return looseSingles >= 4 && currentTurns >= 5;
        }

        // 【未觉醒】为"控牌"而用：牌型碎（散牌多 或 预估回合偏长）时主动刷
        boolean messyHand = looseSingles >= 3 || currentTurns >= hand.size() - 1;
        if (bestNormal == null) {
            // 连牌都打不出时，也要求手牌不臃肿 + 牌型确实碎，否则越刷越接近爆牌
            return handAfter <= 9 && messyHand;
        }
        return messyHand && currentTurns >= 4;
    }

    public List<Card> chooseEmergencyFreeTurnPlay(Player bot) {
        List<Card> hand = safeHand(bot);
        if (hand.isEmpty()) {
            return List.of();
        }

        PlayCandidate fallback = chooseFallbackCandidate(
                hand,
                buildCandidates(hand).stream()
                        .filter(candidate -> candidate.kind != PatternKind.SCROLL)
                        .collect(Collectors.toList()),
                new HashMap<>()
        );
        return fallback == null ? List.of() : new ArrayList<>(fallback.cards);
    }

    private boolean shouldUseGuixin(GameRoom room, Player bot, List<Card> hand,
                                    boolean freeTurn, PlayCandidate bestNormal,
                                    ScrollAction bestScroll, Map<String, Integer> cache) {
        if (bestScroll != null || isThreatSituation(room, bot)) {
            return false;
        }
        if (hand.size() >= 11) {
            return false;
        }
        if (freeTurn) {
            return bestNormal == null && hand.stream().allMatch(card -> SUIT_SCROLL.equals(card.getSuit()));
        }
        if (bestNormal != null && shouldPlayResponse(room, bot, bestNormal, hand, cache)) {
            return false;
        }
        return bestNormal == null || playDisruptionCost(hand, bestNormal) >= 34;
    }

    private boolean shouldUseGushou(GameRoom room, Player bot, List<Card> hand,
                                    PlayCandidate bestNormal, ScrollAction bestScroll,
                                    Map<String, Integer> cache) {
        if (hand.size() > 9) {
            return false;
        }
        // 一手清空手牌即可获胜时，必须打出，不能用固守保留炸弹/王炸
        if (isFinishingPlay(hand, bestNormal)) {
            return false;
        }
        // 固守的定位是“无法舒服接牌时的补牌技”，而不是无脑跳过所有回应。
        // 只要已经有高质量锦囊动作，就应优先走锦囊路线，不应被固守截走。
        if (bestScroll != null) {
            return false;
        }

        boolean threat = isThreatSituation(room, bot);
        int currentTurns = estimateTurnsToFinish(hand, cache);
        long looseSingles = countLooseSingles(hand);
        boolean messyHand = looseSingles >= 3 || currentTurns >= Math.max(5, hand.size() - 1);

        if (bestNormal == null) {
            // 完全无法接牌时，固守用于“补牌重整”通常优于普通 PASS，
            // 但仍要求当前手牌不至于过满，且牌型确实偏慢/偏碎。
            return messyHand || currentTurns >= 4 || hand.size() <= 5;
        }

        // 对手临近收官时，任何能稳稳接上的普通解都优先打出，不能把节奏主动让掉。
        if (threat) {
            return false;
        }

        int disruption = playDisruptionCost(hand, bestNormal);
        int turnsAfterPlay = estimateTurnsToFinish(removeCards(hand, bestNormal.cards), cache);
        boolean premiumResponse = bestNormal.kind == PatternKind.BOMB || bestNormal.kind == PatternKind.ROCKET;

        // 若当前有便宜、不破坏牌型的正常回应，就不该为了“贪 4 张”而开固守。
        if (!premiumResponse && disruption <= 20) {
            return false;
        }
        if (!premiumResponse && turnsAfterPlay <= currentTurns + 1) {
            return false;
        }

        // 炸弹/王炸属于昂贵资源，只有在手牌偏慢且不会立刻错失胜机时才值得保留。
        if (premiumResponse) {
            return hand.size() <= 7 && currentTurns >= 4 && turnsAfterPlay >= 3;
        }

        // 其余情况仅在“手牌偏碎 + 应对代价明显偏高”时发动，避免固守过度抢节奏。
        return hand.size() <= 6 && messyHand && disruption >= 24 && currentTurns >= 4;
    }

    private boolean shouldUseLuanjian(GameRoom room, Player bot, boolean freeTurn,
                                      PlayCandidate bestNormal, ScrollAction bestScroll) {
        if (bestScroll != null || aliveOpponents(room, bot) < 2) {
            return false;
        }
        // 一手清空手牌即可获胜时，必须打出，不能用乱箭消耗掉收官的炸弹/王炸
        if (isFinishingPlay(safeHand(bot), bestNormal)) {
            return false;
        }
        if (bestNormal == null) {
            return !freeTurn || isThreatSituation(room, bot);
        }
        return isThreatSituation(room, bot)
                || bestNormal.kind == PatternKind.BOMB
                || bestNormal.kind == PatternKind.ROCKET;
    }

    private boolean shouldReplace(Player bot, boolean freeTurn, PlayCandidate bestNormal, int currentTurns) {
        List<Card> hand = safeHand(bot);
        if (hand.size() <= 1) {
            return false;
        }
        // 一手清空手牌即可获胜时，必须打出，不能用制衡把收官的王炸/炸弹换没了
        if (isFinishingPlay(hand, bestNormal)) {
            return false;
        }
        if (freeTurn) {
            return bestNormal == null || currentTurns >= Math.min(5, hand.size());
        }
        return bestNormal == null || bestNormal.kind == PatternKind.BOMB || bestNormal.kind == PatternKind.ROCKET;
    }

    private boolean shouldPlayResponse(GameRoom room, Player bot, PlayCandidate candidate,
                                       List<Card> hand, Map<String, Integer> cache) {
        if (candidate == null) {
            return false;
        }
        if (isThreatSituation(room, bot)) {
            return true;
        }

        int disruption = playDisruptionCost(hand, candidate);
        if (candidate.kind == PatternKind.BOMB || candidate.kind == PatternKind.ROCKET) {
            int turnsAfter = estimateTurnsToFinish(removeCards(hand, candidate.cards), cache);
            return turnsAfter <= 2;
        }
        return disruption <= 18;
    }

    private ScrollAction chooseBestScrollAction(GameRoom room, Player bot, List<Card> hand, boolean freeTurn,
                                                PlayCandidate bestNormal, Map<String, Integer> cache) {
        List<Card> scrolls = hand.stream()
                .filter(card -> SUIT_SCROLL.equals(card.getSuit()))
                .collect(Collectors.toList());
        if (scrolls.isEmpty()) {
            return null;
        }

        List<ScrollAction> actions = new ArrayList<>();
        for (Card scroll : scrolls) {
            String rank = scroll.getRank();
            if ("JDSR".equals(rank)
                    && (freeTurn || room.getLastPlayedCards().isEmpty() || bot.getUserId().equals(room.getLastPlayPlayerId()))) {
                continue;
            }

            int score = scrollBaseScore(room, bot, hand, scroll, bestNormal, cache);
            if (score > 0) {
                actions.add(new ScrollAction(List.of(scroll), score));
            }
        }

        return actions.stream().max(Comparator.comparingInt(action -> action.score)).orElse(null);
    }

    private int scrollBaseScore(GameRoom room, Player bot, List<Card> hand, Card scroll,
                                PlayCandidate bestNormal, Map<String, Integer> cache) {
        String rank = scroll.getRank();
        boolean threat = isThreatSituation(room, bot);
        int base = 0;

        if ("WJQF".equals(rank) || "NMRQ".equals(rank)) {
            Card selfResponse = chooseResponseForType(hand, rank);
            if (selfResponse == null) {
                return 0;
            }
            base += threat ? 30 : 14;
            base += (int) Math.max(0, aliveOpponents(room, bot) - 1) * 4;
            base -= discardCost(hand, selfResponse) / 3;
            if (bestNormal == null) {
                base += 10;
            }
        } else if ("WGFD".equals(rank)) {
            int turns = estimateTurnsToFinish(hand, cache);
            base += turns >= 4 ? 16 : 6;
            if (threat) {
                base -= 8;
            }
        } else if ("JDSR".equals(rank)) {
            base += bestNormal == null ? 18 : 8;
            if (room.getLastPlayedCards().size() >= 4) {
                base -= 8;
            }
        }

        if (bestNormal != null && bestNormal.kind != PatternKind.BOMB
                && bestNormal.kind != PatternKind.ROCKET && !threat) {
            base -= 6;
        }
        return base;
    }

    private Card chooseResponseForType(List<Card> hand, String aoeType) {
        return hand.stream()
                .filter(card -> {
                    if ("NMRQ".equals(aoeType)) {
                        return isRedCard(card) || isBigJoker(card);
                    }
                    return isBlackCard(card) || isSmallJoker(card);
                })
                .min(Comparator.comparingInt(card -> discardCost(hand, card)))
                .orElse(null);
    }

    private PlayCandidate chooseBestCandidate(List<Card> hand, List<PlayCandidate> candidates,
                                              boolean freeTurn, Map<String, Integer> cache) {
        return candidates.stream()
                .min(Comparator.comparingInt(candidate -> candidateScore(hand, candidate, freeTurn, cache)))
                .orElse(null);
    }

    private PlayCandidate chooseFallbackCandidate(List<Card> hand, List<PlayCandidate> candidates,
                                                  Map<String, Integer> cache) {
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.stream()
                .min(Comparator.<PlayCandidate>comparingInt(candidate ->
                                estimateTurnsToFinish(removeCards(hand, candidate.cards), cache))
                        .thenComparingInt(candidate -> candidate.primaryWeight)
                        .thenComparingInt(candidate -> candidate.cards.size() * -1))
                .orElse(candidates.get(0));
    }

    private int candidateScore(List<Card> hand, PlayCandidate candidate, boolean freeTurn, Map<String, Integer> cache) {
        List<Card> remaining = removeCards(hand, candidate.cards);

        // 能一手清空手牌（直接获胜）的出法永远视为最优，避免王炸/炸弹
        // 被 kind 惩罚与 disruption 惩罚压过 turnsAfter=0 的优势，
        // 导致手里只剩小王 + 大王时被拆成两张单牌分开出。
        // 以 primaryWeight 做稳定的 tiebreak。
        if (remaining.isEmpty()) {
            return FINISHING_PLAY_SCORE + candidate.primaryWeight;
        }

        int turnsAfter = estimateTurnsToFinish(remaining, cache);
        int disruption = playDisruptionCost(hand, candidate);
        int score = turnsAfter * (freeTurn ? 100 : 120)
                + disruption * (freeTurn ? 3 : 4)
                + candidate.primaryWeight
                - candidate.cards.size() * (freeTurn ? 12 : 4);

        if (candidate.kind == PatternKind.BOMB) {
            score += 50;
        } else if (candidate.kind == PatternKind.ROCKET) {
            score += 80;
        } else if (candidate.kind == PatternKind.STRAIGHT
                || candidate.kind == PatternKind.STRAIGHT_PAIR
                || candidate.kind == PatternKind.AIRPLANE) {
            score -= 12;
        }
        return score;
    }

    private int estimateTurnsToFinish(List<Card> hand, Map<String, Integer> cache) {
        if (hand.isEmpty()) {
            return 0;
        }
        String key = handKey(hand);
        Integer cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        int best = hand.size();
        for (PlayCandidate candidate : buildCandidates(hand)) {
            best = Math.min(best, 1 + estimateTurnsToFinish(removeCards(hand, candidate.cards), cache));
        }
        cache.put(key, best);
        return best;
    }

    private List<PlayCandidate> buildCandidates(List<Card> hand) {
        List<Card> normalCards = hand.stream().filter(this::isNormalCard).collect(Collectors.toList());
        Map<Integer, List<Card>> byWeight = groupByWeight(normalCards);
        List<PlayCandidate> result = new ArrayList<>();
        Set<String> dedupe = new HashSet<>();

        for (Card card : hand) {
            if (SUIT_SCROLL.equals(card.getSuit())) {
                addCandidate(result, dedupe, PatternKind.SCROLL, List.of(card), card.getWeight());
            }
        }

        for (List<Card> cards : byWeight.values()) {
            addCandidate(result, dedupe, PatternKind.SINGLE, List.of(cards.get(0)), cards.get(0).getWeight());
            if (cards.size() >= 2) {
                addCandidate(result, dedupe, PatternKind.PAIR, cards.subList(0, 2), cards.get(0).getWeight());
            }
            if (cards.size() >= 3) {
                addCandidate(result, dedupe, PatternKind.TRIPLE, cards.subList(0, 3), cards.get(0).getWeight());
            }
            if (cards.size() == 4) {
                addCandidate(result, dedupe, PatternKind.BOMB, cards.subList(0, 4), cards.get(0).getWeight());
            }
        }

        if (byWeight.containsKey(14) && byWeight.containsKey(15)) {
            addCandidate(result, dedupe, PatternKind.ROCKET,
                    List.of(byWeight.get(14).get(0), byWeight.get(15).get(0)), 15);
        }

        for (Map.Entry<Integer, List<Card>> tripleEntry : byWeight.entrySet()) {
            if (tripleEntry.getValue().size() < 3) {
                continue;
            }
            int tripleWeight = tripleEntry.getKey();
            List<Card> triple = tripleEntry.getValue().subList(0, 3);
            for (Map.Entry<Integer, List<Card>> otherEntry : byWeight.entrySet()) {
                if (otherEntry.getKey().equals(tripleWeight)) {
                    continue;
                }
                addCandidate(result, dedupe, PatternKind.TRIPLE_WITH_SINGLE,
                        concat(triple, List.of(otherEntry.getValue().get(0))), tripleWeight);
                if (otherEntry.getValue().size() >= 2) {
                    addCandidate(result, dedupe, PatternKind.TRIPLE_WITH_PAIR,
                            concat(triple, otherEntry.getValue().subList(0, 2)), tripleWeight);
                }
            }
        }

        addStraights(result, dedupe, byWeight);
        addStraightPairs(result, dedupe, byWeight);
        addAirplanes(result, dedupe, byWeight);

        return result;
    }

    private void addStraights(List<PlayCandidate> result, Set<String> dedupe, Map<Integer, List<Card>> byWeight) {
        List<Integer> weights = byWeight.keySet().stream()
                .filter(weight -> weight < 13)
                .sorted()
                .collect(Collectors.toList());
        addSequentialCandidates(result, dedupe, byWeight, weights, 5, PatternKind.STRAIGHT, 1);
    }

    private void addStraightPairs(List<PlayCandidate> result, Set<String> dedupe, Map<Integer, List<Card>> byWeight) {
        List<Integer> weights = byWeight.entrySet().stream()
                .filter(entry -> entry.getKey() < 13 && entry.getValue().size() >= 2)
                .map(Map.Entry::getKey)
                .sorted()
                .collect(Collectors.toList());
        addSequentialCandidates(result, dedupe, byWeight, weights, 3, PatternKind.STRAIGHT_PAIR, 2);
    }

    private void addSequentialCandidates(List<PlayCandidate> result, Set<String> dedupe,
                                         Map<Integer, List<Card>> byWeight, List<Integer> weights,
                                         int minRunLength, PatternKind kind, int cardsPerWeight) {
        for (int start = 0; start < weights.size(); start++) {
            int end = start;
            while (end + 1 < weights.size() && weights.get(end + 1) == weights.get(end) + 1) {
                end++;
            }
            int runLength = end - start + 1;
            if (runLength >= minRunLength) {
                for (int length = minRunLength; length <= runLength; length++) {
                    for (int offset = start; offset + length - 1 <= end; offset++) {
                        List<Card> cards = new ArrayList<>();
                        for (int index = offset; index < offset + length; index++) {
                            cards.addAll(byWeight.get(weights.get(index)).subList(0, cardsPerWeight));
                        }
                        addCandidate(result, dedupe, kind, cards, weights.get(offset + length - 1));
                    }
                }
            }
            start = end;
        }
    }

    private void addAirplanes(List<PlayCandidate> result, Set<String> dedupe, Map<Integer, List<Card>> byWeight) {
        List<Integer> tripleWeights = byWeight.entrySet().stream()
                .filter(entry -> entry.getKey() < 13 && entry.getValue().size() >= 3)
                .map(Map.Entry::getKey)
                .sorted()
                .collect(Collectors.toList());

        for (int start = 0; start < tripleWeights.size(); start++) {
            int end = start;
            while (end + 1 < tripleWeights.size() && tripleWeights.get(end + 1) == tripleWeights.get(end) + 1) {
                end++;
            }
            int runLength = end - start + 1;
            if (runLength >= 2) {
                for (int length = 2; length <= runLength; length++) {
                    for (int offset = start; offset + length - 1 <= end; offset++) {
                        List<Integer> run = tripleWeights.subList(offset, offset + length);
                        List<Card> body = new ArrayList<>();
                        for (Integer weight : run) {
                            body.addAll(byWeight.get(weight).subList(0, 3));
                        }
                        addCandidate(result, dedupe, PatternKind.AIRPLANE, body, run.get(run.size() - 1));

                        for (List<Card> wings : chooseWings(byWeight, run, length, 1)) {
                            addCandidate(result, dedupe, PatternKind.AIRPLANE, concat(body, wings), run.get(run.size() - 1));
                        }
                        for (List<Card> wings : chooseWings(byWeight, run, length, 2)) {
                            addCandidate(result, dedupe, PatternKind.AIRPLANE, concat(body, wings), run.get(run.size() - 1));
                        }
                    }
                }
            }
            start = end;
        }
    }

    private List<List<Card>> chooseWings(Map<Integer, List<Card>> byWeight, List<Integer> excludedWeights,
                                         int wingCount, int cardsPerWing) {
        List<List<Card>> candidates = byWeight.entrySet().stream()
                .filter(entry -> !excludedWeights.contains(entry.getKey()))
                .filter(entry -> entry.getValue().size() >= cardsPerWing)
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ArrayList<>(entry.getValue().subList(0, cardsPerWing)))
                .collect(Collectors.toList());

        List<List<Card>> result = new ArrayList<>();
        chooseWingCombination(candidates, wingCount, 0, new ArrayList<>(), result);
        return result;
    }

    private void chooseWingCombination(List<List<Card>> pool, int need, int index,
                                       List<Card> current, List<List<Card>> result) {
        if (need == 0) {
            result.add(new ArrayList<>(current));
            return;
        }
        for (int i = index; i <= pool.size() - need; i++) {
            current.addAll(pool.get(i));
            chooseWingCombination(pool, need - 1, i + 1, current, result);
            for (int remove = 0; remove < pool.get(i).size(); remove++) {
                current.remove(current.size() - 1);
            }
        }
    }

    private void addCandidate(List<PlayCandidate> result, Set<String> dedupe,
                              PatternKind kind, List<Card> cards, int primaryWeight) {
        List<Card> snapshot = new ArrayList<>(cards);
        if (snapshot.isEmpty()) {
            return;
        }
        String key = kind + ":" + cardListKey(snapshot);
        if (!dedupe.add(key)) {
            return;
        }
        result.add(new PlayCandidate(kind, snapshot, primaryWeight));
    }

    private boolean isValidAgainstLast(List<Card> cards, List<Card> lastCards) {
        return ruleEngine == null || ruleEngine.isValidPlay(cards, lastCards);
    }

    private List<Card> removeCards(List<Card> hand, List<Card> cardsToRemove) {
        List<Card> remaining = new ArrayList<>(hand);
        for (Card removeCard : cardsToRemove) {
            for (int index = 0; index < remaining.size(); index++) {
                if (sameCard(remaining.get(index), removeCard)) {
                    remaining.remove(index);
                    break;
                }
            }
        }
        return remaining;
    }

    private int playDisruptionCost(List<Card> hand, PlayCandidate candidate) {
        int cost = 0;
        Map<Integer, Integer> handCounts = countByWeight(hand);
        Map<Integer, Integer> usedCounts = countByWeight(candidate.cards);

        for (Map.Entry<Integer, Integer> entry : usedCounts.entrySet()) {
            int weight = entry.getKey();
            int used = entry.getValue();
            int total = handCounts.getOrDefault(weight, 0);

            if (total == 1) {
                cost += 3;
            } else if (total == 2 && used == 1) {
                cost += 16;
            } else if (total == 3 && used < 3) {
                cost += 24;
            } else if (total == 4 && used < 4) {
                cost += 36;
            } else if (used == total && total >= 2) {
                cost -= 4;
            }
        }

        if (candidate.kind == PatternKind.BOMB) {
            cost += 18;
        } else if (candidate.kind == PatternKind.ROCKET) {
            cost += 30;
        }
        return Math.max(cost, 0);
    }

    private int discardCost(List<Card> hand, Card card) {
        if (SUIT_SCROLL.equals(card.getSuit())) {
            return 80;
        }
        Map<Integer, Integer> counts = countByWeight(hand);
        int total = counts.getOrDefault(card.getWeight(), 1);
        int cost = card.getWeight() * 2;
        if (total == 2) {
            cost += 16;
        } else if (total == 3) {
            cost += 24;
        } else if (total == 4) {
            cost += 36;
        }
        if (card.getWeight() >= 13) {
            cost += 16;
        }
        if (card.getWeight() >= 11) {
            cost += 6;
        }
        if (isStraightUseful(hand, card)) {
            cost += 10;
        }
        return cost;
    }

    private boolean isValidAoeResponse(GameRoom room, Player bot, Card card) {
        if (SUIT_SCROLL.equals(card.getSuit())) {
            return false;
        }
        boolean luanjianInitiator = "WJQF".equals(room.getCurrentAoeType())
                && bot.getUserId().equals(room.getSettings().get("luanjian_initiator"));
        if (luanjianInitiator) {
            return true;
        }
        if ("NMRQ".equals(room.getCurrentAoeType())) {
            return isRedCard(card) || isBigJoker(card);
        }
        if ("WJQF".equals(room.getCurrentAoeType())) {
            return isBlackCard(card) || isSmallJoker(card);
        }
        return true;
    }

    private int guanxingGainScore(List<Card> hand, Card card) {
        int sameWeight = countByWeight(hand).getOrDefault(card.getWeight(), 0);
        int score = card.getWeight() * 3 + sameWeight * 18;
        if (SUIT_SCROLL.equals(card.getSuit())) {
            score += 22;
        }
        if (card.getWeight() >= 13) {
            score += 8;
        }
        if (isStraightUseful(hand, card)) {
            score += 6;
        }
        return score;
    }

    private int wgfdPickScore(List<Card> hand, Card card) {
        return guanxingGainScore(hand, card) - Math.max(0, discardCost(hand, card) / 4);
    }

    private List<Card> chooseLuanjianCards(List<Card> hand) {
        return hand.stream()
                .filter(this::isBlackSuitCard)
                .filter(card -> !SUIT_SCROLL.equals(card.getSuit()))
                .sorted(Comparator.comparingInt(card -> discardCost(hand, card)))
                .limit(2)
                .collect(Collectors.toList());
    }

    private Card chooseReplaceCard(List<Card> hand) {
        return hand.stream()
                .filter(card -> !SUIT_SCROLL.equals(card.getSuit()))
                .min(Comparator.comparingInt(card -> discardCost(hand, card)))
                .orElse(null);
    }

    /**
     * 判断该候选是否一手打完就能清空手牌（即打出即获胜）。
     * 用于阻止技能/锦囊分支把收官的王炸、炸弹拆散或换掉。
     */
    private boolean isFinishingPlay(List<Card> hand, PlayCandidate candidate) {
        return candidate != null && !hand.isEmpty() && candidate.cards.size() == hand.size();
    }

    private boolean isThreatSituation(GameRoom room, Player bot) {
        return room.getPlayers().stream()
                .filter(player -> !player.getUserId().equals(bot.getUserId()))
                .filter(player -> "PLAYING".equals(player.getStatus()))
                .anyMatch(player -> player.getHandCards().size() <= 2);
    }

    private long aliveOpponents(GameRoom room, Player bot) {
        return room.getPlayers().stream()
                .filter(player -> !player.getUserId().equals(bot.getUserId()))
                .filter(player -> "PLAYING".equals(player.getStatus()))
                .count();
    }

    private boolean isJdsrTarget(GameRoom room, String userId) {
        return room.getSettings().containsKey("jdsr_target") && userId.equals(room.getSettings().get("jdsr_target"));
    }

    private long countLooseSingles(List<Card> hand) {
        Map<Integer, Integer> counts = countByWeight(hand);
        return hand.stream()
                .filter(this::isNormalCard)
                .filter(card -> counts.getOrDefault(card.getWeight(), 0) == 1)
                .count();
    }

    private long countGroupsOfAtLeast(List<Card> hand, int threshold) {
        return countByWeight(hand).values().stream().filter(count -> count >= threshold).count();
    }

    private Map<Integer, Integer> countByWeight(Collection<Card> cards) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (Card card : cards) {
            if (SUIT_SCROLL.equals(card.getSuit())) {
                continue;
            }
            counts.merge(card.getWeight(), 1, Integer::sum);
        }
        return counts;
    }

    private Map<Integer, List<Card>> groupByWeight(List<Card> cards) {
        Map<Integer, List<Card>> groups = new LinkedHashMap<>();
        cards.stream()
                .sorted(Comparator.comparingInt(Card::getWeight).thenComparing(Card::getSuit))
                .forEach(card -> groups.computeIfAbsent(card.getWeight(), key -> new ArrayList<>()).add(card));
        return groups;
    }

    private boolean isNormalCard(Card card) {
        return !SUIT_SCROLL.equals(card.getSuit());
    }

    private boolean isStraightUseful(List<Card> hand, Card card) {
        if (!isNormalCard(card) || card.getWeight() >= 13) {
            return false;
        }
        Set<Integer> weights = hand.stream()
                .filter(this::isNormalCard)
                .map(Card::getWeight)
                .collect(Collectors.toSet());
        return weights.contains(card.getWeight() - 1) || weights.contains(card.getWeight() + 1);
    }

    private boolean isBlackCard(Card card) {
        return SUIT_SPADE.equals(card.getSuit()) || SUIT_CLUB.equals(card.getSuit()) || isSmallJoker(card);
    }

    private boolean isBlackSuitCard(Card card) {
        return SUIT_SPADE.equals(card.getSuit()) || SUIT_CLUB.equals(card.getSuit());
    }

    private boolean isRedCard(Card card) {
        return SUIT_HEART.equals(card.getSuit()) || SUIT_DIAMOND.equals(card.getSuit()) || isBigJoker(card);
    }

    private boolean isSmallJoker(Card card) {
        return SUIT_JOKER.equals(card.getSuit()) && RANK_SMALL_JOKER.equals(card.getRank());
    }

    private boolean isBigJoker(Card card) {
        return SUIT_JOKER.equals(card.getSuit()) && RANK_BIG_JOKER.equals(card.getRank());
    }

    private boolean sameCard(Card left, Card right) {
        return left.getSuit().equals(right.getSuit()) && left.getRank().equals(right.getRank());
    }

    private String handKey(List<Card> hand) {
        return hand.stream()
                .sorted(Comparator.comparingInt(Card::getWeight).thenComparing(Card::getSuit).thenComparing(Card::getRank))
                .map(card -> card.getSuit() + "_" + card.getRank())
                .collect(Collectors.joining("|"));
    }

    private String cardListKey(List<Card> cards) {
        return cards.stream()
                .sorted(Comparator.comparingInt(Card::getWeight).thenComparing(Card::getSuit).thenComparing(Card::getRank))
                .map(card -> card.getSuit() + "_" + card.getRank())
                .collect(Collectors.joining("|"));
    }

    private List<Card> concat(List<Card> left, List<Card> right) {
        List<Card> merged = new ArrayList<>(left);
        merged.addAll(right);
        return merged;
    }

    private List<Card> safeHand(Player player) {
        return new ArrayList<>(player.getHandCards() == null ? List.of() : player.getHandCards());
    }

    private enum PatternKind {
        SINGLE,
        PAIR,
        TRIPLE,
        TRIPLE_WITH_SINGLE,
        TRIPLE_WITH_PAIR,
        STRAIGHT,
        STRAIGHT_PAIR,
        AIRPLANE,
        BOMB,
        ROCKET,
        SCROLL
    }

    private static class PlayCandidate {
        private final PatternKind kind;
        private final List<Card> cards;
        private final int primaryWeight;

        private PlayCandidate(PatternKind kind, List<Card> cards, int primaryWeight) {
            this.kind = kind;
            this.cards = cards;
            this.primaryWeight = primaryWeight;
        }
    }

    private static class ScrollAction {
        private final List<Card> cards;
        private final int score;

        private ScrollAction(List<Card> cards, int score) {
            this.cards = cards;
            this.score = score;
        }
    }

    public enum TurnDecisionType {
        PLAY,
        PASS,
        REPLACE,
        USE_SKILL,
        USE_GUSHOU,
        USE_KUROU
    }

    public static class TurnDecision {
        private final TurnDecisionType type;
        private final List<Card> cards;
        private final String skill;

        private TurnDecision(TurnDecisionType type, List<Card> cards, String skill) {
            this.type = type;
            this.cards = cards == null ? List.of() : cards;
            this.skill = skill;
        }

        public static TurnDecision play(List<Card> cards) {
            return new TurnDecision(TurnDecisionType.PLAY, new ArrayList<>(cards), null);
        }

        public static TurnDecision pass() {
            return new TurnDecision(TurnDecisionType.PASS, List.of(), null);
        }

        public static TurnDecision replace(Card card) {
            return new TurnDecision(TurnDecisionType.REPLACE, List.of(card), null);
        }

        public static TurnDecision useSkill(String skill, List<Card> cards) {
            return new TurnDecision(TurnDecisionType.USE_SKILL, new ArrayList<>(cards), skill);
        }

        public static TurnDecision useGushou() {
            return new TurnDecision(TurnDecisionType.USE_GUSHOU, List.of(), "GUSHOU");
        }

        public static TurnDecision useKurou(List<Card> cards) {
            return new TurnDecision(TurnDecisionType.USE_KUROU, new ArrayList<>(cards), "KUROU");
        }

        public TurnDecisionType getType() {
            return type;
        }

        public List<Card> getCards() {
            return cards;
        }

        public String getSkill() {
            return skill;
        }
    }
}
