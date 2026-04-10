from fastapi import FastAPI
from app.api.routes import router

app = FastAPI(title="Poker AI Agent Service")

# 注册 API 路由
app.include_router(router)

if __name__ == "__main__":
    import uvicorn
    # 启动服务：注意模块路径是 app.main:app
    uvicorn.run("app.main:app", host="0.0.0.0", port=8000, reload=True)