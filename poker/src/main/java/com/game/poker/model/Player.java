package com.game.poker.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class Player {
    private String userId;
    private List<Card> handCards;
    private String status;
    private int penaltyCount;
    private boolean bot = false;
    private boolean hasReplacedCardThisTurn = false;
    private boolean ready = false;
    private boolean disconnected = false;
    private boolean hasUsedAoeThisTurn = false;
    private String skill = "ZHIHENG";
    private boolean hasUsedSkillThisTurn = false;
    private boolean guixinDisabled = false;

    // ====== 【苦肉技能】：跨回合累计计数、本回合次数、觉醒态、觉醒后出牌挂起标记 ======
    private int kurouUseCount = 0;
    private int kurouUsesThisTurn = 0;
    private boolean kurouAwakened = false;
    private boolean kurouPendingAwakenDiscard = false;

    public int getKurouUseCount() {
        return kurouUseCount;
    }

    public void setKurouUseCount(int kurouUseCount) {
        this.kurouUseCount = kurouUseCount;
    }

    public int getKurouUsesThisTurn() {
        return kurouUsesThisTurn;
    }

    public void setKurouUsesThisTurn(int kurouUsesThisTurn) {
        this.kurouUsesThisTurn = kurouUsesThisTurn;
    }

    public boolean isKurouAwakened() {
        return kurouAwakened;
    }

    public void setKurouAwakened(boolean kurouAwakened) {
        this.kurouAwakened = kurouAwakened;
    }

    public boolean isKurouPendingAwakenDiscard() {
        return kurouPendingAwakenDiscard;
    }

    public void setKurouPendingAwakenDiscard(boolean kurouPendingAwakenDiscard) {
        this.kurouPendingAwakenDiscard = kurouPendingAwakenDiscard;
    }

    public String getSkill() {
        return skill;
    }

    public void setSkill(String skill) {
        this.skill = skill;
    }

    public boolean isHasUsedSkillThisTurn() {
        return hasUsedSkillThisTurn;
    }

    public void setHasUsedSkillThisTurn(boolean hasUsedSkillThisTurn) {
        this.hasUsedSkillThisTurn = hasUsedSkillThisTurn;
    }

    public boolean isGuixinDisabled() {
        return guixinDisabled;
    }

    public void setGuixinDisabled(boolean guixinDisabled) {
        this.guixinDisabled = guixinDisabled;
    }

    public boolean isHasUsedAoeThisTurn() {
        return hasUsedAoeThisTurn;
    }

    public void setHasUsedAoeThisTurn(boolean hasUsedAoeThisTurn) {
        this.hasUsedAoeThisTurn = hasUsedAoeThisTurn;
    }

    public boolean isDisconnected() {
        return disconnected;
    }

    public void setDisconnected(boolean disconnected) {
        this.disconnected = disconnected;
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public Player(String userId) {
        this.userId = userId;
        this.handCards = new ArrayList<>();
        this.status = "PLAYING";
        this.penaltyCount = 0;
    }

    public Player(String userId, boolean bot) {
        this(userId);
        this.bot = bot;
        if (bot) {
            this.ready = true;
        }
    }

    public int getCardCount() {
        return handCards.size();
    }
}
