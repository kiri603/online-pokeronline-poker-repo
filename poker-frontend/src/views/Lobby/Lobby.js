import { ref } from "vue";
import { playBGM, soundStatus, toggleSound } from "@/store/audioManager.js";
import {
  isPrivate,
  publicRooms,
  roomId,
  roomPassword,
  roomSettings,
  showCreateModal,
  showRuleDetail,
  showRules,
  showUpdates,
} from "@/store/gameState.js";
import { connectWebSocket } from "@/store/gameSocket.js";
import { apiFetch } from "@/store/serverConfig.js";

let roomTimer = null;

// Local UI state: which tab of the info panel is currently active.
// Defaults to "updates" so new visitors see the latest announcement first.
const activeInfoTab = ref("updates");
const refreshing = ref(false);

const fetchRooms = async () => {
  try {
    const response = await apiFetch("/api/rooms", { method: "GET" });
    if (response.ok) {
      const data = await response.json();
      publicRooms.value = Array.isArray(data) ? data : [];
    }
  } catch (error) {
    console.error("大厅轮询失败", error);
  }
};

const refreshRooms = async () => {
  if (refreshing.value) return;
  refreshing.value = true;
  try {
    await fetchRooms();
  } finally {
    // Keep the spin animation visible for a brief moment even if the
    // request resolves instantly, so the refresh action feels tangible.
    setTimeout(() => {
      refreshing.value = false;
    }, 320);
  }
};

const startPolling = () => {
  fetchRooms();
  roomTimer = setInterval(fetchRooms, 3000);
};

const stopPolling = () => {
  if (roomTimer) {
    clearInterval(roomTimer);
    roomTimer = null;
  }
};

const handleJoinClick = async () => {
  if (!roomId.value) return alert("请输入房间号");

  try {
    const response = await apiFetch(`/api/rooms/check?roomId=${roomId.value}`, {
      method: "GET",
    });
    const data = await response.json();

    if (data.exists) {
      if (data.isPrivate) {
        const pwd = prompt("该房间为私密房间，请输入 4 位密码：");
        if (!pwd) return;
        roomPassword.value = pwd;
      } else {
        roomPassword.value = "";
      }
      connectWebSocket(false);
      return;
    }
    showCreateModal.value = true;
  } catch (error) {
    console.error("房间预检失败", error);
    alert("连接房间失败，请稍后再试");
  }
};

const confirmCreateRoom = () => {
  if (isPrivate.value && roomPassword.value.length !== 4) {
    return alert("创建私密房间必须设置 4 位数字密码");
  }
  showCreateModal.value = false;
  connectWebSocket(true);
};

const quickJoin = (id) => {
  roomId.value = id;
  handleJoinClick();
};

const goToRuleDetail = () => {
  showRules.value = false;
  showUpdates.value = false;
  showRuleDetail.value = true;
};

const openInfoPanel = (tab) => {
  activeInfoTab.value = tab === "rules" ? "rules" : "updates";
  if (tab === "rules") {
    showRules.value = true;
    showUpdates.value = false;
  } else {
    showUpdates.value = true;
    showRules.value = false;
  }
};

const setInfoTab = (tab) => {
  activeInfoTab.value = tab === "rules" ? "rules" : "updates";
  if (tab === "rules") {
    showRules.value = true;
    showUpdates.value = false;
  } else {
    showUpdates.value = true;
    showRules.value = false;
  }
};

const closeInfoPanel = () => {
  showRules.value = false;
  showUpdates.value = false;
};

// Derive a human-readable mode label for each room. Flags may arrive either
// at the top level (legacy / current /api/rooms payload) or nested under
// `settings` (websocket room snapshots). Default to 经典 when neither flag
// is present.
const hasScrollCards = (room) =>
  Boolean(
    room?.enableScrollCards ||
      room?.scrollCards ||
      room?.settings?.enableScrollCards,
  );

const hasSkills = (room) =>
  Boolean(
    room?.enableSkills || room?.skills || room?.settings?.enableSkills,
  );

const getRoomModeLabel = (room) => {
  const scroll = hasScrollCards(room);
  const skill = hasSkills(room);
  if (scroll && skill) return "锦囊/技能";
  if (scroll) return "锦囊";
  if (skill) return "技能";
  return "经典";
};

const getRoomModeClass = (room) => {
  const scroll = hasScrollCards(room);
  const skill = hasSkills(room);
  if (scroll && skill) return "mode-both";
  if (scroll) return "mode-scroll";
  if (skill) return "mode-skill";
  return "mode-classic";
};

const getRoomCapacity = (room) =>
  room?.capacity || room?.maxPlayers || room?.playerLimit || 4;

export {
  activeInfoTab,
  closeInfoPanel,
  confirmCreateRoom,
  getRoomCapacity,
  getRoomModeClass,
  getRoomModeLabel,
  goToRuleDetail,
  handleJoinClick,
  isPrivate,
  openInfoPanel,
  playBGM,
  publicRooms,
  quickJoin,
  refreshing,
  refreshRooms,
  roomId,
  roomPassword,
  roomSettings,
  setInfoTab,
  showCreateModal,
  showRuleDetail,
  showRules,
  showUpdates,
  soundStatus,
  startPolling,
  stopPolling,
  toggleSound,
};
