# 基于 SpringBoot + Vue3 的全栈实时多人在线卡牌游戏

这是一个支持多人实时联机、断线重连、以及复杂技能结算机制的 Web 端卡牌对战平台。项目基于经典的扑克玩法进行了深度拓展，引入了武将技能与群体锦囊牌系统。

## 在线体验与源码

- **在线游玩地址：** http://39.102.60.181/
- **GitHub 源码：** https://github.com/kiri603/online-pokeronline-poker-repo/

## 游戏截图
<img width="2541" height="1224" alt="屏幕截图 2026-03-25 211223" src="https://github.com/user-attachments/assets/143f60d6-9656-4907-9527-2b84b3ff2d2f" />
<img width="2548" height="1236" alt="屏幕截图 2026-03-25 211254" src="https://github.com/user-attachments/assets/1e96863e-396c-42b5-8639-ee7c1b32b898" />
<img width="2558" height="1220" alt="image" src="https://github.com/user-attachments/assets/c20a9b73-c455-4530-9562-b2e93ec3ae2d" />
<img width="2548" height="1194" alt="image" src="https://github.com/user-attachments/assets/c428cd13-3d81-46d8-8da7-e317de43c735" />



## 技术栈

- **前端：** Vue 3, Vite, CSS Flexbox 
- **后端：** Java 17, Spring Boot, WebSocket
- **部署与运维：** 阿里云 CentOS, Nginx, GitHub Actions 

## 核心难点与解决方案

### 1. 高并发场景下的线程安全问题 (ConcurrentModificationException)
在游戏进行中，当多名玩家同时进行高频操作（如快速点击“观星”技能，或多人同时响应群体锦囊）时，WebSocket 的广播线程和具体的业务处理线程会同时对同一个手牌 List 进行遍历和修改，导致底层抛出 `ConcurrentModificationException` 引发服务崩溃。
**解决方案：** 放弃了粗粒度的全局锁，引入了房间级别的对象锁（`synchronized(room)`）。在进行发牌、状态转换、以及数据序列化广播时，严格保证业务操作的原子性。将并发请求转化为安全的串行处理，彻底解决了内存数据的读写冲突。

### 2. “幽灵房间”导致的内存泄漏
为了支持玩家断线重连，初版架构中玩家异常掉线仅被标记为离线状态，未被移出内存。这导致游戏结束后，大量空置的房间对象永远滞留在服务器的 ConcurrentHashMap 中，形成“幽灵房间”，引发持续的内存泄漏。
**解决方案：** 重构了 WebSocket 的断开生命周期管理。当检测到连接断开时，系统会触发深度的房间状态检查。如果判定房间内所有玩家均已处于断线状态，系统会立即从内存中彻底剥离并销毁该房间对象，实现了服务器资源的自动回收。

### 3. 复杂 AOE（群体锦囊）的结算队列机制
常规的出牌流转是线性的，但在引入“借刀杀人”、“五谷丰登”等特殊锦囊后，正常的出牌倒计时会被强行中断，系统需要挂起当前状态，并等待场上其余玩家依次或同时做出响应。
**解决方案：** 设计了一套支持中断与恢复的独立状态机。在后端维护了待响应队列（`pendingAoePlayers`）和当前中断事件标记。触发群体锦囊时，系统会封存当前回合上下文，将目标玩家压入队列并启动独立的超时倒计时。结合自动判定逻辑，实现了复杂技能结算的无缝切入与切出，防止了状态死锁。

## 本地运行部署

### 前端
```bash
cd poker-frontend
npm install
npm run dev
```
### 后端
需配置 JDK 17 及 Maven 环境：
```bash
cd poker
mvn clean package -DskipTests
java -jar target/poker-0.0.1-SNAPSHOT.jar
```
