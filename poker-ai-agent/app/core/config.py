import os
from dotenv import load_dotenv

# 加载 .env 文件
load_dotenv()

class Settings:
    WS_BASE_URL: str = "ws://localhost:8080/ws/game"
    LLM_API_KEY: str = os.getenv("LLM_API_KEY")
    LLM_MODEL_ID: str = os.getenv("LLM_MODEL_ID")
    LLM_BASE_URL: str = os.getenv("LLM_BASE_URL")

settings = Settings()