import { soundStatus, toggleSound, playBGM } from "@/store/audioManager.js";
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
  showRuleDetail.value = true;
};

export {
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
