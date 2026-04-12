import { computed, ref } from "vue";

export const ws = ref(null);
export const isConnected = ref(false);
export const roomId = ref("101");
export const userId = ref("");
export const isPrivate = ref(false);
export const roomPassword = ref("");
export const publicRooms = ref([]);

export const authChecked = ref(false);
export const isAuthenticated = ref(false);
export const authUser = ref(null);
export const authMode = ref("login");
export const authLoading = ref(false);
export const authError = ref("");
export const authSuccess = ref("");
export const authUsername = ref("");
export const authPassword = ref("");
export const authCaptchaCode = ref("");
export const authCaptchaImage = ref("");
export const dailySignInVisible = ref(false);
export const dailySignInLoading = ref(false);
export const dailySignInMessage = ref("");

export const gameStarted = ref(false);
export const ownerId = ref("");
export const isReady = ref(false);
export const myStatus = ref("");
export const currentTurn = ref("");
export const lastPlayPlayer = ref("");
export const winner = ref("");

export const jdsrTarget = ref(null);
export const jdsrInitiator = ref(null);

export const handCards = ref([]);
export const tableCards = ref([]);
export const winningCards = ref([]);
export const otherPlayers = ref([]);
export const spectators = ref([]);

export const showSettings = ref(false);
export const roomSettings = ref({
  enableWildcard: false,
  enableScrollCards: false,
  enableSkills: false,
});

export const countdown = ref(20);
export const currentAoeType = ref(null);
export const pendingAoePlayers = ref([]);
export const aoeStartTime = ref(0);
export const aoeInitiator = ref("");
export const luanjianInitiator = ref("");
export const aoeAnimCards = ref([]);

export const serverTimeOffset = ref(0);
export const currentTurnStartTime = ref(0);
export const showSkillSelection = ref(false);
export const mySkill = ref("ZHIHENG");
export const showGuanxingModal = ref(false);
export const guanxingCards = ref([]);
export const selectedGuanxingCards = ref([]);

export const errorMessage = ref("");
export const showRules = ref(true);
export const showUpdates = ref(true);
export const showRuleDetail = ref(false);
export const showCreateModal = ref(false);

export const showEmojiPanel = ref(false);
export const activeEmojis = ref({});
export const activeActionTexts = ref({});
export const isSoundOn = ref(true);
export const isShuffling = ref(false);
export const warningUserId = ref("");
export const skillCountdown = ref(20);
export const skillTimer = ref(null);

export const emojiList = ref([
  "image_emoticon.png",
  "image_emoticon2.png",
  "image_emoticon3.png",
  "image_emoticon6.png",
  "image_emoticon9.png",
  "image_emoticon10.png",
  "image_emoticon15.png",
  "image_emoticon16.png",
  "image_emoticon17.png",
  "image_emoticon22.png",
  "image_emoticon23.png",
  "image_emoticon24.png",
  "image_emoticon27.png",
  "image_emoticon28.png",
  "image_emoticon33.png",
  "image_emoticon34.png",
  "image_emoticon35.png",
  "image_emoticon36.png",
  "image_emoticon73.png",
  "image_emoticon88.png",
]);
export const aliEmojiList = ref(["icon-shengqi"]);

export const showWgfdModal = ref(false);
export const wgfdCards = ref([]);
export const selectedWgfdCard = ref([]);

export const isClassicMode = computed(
  () =>
    !roomSettings.value.enableWildcard && !roomSettings.value.enableScrollCards,
);

export const isSpectator = computed(() =>
  spectators.value.includes(userId.value),
);

export const sortedHandCards = computed(() =>
  [...handCards.value].sort((a, b) => a.weight - b.weight),
);

export const sortedTableCards = computed(() =>
  [...tableCards.value].sort((a, b) => a.weight - b.weight),
);

export const sortedWinningCards = computed(() =>
  [...winningCards.value].sort((a, b) => a.weight - b.weight),
);

export const selectedCards = computed(() =>
  handCards.value.filter((card) => card.selected),
);

export const allReady = computed(
  () =>
    otherPlayers.value.length > 0 && otherPlayers.value.every((p) => p.isReady),
);

export const killText = computed(() => {
  const count = otherPlayers.value.length;
  if (count === 1) return "一杀 卧龙出山！";
  if (count === 2) return "双连 一战成名！";
  if (count === 3) return "三连 举世皆惊！";
  return "一杀 卧龙出山！";
});

export const amIPendingAoe = computed(() =>
  pendingAoePlayers.value.includes(userId.value),
);

export const hasValidAoeCard = computed(() => {
  if (!currentAoeType.value || !amIPendingAoe.value) return true;
  if (currentAoeType.value === "GUANXING" || currentAoeType.value === "WGFD")
    return true;

  if (currentAoeType.value === "NMRQ") {
    return handCards.value.some(
      (c) =>
        c.suit === "♥" ||
        c.suit === "♦" ||
        (c.suit === "JOKER" && c.rank === "大王"),
    );
  }

  if (currentAoeType.value === "WJQF") {
    return handCards.value.some(
      (c) =>
        c.suit === "♠" ||
        c.suit === "♣" ||
        (c.suit === "JOKER" && c.rank === "小王"),
    );
  }
  return true;
});

export const onlyHasScrolls = computed(
  () =>
    handCards.value.length > 0 &&
    handCards.value.every((card) => card.suit === "SCROLL"),
);
