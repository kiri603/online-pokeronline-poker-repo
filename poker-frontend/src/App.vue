<template>
  <div class="game-container">
    <ErrorToast />
    <RulesDetail v-if="showRuleDetail" />
    <AccountHub
      v-if="authChecked && isAuthenticated && authUser && !authUser.guest"
    />

    <div
      v-if="
        authChecked &&
        isAuthenticated &&
        authUser &&
        !authUser.guest &&
        dailySignInVisible
      "
      class="daily-signin-overlay"
    >
      <div class="daily-signin-card">
        <div class="daily-signin-title">每日签到</div>
        <div class="daily-signin-glow">经验 +3</div>
        <button
          class="daily-signin-btn"
          :disabled="dailySignInLoading"
          @click="claimDailySignIn"
        >
          {{ dailySignInLoading ? "签到中..." : "签到" }}
        </button>
        <div v-if="dailySignInMessage" class="daily-signin-message">
          {{ dailySignInMessage }}
        </div>
      </div>
    </div>

    <AuthView v-if="!authChecked || !isAuthenticated" />
    <Lobby v-else-if="!isConnected" />

    <div v-else class="game-table">
      <div class="header">
        <span>房间号: {{ roomId }}</span>
        <span>我的ID: {{ userId }}</span>
      </div>

      <WaitingRoom v-if="!gameStarted" />
      <GameBoard v-else />
    </div>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, watch } from "vue";
import {
  bootstrapAuth,
  claimDailySignIn,
  verifyCurrentSession,
} from "@/store/authStore.js";
import { fetchSocialOverview, resetSocialState } from "@/store/socialStore.js";
import {
  authChecked,
  authUser,
  dailySignInLoading,
  dailySignInMessage,
  dailySignInVisible,
  gameStarted,
  isAuthenticated,
  isConnected,
  roomId,
  showRuleDetail,
  userId,
} from "@/store/gameState.js";

import ErrorToast from "@/views/Modals/ErrorToast.vue";
import AuthView from "@/views/Auth/index.vue";
import Lobby from "@/views/Lobby/index.vue";
import WaitingRoom from "@/views/WaitingRoom/index.vue";
import GameBoard from "@/views/GameBoard/index.vue";
import RulesDetail from "@/views/RulesDetail/index.vue";
import AccountHub from "@/views/Social/AccountHub.vue";

let authHeartbeat = null;
let socialHeartbeat = null;

onMounted(async () => {
  await bootstrapAuth();
  authHeartbeat = window.setInterval(() => {
    if (
      isAuthenticated.value &&
      authUser.value &&
      !authUser.value.guest
    ) {
      verifyCurrentSession();
    }
  }, 5000);
  socialHeartbeat = window.setInterval(() => {
    if (
      isAuthenticated.value &&
      authUser.value &&
      !authUser.value.guest
    ) {
      fetchSocialOverview();
    }
  }, 2000);
  if (
    isAuthenticated.value &&
    authUser.value &&
    !authUser.value.guest
  ) {
    fetchSocialOverview();
  }
});

onUnmounted(() => {
  if (authHeartbeat) {
    clearInterval(authHeartbeat);
    authHeartbeat = null;
  }
  if (socialHeartbeat) {
    clearInterval(socialHeartbeat);
    socialHeartbeat = null;
  }
});

watch(
  () => [isAuthenticated.value, authUser.value?.guest],
  ([authed, guest]) => {
    if (authed && !guest) {
      fetchSocialOverview();
      return;
    }
    resetSocialState();
  },
  { immediate: false },
);
</script>

<style>
html,
body,
#app {
  margin: 0;
  padding: 0;
  width: 100%;
  height: 100%;
  max-width: none !important;
  overflow: hidden;
}

.game-container {
  width: 100vw;
  height: 100vh;
  background-color: #2c3e50;
  color: white;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  font-family: Arial, sans-serif;
}

.game-table {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  position: relative;
}

.header {
  padding: 15px 20px;
  background: rgba(10, 10, 10, 0.2);
  display: flex;
  gap: 20px;
  align-items: center;
  font-size: 18px;
}

.daily-signin-overlay {
  position: fixed;
  inset: 0;
  z-index: 1400;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(10, 18, 29, 0.56);
  backdrop-filter: blur(6px);
}

.daily-signin-card {
  width: min(420px, calc(100vw - 32px));
  padding: 28px 24px;
  border-radius: 24px;
  background: linear-gradient(145deg, #2a4055, #314b63);
  border: 1px solid rgba(255, 255, 255, 0.12);
  box-shadow: 0 24px 48px rgba(9, 15, 23, 0.42);
  text-align: center;
  color: #fff;
}

.daily-signin-title {
  font-size: 30px;
  font-weight: 800;
}

.daily-signin-glow {
  margin-top: 14px;
  font-size: 36px;
  font-weight: 900;
  color: #69f1a7;
  text-shadow:
    0 0 12px rgba(105, 241, 167, 0.7),
    0 0 24px rgba(255, 199, 94, 0.32);
  animation: signinPulse 2s ease-in-out infinite;
}

.daily-signin-desc {
  margin-top: 14px;
  line-height: 1.7;
  color: #dbe8f5;
}

.daily-signin-btn {
  margin-top: 22px;
  min-width: 180px;
  padding: 12px 24px;
  border: none;
  border-radius: 14px;
  background: linear-gradient(135deg, #2fb96f, #36d079);
  color: #fff;
  font-size: 18px;
  font-weight: 800;
  cursor: pointer;
}

.daily-signin-btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.daily-signin-message {
  margin-top: 14px;
  font-size: 14px;
  color: #ffe096;
}

@keyframes signinPulse {
  0%,
  100% {
    transform: scale(1);
    opacity: 1;
  }
  50% {
    transform: scale(1.04);
    opacity: 0.88;
  }
}

@media screen and (max-width: 768px) {
  .header {
    font-size: 14px;
    padding: 10px;
    flex-wrap: wrap;
  }

  .daily-signin-card {
    padding: 24px 18px;
    border-radius: 20px;
  }

  .daily-signin-title {
    font-size: 26px;
  }

  .daily-signin-glow {
    font-size: 32px;
  }
}
</style>
