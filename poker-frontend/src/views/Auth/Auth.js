import { ref } from "vue";
import {
  authCaptchaCode,
  authCaptchaImage,
  authChecked,
  authError,
  authLoading,
  authMode,
  authPassword,
  authSuccess,
  authUsername,
} from "@/store/gameState.js";
import {
  refreshCaptcha,
  submitAuth,
  submitGuestLogin,
  switchAuthMode,
} from "@/store/authStore.js";

export const authModalVisible = ref(false);

export const openAuthModal = () => {
  authError.value = "";
  authSuccess.value = "";
  authModalVisible.value = true;
};

export const closeAuthModal = () => {
  authModalVisible.value = false;
};

export {
  authCaptchaCode,
  authCaptchaImage,
  authChecked,
  authError,
  authLoading,
  authMode,
  authPassword,
  authSuccess,
  authUsername,
  refreshCaptcha,
  submitAuth,
  submitGuestLogin,
  switchAuthMode,
};
