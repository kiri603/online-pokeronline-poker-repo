from openai import AsyncOpenAI
import json
import logging
import asyncio
import re  # ======= 【新增】：引入正则表达式库 =======
from app.core.config import settings

logger = logging.getLogger(__name__)

client = AsyncOpenAI(
    api_key=settings.LLM_API_KEY,
    base_url=settings.LLM_BASE_URL
)

def format_cards(cards: list) -> str:
    if not cards: return "无"
    sorted_cards = sorted(cards, key=lambda x: x['weight'])
    return ", ".join([f"{c['suit']}{c['rank']}(w:{c['weight']})" for c in sorted_cards])

def analyze_table(table_cards: list) -> dict:
    if not table_cards:
        return {"count": 0, "max_weight": 0, "desc": "桌面为空"}
    count = len(table_cards)
    max_weight = max([c['weight'] for c in table_cards])
    return {"count": count, "max_weight": max_weight, "desc": format_cards(table_cards)}

async def get_ai_decision(hand_cards: list, table_cards: list) -> dict:
    # ======= 【防限流机制】：在每次请求大模型前，强行冷却 1 秒 =======
    # 免费 API 极其脆弱，这样能大概率绕过 429 报错
    await asyncio.sleep(1)
    
    table_info = analyze_table(table_cards)
    
    if table_info["count"] == 0:
        constraint_prompt = "【当前局势】：桌面为空，你是自由出牌回合！\n【硬性约束】：你【必须出牌】(action: PLAY)，绝对不能过(PASS)。"
    else:
        constraint_prompt = f"""
        【当前局势】：桌面上是 {table_info["count"]} 张牌：[{table_info["desc"]}]。核心权重为 {table_info["max_weight"]}。
        【硬性约束】：
        1. 数量限制：你如果要出牌，必须打出恰好 {table_info["count"]} 张牌（除非你手里有 4 张一样的炸弹）。
        2. 权重压制：你打出的牌，其权重(w:X)必须【严格大于】桌面的权重 {table_info["max_weight"]}！
        3. 如果你手里没有符合上述条件的牌，不要犹豫，直接选择要不起 (action: PASS)。
        """

    system_prompt = """
    你是一个极其聪明、狡猾的顶级斗地主 AI。
    卡牌权重规则：3(w:1) < 4(w:2) < ... < A(w:12) < 2(w:13) < 小王(w:14) < 大王(w:15)。

    【AI 智能战术库】
    - 自由出牌时：尽量把手里零散的、权重极小（w:1~w:4）的废牌先打出去。不要一上来就扔出 2 或 王。
    - 压制别人时：用“刚好能压住”的牌去打！比如桌面权重是 5，你手里有 6 也有 13，你必须用 6 去压制，把 13 这种大牌留到最后保命。
    - 保护大牌：绝对不要为了压制一张小牌，去拆散你手里的大对子或炸弹。

    【输出格式要求】
    严格输出纯 JSON 对象：
    {
        "reason": "15字以内分析",
        "action": "PLAY" 或 "PASS",
        "cards": [{"suit": "♠", "rank": "4", "weight": 2}] 
    }
    """

    user_prompt = f"""
    你的完整手牌: {format_cards(hand_cards)}
    你的原始手牌 JSON (请从中拷贝出牌数据): {json.dumps(hand_cards, ensure_ascii=False)}
    
    {constraint_prompt}
    
    请严格遵循物理约束和战术库，给出决策。
    """

    result_text = ""
    try:
        response = await asyncio.wait_for(
            client.chat.completions.create(
                model=settings.LLM_MODEL_ID,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt}
                ],
                temperature=0.01, 
                max_tokens=200
            ),
            timeout=19.0 
        )
        
        result_text = response.choices[0].message.content.strip()
        
        # ======= 【核心修复：防弹级 JSON 提取器】 =======
        # 使用正则寻找第一对 {} 中间的所有内容，无视外面包裹的任何奇怪文字
        match = re.search(r'\{.*\}', result_text, re.DOTALL)
        if match:
            clean_json_str = match.group(0)
        else:
            raise ValueError(f"模型回复中没有找到JSON格式: {result_text}")
            
        decision = json.loads(clean_json_str)
        logger.info(f"🧠 AI思考: {decision.get('reason')} | 动作: {decision.get('action')} | 准备出牌数量: len({decision.get('cards', [])})")
        return decision

    except json.JSONDecodeError:
        logger.error(f"❌ JSON 格式彻底损坏，无法解析: {result_text}")
        if not table_cards:
            return {"action": "PLAY", "cards": [min(hand_cards, key=lambda x: x['weight'])], "reason": "JSON乱码兜底出单牌"}
        else:
            return {"action": "PASS", "cards": [], "reason": "JSON乱码兜底过牌"}

    except asyncio.TimeoutError:
        logger.error("⏱️ 大模型思考超时 (超过28秒)！触发极速兜底机制。")
        if not table_cards:
            return {"action": "PLAY", "cards": [min(hand_cards, key=lambda x: x['weight'])], "reason": "超时强出单牌"}
        else:
            return {"action": "PASS", "cards": [], "reason": "超时强行过牌"}
            
    except Exception as e:
        logger.error(f"❌ 未知异常: {e}")
        if not table_cards:
            return {"action": "PLAY", "cards": [min(hand_cards, key=lambda x: x['weight'])], "reason": "报错强出单牌"}
        else:
            return {"action": "PASS", "cards": [], "reason": "报错强行过牌"}