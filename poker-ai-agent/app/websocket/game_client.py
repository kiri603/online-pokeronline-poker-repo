import asyncio
import websockets
import json
import logging
from app.core.config import settings
from app.schemas.game_models import Card
from app.agent.llm_client import get_ai_decision

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

class PokerAgentSession:
    def __init__(self, room_id: str, ai_name: str):
        self.room_id = room_id
        self.ai_name = ai_name
        self.hand_cards = []  # 记录 AI 的手牌
        self.is_my_turn = False
        
    async def run(self):
        try:
            async with websockets.connect(settings.WS_BASE_URL) as ws:
                logger.info(f"[{self.ai_name}] 成功连接到游戏服务器")
                
                # 1. 加入房间并准备
                await ws.send(json.dumps({
                    "type": "JOIN_ROOM",
                    "roomId": self.room_id,
                    "userId": self.ai_name,
                    "data": {"isCreating": False, "isPrivate": False, "password": ""}
                }))
                await asyncio.sleep(1)
                await ws.send(json.dumps({"type": "READY", "roomId": self.room_id, "userId": self.ai_name}))
                
                # 2. 持续监听事件
                async for message in ws:
                    data = json.loads(message)
                    await self.handle_event(ws, data)
                    
        except Exception as e:
            logger.error(f"[{self.ai_name}] 连接断开: {e}")

    async def handle_event(self, ws, data: dict):
        event = data.get('event')
        
        # --- 1. 同步我的手牌 ---
        if event == "SYNC_HAND":
            self.hand_cards = data.get("cards", [])
            logger.info(f"[{self.ai_name}] 收到最新手牌，共 {len(self.hand_cards)} 张")
            
        # --- 2. 同步全场状态（最关键的触发点） ---
        elif event == "SYNC_STATE":
            current_turn = data.get("currentTurn")
            table_cards = data.get("tableCards", [])
            
            # 如果轮到我了，并且之前不是我的回合（防止重复触发）
            if current_turn == self.ai_name and not self.is_my_turn:
                self.is_my_turn = True
                logger.info(f"[{self.ai_name}] 轮到我出牌了！桌面牌: {table_cards}")
                # 开启一个后台任务去思考出牌，避免阻塞 WebSocket 监听
                asyncio.create_task(self.think_and_act(ws, table_cards))
            elif current_turn != self.ai_name:
                self.is_my_turn = False
                
        # --- 3. 容错机制：如果我出了错牌被 Java 后端拦截 ---
        elif event == "ERROR":
            msg = data.get('msg', '')
            logger.warning(f"[{self.ai_name}] 操作被服务器拒绝: {msg}")
            # 如果是当前回合出的错（比如 AI 幻觉出了没有的牌），强制 PASS 兜底，防止游戏卡死倒计时
            if self.is_my_turn:
                logger.error("触发自动过牌兜底机制！")
                await ws.send(json.dumps({"type": "PASS", "roomId": self.room_id, "userId": self.ai_name}))

    async def think_and_act(self, ws, table_cards: list):
        """调用大模型思考，并发送出牌指令"""
        try:
            
            
            # 调用真实大模型
            decision = await get_ai_decision(self.hand_cards, table_cards)
            
            action = decision.get("action")
            cards_to_play = decision.get("cards", [])
            
            # 根据大模型的决定发送 WebSocket 指令给 Java 后端
            if action == "PLAY" and cards_to_play:
                logger.info(f"[{self.ai_name}] 决定出牌: {[c['suit']+c['rank'] for c in cards_to_play]}")
                await ws.send(json.dumps({
                    "type": "PLAY_CARD",
                    "roomId": self.room_id,
                    "userId": self.ai_name,
                    "data": cards_to_play
                }))
            else:
                logger.info(f"[{self.ai_name}] 决定要不起 (PASS)")
                await ws.send(json.dumps({
                    "type": "PASS",
                    "roomId": self.room_id,
                    "userId": self.ai_name
                }))
                
        # ====== 【修复 2】：新增绝对防线 ======
        except Exception as e:
            logger.error(f"[{self.ai_name}] 思考过程发生严重异常: {e}")
            # 如果大模型代码报错，立刻强行发送过牌指令，绝不卡死游戏进度！
            await ws.send(json.dumps({
                "type": "PASS",
                "roomId": self.room_id,
                "userId": self.ai_name
            }))

# 需要把之前的单纯函数改为实例化调用
async def connect_and_play(room_id: str, ai_name: str):
    session = PokerAgentSession(room_id, ai_name)
    await session.run()