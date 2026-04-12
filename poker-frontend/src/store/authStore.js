import * as state from "./gameState.js";
import {
  apiFetch,
  clearTabAuthToken,
  getTabAuthToken,
  setTabAuthToken,
} from "./serverConfig.js";
import { resetSocialState } from "./socialStore.js";

const ACTIVE_TAB_PREFIX = "poker:active-tab:";
const TAB_ID_KEY = "poker:tab-id";
const TAB_HEARTBEAT_MS = 3000;
const ACTIVE_TAB_STALE_MS = 8000;

let activeTabHeartbeat = null;
let activeTabStorageBound = false;
let unloadCleanupBound = false;
let ownedTabUsername = "";

const clearAuthMessages = () => {
  state.authError.value = "";
  state.authSuccess.value = "";
};

const syncDailySignInState = (authPayload) => {
  const available =
    !!authPayload &&
    !authPayload.guest &&
    !!authPayload.dailySignInAvailable;
  state.dailySignInVisible.value = available;
  state.dailySignInLoading.value = false;
  state.dailySignInMessage.value = available ? "每日签到可获得 3 点经验值" : "";
};

const ensureTabId = () => {
  if (typeof window === "undefined") {
    return "server-tab";
  }
  let tabId = window.sessionStorage.getItem(TAB_ID_KEY);
  if (!tabId) {
    tabId =
      window.crypto?.randomUUID?.() ||
      `tab-${Date.now()}-${Math.random().toString(16).slice(2)}`;
    window.sessionStorage.setItem(TAB_ID_KEY, tabId);
  }
  return tabId;
};

const activeTabKey = (username) => `${ACTIVE_TAB_PREFIX}${username}`;

const readTabOwner = (username) => {
  if (typeof window === "undefined" || !username) {
    return null;
  }
  try {
    const raw = window.localStorage.getItem(activeTabKey(username));
    return raw ? JSON.parse(raw) : null;
  } catch (error) {
    return null;
  }
};

const writeTabOwner = (username) => {
  if (typeof window === "undefined" || !username) {
    return;
  }
  window.localStorage.setItem(
    activeTabKey(username),
    JSON.stringify({
      tabId: ensureTabId(),
      updatedAt: Date.now(),
    }),
  );
};

const clearTabOwner = (username) => {
  if (typeof window === "undefined" || !username) {
    return;
  }
  const owner = readTabOwner(username);
  if (owner?.tabId === ensureTabId()) {
    window.localStorage.removeItem(activeTabKey(username));
  }
};

const stopTabOwnership = (username = ownedTabUsername) => {
  if (activeTabHeartbeat) {
    clearInterval(activeTabHeartbeat);
    activeTabHeartbeat = null;
  }
  clearTabOwner(username);
  ownedTabUsername = "";
};

const handleStorageConflict = async (event) => {
  const currentUser = state.authUser.value;
  if (!currentUser?.username) {
    return;
  }
  if (event.key !== activeTabKey(currentUser.username) || !event.newValue) {
    return;
  }
  const incomingOwner = readTabOwner(currentUser.username);
  if (incomingOwner?.tabId && incomingOwner.tabId !== ensureTabId()) {
    await resetAuthState("该账号已在当前设备的其他页面打开");
  }
};

const bindTabOwnershipListeners = () => {
  if (typeof window === "undefined") {
    return;
  }
  if (!activeTabStorageBound) {
    window.addEventListener("storage", handleStorageConflict);
    activeTabStorageBound = true;
  }
  if (!unloadCleanupBound) {
    window.addEventListener("beforeunload", () => {
      stopTabOwnership();
    });
    unloadCleanupBound = true;
  }
};

const hasAnyOtherActiveAuthTab = () => {
  if (typeof window === "undefined") {
    return false;
  }
  const myTabId = ensureTabId();
  const now = Date.now();
  for (let index = 0; index < window.localStorage.length; index += 1) {
    const storageKey = window.localStorage.key(index);
    if (!storageKey || !storageKey.startsWith(ACTIVE_TAB_PREFIX)) {
      continue;
    }
    try {
      const payload = JSON.parse(window.localStorage.getItem(storageKey) || "null");
      if (!payload?.tabId || !payload?.updatedAt) {
        continue;
      }
      if (payload.tabId === myTabId) {
        continue;
      }
      if (now - Number(payload.updatedAt) <= ACTIVE_TAB_STALE_MS) {
        return true;
      }
    } catch (error) {
      continue;
    }
  }
  return false;
};

const startTabOwnership = (user) => {
  if (!user?.username) {
    return;
  }
  bindTabOwnershipListeners();
  stopTabOwnership();
  ownedTabUsername = user.username;
  writeTabOwner(user.username);
  activeTabHeartbeat = window.setInterval(() => {
    writeTabOwner(user.username);
  }, TAB_HEARTBEAT_MS);
};

const clearClientSessionState = () => {
  if (state.ws.value) {
    const currentSocket = state.ws.value;
    state.ws.value = null;
    if (
      currentSocket.readyState === WebSocket.OPEN ||
      currentSocket.readyState === WebSocket.CONNECTING
    ) {
      currentSocket.close();
    }
  }
  state.authUser.value = null;
  state.isAuthenticated.value = false;
  state.userId.value = "";
  state.authUsername.value = "";
  state.authPassword.value = "";
  state.authCaptchaCode.value = "";
  state.dailySignInVisible.value = false;
  state.dailySignInLoading.value = false;
  state.dailySignInMessage.value = "";
  state.isConnected.value = false;
  state.gameStarted.value = false;
  state.ownerId.value = "";
  state.currentTurn.value = "";
  state.lastPlayPlayer.value = "";
  state.winner.value = "";
  state.otherPlayers.value = [];
  state.spectators.value = [];
  state.handCards.value = [];
  state.tableCards.value = [];
  state.pendingAoePlayers.value = [];
  state.currentAoeType.value = null;
};

const syncTabToken = (authPayload) => {
  if (authPayload?.tabToken) {
    setTabAuthToken(authPayload.tabToken);
    return;
  }
  clearTabAuthToken();
};

export const resetAuthState = async (
  message = "",
  { refreshCaptcha: shouldRefreshCaptcha = true } = {},
) => {
  clearAuthMessages();
  stopTabOwnership();
  clearTabAuthToken();
  clearClientSessionState();
  resetSocialState();
  state.authMode.value = "login";
  if (message) {
    state.authError.value = message;
  }
  if (shouldRefreshCaptcha) {
    await refreshCaptcha();
  }
};

export const switchAuthMode = (mode) => {
  state.authMode.value = mode;
  clearAuthMessages();
};

export const refreshCaptcha = async () => {
  try {
    const response = await apiFetch("/api/auth/captcha", { method: "POST" });
    const data = await response.json();
    state.authCaptchaImage.value = data.imageBase64 || "";
  } catch (error) {
    state.authError.value = "验证码加载失败，请重试";
  }
};

export const bootstrapAuth = async () => {
  clearAuthMessages();
  state.authChecked.value = false;
  const existingTabToken = getTabAuthToken();
  if (!existingTabToken && hasAnyOtherActiveAuthTab()) {
    state.authUser.value = null;
    state.isAuthenticated.value = false;
    state.userId.value = "";
    await refreshCaptcha();
    state.authChecked.value = true;
    return;
  }
  try {
    const response = await apiFetch("/api/auth/me", { method: "GET" });
    if (!response.ok) {
      throw new Error("NOT_AUTHENTICATED");
    }
    const data = await response.json();
    state.authUser.value = data;
    state.isAuthenticated.value = true;
    state.authChecked.value = true;
    state.userId.value = data.username;
    syncTabToken(data);
    syncDailySignInState(data);
    startTabOwnership(data);
  } catch (error) {
    state.authUser.value = null;
    state.isAuthenticated.value = false;
    state.userId.value = "";
    await refreshCaptcha();
  } finally {
    state.authChecked.value = true;
  }
};

export const submitAuth = async () => {
  clearAuthMessages();
  state.authLoading.value = true;
  try {
    const endpoint =
      state.authMode.value === "register" ? "/api/auth/register" : "/api/auth/login";
    const response = await apiFetch(endpoint, {
      method: "POST",
      body: JSON.stringify({
        username: state.authUsername.value,
        password: state.authPassword.value,
        captchaCode: state.authCaptchaCode.value,
      }),
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      throw new Error(data.message || "操作失败");
    }

    state.authUser.value = data;
    state.isAuthenticated.value = true;
    state.userId.value = data.username;
    syncTabToken(data);
    syncDailySignInState(data);
    startTabOwnership(data);
    state.authSuccess.value =
      state.authMode.value === "register" ? "注册并登录成功" : "登录成功";
    state.authCaptchaCode.value = "";
  } catch (error) {
    state.authError.value = error.message || "登录失败";
    await refreshCaptcha();
  } finally {
    state.authLoading.value = false;
  }
};

export const submitGuestLogin = async () => {
  clearAuthMessages();
  state.authLoading.value = true;
  try {
    const response = await apiFetch("/api/auth/guest", {
      method: "POST",
      body: JSON.stringify({}),
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      throw new Error(data.message || "游客登录失败");
    }
    state.authUser.value = data;
    state.isAuthenticated.value = true;
    state.authChecked.value = true;
    state.userId.value = data.username;
    clearTabAuthToken();
    syncDailySignInState(data);
    state.authSuccess.value = `已使用 ${data.username} 进入大厅`;
  } catch (error) {
    state.authError.value = error.message || "游客登录失败";
  } finally {
    state.authLoading.value = false;
  }
};

export const verifyCurrentSession = async () => {
  if (
    !state.isAuthenticated.value ||
    !state.authUser.value ||
    state.authUser.value.guest
  ) {
    return;
  }
  try {
    const response = await apiFetch("/api/auth/me", { method: "GET" });
    if (!response.ok) {
      throw new Error("UNAUTHORIZED");
    }
    const data = await response.json();
    state.authUser.value = data;
    state.userId.value = data.username;
    syncTabToken(data);
    syncDailySignInState(data);
    startTabOwnership(data);
  } catch (error) {
    if (error.message === "UNAUTHORIZED") {
      await resetAuthState("登录状态已失效，请重新登录");
    }
  }
};

export const logout = async () => {
  clearAuthMessages();
  try {
    await apiFetch("/api/auth/logout", { method: "POST" });
  } finally {
    await resetAuthState();
  }
};

export const claimDailySignIn = async () => {
  if (!state.authUser.value || state.authUser.value.guest) {
    return;
  }
  state.dailySignInLoading.value = true;
  state.dailySignInMessage.value = "";
  try {
    const response = await apiFetch("/api/auth/daily-signin", { method: "POST" });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      throw new Error(data.message || "签到失败");
    }
    state.authUser.value = {
      ...state.authUser.value,
      dailySignInAvailable: false,
    };
    state.dailySignInVisible.value = false;
    state.dailySignInMessage.value = `签到成功，经验+${data.gainedExperience ?? 3}`;
    state.authSuccess.value = state.dailySignInMessage.value;
  } catch (error) {
    state.dailySignInMessage.value = error.message || "签到失败";
    state.authError.value = state.dailySignInMessage.value;
  } finally {
    state.dailySignInLoading.value = false;
  }
};
