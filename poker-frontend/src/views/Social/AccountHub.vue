<template>
  <div v-if="authUser" class="account-hub">
    <button class="account-trigger" @click="handleAccountClick">
      <span class="account-trigger-name">{{
        authUser.nickname || authUser.username
      }}</span>
      <span class="account-trigger-arrow">▾</span>
      <span v-if="socialNotificationCount > 0" class="account-trigger-dot"></span>
    </button>
  </div>

  <div
    v-if="socialProfileVisible"
    class="social-overlay"
    @click="closeProfilePanel"
  >
    <div class="social-panel" @click.stop>
      <div class="social-panel-header">
        <div class="social-header-main">
          <div class="social-title">个人详情</div>
          <div class="social-subtitle">
            {{ profile.userId || authUser?.username }}
            <span v-if="socialNotificationCount > 0" class="inline-dot"></span>
            <span v-if="socialNotificationCount > 0" class="inline-hint"
              >有新的社交提醒</span
            >
          </div>
        </div>
        <div class="header-level-panel">
          <div class="header-level-main">
            <div class="header-level-title">
              Lv.{{ profile.level || 1 }}
              <span v-if="profile.maxLevel" class="level-max-tag">已满级</span>
            </div>
            <div class="header-level-desc">
              经验 {{ profile.experience || 0 }}
              <span v-if="!profile.maxLevel">
                / 下一等级 {{ profile.nextLevelExp || 0 }}
              </span>
            </div>
          </div>
          <div class="header-level-track">
            <div class="header-level-fill" :style="levelProgressWidth"></div>
          </div>
        </div>
        <div class="social-header-actions">
          <button
            v-if="isViewingSelf"
            class="social-tab-btn"
            :class="{ active: socialActiveTab === 'profile' }"
            @click="socialActiveTab = 'profile'"
          >
            战绩
          </button>
          <button
            v-if="isViewingSelf"
            class="social-tab-btn"
            :class="{ active: socialActiveTab === 'friends' }"
            @click="socialActiveTab = 'friends'"
          >
            好友
            <span
              v-if="socialFriendNotificationCount > 0"
              class="tab-dot"
            ></span>
          </button>
          <button v-if="isViewingSelf" class="social-logout-btn" @click="logout">
            退出登录
          </button>
          <button class="social-close-btn" @click="closeProfilePanel">×</button>
        </div>
      </div>

      <div v-if="socialProfileLoading" class="social-loading">正在加载资料...</div>

      <div v-else-if="socialActiveTab === 'profile'" class="social-body">
        <div v-if="!isViewingSelf" class="profile-toolbar">
          <button class="social-mini-btn" @click="openSelfProfile">
            返回我的战绩
          </button>
        </div>

        <div class="profile-summary-grid">
          <div class="summary-card">
            <span class="summary-label">注册时间</span>
            <strong>{{ formatTime(profile.registeredAt) }}</strong>
          </div>
          <div class="summary-card">
            <span class="summary-label">对局总数</span>
            <strong>{{ profile.totalGames || 0 }}</strong>
          </div>
          <div class="summary-card">
            <span class="summary-label">总胜 / 负</span>
            <strong>{{ profile.wins || 0 }} / {{ profile.losses || 0 }}</strong>
          </div>
          <div class="summary-card glow-card">
            <span class="summary-label">最近50场胜率</span>
            <strong>{{ profile.recentWinRate || 0 }}%</strong>
          </div>
        </div>

        <div class="tip-box">
          {{ profile.recentWinRateTip }}
        </div>

        <div class="records-section">
          <div class="section-title">最近50次战绩</div>
          <div v-if="records.length === 0" class="empty-state">
            还没有可统计的真人对局战绩
          </div>
          <div v-else class="record-list">
            <div v-for="(record, index) in records" :key="`${record.roomId}-${index}`" class="record-card">
              <div class="record-main">
                <div class="record-line">
                  <span class="record-mode">{{ record.modeLabel }}</span>
                  <span class="record-time">{{ formatTime(record.startedAt) }}</span>
                  <span class="record-result" :class="{ win: record.won, lose: !record.won }">
                    {{ record.won ? "胜利" : "失利" }}
                  </span>
                </div>
                <div class="record-meta">
                  <span>房间 {{ record.roomId }}</span>
                  <span>{{ record.playerCount }}人场</span>
                  <span>赢家 {{ record.winnerUserId }}</span>
                </div>
                <div class="record-players">
                  对局人ID：{{ (record.participantIds || []).join("、") }}
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-else class="social-body social-friends-body">
        <div class="friends-left">
          <div class="section-title">搜索添加好友</div>
          <div class="search-row">
            <input
              v-model="socialSearchKeyword"
              class="social-input"
              placeholder="输入账号ID搜索"
              @keydown.enter.prevent="searchSocialUsers"
            />
            <button class="social-primary-btn" @click="searchSocialUsers">
              搜索
            </button>
          </div>
          <div v-if="socialSearchResults.length > 0" class="search-result-list">
            <div
              v-for="result in socialSearchResults"
              :key="result.userId"
              class="search-result-item"
            >
              <div>
                <strong>{{ result.userId }}</strong>
                <span class="mini-status" :class="{ online: result.online }">
                  {{ result.online ? "在线" : "离线" }}
                </span>
              </div>
              <button
                v-if="!result.alreadyFriend && !result.pendingOutgoing"
                class="social-mini-btn"
                @click="sendFriendRequest(result.userId)"
              >
                添加
              </button>
              <span v-else class="result-state">{{
                result.alreadyFriend
                  ? "已是好友"
                  : result.pendingOutgoing
                    ? "已申请"
                    : result.pendingIncoming
                      ? "等你处理"
                      : ""
              }}</span>
            </div>
          </div>
          <div
            v-else-if="socialSearchEmptyMessage"
            class="empty-state small search-empty"
          >
            {{ socialSearchEmptyMessage }}
          </div>

          <div class="section-title">待处理好友申请</div>
          <div v-if="pendingRequests.length === 0" class="empty-state small">
            暂无新的好友申请
          </div>
          <div v-else class="request-list">
            <div
              v-for="request in pendingRequests"
              :key="request.id"
              class="request-item"
            >
              <div>
                <strong>{{ request.fromUserId }}</strong>
                <div class="request-time">{{ formatTime(request.createdAt) }}</div>
              </div>
              <div class="request-actions">
                <button
                  class="social-mini-btn agree"
                  @click="respondFriendRequest(request.id, true)"
                >
                  接受
                </button>
                <button
                  class="social-mini-btn reject"
                  @click="respondFriendRequest(request.id, false)"
                >
                  拒绝
                </button>
              </div>
            </div>
          </div>

          <div class="section-title">好友列表</div>
          <div v-if="friends.length === 0" class="empty-state small">
            还没有好友，先去搜索一个吧
          </div>
          <div v-else class="friend-list">
            <div
              v-for="friend in friends"
              :key="friend.userId"
              class="friend-item"
              :class="{ active: socialChatFriendId === friend.userId }"
            >
              <div class="friend-item-head">
                <div class="friend-item-main">
                  <strong>{{ friend.userId }}</strong>
                  <span class="mini-status" :class="{ online: friend.online }">
                    {{ friend.online ? "在线" : "离线" }}
                  </span>
                </div>
                <div class="friend-item-actions">
                  <button
                    class="social-mini-btn"
                    @click="openConversation(friend.userId)"
                  >
                    私聊
                  </button>
                  <button
                    class="social-mini-btn view"
                    @click="openFriendProfile(friend.userId)"
                  >
                    查看
                  </button>
                  <button
                    class="social-mini-btn danger"
                    @click="confirmRemoveFriend(friend.userId)"
                  >
                    删除
                  </button>
                </div>
              </div>
              <div class="friend-item-meta">
                <span v-if="friend.unreadCount > 0" class="friend-unread">
                  {{ friend.unreadCount }} 条未读
                </span>
              </div>
            </div>
          </div>
        </div>

        <div class="friends-right">
          <div class="section-title">私聊窗口</div>
          <div v-if="!socialChatFriendId" class="empty-state">
            从左侧选择一位好友开始聊天
          </div>
          <template v-else>
            <div class="chat-head">
              <strong>{{ socialChatFriendId }}</strong>
              <span class="mini-status" :class="{ online: activeChatFriend?.online }">
                {{ activeChatFriend?.online ? "在线" : "离线" }}
              </span>
            </div>
            <div class="chat-list">
              <div
                v-for="(message, index) in socialChatMessages"
                :key="`${message.createdAt}-${index}`"
                class="chat-bubble"
                :class="{ mine: message.mine }"
              >
                <div class="chat-content">{{ message.content }}</div>
                <div class="chat-time">{{ formatTime(message.createdAt) }}</div>
              </div>
            </div>
            <div class="chat-input-row">
              <textarea
                v-model="socialChatDraft"
                class="chat-input"
                placeholder="输入私聊内容"
              ></textarea>
              <button class="social-primary-btn" @click="sendSocialMessage">
                发送
              </button>
            </div>
          </template>
        </div>
      </div>
    </div>
  </div>

  <div v-if="socialInvitePrompt" class="invite-prompt">
    <div class="invite-title">好友房间邀请</div>
    <div class="invite-desc">
      {{ socialInvitePrompt.fromUserId }} 邀请你进入房间
      {{ socialInvitePrompt.roomId }}
    </div>
    <div class="invite-meta">
      {{
        socialInvitePrompt.privateRoom
          ? "这是一个私密房间，接受后会自动携带邀请码进入。"
          : "这是一个公开房间，接受后会立即跳转加入。"
      }}
    </div>
    <div class="invite-meta warning">
      接受邀请会离开你当前所在页面；如果你正在对局中，会按中途退出处理。
    </div>
    <div class="invite-actions">
      <button class="social-mini-btn agree" @click="respondRoomInvite(socialInvitePrompt.id, true)">
        接受
      </button>
      <button class="social-mini-btn reject" @click="respondRoomInvite(socialInvitePrompt.id, false)">
        拒绝
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed } from "vue";
import { authUser } from "@/store/gameState.js";
import { logout } from "@/store/authStore.js";
import {
  canUseSocial,
  closeProfilePanel,
  fetchSocialOverview,
  friends,
  openConversation,
  openFriendProfile,
  openProfilePanel,
  pendingRequests,
  removeFriend,
  respondFriendRequest,
  respondRoomInvite,
  searchSocialUsers,
  sendFriendRequest,
  sendSocialMessage,
  socialActiveTab,
  socialChatDraft,
  socialChatFriendId,
  socialChatMessages,
  socialFriendNotificationCount,
  socialInvitePrompt,
  socialNotificationCount,
  socialProfile,
  socialProfileLoading,
  socialProfileVisible,
  socialSearchKeyword,
  socialSearchEmptyMessage,
  socialSearchResults,
} from "./socialBindings.js";

const profile = computed(() => socialProfile.value || {});
const records = computed(() => profile.value.recentRecords || []);
const isViewingSelf = computed(() => profile.value.self !== false);
const levelProgressWidth = computed(() => ({
  width: `${Math.max(0, Math.min(100, profile.value.levelProgressPercent || 0))}%`,
}));
const activeChatFriend = computed(() =>
  friends.value.find((friend) => friend.userId === socialChatFriendId.value) || null,
);

const handleAccountClick = async () => {
  if (!canUseSocial.value) {
    alert("游客登录暂不支持个人详情与好友功能");
    return;
  }
  await openProfilePanel("profile");
  await fetchSocialOverview();
};

const openSelfProfile = async () => {
  await openProfilePanel("profile");
};

const confirmRemoveFriend = async (friendUserId) => {
  if (!window.confirm(`确定要删除好友 ${friendUserId} 吗？`)) {
    return;
  }
  await removeFriend(friendUserId);
};

const formatTime = (value) => {
  if (!value) return "--";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return String(value).replace("T", " ").slice(0, 16);
  }
  return date.toLocaleString("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
};
</script>

<style scoped>
.account-hub {
  position: fixed;
  top: 18px;
  right: 74px;
  z-index: 1200;
}

.account-trigger {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.12);
  color: #edf6ff;
  backdrop-filter: blur(10px);
  cursor: pointer;
  box-shadow: 0 8px 22px rgba(19, 31, 44, 0.18);
}

.account-trigger-name {
  max-width: 108px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  font-weight: 700;
}

.account-trigger-arrow {
  font-size: 11px;
  opacity: 0.78;
}

.account-trigger-dot,
.inline-dot,
.tab-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #ff5d64;
  box-shadow: 0 0 12px rgba(255, 93, 100, 0.88);
}

.account-trigger-dot {
  position: absolute;
  top: 4px;
  right: 5px;
}

.tab-dot {
  position: absolute;
  top: 6px;
  right: 6px;
}

.social-overlay {
  position: fixed;
  inset: 0;
  background: rgba(13, 23, 34, 0.66);
  backdrop-filter: blur(4px);
  z-index: 1300;
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 20px;
}

.social-panel {
  width: min(1040px, 100%);
  max-height: min(86vh, 900px);
  background: linear-gradient(145deg, #2a4055, #314b63);
  border-radius: 24px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  box-shadow: 0 24px 46px rgba(8, 14, 21, 0.38);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.social-panel-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
  padding: 22px 24px 18px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.social-header-main {
  min-width: 140px;
}

.social-title {
  font-size: 28px;
  font-weight: 800;
  color: #ffffff;
  text-shadow:
    0 0 16px rgba(255, 210, 106, 0.45),
    0 0 26px rgba(255, 163, 71, 0.18);
}

.social-subtitle {
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 6px;
  color: #cfe2f3;
  font-size: 13px;
}

.inline-hint {
  color: #ffdb91;
}

.social-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.header-level-panel {
  flex: 1;
  min-width: 180px;
  max-width: 520px;
  padding: 10px 14px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.08);
}

.header-level-main {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.header-level-title {
  font-size: 22px;
  font-weight: 800;
  color: #ffffff;
  white-space: nowrap;
}

.header-level-desc {
  color: #d6e3ef;
  font-size: 13px;
  text-align: right;
}

.header-level-track {
  margin-top: 10px;
  width: 100%;
  height: 12px;
  border-radius: 999px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.12);
}

.header-level-fill {
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(135deg, #ffd56a, #49d687);
  box-shadow: 0 0 16px rgba(73, 214, 135, 0.38);
}

.social-tab-btn,
.social-logout-btn,
.social-close-btn,
.social-primary-btn,
.social-mini-btn {
  border: none;
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.social-tab-btn {
  position: relative;
  padding: 10px 16px;
  background: rgba(255, 255, 255, 0.12);
  color: #dce9f6;
  font-weight: 700;
}

.social-tab-btn.active {
  background: linear-gradient(135deg, #2f9ce5, #4fbbff);
  color: #fff;
}

.social-logout-btn {
  padding: 10px 14px;
  background: rgba(231, 76, 60, 0.9);
  color: #fff;
  font-weight: 700;
}

.social-close-btn {
  width: 40px;
  height: 40px;
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
  font-size: 24px;
}

.social-loading,
.social-body {
  padding: 22px 24px 24px;
}

.social-body {
  overflow: auto;
}

.profile-summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.summary-card {
  padding: 16px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.1);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.glow-card {
  background: linear-gradient(135deg, rgba(52, 152, 219, 0.38), rgba(241, 196, 15, 0.28));
}

.summary-label,
.request-time,
.record-meta,
.record-players,
.invite-meta,
.friend-item-meta {
  color: #cddcea;
  font-size: 13px;
}

.tip-box {
  margin-top: 16px;
  padding: 14px 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.08);
  color: #f7d58e;
  line-height: 1.6;
}

.level-max-tag {
  margin-left: 10px;
  padding: 4px 10px;
  border-radius: 999px;
  background: rgba(255, 214, 102, 0.18);
  color: #ffd666;
  font-size: 12px;
  vertical-align: middle;
}

.profile-toolbar {
  margin-bottom: 14px;
  display: flex;
  justify-content: flex-end;
}

.records-section,
.section-title {
  margin-top: 18px;
}

.section-title {
  font-size: 18px;
  font-weight: 700;
  color: #ffffff;
}

.record-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 12px;
}

.record-card,
.request-item,
.search-result-item,
.friend-item,
.chat-list,
.invite-prompt {
  background: rgba(15, 28, 39, 0.26);
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.record-card {
  padding: 14px 16px;
  border-radius: 18px;
}

.record-line,
.record-meta,
.request-item,
.search-result-item,
.friend-item-head,
.chat-head,
.search-row,
.invite-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.record-main {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.record-mode {
  padding: 5px 10px;
  border-radius: 999px;
  background: rgba(68, 181, 255, 0.22);
  color: #bce6ff;
  font-size: 12px;
}

.record-result {
  font-weight: 700;
}

.record-result.win {
  color: #68f7ac;
}

.record-result.lose {
  color: #ff8f8f;
}

.social-friends-body {
  display: grid;
  grid-template-columns: 360px minmax(0, 1fr);
  gap: 18px;
}

.friends-left,
.friends-right {
  min-width: 0;
}

.search-row {
  margin-top: 12px;
}

.social-input,
.chat-input {
  width: 100%;
  border: none;
  outline: none;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.9);
  color: #1c2c3d;
  box-sizing: border-box;
}

.social-input {
  padding: 12px 14px;
  font-size: 14px;
}

.chat-input-row {
  display: grid;
  grid-template-columns: 1fr 108px;
  gap: 10px;
  margin-top: 12px;
}

.chat-input {
  min-height: 76px;
  padding: 12px 14px;
  resize: vertical;
}

.social-primary-btn {
  padding: 12px 18px;
  background: linear-gradient(135deg, #2fb96f, #37d17b);
  color: #fff;
  font-weight: 700;
}

.search-result-list,
.request-list,
.friend-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 12px;
}

.search-result-item,
.request-item {
  padding: 12px 14px;
  border-radius: 16px;
}

.result-state {
  color: #ffd98b;
  font-size: 13px;
}

.friend-item {
  padding: 12px 14px;
  border-radius: 18px;
  color: #fff;
}

.friend-item.active {
  border-color: rgba(103, 196, 255, 0.7);
  background: rgba(40, 124, 183, 0.26);
}

.friend-item-main,
.friend-item-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.social-mini-btn.view {
  background: rgba(241, 196, 15, 0.95);
  color: #213043;
}

.friend-unread {
  color: #ffd98b;
}

.mini-status {
  font-size: 12px;
  color: #aabccd;
}

.mini-status.online {
  color: #77f3b2;
}

.social-mini-btn {
  padding: 8px 12px;
  font-weight: 700;
  color: #fff;
  background: rgba(52, 152, 219, 0.92);
}

.social-mini-btn.agree {
  background: rgba(39, 174, 96, 0.95);
}

.social-mini-btn.reject {
  background: rgba(192, 57, 43, 0.95);
}

.social-mini-btn.danger {
  background: rgba(192, 57, 43, 0.95);
}

.request-actions {
  display: flex;
  gap: 8px;
}

.chat-head {
  margin-top: 12px;
  padding: 12px 14px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.08);
}

.chat-list {
  margin-top: 12px;
  padding: 14px;
  border-radius: 18px;
  min-height: 340px;
  max-height: 440px;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.chat-bubble {
  max-width: 82%;
  padding: 10px 12px;
  border-radius: 16px 16px 16px 4px;
  background: rgba(255, 255, 255, 0.12);
}

.chat-bubble.mine {
  align-self: flex-end;
  border-radius: 16px 16px 4px 16px;
  background: rgba(52, 152, 219, 0.28);
}

.chat-content {
  line-height: 1.55;
  white-space: pre-wrap;
}

.chat-time {
  margin-top: 6px;
  font-size: 11px;
  color: #bbcad8;
}

.empty-state {
  margin-top: 12px;
  padding: 20px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.08);
  color: #c8d8e7;
  text-align: center;
}

.empty-state.small {
  padding: 14px;
}

.search-empty {
  margin-bottom: 4px;
}

.invite-prompt {
  position: fixed;
  right: 24px;
  bottom: 24px;
  width: min(360px, calc(100vw - 24px));
  padding: 18px;
  border-radius: 20px;
  z-index: 1250;
  box-shadow: 0 18px 36px rgba(9, 16, 24, 0.35);
  color: #fff;
}

.invite-title {
  font-size: 20px;
  font-weight: 800;
  color: #ffd57e;
}

.invite-desc {
  margin-top: 8px;
  font-size: 15px;
  line-height: 1.6;
}

.invite-meta {
  margin-top: 8px;
  line-height: 1.6;
}

.invite-meta.warning {
  color: #ffd1c3;
}

.invite-actions {
  margin-top: 14px;
}

@media screen and (max-width: 900px) {
  .social-friends-body {
    grid-template-columns: 1fr;
  }

  .profile-summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media screen and (max-width: 768px) {
  .account-hub {
    top: 10px;
    right: 48px;
  }

  .account-trigger {
    padding: 6px 10px;
  }

  .account-trigger-name {
    max-width: 84px;
    font-size: 11px;
  }

  .social-overlay {
    padding: 10px;
  }

  .social-panel {
    max-height: 92vh;
    border-radius: 18px;
  }

  .social-panel-header {
    padding: 16px;
    flex-direction: column;
    align-items: stretch;
  }

  .social-title {
    font-size: 24px;
  }

  .social-header-actions {
    flex-wrap: wrap;
  }

  .header-level-panel {
    max-width: none;
  }

  .header-level-main {
    flex-direction: column;
    align-items: flex-start;
  }

  .header-level-desc {
    text-align: left;
  }

  .social-loading,
  .social-body {
    padding: 16px;
  }

  .profile-summary-grid {
    grid-template-columns: 1fr;
  }

  .friend-item-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .friend-item-actions {
    width: 100%;
  }

  .chat-input-row {
    grid-template-columns: 1fr;
  }

  .invite-prompt {
    left: 10px;
    right: 10px;
    bottom: 10px;
    width: auto;
  }
}
</style>
