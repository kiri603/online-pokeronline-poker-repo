<template src="./WaitingRoom.html"></template>

<script setup>
import {
  showSettings,
  roomSettings,
  isClassicMode,
  ownerId,
  isOwner,
  isManager,
  roomId,
  userId,
  isReady,
  otherPlayers,
  displayPlayers,
  canUseSocial,
  canQuickAddFriend as canQuickAddFriendImpl,
  isFriendPlayer as isFriendPlayerImpl,
  quickAddFriend as quickAddFriendImpl,
  socialDrawerOpen,
  waitingRoomFriendList,
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
  disbandRoom,
  toggleSound,
  spectators,
  isSpectator,
  playBGM,
  // 新增
  seatSlots,
  occupiedCount,
  avatarForPlayer as avatarForPlayerImpl,
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
} from "./WaitingRoom.js";

const waitingRoomIsOwner = isOwner;
const waitingRoomIsManager = isManager;
const waitingRoomPlayers = displayPlayers;
const waitingRoomActions = waitingActionsClass;
const canQuickAddFriend = canQuickAddFriendImpl;
const isFriendPlayer = isFriendPlayerImpl;
const quickAddFriend = quickAddFriendImpl;
const avatarForPlayer = avatarForPlayerImpl;

// ====== 【核心防白屏机制】：强制暴露变量，防 Vite 误删 ======
const _exposeToHtml = {
  showSettings,
  roomSettings,
  isClassicMode,
  ownerId,
  isOwner,
  isManager,
  waitingRoomIsOwner,
  waitingRoomIsManager,
  roomId,
  userId,
  isReady,
  otherPlayers,
  displayPlayers,
  canUseSocial,
  canQuickAddFriend,
  isFriendPlayer,
  quickAddFriend,
  socialDrawerOpen,
  waitingRoomFriendList,
  toggleWaitingFriendDrawer,
  inviteFriendToRoom,
  waitingRoomPlayers,
  allReady,
  waitingActionsClass,
  waitingRoomActions,
  handleScrollCardsChange,
  kickPlayer,
  startGame,
  addScriptAi,
  toggleReady,
  returnToLobby,
  soundStatus,
  disbandRoom,
  toggleSound,
  spectators,
  isSpectator,
  playBGM,
  // 新增
  seatSlots,
  occupiedCount,
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
</script>

<style scoped src="./WaitingRoom.css"></style>
