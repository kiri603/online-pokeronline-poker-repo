from pydantic import BaseModel, Field
from typing import List, Optional

class Card(BaseModel):
    suit: str
    rank: str
    weight: int

class AiDecision(BaseModel):
    action: str = Field(description="行动类型：'PLAY' (出牌), 'PASS' (要不起), 'ZHIHENG' (制衡换牌)")
    cards: List[Card] = Field(default=[], description="打出或制衡的具体卡牌列表")
    reason: str = Field(description="AI 思考的过程，为什么这么出牌？")