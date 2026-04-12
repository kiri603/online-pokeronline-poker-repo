import { computed, ref } from "vue";
import {
  authUser,
  isAuthenticated,
  isConnected,
  isPrivate,
  roomId,
  roomPassword,
  ws,
} from "./gameState.js";
import { apiFetch } from "./serverConfig.js";

const createEmptyOverview = () => ({
  notificationCount: 0,
  unreadMessageCount: 0,
  pendingFriendRequestCount: 0,
  pendingInviteCount: 0,
  friends: [],
  pendingFriendRequests: [],
  pendingInvites: [],
});

export const socialOverview = ref(createEmptyOverview());
export const socialProfile = ref(null);
export const socialProfileVisible = ref(false);
export const socialProfileLoading = ref(false);
export const socialActiveTab = ref("profile");
export const socialProfileUserId = ref("");
export const socialSearchKeyword = ref("");
export const socialSearchResults = ref([]);
export const socialSearchLoading = ref(false);
export const socialSearchEmptyMessage = ref("");
export const socialDrawerOpen = ref(false);
export const socialChatFriendId = ref("");
export const socialChatMessages = ref([]);
export const socialChatDraft = ref("");
export const socialInvitePrompt = ref(null);

export const socialNotificationCount = computed(
  () => socialOverview.value.notificationCount || 0,
);
export const socialFriendNotificationCount = computed(
  () =>
    (socialOverview.value.unreadMessageCount || 0) +
    (socialOverview.value.pendingFriendRequestCount || 0),
);
export const socialFriends = computed(
  () => socialOverview.value.friends || [],
);
export const socialFriendIds = computed(
  () => new Set(socialFriends.value.map((friend) => friend.userId)),
);
export const socialIncomingFriendRequestIds = computed(
  () =>
    new Set(
      (socialOverview.value.pendingFriendRequests || []).map(
        (request) => request.fromUserId,
      ),
    ),
);
export const onlineFriends = computed(() =>
  socialFriends.value.filter((friend) => friend.online),
);
export const canUseSocial = computed(
  () =>
    isAuthenticated.value &&
    authUser.value &&
    !authUser.value.guest,
);

const requestJson = async (path, options = {}) => {
  const response = await apiFetch(path, options);
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(data.message || "操作失败");
  }
  return data;
};

export const resetSocialState = () => {
  socialOverview.value = createEmptyOverview();
  socialProfile.value = null;
  socialProfileVisible.value = false;
  socialProfileLoading.value = false;
  socialActiveTab.value = "profile";
  socialProfileUserId.value = "";
  socialSearchKeyword.value = "";
  socialSearchResults.value = [];
  socialSearchLoading.value = false;
  socialSearchEmptyMessage.value = "";
  socialDrawerOpen.value = false;
  socialChatFriendId.value = "";
  socialChatMessages.value = [];
  socialChatDraft.value = "";
  socialInvitePrompt.value = null;
};

export const fetchSocialOverview = async () => {
  if (!canUseSocial.value) {
    resetSocialState();
    return;
  }
  try {
    const data = await requestJson("/api/social/overview", { method: "GET" });
    socialOverview.value = {
      ...createEmptyOverview(),
      ...data,
    };

    const pendingInvites = socialOverview.value.pendingInvites || [];
    if (pendingInvites.length === 0) {
      socialInvitePrompt.value = null;
    } else if (
      !socialInvitePrompt.value ||
      !pendingInvites.some((invite) => invite.id === socialInvitePrompt.value.id)
    ) {
      socialInvitePrompt.value = pendingInvites[0];
    }

    if (
      socialProfileVisible.value &&
      socialActiveTab.value === "friends" &&
      socialChatFriendId.value
    ) {
      await openConversation(socialChatFriendId.value, {
        silent: true,
        preserveTab: true,
      });
    }
  } catch (error) {
    console.error("社交概览加载失败", error);
  }
};

export const fetchSocialProfile = async (targetUserId = "") => {
  if (!canUseSocial.value) {
    return;
  }
  socialProfileLoading.value = true;
  try {
    const normalizedTarget =
      targetUserId || socialProfileUserId.value || authUser.value?.username || "";
    socialProfileUserId.value = normalizedTarget;
    const path =
      normalizedTarget && normalizedTarget !== authUser.value?.username
        ? `/api/social/profile/${encodeURIComponent(normalizedTarget)}`
        : "/api/social/profile";
    socialProfile.value = await requestJson(path, {
      method: "GET",
    });
  } catch (error) {
    alert(error.message || "个人资料加载失败");
  } finally {
    socialProfileLoading.value = false;
  }
};

export const openProfilePanel = async (tab = "profile") => {
  if (!canUseSocial.value) {
    alert("游客登录暂不支持个人详情与好友功能");
    return;
  }
  socialProfileVisible.value = true;
  socialActiveTab.value = tab;
  socialProfileUserId.value = authUser.value?.username || "";
  try {
    await Promise.all([fetchSocialOverview(), fetchSocialProfile(authUser.value?.username || "")]);
  } catch (error) {
    alert(error.message || "个人详情加载失败");
  }
};

export const openFriendProfile = async (friendUserId) => {
  if (!canUseSocial.value) {
    return;
  }
  socialProfileVisible.value = true;
  socialActiveTab.value = "profile";
  socialProfileUserId.value = friendUserId;
  try {
    await Promise.all([fetchSocialOverview(), fetchSocialProfile(friendUserId)]);
  } catch (error) {
    alert(error.message || "好友资料加载失败");
  }
};

export const closeProfilePanel = () => {
  socialProfileVisible.value = false;
};

export const searchSocialUsers = async () => {
  if (!canUseSocial.value) {
    return;
  }
  const keyword = socialSearchKeyword.value.trim();
  if (keyword.length < 2) {
    socialSearchResults.value = [];
    socialSearchEmptyMessage.value = "";
    return;
  }
  socialSearchLoading.value = true;
  socialSearchResults.value = [];
  try {
    const results = await requestJson(
      `/api/social/search?keyword=${encodeURIComponent(keyword)}`,
      { method: "GET" },
    );
    socialSearchResults.value = results;
    socialSearchEmptyMessage.value =
      results.length === 0 ? `没有${keyword}这位玩家哦` : "";
    if (results.length === 0) {
      alert(socialSearchEmptyMessage.value);
    }
  } catch (error) {
    alert(error.message || "搜索失败");
  } finally {
    socialSearchLoading.value = false;
  }
};

export const sendFriendRequest = async (targetUserId) => {
  try {
    await requestJson("/api/social/friend-requests", {
      method: "POST",
      body: JSON.stringify({ targetUserId }),
    });
    await Promise.all([fetchSocialOverview(), fetchSocialProfile(), searchSocialUsers()]);
    alert("好友申请已发送");
  } catch (error) {
    alert(error.message || "发送好友申请失败");
  }
};

export const respondFriendRequest = async (requestId, accept) => {
  try {
    await requestJson(`/api/social/friend-requests/${requestId}/respond`, {
      method: "POST",
      body: JSON.stringify({ accept }),
    });
    await Promise.all([fetchSocialOverview(), fetchSocialProfile()]);
  } catch (error) {
    alert(error.message || "处理好友申请失败");
  }
};

export const removeFriend = async (targetUserId) => {
  try {
    await requestJson("/api/social/friends/remove", {
      method: "POST",
      body: JSON.stringify({ targetUserId }),
    });
    if (socialChatFriendId.value === targetUserId) {
      socialChatFriendId.value = "";
      socialChatMessages.value = [];
      socialChatDraft.value = "";
    }
    if (socialProfileUserId.value === targetUserId) {
      socialProfileUserId.value = authUser.value?.username || "";
    }
    await Promise.all([fetchSocialOverview(), fetchSocialProfile()]);
  } catch (error) {
    alert(error.message || "删除好友失败");
  }
};

export const openConversation = async (
  friendUserId,
  { silent = false, preserveTab = false } = {},
) => {
  try {
    socialChatFriendId.value = friendUserId;
    if (!preserveTab) {
      socialActiveTab.value = "friends";
    }
    socialChatMessages.value = await requestJson(
      `/api/social/messages/${encodeURIComponent(friendUserId)}`,
      { method: "GET" },
    );
    if (!silent) {
      await fetchSocialOverview();
    }
  } catch (error) {
    if (!silent) {
      alert(error.message || "加载聊天记录失败");
    }
  }
};

export const sendSocialMessage = async () => {
  const content = socialChatDraft.value.trim();
  if (!socialChatFriendId.value || !content) {
    return;
  }
  try {
    await requestJson("/api/social/messages", {
      method: "POST",
      body: JSON.stringify({
        toUserId: socialChatFriendId.value,
        content,
      }),
    });
    socialChatDraft.value = "";
    await openConversation(socialChatFriendId.value, { silent: true });
    await fetchSocialOverview();
  } catch (error) {
    alert(error.message || "发送私聊失败");
  }
};

export const toggleWaitingFriendDrawer = async () => {
  if (!canUseSocial.value) {
    alert("游客账号暂不支持好友邀请");
    return;
  }
  socialDrawerOpen.value = !socialDrawerOpen.value;
  if (socialDrawerOpen.value) {
    await fetchSocialOverview();
  }
};

export const inviteFriendToRoom = async (targetUserId) => {
  if (!roomId.value) {
    alert("当前没有可邀请的房间");
    return;
  }
  try {
    await requestJson("/api/social/invites", {
      method: "POST",
      body: JSON.stringify({
        targetUserId,
        roomId: roomId.value,
      }),
    });
    await fetchSocialOverview();
    alert(`已向 ${targetUserId} 发出邀请`);
  } catch (error) {
    alert(error.message || "邀请发送失败");
  }
};

export const respondRoomInvite = async (inviteId, accept) => {
  try {
    const result = await requestJson(`/api/social/invites/${inviteId}/respond`, {
      method: "POST",
      body: JSON.stringify({ accept }),
    });
    socialInvitePrompt.value = null;
    await fetchSocialOverview();
    if (accept) {
      const gameSocketModule = await import("./gameSocket.js");
      roomId.value = result.roomId || "";
      roomPassword.value = result.roomPassword || "";
      isPrivate.value = !!result.privateRoom;
      if (ws.value) {
        const currentSocket = ws.value;
        ws.value = null;
        currentSocket.close();
        window.setTimeout(() => {
          gameSocketModule.connectWebSocket(false);
        }, isConnected.value ? 280 : 0);
      } else {
        gameSocketModule.connectWebSocket(false);
      }
    }
  } catch (error) {
    alert(error.message || "处理邀请失败");
  }
};
