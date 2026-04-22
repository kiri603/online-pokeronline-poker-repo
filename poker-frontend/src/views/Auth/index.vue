<template src="./Auth.html"></template>

<script setup>
import {
  authCaptchaCode,
  authCaptchaImage,
  authChecked,
  authError,
  authLoading,
  authMode,
  authModalVisible,
  authPassword,
  authSuccess,
  authUsername,
  closeAuthModal,
  openAuthModal,
  refreshCaptcha,
  submitAuth,
  submitGuestLogin,
  switchAuthMode,
} from "./Auth.js";

const _exposeToHtml = {
  authCaptchaCode,
  authCaptchaImage,
  authChecked,
  authError,
  authLoading,
  authMode,
  authModalVisible,
  authPassword,
  authSuccess,
  authUsername,
  closeAuthModal,
  openAuthModal,
  refreshCaptcha,
  submitAuth,
  submitGuestLogin,
  switchAuthMode,
};
</script>

<style scoped src="./Auth.css"></style>
