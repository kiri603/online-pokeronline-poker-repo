// src/store/gameSocket.js
import * as state from "./gameState.js";
import {
  playAudio,
  playCardAudio,
  playBGM,
  stopCountdownAudio,
} from "./audioManager.js";
import { resetAuthState } from "./authStore.js";
import { getTabAuthToken, getWsBaseUrl } from "./serverConfig.js";

// 导出全局 WebSocket 实例
export const ws = state.ws;

// ==========================================
// 1. 局部定时器变量 (不引起页面刷新的纯逻辑变量)
// ==========================================
let errorTimeout = null;
let actionTextTimeouts = {};
let emojiTimeouts = {};
let globalTimer = null;
let isTimeoutTriggered = false;
let lastTickSecond = -1;
// 苦肉音效延播句柄：若紧随其后收到 SKILL_AWAKEN，则取消普通语音，避免两段语音撞车
let pendingKurouSfxTimeout = null;

// ==========================================
// 2. 基础通信与动作封装
// ==========================================

// 核心：发送消息方法
export const sendMsg = (type, data) => {
  if (ws.value && ws.value.readyState === WebSocket.OPEN) {
    ws.value.send(
      JSON.stringify({
        type,
        roomId: state.roomId.value,
        userId: state.userId.value,
        data,
      }),
    );
  }
};

// 全局错误提示弹窗
export const showError = (msg) => {
  state.errorMessage.value = msg;
  if (errorTimeout) clearTimeout(errorTimeout);
  errorTimeout = setTimeout(() => {
    state.errorMessage.value = "";
  }, 3000);
};

// 浮动动作文字特效 (如：制衡、要不起)
export const showActionText = (targetUserId, text, type = "skill") => {
  // 使用 Date.now() 作为 key，确保连续触发相同文字也能重置动画
  state.activeActionTexts.value[targetUserId] = { text, type, id: Date.now() };
  if (actionTextTimeouts[targetUserId])
    clearTimeout(actionTextTimeouts[targetUserId]);

  actionTextTimeouts[targetUserId] = setTimeout(() => {
    delete state.activeActionTexts.value[targetUserId];
  }, 1500);
};

// 快捷动作：要不起
export const passTurn = () => sendMsg("PASS", null);

// 倒计时结束：自动托管出牌
export const handleTimeout = () => {
  if (state.tableCards.value.length === 0) {
    // 自由出牌回合，强行帮忙出一张最小的普通牌
    // ====== 【核心修复】：必须使用 state.handCards.value，不能用 sortedHandCards ======
    if (state.handCards.value.length > 0) {
      let cardToPlay = state.handCards.value.find((c) => c.suit !== "SCROLL");
      if (!cardToPlay) cardToPlay = state.handCards.value[0];

      sendMsg("PLAY_CARD", [
        {
          suit: cardToPlay.suit,
          rank: cardToPlay.rank,
          weight: cardToPlay.weight,
        },
      ]);
    }
  } else {
    // 否则直接要不起
    passTurn();
  }
};

// ==========================================
// 3. 核心 WebSocket 连接与分发器
// ==========================================
export const connectWebSocket = (isCreating = false) => {
  if (!state.roomId.value || !state.userId.value)
    return alert("请输入完整信息");
  if (state.isPrivate.value && state.roomPassword.value.length !== 4) {
    return alert("创建私密房间必须设置 4 位密码！");
  }

  const tabToken = getTabAuthToken();
  const wsUrl = tabToken
    ? `${getWsBaseUrl()}/ws/game?authToken=${encodeURIComponent(tabToken)}`
    : `${getWsBaseUrl()}/ws/game`;
  ws.value = new WebSocket(wsUrl);
  // ==============================================
  // 【连接成功钩子】
  ws.value.onopen = () => {
    state.isConnected.value = true;
    sendMsg("JOIN_ROOM", {
      isPrivate: state.isPrivate.value,
      password: state.roomPassword.value,
      isCreating: isCreating, // 告诉后端我是来创建的还是加入的
      settings: state.roomSettings.value, // 创建时直接把规则带给后端
    });

    // 建立连接后，启动全局绝对时间倒计时驱动器！
    if (globalTimer) clearInterval(globalTimer);
    globalTimer = setInterval(() => {
      if (state.gameStarted.value && !state.winner.value) {
        const nowServerTime = Date.now() - state.serverTimeOffset.value;

        // 模式A：正在进行锦囊牌结算
        if (
          state.currentAoeType.value &&
          state.amIPendingAoe.value &&
          state.aoeStartTime.value
        ) {
          const elapsedSeconds = Math.floor(
            (nowServerTime - state.aoeStartTime.value) / 1000,
          );
          let remain = 10 - elapsedSeconds;
          if (remain < 0) remain = 0;
          state.countdown.value = remain;
          if (remain <= 5 && remain > 0 && lastTickSecond !== remain) {
            playAudio("countdown"); // 播放倒计时音效
            lastTickSecond = remain;
          } else if (remain > 5 || remain === 0) {
            if (lastTickSecond !== -1) {
              stopCountdownAudio();
            }
            lastTickSecond = -1; // 重置标记
          }
          if (remain === 0 && !isTimeoutTriggered) {
            isTimeoutTriggered = true;
            if (state.currentAoeType.value === "GUANXING") {
              // ====== 【核心修复】：倒计时自动提交时也必须清洗数据 ======
              const cleanCards = state.guanxingCards.value
                .slice(0, 2)
                .map((c) => ({
                  suit: c.suit,
                  rank: c.rank,
                  weight: c.weight,
                }));
              sendMsg("GUANXING_SELECT", cleanCards);
              state.showGuanxingModal.value = false;
            } else if (state.currentAoeType.value === "WGFD") {
              // ====== 【新增：五谷丰登超时随机自动拿第一张】 ======
              const cleanCard = {
                suit: state.wgfdCards.value[0].suit,
                rank: state.wgfdCards.value[0].rank,
                weight: state.wgfdCards.value[0].weight,
              };
              sendMsg("WGFD_SELECT", cleanCard);
              state.showWgfdModal.value = false;
            } else if (state.currentAoeType.value === "GUSHOU_DISCARD") {
              const required = Math.min(2, state.handCards.value.length);
              const cleanCards = state.handCards.value
                .slice(0, required)
                .map((c) => ({ suit: c.suit, rank: c.rank, weight: c.weight }));
              sendMsg("GUSHOU_DISCARD", cleanCards);
            } else if (state.currentAoeType.value === "KUROU_AWAKEN_DISCARD") {
              // 【苦肉·觉醒】：超时自动跳过额外弃置
              sendMsg("KUROU_AWAKEN_DISCARD", null);
            } else if (state.currentAoeType.value === "GUIXIN_DECISION") {
              sendMsg("GUIXIN_DECISION", { accept: false });
            } else {
              sendMsg("RESPOND_AOE", null);
            } // 超时自动要不起
          }
          if (remain > 0) isTimeoutTriggered = false;
        }
        // 模式B：正常的出牌回合
        else if (
          !state.currentAoeType.value &&
          state.currentTurn.value &&
          state.currentTurnStartTime.value
        ) {
          const elapsedSeconds = Math.floor(
            (nowServerTime - state.currentTurnStartTime.value) / 1000,
          );
          let maxTime = 20;
          if (
            state.jdsrTarget.value &&
            state.jdsrTarget.value === state.currentTurn.value
          ) {
            maxTime = 10;
          }
          let remain = maxTime - elapsedSeconds;
          if (remain < 0) remain = 0;
          state.countdown.value = remain;
          if (remain <= 5 && remain > 0 && lastTickSecond !== remain) {
            playAudio("countdown"); // 全场播放紧迫感音效
            lastTickSecond = remain;
          } else if (remain > 5 || remain === 0) {
            if (lastTickSecond !== -1) {
              stopCountdownAudio();
            }
            lastTickSecond = -1; // 重置标记
          }

          if (
            remain === 0 &&
            state.currentTurn.value === state.userId.value &&
            !isTimeoutTriggered
          ) {
            isTimeoutTriggered = true;
            handleTimeout();
          }
          if (remain > 0) isTimeoutTriggered = false;
        }
      }
    }, 500);
  };

  // 【消息接收拦截器】
  ws.value.onmessage = async (event) => {
    const res = JSON.parse(event.data);

    switch (res.event) {
      case "SYNC_STATE":
        state.currentTurn.value = res.currentTurn;
        state.currentAoeType.value = res.currentAoeType;
        if (res.tableCards) state.tableCards.value = res.tableCards;
        if (res.lastPlayPlayer) state.lastPlayPlayer.value = res.lastPlayPlayer;

        state.pendingAoePlayers.value = res.pendingAoePlayers || [];
        state.aoeInitiator.value = res.aoeInitiator || "";
        state.luanjianInitiator.value = res.luanjianInitiator || "";
        if (res.aoeStartTime)
          state.aoeStartTime.value = Number(res.aoeStartTime);
        if (res.currentAoeType === "WGFD") {
          if (res.settings && res.settings.wgfdCards) {
            // 洗掉残留的选中状态
            state.wgfdCards.value = res.settings.wgfdCards.map((c) => ({
              ...c,
              selected: false,
            }));
          }
          state.showWgfdModal.value = true; // 所有人都强制看，保证信息公开
        } else {
          state.showWgfdModal.value = false;
        }
        // ====== 【核心修复】：防止观星弹窗幽灵卡死 ======
        // 如果房间当前根本不是观星状态，或者虽然是观星状态但操作人不是我，强制关闭选牌弹窗！
        if (res.currentAoeType !== "GUANXING") {
          state.showGuanxingModal.value = false;
        } else if (
          !state.pendingAoePlayers.value.includes(state.userId.value)
        ) {
          state.showGuanxingModal.value = false;
        }
        // ============================================

        state.ownerId.value = res.ownerId;
        if (res.serverTime)
          state.serverTimeOffset.value = Date.now() - Number(res.serverTime);
        if (res.currentTurnStartTime)
          state.currentTurnStartTime.value = Number(res.currentTurnStartTime);

        state.otherPlayers.value = res.players.filter(
          (p) => p.userId !== state.userId.value,
        );
        state.spectators.value = res.spectators || [];

        // 解析我自己的准备状态
        const me = res.players.find((p) => p.userId === state.userId.value);
        if (me) {
          state.isReady.value = me.isReady;
          state.myStatus.value = me.status;
          if (me.skill) state.mySkill.value = me.skill;
          // 【苦肉】：本地 me 的累计计数 / 本回合次数 / 觉醒态同步
          if (typeof me.kurouUseCount === "number") {
            state.kurouUseCount.value = me.kurouUseCount;
          }
          if (typeof me.kurouUsesThisTurn === "number") {
            state.kurouUsesThisTurn.value = me.kurouUsesThisTurn;
          }
          if (typeof me.kurouAwakened === "boolean") {
            state.kurouAwakened.value = me.kurouAwakened;
          }
          if (typeof me.guixinDisabled === "boolean") {
            state.guixinDisabled.value = me.guixinDisabled;
          }
        }
        if (res.isStarted !== undefined) {
          // 如果后端标记游戏已结束，且产生了胜者，但我自己还没返回大厅（状态不是 WAITING）
          // 则在本地强行维持 gameStarted = true，让我能继续看结算面板，不会被别人强拽出去！
          if (
            !res.isStarted &&
            state.winner.value !== "" &&
            me &&
            me.status !== "WAITING"
          ) {
            state.gameStarted.value = true;
          } else {
            state.gameStarted.value = res.isStarted;
          }
        }
        // 解析房间高级设置 (丢弃合并语法，彻底防止幽灵重置Bug)
        if (res.settings) {
          state.roomSettings.value.enableWildcard =
            res.settings.enableWildcard === true;
          state.roomSettings.value.enableScrollCards =
            res.settings.enableScrollCards === true;
          state.roomSettings.value.enableSkills =
            res.settings.enableSkills === true;

          // ====== 【新增：提取借刀杀人状态】 ======
          state.jdsrTarget.value = res.settings.jdsr_target || null;
          state.jdsrInitiator.value = res.settings.jdsr_initiator || null;
          state.guixinPendingOwner.value =
            res.settings.guixin_pending_owner || "";
          state.guixinPendingPasser.value =
            res.settings.guixin_pending_passer || "";
        } else {
          state.jdsrTarget.value = null;
          state.jdsrInitiator.value = null;
          state.guixinPendingOwner.value = "";
          state.guixinPendingPasser.value = "";
        }
        break;

      case "AOE_ANIMATION":
        state.aoeAnimCards.value.push({
          id: Date.now() + Math.random(),
          userId: res.userId,
          card: res.card,
        });
        setTimeout(() => {
          state.aoeAnimCards.value.shift();
        }, 1000); // 1秒后清除DOM
        break;

      // ====== 【铁骑】：展示判定牌 + 播放语音；成功则 500ms 后叠加马嘶声 + 给被压制玩家飘"压制" ======
      case "TIEQI_JUDGE": {
        const entry = {
          id: Date.now() + Math.random(),
          userId: res.userId,
          card: res.card,
          success: res.success === true,
          maxRedWeight: res.maxRedWeight,
        };
        state.tieqiJudgeCards.value.push(entry);
        showActionText(res.userId, "铁骑", "skill");
        playAudio("action_tieqi");
        if (entry.success) {
          setTimeout(() => playAudio("skill_tieqi_horse"), 500);
          const suppressed = Array.isArray(res.suppressed) ? res.suppressed : [];
          suppressed.forEach((uid, i) => {
            setTimeout(() => showActionText(uid, "压制", "skill"), 300 + i * 120);
          });
        }
        const ttl = entry.success ? 4000 : 3000;
        setTimeout(() => {
          const idx = state.tieqiJudgeCards.value.findIndex(
            (x) => x.id === entry.id,
          );
          if (idx >= 0) state.tieqiJudgeCards.value.splice(idx, 1);
        }, ttl);
        break;
      }

      case "KICKED":
        if (res.targetId === state.userId.value) {
          alert("你已被房主踢出房间！");
          if (ws.value) ws.value.close();
          state.isConnected.value = false;
        }
        break;
      case "START_SKILL_SELECTION":
        state.gameStarted.value = true;
        state.showSkillSelection.value = true;

        // ====== 【新增：20秒倒计时与进度条驱动】 ======
        state.skillCountdown.value = 20;
        if (state.skillTimer.value) clearInterval(state.skillTimer.value);

        state.skillTimer.value = setInterval(() => {
          state.skillCountdown.value--;

          // 最后 5 秒，并且如果玩家还没选将（弹窗还在），播放倒数音效
          if (
            state.showSkillSelection.value &&
            state.skillCountdown.value <= 5 &&
            state.skillCountdown.value > 0
          ) {
            playAudio("countdown");
          }

          // 时间到 0
          if (state.skillCountdown.value <= 0) {
            clearInterval(state.skillTimer.value);
            stopCountdownAudio();

            // 如果时间到了玩家还没选，自动帮他选“制衡”
            if (state.showSkillSelection.value) {
              sendMsg("SELECT_SKILL", "ZHIHENG");
              state.mySkill.value = "ZHIHENG";
              state.showSkillSelection.value = false;
            }
          }
        }, 1000);
        // ==========================================
        break;
      case "GUANXING_SHOW":
        state.guanxingCards.value = res.cards.map((c) => ({
          ...c,
          selected: false,
        }));
        state.showGuanxingModal.value = true;
        break;
      case "SYNC_HAND":
        state.handCards.value = res.cards.map((c) => ({
          ...c,
          selected: false,
        }));
        break;

      case "GAME_STARTED":
        if (state.skillTimer.value) clearInterval(state.skillTimer.value);
        stopCountdownAudio();
        state.showSkillSelection.value = false;

        state.gameStarted.value = true;
        state.winner.value = "";
        state.tableCards.value = [];
        state.lastPlayPlayer.value = "";
        // 【苦肉】新开局时重置本地 me 的累计计数/本回合次数/觉醒态
        state.kurouUseCount.value = 0;
        state.kurouUsesThisTurn.value = 0;
        state.kurouAwakened.value = false;
        state.guixinDisabled.value = false;
        state.guixinPendingOwner.value = "";
        state.guixinPendingPasser.value = "";
        playAudio("shuffle");
        state.isShuffling.value = true;
        setTimeout(() => {
          state.isShuffling.value = false;
        }, 1200);
        playBGM("Normal");
        break;
      case "DECK_SHUFFLED":
        playAudio("shuffle");
        state.isShuffling.value = true;
        setTimeout(() => {
          state.isShuffling.value = false;
        }, 1200);
        break;
      case "CARDS_PLAYED":
        if (
          res.cards &&
          res.cards.length === 1 &&
          res.cards[0].suit === "SCROLL" &&
          res.cards[0].rank === "JDSR"
        ) {
          showActionText(res.userId, "借刀杀人", "skill"); // 弹出橙黄发光字体
          playAudio("skill_jdsr"); // 播放语音
          break; // 拦截完毕，不进入桌面
        }

        state.tableCards.value = res.cards;
        state.lastPlayPlayer.value = res.userId;
        playCardAudio(res.cards);
        break;

      case "ROUND_RESET":
        state.tableCards.value = [];
        state.lastPlayPlayer.value = "";
        break;

      case "GAME_OVER":
        stopCountdownAudio();
        state.winner.value = res.winner;
        state.winningCards.value = res.winningCards || [];
        if (res.winner === state.userId.value) {
          playBGM("Win", false);
        } else {
          playBGM("Lose", false);
        }
        break;

      case "ROOM_RESET":
        state.gameStarted.value = false;
        state.winner.value = "";
        state.handCards.value = [];
        state.tableCards.value = [];
        state.lastPlayPlayer.value = "";
        state.currentTurn.value = "";
        state.guixinDisabled.value = false;
        state.guixinPendingOwner.value = "";
        state.guixinPendingPasser.value = "";
        playBGM("Welcome");
        break;

      case "GAME_ABORTED":
        alert(res.msg);
        state.gameStarted.value = false;
        state.winner.value = "";
        state.handCards.value = [];
        state.tableCards.value = [];
        break;

      case "AOE_PLAYED":
        showActionText(res.userId, res.aoeName, "skill");
        if (res.aoeName === "南蛮入侵") playAudio("skill_nmrq");
        else if (res.aoeName === "万箭齐发") playAudio("skill_wjqf");
        else if (res.aoeName === "五谷丰登") playAudio("skill_wgfd");
        else if (res.aoeName === "借刀杀人" || res.aoeName === "JDSR")
          playAudio("skill_jdsr");
        break;

      case "GUIXIN_RESOLVED":
        if (res.accepted) showActionText(res.userId, "归心", "skill");
        break;

      case "ERROR":
        if (res.msg === "REQUIRE_PASSWORD" || res.msg === "密码错误") {
          showError(res.msg);
          state.roomPassword.value = ""; // 【核心】：清空携带的错误密码
          if (ws.value) ws.value.close(); // 断开失败的连接

          setTimeout(() => {
            const pwd = prompt(
              "密码错误！该房间为私密房间，请重新输入 4 位密码：",
            );
            if (pwd !== null && pwd.trim() !== "") {
              state.roomPassword.value = pwd;
              connectWebSocket(false); // 带着新密码重新尝试加入
            }
          }, 500);
        } else if (res.msg === "该房间已被他人创建") {
          alert(res.msg);
          state.showCreateModal.value = false; // 关闭创建面板，退回大厅
          if (ws.value) ws.value.close();
        } else {
          // ... 原有的其他报错处理逻辑保持不变 ...
          showError(res.msg);
          state.handCards.value.forEach((c) => (c.selected = false));

          // 如果是倒计时触发的自动出牌报错，为了防止卡死在 0s，1.5秒后强制要不起
          if (state.countdown.value === 0 && isTimeoutTriggered) {
            setTimeout(() => {
              passTurn();
            }, 1500);
          }
        }
        break;
      case "CARD_WARNING":
        // 1. 播放对于的音效 (last1.mp3 或 last2.mp3)
        playAudio(`last_${res.count}`);

        // 2. 锁定发光目标
        state.warningUserId.value = res.userId;

        // 3. 1秒后自动关闭发光特效
        setTimeout(() => {
          if (state.warningUserId.value === res.userId) {
            state.warningUserId.value = "";
          }
        }, 1000);
        break;
      case "EMOJI_RECEIVED":
        state.activeEmojis.value[res.userId] = res.emoji;
        if (emojiTimeouts[res.userId]) clearTimeout(emojiTimeouts[res.userId]);
        // 气泡停留 3 秒后自动消失
        emojiTimeouts[res.userId] = setTimeout(() => {
          delete state.activeEmojis.value[res.userId];
        }, 3000);
        break;
      case "FORCE_LOGOUT":
        await resetAuthState(
          res.msg || "账号已在其他设备登录，请重新登录",
          { refreshCaptcha: true },
        );
        break;
      case "SKILL_USED":
        if (res.skillName === "LUANJIAN") {
          showActionText(res.userId, "乱箭", "skill"); // 弹出酷炫文字特效
          playAudio("action_luanjian"); // 播放你的 Mp3
        } else if (res.skillName === "GUANXING") {
          showActionText(res.userId, "观星", "skill"); // 弹出酷炫文字特效
          playAudio("action_guanxing"); // 播放你的 Mp3
        } else if (res.skillName === "GUSHOU") {
          // ====== 【新增：固守全服语音与飘字特效】 ======
          showActionText(res.userId, "固守", "skill");
          playAudio("action_gushou");
        } else if (res.skillName === "KUROU") {
          // ====== 【新增：苦肉全服飘字 + 语音】 ======
          showActionText(res.userId, "苦肉", "skill");
          if (res.userId === state.userId.value) {
            state.kurouUsesThisTurn.value = Math.min(
              2,
              state.kurouUsesThisTurn.value + 1,
            );
          }
          // 短暂延迟播放，若紧随其后收到 SKILL_AWAKEN 则跳过普通语音
          if (pendingKurouSfxTimeout) {
            clearTimeout(pendingKurouSfxTimeout);
          }
          pendingKurouSfxTimeout = setTimeout(() => {
            pendingKurouSfxTimeout = null;
            playAudio("action_kurou");
          }, 120);
        } else if (res.skillName === "GUIXIN") {
          showActionText(res.userId, "归心", "skill");
          playAudio("action_guixin");
        }
        break;
      case "SKILL_AWAKEN":
        // 【新增：觉醒特效】苦肉累计 >=3 次触发整局永久觉醒
        if (pendingKurouSfxTimeout) {
          clearTimeout(pendingKurouSfxTimeout);
          pendingKurouSfxTimeout = null;
        }
        showActionText(res.userId, "苦肉·觉醒", "skill");
        playAudio("action_kurou_awaken");
        if (res.userId === state.userId.value) {
          state.kurouAwakened.value = true;
        }
        break;
      case "PLAYER_REPLACED":
        showActionText(res.userId, "制衡", "skill");
        playAudio("action_zhiheng");
        break;

      case "PLAYER_PASSED":
        showActionText(res.userId, "要不起", "pass");
        playAudio("action_pass");
        break;
    }
  };

  // 【断开连接钩子】
  ws.value.onclose = () => {
    state.isConnected.value = false;
    state.gameStarted.value = false;
    state.winner.value = "";
    state.handCards.value = [];
    state.tableCards.value = [];
    state.otherPlayers.value = [];
    // 彻底重置残留的房间设置
    state.roomSettings.value = {
      enableWildcard: false,
      enableScrollCards: false,
    };
    state.guixinDisabled.value = false;
    state.guixinPendingOwner.value = "";
    state.guixinPendingPasser.value = "";
    if (globalTimer) clearInterval(globalTimer);
  };
};
