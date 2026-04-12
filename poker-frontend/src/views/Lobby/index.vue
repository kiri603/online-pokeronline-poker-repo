<template src="./Lobby.html"></template>

<script setup>
import { onMounted, onUnmounted } from "vue";
import {
  confirmCreateRoom,
  goToRuleDetail,
  handleJoinClick,
  isPrivate,
  playBGM,
  publicRooms,
  quickJoin,
  roomId,
  roomPassword,
  roomSettings,
  showCreateModal,
  showRuleDetail,
  showRules,
  showUpdates,
  soundStatus,
  startPolling,
  stopPolling,
  toggleSound,
} from "./Lobby.js";

const _exposeToHtml = {
  confirmCreateRoom,
  goToRuleDetail,
  handleJoinClick,
  isPrivate,
  playBGM,
  publicRooms,
  quickJoin,
  roomId,
  roomPassword,
  roomSettings,
  showCreateModal,
  showRuleDetail,
  showRules,
  showUpdates,
  soundStatus,
  startPolling,
  stopPolling,
  toggleSound,
};

onMounted(() => {
  startPolling();
  playBGM("Welcome");
});

onUnmounted(() => {
  stopPolling();
});
</script>

<style scoped src="./Lobby.css"></style>
