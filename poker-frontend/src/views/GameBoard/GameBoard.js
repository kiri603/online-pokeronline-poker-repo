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
  onlyHasScrolls,
  isShuffling,
  warningUserId,
  mySkill,
  showSkillSelection,
  showGuanxingModal,
  guanxingCards,
  selectedGuanxingCards,
  skillCountdown,
  showWgfdModal,
  wgfdCards,
  selectedWgfdCard,
  jdsrTarget,
  jdsrInitiator,
  luanjianInitiator, // <--- 确保只有这一个是新增的
  kurouUseCount,
  kurouUsesThisTurn,
  kurouAwakened,
  guixinDisabled,
  guixinPendingPasser,
  showGuixinModal,
  tieqiJudgeCards,
} from "@/store/gameState.js";
import { computed } from "vue";
import { soundStatus, toggleSound, playBGM } from "@/store/audioManager.js";

import { ws, sendMsg, passTurn } from "@/store/gameSocket.js";

// ====== 卡牌图片与交互逻辑 ======
const getCardImageUrl = (card) => {
  if (card.suit === "SCROLL") return `/images/${card.rank}.png`;
  if (card.suit === "JOKER")
    return card.rank === "小王"
      ? `/images/JokerSmall.png`
      : `/images/JokerBig.png`;
  const suitMap = { "♠": "Spade", "♥": "Heart", "♣": "Club", "♦": "Diamond" };
  return `/images/${suitMap[card.suit]}${card.rank}.png`;
};

const handleImageError = (e) => {
  e.target.style.display = "none";
};

const toggleSelect = (card) => {
  if (currentTurn.value === userId.value || amIPendingAoe.value) {
    card.selected = !card.selected;
  }
};

// ====== 锦囊响应逻辑 ======
const respondAoe = (card) => {
  if (card) {
    if (currentAoeType.value === "NMRQ") {
      const isRed = card.suit === "♥" || card.suit === "♦";
      const isBigJoker = card.suit === "JOKER" && card.rank === "大王";
      if (!isRed && !isBigJoker)
        return alert("南蛮入侵：必须弃置一张红色花色卡牌或大王！");
    } else if (currentAoeType.value === "WJQF") {
      if (luanjianInitiator.value !== userId.value) {
        const isBlack = card.suit === "♠" || card.suit === "♣";
        const isSmallJoker = card.suit === "JOKER" && card.rank === "小王";
        if (!isBlack && !isSmallJoker)
          return alert("万箭齐发：必须弃置一张黑色花色卡牌或小王！");
      }
    }
    sendMsg("RESPOND_AOE", {
      suit: card.suit,
      rank: card.rank,
      weight: card.weight,
    });
  } else {
    sendMsg("RESPOND_AOE", null);
  }
};

const discardAoe = () => respondAoe(selectedCards.value[0]);

// ====== 基础出牌与动作 ======
const playCards = () =>
  sendMsg(
    "PLAY_CARD",
    selectedCards.value.map((c) => ({
      suit: c.suit,
      rank: c.rank,
      weight: c.weight,
    })),
  );

const replaceCard = () => {
  if (mySkill.value === "ZHIHENG" || !mySkill.value) {
    // ====== 【核心修复】：剔除制衡卡牌的 selected 脏属性 ======
    const c = selectedCards.value[0];
    const cleanCard = { suit: c.suit, rank: c.rank, weight: c.weight };
    sendMsg("REPLACE_CARD", cleanCard);
    c.selected = false;
    // =======================================================
  } else if (mySkill.value === "LUANJIAN") {
    if (selectedCards.value.length !== 2) return alert("乱箭只能选择 1 张牌");
    const isBlack = selectedCards.value.every(
      (c) => c.suit === "♠" || c.suit === "♣",
    );
    if (!isBlack) return alert("乱箭必须选择黑色牌！");

    const cleanCards = selectedCards.value.map((c) => ({
      suit: c.suit,
      rank: c.rank,
      weight: c.weight,
    }));
    sendMsg("USE_SKILL", { skill: "LUANJIAN", cards: cleanCards });

    selectedCards.value.forEach((c) => (c.selected = false));
  } else if (mySkill.value === "GUANXING") {
    sendMsg("USE_SKILL", { skill: "GUANXING" });
  } else if (mySkill.value === "GUSHOU") {
    sendMsg("USE_GUSHOU", null);
  } else if (mySkill.value === "KUROU") {
    if (kurouUsesThisTurn.value >= 2)
      return alert("本回合苦肉已使用 2 次！");
    if (selectedCards.value.length !== 2)
      return alert("苦肉必须选择 2 张牌！");
    const cleanCards = selectedCards.value.map((c) => ({
      suit: c.suit,
      rank: c.rank,
      weight: c.weight,
    }));
    sendMsg("USE_SKILL", { skill: "KUROU", cards: cleanCards });
    selectedCards.value.forEach((c) => (c.selected = false));
  } else if (mySkill.value === "GUIXIN") {
    if (guixinDisabled.value) return alert("归心已禁用，需要保护他人要不起后解除！");
    sendMsg("USE_SKILL", { skill: "GUIXIN" });
  }
};
const selectSkill = (s) => {
  mySkill.value = s;
  showSkillSelection.value = false;
  sendMsg("SELECT_SKILL", s);
};
const toggleGuanxingSelect = (card) => {
  card.selected = !card.selected;
  selectedGuanxingCards.value = guanxingCards.value.filter((c) => c.selected);
};
const confirmGuanxing = () => {
  if (selectedGuanxingCards.value.length !== 2) return;

  // ====== 【核心修复】：必须剔除 Vue 绑定的 selected 属性！ ======
  // 否则后端的 JSON 解析器会因为找不到 selected 字段直接抛出异常，导致卡死在 0s！
  const cleanCards = selectedGuanxingCards.value.map((c) => ({
    suit: c.suit,
    rank: c.rank,
    weight: c.weight,
  }));

  sendMsg("GUANXING_SELECT", cleanCards);
  showGuanxingModal.value = false;
};

// ====== 表情包控制 ======
const toggleEmojiPanel = () => {
  showEmojiPanel.value = !showEmojiPanel.value;
};
const sendEmoji = (emoji) => {
  sendMsg("SEND_EMOJI", emoji);
  showEmojiPanel.value = false;
};

// ====== 房间控制 ======
const exitGame = () => {
  if (confirm("确定要退出当前房间吗？(游戏中退出会导致对局中止)")) {
    if (ws.value) ws.value.close();
  }
};
const returnToRoom = () => sendMsg("RETURN_TO_ROOM", null);
// ====== 新增：五谷丰登的 UI 交互 ======
const toggleWgfdSelect = (card) => {
  if (!amIPendingAoe.value) return; // 还没轮到自己，只能看不能选
  wgfdCards.value.forEach((c) => (c.selected = false)); // 单选机制
  card.selected = true;
  selectedWgfdCard.value = [card];
};

const confirmWgfd = () => {
  if (selectedWgfdCard.value.length !== 1) return;
  const cleanCard = {
    suit: selectedWgfdCard.value[0].suit,
    rank: selectedWgfdCard.value[0].rank,
    weight: selectedWgfdCard.value[0].weight,
  };
  sendMsg("WGFD_SELECT", cleanCard);
};
// ====== 新增：固守弃牌交互 ======
const confirmGushouDiscard = () => {
  const required = Math.min(2, handCards.value.length);
  if (selectedCards.value.length !== required) return;
  const cleanCards = selectedCards.value.map((c) => ({
    suit: c.suit,
    rank: c.rank,
    weight: c.weight,
  }));
  sendMsg("GUSHOU_DISCARD", cleanCards);
  selectedCards.value.forEach((c) => (c.selected = false));
};

// ====== 新增：苦肉·觉醒额外弃黑交互 ======
const confirmKurouAwakenDiscard = () => {
  if (selectedCards.value.length !== 1) return;
  const c = selectedCards.value[0];
  const isBlackSuit = c.suit === "\u2660" || c.suit === "\u2663";
  const isSmallJoker = c.suit === "JOKER" && c.rank === "\u5c0f\u738b";
  if (!isBlackSuit && !isSmallJoker) {
    return alert("觉醒弃置必须为黑色牌（♠ / ♣ / 小王）！");
  }
  const cleanCard = { suit: c.suit, rank: c.rank, weight: c.weight };
  sendMsg("KUROU_AWAKEN_DISCARD", cleanCard);
  selectedCards.value.forEach((card) => (card.selected = false));
};
const skipKurouAwakenDiscard = () => {
  selectedCards.value.forEach((card) => (card.selected = false));
  sendMsg("KUROU_AWAKEN_DISCARD", null);
};
const confirmGuixinDecision = (accept) => {
  sendMsg("GUIXIN_DECISION", { accept });
};
export {
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
  onlyHasScrolls,
  soundStatus,
  toggleSound,
  isShuffling,
  warningUserId,
  playBGM,
  mySkill,
  showSkillSelection,
  showGuanxingModal,
  guanxingCards,
  selectedGuanxingCards,
  selectSkill,
  toggleGuanxingSelect,
  confirmGuanxing,
  skillCountdown,
  showWgfdModal,
  wgfdCards,
  selectedWgfdCard,
  toggleWgfdSelect,
  confirmWgfd,
  confirmGushouDiscard,
  jdsrTarget,
  jdsrInitiator,
  luanjianInitiator, // <--- 确保只有这一个是新增的
  kurouUseCount,
  kurouUsesThisTurn,
  kurouAwakened,
  guixinDisabled,
  guixinPendingPasser,
  showGuixinModal,
  confirmGuixinDecision,
  confirmKurouAwakenDiscard,
  skipKurouAwakenDiscard,
  tieqiJudgeCards,
};
