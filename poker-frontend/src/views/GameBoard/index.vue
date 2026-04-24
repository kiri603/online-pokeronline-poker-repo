<template src="./GameBoard.html"></template>

<script setup>
// 【核心修复 1】：移除了 onMounted 中的 playBGM("Normal")。
import {
  userId,
  otherPlayers,
  currentTurn,
  countdown,
  activeActionTexts,
  activeEmojis,
  currentAoeType,
  pendingAoePlayers,
  aoeInitiator,
  tableCards,
  lastPlayPlayer,
  sortedTableCards,
  isSpectator,
  myStatus,
  handCards,
  amIPendingAoe,
  selectedCards,
  aoeAnimCards,
  sortedHandCards,
  showEmojiPanel,
  emojiList,
  winner,
  winningCards,
  killText,
  sortedWinningCards,
  getCardImageUrl,
  handleImageError,
  toggleSelect,
  respondAoe,
  discardAoe,
  playCards,
  replaceCard,
  passTurn,
  toggleEmojiPanel,
  sendEmoji,
  exitGame,
  returnToRoom,
  soundStatus,
  toggleSound,
  isShuffling,
  warningUserId,
  mySkill,
  showSkillSelection,
  showGuanxingModal,
  guanxingCards,
  selectedGuanxingCards,
  selectSkill,
  toggleGuanxingSelect,
  confirmGuanxing,
  playBGM,
  skillCountdown,
  showWgfdModal,
  wgfdCards,
  selectedWgfdCard,
  toggleWgfdSelect,
  confirmWgfd,
  confirmGushouDiscard,
  jdsrTarget,
  jdsrInitiator,
  luanjianInitiator, // <--- 干净的唯一引入
  kurouUseCount,
  kurouAwakened,
  confirmKurouAwakenDiscard,
  skipKurouAwakenDiscard,
  tieqiJudgeCards,
} from "./GameBoard.js";

// ====== 【核心修复 2：防脱发防白屏机制】 ======
const _exposeToHtml = {
  userId,
  otherPlayers,
  currentTurn,
  countdown,
  activeActionTexts,
  activeEmojis,
  currentAoeType,
  pendingAoePlayers,
  aoeInitiator,
  tableCards,
  lastPlayPlayer,
  sortedTableCards,
  isSpectator,
  myStatus,
  handCards,
  amIPendingAoe,
  selectedCards,
  aoeAnimCards,
  sortedHandCards,
  showEmojiPanel,
  emojiList,
  winner,
  winningCards,
  killText,
  sortedWinningCards,
  getCardImageUrl,
  handleImageError,
  toggleSelect,
  respondAoe,
  discardAoe,
  playCards,
  replaceCard,
  passTurn,
  toggleEmojiPanel,
  sendEmoji,
  exitGame,
  returnToRoom,
  soundStatus,
  toggleSound,
  isShuffling,
  warningUserId,
  mySkill,
  showSkillSelection,
  showGuanxingModal,
  guanxingCards,
  selectedGuanxingCards,
  selectSkill,
  toggleGuanxingSelect,
  confirmGuanxing,
  playBGM,
  skillCountdown,
  showWgfdModal,
  wgfdCards,
  selectedWgfdCard,
  toggleWgfdSelect,
  confirmWgfd,
  confirmGushouDiscard,
  jdsrTarget,
  jdsrInitiator,
  luanjianInitiator, // <--- 干净的唯一暴露
  kurouUseCount,
  kurouAwakened,
  confirmKurouAwakenDiscard,
  skipKurouAwakenDiscard,
  tieqiJudgeCards,
};
</script>

<style scoped src="./GameBoard.css">
/* ====== 1. 全局容器与桌面的居中布局 ====== */
.battle-container {
  display: flex;
  flex-direction: column;
  width: 100vw;
  height: 100vh;
  background-color: #2c3e50;
  color: #ecf0f1;
  font-family: "Helvetica Neue", Helvetica, Arial, sans-serif;
  overflow: hidden;
  position: relative;
}

/* 彻底修复：将其他玩家区域放在最上方且居中 */
.player-list {
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 10px 0;
  gap: 15px;
  background-color: rgba(44, 62, 80, 0.8);
  height: 110px; /* 固定高度 */
  border-bottom: 2px solid rgba(0, 0, 0, 0.3);
}

/* 核心修复：让桌面区域占据所有剩余空间且绝对居中 */
.table-area {
  flex: 1;
  display: flex;
  justify-content: center;
  align-items: center;
  position: relative;
}

/* 徹底修复：桌面中央容器，使用 Flex 排版保证内部卡牌绝对居中 */
.table-center {
  width: 80%; /* 这里定义桌面的大致范围 */
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  position: relative;
}

/* ====== 2. 卡牌显示区域的居中排版 ====== */

/* 通用：锦囊和普通牌显示 */
.played-cards,
.aoe-pulse {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

/* 卡牌行：彻底移除浮动和 Margin，使用 Flex 居中且可以换行 */
.card-display-row {
  display: flex;
  justify-content: center;
  align-items: center;
  flex-wrap: wrap; /* 防止太多牌顶破界面 */
  gap: 10px; /* 卡牌间距 */
  width: 100%;
}

/* 卡牌样式 */
.card.mini {
  width: 90px;
  height: 130px;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 5px 15px rgba(0, 0, 0, 0.5);
  border: 1px solid rgba(255, 255, 255, 0.2);
}

.card.mini img {
  width: 100%;
  height: 100%;
}

.fallback-text {
  font-size: 14px;
}

/* 桌面提示字 */
.table-hint {
  font-size: 16px;
  color: #f39c12;
  font-weight: bold;
}

/* ====== 3. 各种响应/警告栏的绝对居中 ====== */

/* 通用：将响应栏固定在底部手牌上方，保证它不会顶到桌面上的牌 */
.aoe-action-bar,
.spectator-bar {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 120px; /* 固定高度，放在手牌区域上方 */
  height: 60px;
  background-color: rgba(44, 62, 80, 0.95);
  border-top: 2px solid rgba(0, 0, 0, 0.3);
  display: flex;
  justify-content: center; /* 核心：内容水平居中 */
  align-items: center;
  gap: 15px;
  z-index: 10;
}

/* 核心修复：借刀杀人响应栏也必须绝对居中且不影响桌面定位 */
.aoe-action-bar.jdsr-target-bar {
  background-color: rgba(231, 76, 60, 0.15); /* 加一层淡红底色 */
  border-top: 2px solid rgba(231, 76, 60, 0.5);
}

.aoe-hint,
.pass-wait {
  font-size: 16px;
  color: #bdc3c7;
}

/* 按钮居中，移除其他定位干扰 */
.play-btn,
.pass-btn {
  padding: 8px 16px;
  border-radius: 4px;
  border: none;
  font-weight: bold;
}

.play-btn:disabled {
  background-color: #bdc3c7;
}

/* 底部的文字提示也保持居中 */
.wait-text {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 140px;
  text-align: center;
  font-size: 16px;
  color: #bdc3c7;
}

/* ====== 4. 底部手牌区域 ====== */
.player-hand-area {
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  height: 120px;
  background-color: rgba(52, 73, 94, 0.9);
  border-top: 2px solid rgba(0, 0, 0, 0.3);
  padding: 10px;
}

.hand-cards {
  display: flex;
  justify-content: center;
  align-items: flex-end;
  gap: 8px;
  width: 100%;
}
</style>
