from fastapi import APIRouter, BackgroundTasks
from app.websocket.game_client import connect_and_play

router = APIRouter()

@router.get("/api/bot/join")
async def join_bot(roomId: str, aiName: str = "AI_陪玩", background_tasks: BackgroundTasks = BackgroundTasks()):
    # 将 WebSocket 连接任务交给后台异步执行
    background_tasks.add_task(connect_and_play, roomId, aiName)
    return {"status": "success", "msg": f"已派遣 {aiName} 前往房间 {roomId}"}