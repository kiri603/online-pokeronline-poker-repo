import os
import asyncio
import edge_tts

# 【核心修改 1】：更换为浑厚、硬朗、有气场的男声（云健）
VOICE = "zh-CN-YunxiaNeural"

# 【核心修改 2】：强制加快语速，加大音量！
# rate="+25%" 表示语速加快25%，出牌更果断
# volume="+20%" 表示音量增加20%，更有爆发力
RATE = "+25%"
VOLUME = "+20%"

# 你的文案字典 (可以给大招加上感叹号，TTS会根据标点符号加重语气！)
texts = {

    # "last_1": "我就亿张牌啦~",
    # "last_2": "我只剩两张牌啦~",
    # # 单牌
    # "single_3": "三", "single_4": "四", "single_5": "五", "single_6": "六",
    # "single_7": "七", "single_8": "八", "single_9": "九", "single_10": "十",
    # "single_J": "钩", "single_Q": "悛", "single_K": "K", "single_A": "尖",
    # "single_2": "二", "single_joker_small": "小王！", "single_joker_big": "大王！！",

    # 对子
    # "pair_3": "对三", "pair_4": "对四", "pair_5": "对五", "pair_6": "对六",
    # "pair_7": "对七", "pair_8": "对八", "pair_9": "对九", "pair_10": "对十",
    # "pair_J": "对钩", "pair_Q": "对圈", "pair_K": "对K", "pair_A": "对尖",
    # "pair_2": "对二",

    # 统称与技能
    # "combo_three": "三张",
    # "combo_three_one": "三带一",
    # "combo_three_pair": "三带一对儿",
    # "combo_straight": "顺子",
    # "combo_straight_pair": "连对",
    # "combo_plane": "飞机",
    # "combo_bomb": "炸弹！！",
    # "combo_rocket": "王炸！！！",
    # "action_pass": "要不起",
    # "action_zhiheng":"制衡",
    # "action_guanxing":"观星",
    # "action_luanjian":"乱箭",
    # "action_gushou":"固守"


    # "skill_nmrq": "南蛮入侵！！",
    # "skill_wjqf": "万箭齐发！！"
    # "skill_wgfd": "五谷丰登"
    # "skill_jdsr": "借刀杀人！"
    "action_guixin": "归心！"


}

os.makedirs("audios", exist_ok=True)

async def generate_all_audios():
    for filename, text in texts.items():
        output_path = f"audios/{filename}.mp3"
        print(f"正在生成: {text} -> {output_path}")

        # 【核心修改 3】：在这里传入 rate 和 volume 参数
        communicate = edge_tts.Communicate(text, VOICE, rate=RATE, volume=VOLUME)
        await communicate.save(output_path)

if __name__ == "__main__":
    asyncio.run(generate_all_audios())
    print("音频重新生成完毕！")
