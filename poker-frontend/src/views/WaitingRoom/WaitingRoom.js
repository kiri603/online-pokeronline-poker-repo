import { computed } from "vue";
import { authUser } from "@/store/gameState.js";
import {
  showSettings,
  roomSettings,
  isClassicMode,
  ownerId,
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
// 修改房间设置同步到后端
const updateSettings = () => {
  if (ownerId.value === userId.value) {
    sendMsg("UPDATE_SETTINGS", roomSettings.value);
  }
};

// 监听锦囊牌模式开关变化
const handleScrollCardsChange = () => {
  if (roomSettings.value.enableScrollCards) {
    alert("牌堆将额外加入两张【南蛮入侵】和两张【万箭齐发】");
  }
  updateSettings();
};

// 房主专属踢人功能
const kickPlayer = (targetId) => {
  if (confirm(`确定要踢出玩家 ${targetId} 吗？`)) {
    sendMsg("KICK_PLAYER", targetId);
  }
};

// 核心流程按钮操作
const startGame = () => sendMsg("START_GAME", null);
const toggleReady = () => sendMsg("READY", null);
const addScriptAi = () => sendMsg("ADD_SCRIPT_AI", null);

// 返回联机大厅 (主动断开 WebSocket)
const returnToLobby = () => {
  if (ws.value) ws.value.close();
};
export {
  showSettings,
  roomSettings,
  isClassicMode,
  ownerId,
  isOwner,
  isManager,
  userId,
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
};
