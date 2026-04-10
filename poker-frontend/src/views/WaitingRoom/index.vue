<template src="./WaitingRoom.html"></template>

<script setup>
import {
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
} from "./WaitingRoom.js";

const waitingRoomIsOwner = isOwner;
const waitingRoomIsManager = isManager;
const waitingRoomPlayers = displayPlayers;
const waitingRoomActions = waitingActionsClass;

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
  userId,
  isReady,
  otherPlayers,
  displayPlayers,
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
};
</script>

<style scoped src="./WaitingRoom.css"></style>
