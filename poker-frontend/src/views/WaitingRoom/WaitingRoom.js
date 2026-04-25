import { computed, ref } from "vue";
import { authUser } from "@/store/gameState.js";
import {
  showSettings,
  roomSettings,
  isClassicMode,
  ownerId,
  roomId,
  userId,
  isReady,
  otherPlayers,
  allReady,
  spectators,
  isSpectator,
} from "@/store/gameState.js";
import { soundStatus, toggleSound, playBGM } from "@/store/audioManager.js";
import { sendMsg, ws } from "@/store/gameSocket.js";
import {
  canUseSocial,
  inviteFriendToRoom,
  onlineFriends,
  sendFriendRequest,
  socialFriendIds,
  socialIncomingFriendRequestIds,
  socialDrawerOpen,
  toggleWaitingFriendDrawer,
} from "@/store/socialStore.js";

const isOwner = computed(() => ownerId.value === userId.value);
const isManager = computed(() => userId.value === "room_manager");

const displayPlayers = computed(() => {
  const players = [];

  if (!isSpectator.value) {
    players.push({
      userId: userId.value,
      isReady: isReady.value,
      isBot: false,
      isSelf: true,
      orderSeed: -1,
    });
  }

  otherPlayers.value.forEach((player, index) => {
    players.push({
      ...player,
      isSelf: player.userId === userId.value,
      orderSeed: index,
    });
  });

  return players
    .filter(
      (player, index, allPlayers) =>
        allPlayers.findIndex((candidate) => candidate.userId === player.userId) ===
        index,
    )
    .sort((left, right) => {
      const rank = (player) => {
        if (player.userId === ownerId.value) return 0;
        if (player.isSelf) return 1;
        if (!player.isBot) return 2;
        return 3;
      };

      return rank(left) - rank(right) || left.orderSeed - right.orderSeed;
    });
});

const waitingActionsClass = computed(() => ({
  "owner-actions": isOwner.value,
  "player-actions": !isOwner.value && !isManager.value,
  "manager-actions": isManager.value,
}));

const waitingRoomFriendList = computed(() =>
  onlineFriends.value.filter((friend) => friend.userId !== authUser.value?.username),
);

const isFriendPlayer = (player) =>
  canUseSocial.value && socialFriendIds.value.has(player.userId);

const canQuickAddFriend = (player) =>
  canUseSocial.value &&
  !player.isSelf &&
  !player.isBot &&
  !String(player.userId || "").startsWith("游客") &&
  !socialFriendIds.value.has(player.userId) &&
  !socialIncomingFriendRequestIds.value.has(player.userId);

const quickAddFriend = async (player) => {
  await sendFriendRequest(player.userId);
};

const disbandRoom = () => {
  if (confirm("🚨 警告：确定要彻底从内存中抹除这个房间，并踢出所有人吗？")) {
    sendMsg("DISBAND_ROOM", null);
  }
};
const updateSettings = () => {
  if (ownerId.value === userId.value) {
    sendMsg("UPDATE_SETTINGS", roomSettings.value);
  }
};

const handleScrollCardsChange = () => {
  if (roomSettings.value.enableScrollCards) {
    alert("牌堆将额外加入两张【南蛮入侵】和两张【万箭齐发】");
  }
  updateSettings();
};

const kickPlayer = (targetId) => {
  if (confirm(`确定要踢出玩家 ${targetId} 吗？`)) {
    sendMsg("KICK_PLAYER", targetId);
  }
};

const startGame = () => sendMsg("START_GAME", null);
const toggleReady = () => sendMsg("READY", null);
const addScriptAi = () => sendMsg("ADD_SCRIPT_AI", null);

const returnToLobby = () => {
  if (ws.value) ws.value.close();
};

// ====== 【等待大厅新版 UI 状态 & 逻辑】 ======

// 4 座位网格：占用位排前面，空位自动补齐到 4
const seatSlots = computed(() => {
  const slots = Array.from({ length: 4 }, (_, index) => ({
    index,
    seatNo: index + 1,
    player: null,
  }));
  displayPlayers.value.forEach((player, idx) => {
    if (idx < 4) {
      slots[idx].player = player;
    }
  });
  return slots;
});

const occupiedCount = computed(() => displayPlayers.value.length);

// 默认头像池（暂无头像系统时按座位序号循环使用）
const DEFAULT_AVATARS = [
  "/images/emojis/mascot_01_xiao.png",
  "/images/emojis/mascot_02_kaixin.png",
  "/images/emojis/mascot_09_deyi.png",
  "/images/emojis/mascot_07_haixiu.png",
];

const avatarForPlayer = (player, seatIndex) => {
  if (player?.avatar) return player.avatar;
  return DEFAULT_AVATARS[seatIndex % DEFAULT_AVATARS.length];
};

// 邀请弹窗状态
const inviteModalOpen = ref(false);
const inviteFocusedSeat = ref(null);
const copyFeedback = ref("");
let copyFeedbackTimer = null;

const openInviteModal = async (seatIndex) => {
  inviteFocusedSeat.value = typeof seatIndex === "number" ? seatIndex : null;
  inviteModalOpen.value = true;
  // 注册用户打开时刷新一下好友列表
  if (canUseSocial.value && !socialDrawerOpen.value) {
    try {
      const { fetchSocialOverview } = await import("@/store/socialStore.js");
      await fetchSocialOverview();
    } catch (_) {
      /* 静默失败，弹窗仍可用 */
    }
  }
};

const closeInviteModal = () => {
  inviteModalOpen.value = false;
  inviteFocusedSeat.value = null;
};

const flashCopyFeedback = (text) => {
  copyFeedback.value = text;
  if (copyFeedbackTimer) {
    window.clearTimeout(copyFeedbackTimer);
  }
  copyFeedbackTimer = window.setTimeout(() => {
    copyFeedback.value = "";
    copyFeedbackTimer = null;
  }, 2000);
};

const copyRoomId = async () => {
  const value = String(roomId.value || "");
  if (!value) {
    flashCopyFeedback("当前无房间号");
    return;
  }
  try {
    if (navigator?.clipboard?.writeText) {
      await navigator.clipboard.writeText(value);
    } else {
      // 回退方案：老浏览器 / 非 HTTPS 环境
      const input = document.createElement("input");
      input.value = value;
      document.body.appendChild(input);
      input.select();
      document.execCommand("copy");
      document.body.removeChild(input);
    }
    flashCopyFeedback(`房间号 ${value} 已复制`);
  } catch (_) {
    flashCopyFeedback(`复制失败，可手动输入 ${value}`);
  }
};

// 邀请弹窗里的 AI 按钮点击
const addAiAndClose = () => {
  addScriptAi();
  closeInviteModal();
};

// 邀请弹窗里的邀请好友点击
const inviteFromModal = async (friendUserId) => {
  await inviteFriendToRoom(friendUserId);
};

const toggleSettingsPanel = () => {
  if (!showSettings.value && socialDrawerOpen.value) {
    socialDrawerOpen.value = false;
  }
  showSettings.value = !showSettings.value;
};

const toggleFriendDrawerExclusive = async () => {
  if (!socialDrawerOpen.value && showSettings.value) {
    showSettings.value = false;
  }
  await toggleWaitingFriendDrawer();
};

export {
  showSettings,
  roomSettings,
  isClassicMode,
  ownerId,
  isOwner,
  isManager,
  userId,
  roomId,
  isReady,
  otherPlayers,
  displayPlayers,
  canUseSocial,
  canQuickAddFriend,
  socialDrawerOpen,
  isFriendPlayer,
  waitingRoomFriendList,
  quickAddFriend,
  toggleWaitingFriendDrawer,
  inviteFriendToRoom,
  allReady,
  waitingActionsClass,
  handleScrollCardsChange,
  kickPlayer,
  startGame,
  addScriptAi,
  toggleReady,
  returnToLobby,
  soundStatus,
  toggleSound,
  playBGM,
  spectators,
  isSpectator,
  disbandRoom,
  // ===== 新增导出 =====
  seatSlots,
  occupiedCount,
  DEFAULT_AVATARS,
  avatarForPlayer,
  inviteModalOpen,
  inviteFocusedSeat,
  openInviteModal,
  closeInviteModal,
  copyRoomId,
  copyFeedback,
  addAiAndClose,
  inviteFromModal,
  toggleSettingsPanel,
  toggleFriendDrawerExclusive,
};
