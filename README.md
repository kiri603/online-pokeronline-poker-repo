# 🃏 Online-Poker - 实时多人策略卡牌游戏

> 融合经典扑克玩法与“武将技能/群体锦囊”机制的全栈 Web 联机对战平台。

## 🌐 在线体验与源码

  - **在线游玩地址：** [http://39.102.60.181/](http://39.102.60.181/)
  - **GitHub 源码：** [https://github.com/kiri603/online-poker-repo/](https://github.com/kiri603/online-poker-repo/)

## 💡 项目简介

本项目是一个支持多人实时联机、复杂技能结算以及断线重连的 Web 端卡牌对战游戏。

  - **解决什么问题？** 传统的 Web 棋牌项目往往局限于简单的回合制发牌，缺乏应对高频并发操作和复杂多状态中断（如群体技能结算）的工程解决方案。本项目通过自定义状态机和细粒度锁机制，解决了高并发下的状态同步难题。
  - **有什么特色功能？** 在基础扑克玩法上进行了深度拓展，独创性地引入了类似《三国杀》的“武将技能”（如观星）与“群体锦囊”系统。
  - **适用于什么场景？** 适合好友在线开黑娱乐，同时也是展示 **全栈开发、WebSocket 实时通信、并发编程与内存管理** 能力的优秀工程实践。

## ✨ 核心特性

  - [x] **⚡ 毫秒级实时同步**：基于 WebSocket 实现双向通信，确保多人开局、出牌、技能结算的极低延迟。
  - [x] **🛡️ 健壮的会话管理**：支持玩家意外掉线后的“断线重连”与无缝状态恢复，游戏进程不中断。
  - [x] **🧠 独立业务状态机**：完美支持“借刀杀人”、“五谷丰登”等复杂 AOE（群体锦囊）技能的挂起、插队响应与状态恢复。
  - [x] **🎨 现代化沉浸式 UI**：基于 Vue 3 打造的响应式游戏大厅与对战桌面，动画流畅，交互自然。

## 🛠️ 技术栈

  - **前端架构：** `Vue 3` + `Vite` + `CSS Flexbox`
  - **后端架构：** `Java 25` + `Spring Boot` + `WebSocket`
  - **部署与运维：** 阿里云 CentOS + `Nginx` + `GitHub Actions` 自动化部署



## 📸 游戏截图

### 联机大厅 

<img width="2541" height="1224" alt="屏幕截图 2026-03-25 211223" src="https://github.com/user-attachments/assets/9db3596f-12d8-4c74-9380-d0f59d70a207" />

### 游戏对战桌面 

<img width="2548" height="1236" alt="屏幕截图 2026-03-25 211254" src="https://github.com/user-attachments/assets/9f86a03a-e4e3-41e7-835b-201036bb66c1" />


### 表情功能演示

<img width="2558" height="1220" alt="屏幕截图 2026-03-25 211640" src="https://github.com/user-attachments/assets/ebfa02ee-3d28-48c4-93af-25e58e521115" />

### 角色与技能选择 

<img width="2548" height="1194" alt="屏幕截图 2026-03-25 211315" src="https://github.com/user-attachments/assets/6a45e275-4847-45a8-a713-cb2cd7f2ecfa" />


## 🚀 本地运行部署

### 前端开发环境

```bash
cd poker-frontend
# 安装依赖
npm install
# 启动本地开发服务器
npm run dev
```

### 后端运行环境

*需确保本地已配置 `JDK 25` 及 `Maven` 环境。*

```bash
cd poker
# 清理并打包项目（跳过测试）
mvn clean package -DskipTests
# 启动 Spring Boot 服务
java -jar target/poker-0.0.1-SNAPSHOT.jar
```

## 📅 未来规划

  - [ ] 丰富武将池与锦囊牌库，加入更复杂的连锁响应技能。
  - [ ] 为大厅加入全局聊天频道，增强玩家互动。

## 🤝 贡献指南

欢迎提交 Pull Request 优化代码或提出 Issue 讨论新的游戏机制。如果这个项目对你有启发，欢迎点个 ⭐️ Star！

## 👨‍💻 作者

  - **GitHub:** [@kiri603](https://www.google.com/search?q=https://github.com/kiri603)
  - **邮箱:**1306842652@qq.com / chenziqian0603@gmail.com
