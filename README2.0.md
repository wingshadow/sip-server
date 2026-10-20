# sip-server 2.0 — GB/T 28181 视频服务平台

> Spring Boot 3.3 + Netty + 自研媒体引擎，支持 GB/T 28181-2022 设备的注册、目录、实时预览、录像回放、录像下载、语音对讲/广播、告警订阅。

---

## 目录

1. [项目简介](#1-项目简介)
2. [版本演进](#2-版本演进)
3. [整体架构](#3-整体架构)
4. [local 模式重构详解](#4-local-模式重构详解)
5. [代码结构](#5-代码结构)
6. [SIP 信令详解与命令结构](#6-sip-信令详解与命令结构)
7. [问题与解决方法](#7-问题与解决方法)
8. [重构后优势](#8-重构后优势)
9. [HTTP API 一览](#9-http-api-一览)
10. [配置说明](#10-配置说明)
11. [前端页面](#11-前端页面)
12. [已知限制与后续方向](#12-已知限制与后续方向)

---

## 1. 项目简介

`sip-server` 是一个 GB/T 28181-2022 国标视频服务平台，核心能力：

| 能力 | 说明 |
|---|---|
| 设备接入 | UDP 5060 SIP 信令，注册/心跳/目录/离线检测 |
| 实时预览 | INVITE 点播 → 设备 RTP 推流 → HTTP-FLV 浏览器播放（flv.js） |
| 录像回放 | 云端回放 INVITE（SDP 带时间轴）→ FLV 流 |
| 录像下载 | 回放式下载 → MP4 落盘（faststart）→ HTTP 下载 |
| 语音对讲 | 语音 INVITE（G.711a PCMU/PCMA）→ WebSocket 播放 |
| 语音广播 | MANSCDP Broadcast MESSAGE 下发 |
| 告警订阅 | SUBSCRIBE Event: presence + 自动续订 |
| 录像查询 | RecordInfo MANSCDP 查询，多包 SN 聚合 |

技术栈：Java 17 / Spring Boot 3.3.x / Netty 4.1（SIP UDP）/ MyBatis-Plus / 原生 ffmpeg 进程 / 纯静态前端。

---

## 2. 版本演进

### 1.0（起点）

- 单端口 RTP 接收器（端口写死 30000），按 SSRC 匹配会话；
- PlayService / PlaybackService 两个并行服务各自维护会话；
- 无注册/录像/语音能力；
- 使用 ZLMediaKit 作为媒体面。

### 2.0（当前）

- **local 自研媒体引擎**（默认模式）：RtpPortPool + 每会话独立 UDP 端口 + RtpReceiver + ffmpeg 管道 + FlvBroadcast，**零第三方媒体服务依赖**；
- 媒体服务重组为 `service/media/`（MediaService 门面 + RtpReceiver + RtpPortPool）、`service/local/`（FlvBroadcast）、`service/voice/`（对讲/广播）、`service/zlm/`（ZLM 模式保留，可切换）；
- SIP 处理器按职责拆分为 `handler/` 下 10 个类，路由器 `SipRequestHandler` 统一分发；
- 新增：注册鉴权、目录、录像查询/回放/下载、语音对讲/广播、告警订阅、Open API（`/api/v1/**`）；
- 修复 1.0 全部遗留问题（见[第 7 章](#7-问题与解决方法)）。

---

## 3. 整体架构

```
┌──────────────────────────────────────────────────────────────────────┐
│  摄像机（GB/T 28181-2022）                                            │
│   信令 UDP 5060 ◀──SIP──▶ 平台        媒体 RTP ──▶ 平台会话端口        │
└──────────────────────────────────────────────────────────────────────┘
        │                                          │
        ▼                                          ▼
┌────────────────────┐              ┌──────────────────────────────────┐
│ UdpSipServer       │              │ RtpPortPool (30000-30099)        │
│ (Netty NIO, 1线程)  │              │  每会话独占一个 UDP 端口          │
└────────┬───────────┘              └──────────────┬───────────────────┘
         │ handle()                                 │ register()
         ▼                                          ▼
┌────────────────────┐              ┌──────────────────────────────────┐
│ SipRequestHandler  │              │ RtpReceiver                      │
│ (路由器)            │              │  每会话 DatagramSocket + 接收线程 │
└────────┬───────────┘              └──────────────┬───────────────────┘
         │ 分发                                      │ SinkWriter(512块队列)
    ┌────┼────┬─────────┬──────────┐                │ 慢下游丢帧不阻塞
    ▼    ▼    ▼         ▼          ▼                ▼
 Register Message  MediaSignal  VoiceSignal   ┌────────────────────────┐
 Handler  Handler  Handler      Handler        │ ffmpeg 进程            │
    │      │         │            │            │  -f mpeg -i pipe:0     │
    │      │         ▼            ▼            │  直播/回放 → FLV pipe:1│
    │      │   ┌──────────┐  ┌──────────┐      │  下载 → MP4 .part     │
    │      │   │MediaService│ │VoiceService│    └──────┬─────────┬─────┘
    │      │   │ (门面/状态机│ │(对讲/广播) │           │         │
    │      │   │  看门狗/收尾)│ └──────────┘           ▼         ▼
    │      │   └─────┬────┘                    ┌──────────┐  ┌──────────┐
    │      │         │                         │FlvBroadcast│ │ MP4 文件 │
    │      │         ▼                         │ 多客户端共享│ │ .part→改名│
    │      │   ZlmMediaService(zlm模式)         └────┬─────┘  └──────────┘
    │      │                                          │
    ▼      ▼                                         ▼
 DeviceRegistry / CatalogService            FlvController (/api/flv/**)
```

**双模式说明**（`sip.media.mode`）：

- `local`（默认）：自研链路，本文档重点；
- `zlm`：ZLMediaKit 接管媒体面，`ZlmMediaService` 实现同一套门面接口，SIP 信令层完全不变。

---

## 4. local 模式重构详解

### 4.1 重构动机

1.0 的单端口共享接收器存在根本缺陷：多路会话共用一个 UDP 端口，只能靠 SSRC 区分设备，设备 SSRC 复用/冲突时串流；下游（ffmpeg）阻塞会拖垮接收线程；播放器断连后 ffmpeg 泄漏。local 模式重构目标：

- **端口级隔离**：一个会话一个端口，从根上消除 SSRC 冲突；
- **背压安全**：慢下游只丢帧，永不阻塞 RTP 接收；
- **文件完整性**：下载文件必须带 `moov` 索引可播放；
- **零依赖**：不依赖 ZLMediaKit 等外部媒体服务。

### 4.2 总体数据流

```
设备 RTP(PS over UDP)
   │
   ▼ 每会话独立 DatagramSocket（端口来自 RtpPortPool）
RtpReceiver.run()  ── 每 callId 一条 daemon 线程 "rtp-recv-<callId后6位>"
   │ dispatch()：socket 专属于单会话，包直接归属（无需 SSRC 解复用）
   ▼ RTP 载荷（PS 裸流，去掉 RTP 头）
SinkWriter ── ArrayBlockingQueue 512 块（≈700KB）
   │ 写线程 poll 1000ms；队列满 → dropOldest + overflows++
   ▼ OutputStream（= ffmpeg stdin 管道）
ffmpeg -f mpeg -i pipe:0
   ├─ 直播/回放：-c:v copy -f flv pipe:1 ──▶ FlvBroadcast ──▶ 多浏览器 flv.js
   └─ 下载：-c:v libx264 -movflags +faststart -f mp4 <name>.part ──▶ rename → .mp4
```

### 4.3 RtpPortPool（端口池）

| 属性 | 值 |
|---|---|
| 默认范围 | `30000–30099`（100 个端口，`sip.media.local.port-pool-start/end`） |
| 分配算法 | `AtomicBoolean[] claims` 按位扫描，优先复用"最近连续释放的一段" |
| 耗尽行为 | 快速失败返回 `-1`，MediaService 回 502（端口池耗尽） |
| 绑定失败 | `register()` 自动换池中下一个端口重试（最多 8 次） |
| 释放幂等 | 重复 `release(port)` 不报错（未占用返回 false） |

`MediaConfig` 以 `@Bean` 工厂方式装配（端口范围配置驱动、构造需参数），供 RtpReceiver / MediaService / DeviceController 共用。

### 4.4 RtpReceiver 与 SinkWriter

**RtpReceiver**（`service/media/RtpReceiver.java`）：

- `register(callId, ...)`：分配端口 → 绑定 `DatagramSocket` → 登记路由（`ConcurrentHashMap<callId, Session>`）→ 启动接收线程。**在发送 INVITE 之前调用**；
- `attachSink(callId, OutputStream)`：挂接 ffmpeg stdin（200 OK 后 MediaService 调用）；
- `stop(callId)`：`detachSink` → 关闭 socket → 从 `routes` 移除。**这是 ffmpeg 收到 EOF 的唯一入口**；
- `Session` 字段：`callId / deviceId / channelId / deviceHost / devicePort / port / ssrc / srcAddr / sink / sinkWriter / packets / overflows / lastRtpNanos`；
- 首个包、源地址、socket 关闭均打日志，便于诊断。

**SinkWriter**（内部类）：

| 机制 | 说明 |
|---|---|
| 队列 | `ArrayBlockingQueue` 512 块，每块 ≤64KB（≈700KB 缓冲） |
| 写线程 | `poll(1000ms)` 取块写入 ffmpeg stdin；`closed` 时 `close()` 退出 |
| 背压 | 队列满 → 丢弃最旧块（`dropOldest`）+ `overflows++`，**永不阻塞接收线程** |
| EOF 保证 | `close()` 置标志 + `clear()` + `interrupt()` + **直接 `out.close()`** |
| 兜底原因 | 写线程阻塞在 `out.write()`（管道满）时 `interrupt` 无法唤醒，stdin 不关 ffmpeg 就收不到 EOF（详见 7.1） |

### 4.5 MediaService：门面与会话状态机

`MediaService`（`service/media/MediaService.java`，约 1090 行）是 local 引擎的**唯一门面**，职责：

1. **模式分发**：`mediaProps.isZlm()` 时委派 `ZlmMediaService`；
2. **会话管理**：`sessions: ConcurrentHashMap<"deviceId:channelId", StreamSession>`；
3. **INVITE/ACK/BYE/INFO 全流程** + 401 挑战 + 200 OK 响应路由；
4. **看门狗体系**（见 4.6）；
5. **收尾与下载落盘**（见 4.8、4.9）；
6. **真实进度反馈**（见 4.10）。

**会话状态机**（`StreamSession`）：

```
(创建) ──INVITE──▶ INVITING ──200 OK──▶ PLAYING ──BYE/超时/用户停止──▶ STOPPED
                        │                  │
                        └── 488/500/603 ───┴──▶ STOPPED（释放端口 + 主动 BYE 兜底）
```

| 状态 | 含义 | 迁移触发 |
|---|---|---|
| `INVITING` | INVITE 已发，等待 200 OK | `startLive/startPlayback/startDownload` |
| `PLAYING` | 收到 200 OK、ACK 已发、SDP 已解析 | `handleResponse` → `markPlaying` |
| `STOPPED` | 终态 | `markStopped`（设备 BYE=3 / 看门狗 / 用户停止 / 强制停止） |

辅助标志：`ffmpegCrashed`（stderr EOF）、`forcedBye`（回放结束强制 BYE）、`emptyFile`（ffmpeg 无输出）、`timeoutReason`、`terminal`（0 未知 / -1 超时 / -2 暂停——用于回放区间非法判定）。

**关键调用路径**：

| 入口 | 流程 |
|---|---|
| `startLive` | 端口分配 → INVITE → 返回 waitForPlaying(30s) |
| `startPlayback` | 同上 + `waitForFfmpegReady(10s)` |
| `startDownload` | 同上 + `INFO MANSRTSP: SCALE N`（会话建立后异步） |
| `handleResponse(200)` | callId 查会话 → `onDeviceAnswer`（解析 SDP：host/port/ssrc/媒体类型）→ `markPlaying` → ACK → 下载补 SCALE |
| `handleDeviceBye` | `markStopped(3)` + `cleanup` + 可选回 BYE → `finalizeDownload` |
| `doStop`（用户停止） | `cleanup` + `finalizeDownload` |
| `stopIfNoClients` | FLV 最后一个观众断开 → 停会话 |
| `shutdown` | 停止全部会话 + 端口池 shutdown |

**200 OK 早于会话就绪的兜底**：`pendingSdp` 仅做 `parseSsrc` 记录（ACK 丢弃，设备可能超时——只发生在边缘竞态，可接受）。

**端口/地址学习**：`onDeviceAnswer` 从应答 SDP 提取 `host/port/ssrc`；回放 SDP 的 `t=start end` 解析为录像时间窗。

### 4.6 看门狗体系

全部看门狗跑在 `mediaTasks`（size-1 daemon 线程池）上，`ScheduledThreadPoolExecutor` 先取消同 key 旧任务再排新的，保证同会话只有一个在途：

| 看门狗 | 常量 | 值 | 行为 |
|---|---|---|---|
| ffmpeg 就绪等待 | `FFMPEG_READY_WAIT_MILLIS` | 10s | 回放等 ffmpeg 产出；超时 → 504 |
| 回放结束看门狗 | `PLAYBACK_END_WATCHDOG_MILLIS` | 15s | 回放无 RTP 超 15s → 强制 BYE + `markStopped(-1)` |
| 下载空闲看门狗 | `DOWNLOAD_IDLE_MILLIS` | 15s（`download-idle-seconds`） | 下载无 RTP 超 15s → 主动 BYE 收尾；**首包宽限 4 倍（60s）** |
| ffmpeg 崩溃检测 | `ffmpegCrashed` 标志 | — | stderr EOF 且 `waitFor` 失败 → 标记崩溃，清理时记录 |

**下载空闲看门狗的意义**：设备静默停推但不发 BYE 时（常见于 NVR 提前结束），15s 判定后主动 BYE → 关 stdin → ffmpeg 写 moov 落盘，避免下载卡在进度上限不动。

### 4.7 FlvBroadcast（HTTP-FLV 广播）

`service/local/FlvBroadcast.java`——**只负责读 ffmpeg stdout，不管理进程**（进程由 MediaService 的 `buildFfmpeg` 创建，`new FlvBroadcast(session, ffmpeg.getInputStream())`）：

| 机制 | 说明 |
|---|---|
| 广播模型 | 一个 `Broadcast` = ffmpeg 的 `ProcessPipeInputStream` + `CopyOnWriteArrayList<Reader>` |
| 读线程 | 每广播一条 daemon 线程，8KB 缓冲读 stdout，逐 Reader `write` |
| Bootstrap | 就绪前的前几个包暂存（bootBytes/bootLen），新 Reader 先补发，保证 flv.js 拿到含头部的完整流 |
| 慢客户端 | 写超 1s 的 Reader 标记 `slow`，后续直接跳过 |
| 析构 | 最后一个 Reader detach → `stopIfNoClients` 停会话 → ffmpeg EOF → 线程退出 |
| 竞态防护 | `broadcastLock` 防并发 bootstrap；未就绪 → 503 + `Retry-After: 1` |
| 生命周期 | ffmpeg stdout EOF → 通知所有 Reader → 广播移除 |

`MediaService.getFlvBroadcast()`：`flvBroadcasts` map + 等待 ffmpeg 就绪（最长 10s）→ 不存在则创建。

### 4.8 录像下载链路

```
startDownload ──▶ INVITE(回放式 SDP + a=downloadspeed:4)
                      │ 200 OK → ACK → markPlaying
                      ▼
              INFO MANSRTSP: SCALE 4   （倍速兜底，异步）
                      ▼
  设备 4x 推流 → RtpReceiver → SinkWriter → ffmpeg stdin
                      ▼
   ffmpeg: -f mpeg -i pipe:0 -map 0:v:0 -map 0:a:0? \
           -c:v libx264 -preset ultrafast -c:a aac -b:a 64k \
           -movflags +faststart -f mp4 <downloadDir>/<name>.part
                      ▼
  设备推流结束（BYE 或 15s 空闲看门狗）
                      ▼
  cleanup(): rtpReceiver.stop() → stdin EOF → ffmpeg 写 moov + faststart 重排 → 退出
                      ▼
  finalizeDownload(): .part ──rename──▶ <filename>.mp4（或 <callId>.mp4）
                      ▼
  FlvController /api/download/file 流式返回（Content-Length）
```

要点：

- **下载是 libx264 重编码**（`-preset ultrafast`），PS 里的 H.264 重封装为 MP4 需要 Annex-B 起始码处理，重编码规避兼容性问题；`-map 0:a:0?` 无音频流不失败；
- **`.part` 中间文件**：下载中文件始终是 `<name>.part`，收尾成功后才改名为 `.mp4`——浏览器永远拿到完整文件；
- **文件名**：`rec_<deviceId>_<yyMMdd>_<HHmmss>_<yyMMdd>_<HHmmss>.mp4`，起始时间为空时回退 `rec_<callId>.mp4`；
- **倍速协商**：INVITE SDP 带 `a=downloadspeed:4`；会话建立后再经 SIP INFO 下发 `MANSRTSP: SCALE 4` 兜底；设备不支持时按 1x 推流（进度算法对此免疫，见 4.10）；
- **下载目录**：`sip.media.local.download-dir`，默认 `./downloads`，启动/下载前自动创建；
- **FlvController 等待策略**：`awaitFinalDownloadFile` 先等激活（180s），再等终态（30s）；优先 `session.downloadFile()`，回退 `downloads/<callId>.mp4`；文件不存在 → 504。

### 4.9 收尾顺序（本轮核心修复）

**问题**：`cleanup()` 原顺序是 `p.destroy()` → `waitFor` → `rtpReceiver.stop()`。Windows 上 `destroy()` 即 `TerminateProcess`，ffmpeg **瞬间死亡**，`-movflags +faststart` 要求的"flush → 写 moov → 重排文件"三步都不发生，`.part` 改名后就是**没有 moov 的损坏文件**（ffprobe: `moov atom not found`）。

**修复后顺序**：

```java
rtpReceiver.stop(s.getCallId());   // ① detachSink → SinkWriter.close() → 直接 out.close()
                                   // ② socket 关闭 → 接收线程退出
p.waitFor(6000 / 60000);           // ③ ffmpeg 自行收尾：flush 编码队列 → 写 moov → faststart 重排 → 退出
if (p.isAlive()) p.destroyForcibly(); // ④ 仅超时兜底（此时文件已损坏，防线程泄漏）
finalizeDownload(s);               // ⑤ .part → .mp4
```

| 会话类型 | 等待时长 | 依据 |
|---|---|---|
| LIVE | `FFMPEG_WAIT_MILLIS` = 6s | 转封装收尾很快 |
| DOWNLOAD | `FFMPEG_DOWNLOAD_WAIT_MILLIS` = 60s | faststart 需要**二次重写整个文件**，62MB 级也要数秒 |

**所有停止路径**（看门狗 `doStop`、FlvController 超时强停、用户停止、设备 BYE）均汇聚到同一 `cleanup()`，无第二处强杀 ffmpeg 的代码。

### 4.10 真实进度反馈

`GET /api/download/status?deviceId=&channelId=` 返回 `{active, size, outTimeSec, updatedAt}`：

- `size`：`.part` 文件当前字节数；
- `outTimeSec`：后台任务解析 ffmpeg 日志（`time=00:01:23.45`，兼容 `time=-`）换算的**已输出媒体秒数**——下载重编码期间这是唯一可信进度；
- `active`：会话是否存活。

前端（playback.html）两阶段反馈 + **单调不回退**（详见 7.2）：

- 阶段 1（转码中）：`realPct = outTimeSec/录像时长`，`estPct = 已耗时/预期时长`，**取较小值**；设备不按倍速推流（降 1x）时估算虚高，取小后自动以真实为准；
- 阶段 2（传输中）：接收字节映射到 95%→100%，与阶段 1 无缝衔接。

### 4.11 ffmpeg 命令详解

`MediaService.buildFfmpeg()` 构造（`ffmpegPath` 默认 `ffmpeg`，从 PATH 查找，可配置为绝对路径）：

**直播 / 回放**（FLV 输出到管道）：

```bash
ffmpeg -f mpeg -i pipe:0 \
       -map 0:v:0 -map 0:a:0? \
       -c:v copy -c:a aac -b:a 64k \
       -flvflags no_duration_filesize \
       -f flv pipe:1
```

- 视频流 **copy 直通**（PS→FLV 转封装，CPU 占用极低）；
- 音频转 AAC（浏览器 FLV 播放需要）；
- `no_duration_filesize`：管道输出无总时长/文件大小，flv.js 按直播流处理。

**下载**（MP4 落盘）：

```bash
ffmpeg -f mpeg -i pipe:0 \
       -map 0:v:0 -map 0:a:0? \
       -c:v libx264 -preset ultrafast -c:a aac -b:a 64k \
       -movflags +faststart \
       -f mp4 <downloadDir>/<name>.part
```

- **libx264 ultrafast 重编码**：规避 PS→MP4 的 Annex-B 兼容问题；
- **`+faststart`**：收尾时把 `moov` 索引写到文件头部，支持边下边播；
- 中间产物 `.part`，收尾成功后 rename。

**stderr 必须保留**：`drainFfmpegLog` 按行打印 ffmpeg stderr——banner、stream mapping、`Invalid data found`、编码错误等全部在此，是定位媒体链路问题的唯一依据。下载的 stdout 是二进制内容，静默丢弃（`drainQuietly`）。

### 4.12 关键常量汇总

| 常量 | 值 | 位置 |
|---|---|---|
| `SINK_QUEUE` | 512 块（≈700KB） | RtpReceiver |
| 端口池范围 | 30000–30099 | `sip.media.local.port-pool-*` |
| 语音端口池范围 | 30100–30150 | `sip.voice.port-pool-*` |
| `FFMPEG_READY_WAIT_MILLIS` | 10s | MediaService |
| `PLAYBACK_END_WATCHDOG_MILLIS` | 15s | MediaService |
| `DOWNLOAD_IDLE_MILLIS` | 15s（首包 60s） | MediaService |
| `FFMPEG_WAIT_MILLIS`（LIVE） | 6s | MediaService |
| `FFMPEG_DOWNLOAD_WAIT_MILLIS` | 60s | MediaService |
| `waitForPlaying` | 30s | MediaService |
| `downloadSpeed` | 4 | `sip.media.local.download-speed` |
| `stopSet` 上限 | 4096（有界 FIFO） | MediaService |
| `inflightTasks` 上限 | 512 | MediaService |

---

## 5. 代码结构

```
src/main/java/com/example/sip/
├── SipServerApplication.java          # Spring Boot 启动类
├── api/                               # 对外 Open API（/api/v1/**，X-API-Key 鉴权）
│   ├── auth/OpenApiAuthInterceptor.java
│   ├── config/OpenApiWebConfig.java   # 仅拦截 /api/v1/**
│   ├── config/OpenApiProperties.java  # enabled + api-keys
│   ├── controller/OpenApiController.java
│   ├── dto/                           # 请求 DTO
│   ├── exception/                     # Open API 异常体系
│   └── GlobalExceptionHandler.java
├── config/
│   ├── SipServerProperties.java       # sip.server.*（id/host/port/ip/domain/password/heartbeat-timeout）
│   ├── MediaProperties.java           # sip.media.*（mode/local/zlm/voice…）
│   ├── MediaConfig.java               # RtpPortPool @Bean 装配
│   ├── VoiceProperties.java           # sip.voice.*（enabled/port-pool）
│   └── AuthProperties.java            # 页面登录账号
├── controller/
│   ├── DeviceController.java          # /api/devices|play|playback|records|voice|alarm…
│   ├── FlvController.java             # FLV/下载流端点
│   └── LoginController.java           # /api/login|logout|auth/check
├── handler/                           # SIP 处理器（按职责拆分）
│   ├── SipRequestHandler.java         # 路由器：请求/响应分发
│   ├── SipResponder.java              # 统一响应发送
│   ├── SipUtil.java                   # SIP 头/URL 提取、XML 解析
│   ├── SipChannelHolder.java          # SIP UDP Channel 持有者
│   ├── RegisterHandler.java           # 注册/401 挑战/注销/摘要校验
│   ├── DeviceMessageHandler.java      # MESSAGE：Keepalive/Catalog/Alarm/RecordInfo
│   ├── MediaSignalHandler.java        # INVITE/ACK/BYE → MediaService
│   ├── VoiceSignalHandler.java        # INVITE/ACK/BYE → VoiceService
│   ├── RecordQueryHandler.java        # RecordInfo 分包聚合（SN）
│   └── AlarmSubscribeHandler.java     # SUBSCRIBE/NOTIFY + 自动续订
├── model/
│   ├── Device.java / Channel.java     # 设备/通道（addr 懒提取）
│   ├── SipMessage.java                # SIP 报文模型（原始字节 + 懒解析头）
│   ├── StreamSession.java             # 媒体会话（状态机/端口/ffmpeg 引用）
│   ├── RecordInfo.java / RecordInfoQuery.java
│   └── VoiceSession.java              # 语音会话
├── netty/
│   └── UdpSipServer.java              # Netty UDP 5060 信令服务器
├── parser/
│   └── RecordInfoParser.java          # RecordInfo XML → 对象
├── service/
│   ├── DeviceRegistry.java            # 设备注册表（并发安全）
│   ├── CatalogService.java            # 平台目录 XML 构造
│   ├── AuthService.java               # 登录会话
│   ├── media/                         # 【local 引擎核心】
│   │   ├── MediaService.java          # 媒体门面（会话/看门狗/收尾/下载/状态）
│   │   ├── RtpReceiver.java           # 多端口 RTP 接收 + SinkWriter
│   │   └── RtpPortPool.java           # UDP 端口池
│   ├── local/
│   │   └── FlvBroadcast.java          # HTTP-FLV 广播（读 ffmpeg stdout）
│   ├── voice/
│   │   ├── VoiceService.java          # 对讲/广播编排
│   │   └── VoiceRtpEndpoint.java      # 每会话 RTP 端点（G.711a 环形缓冲）
│   ├── zlm/
│   │   ├── ZlmMediaService.java       # ZLM 媒体面实现
│   │   └── ZlmApiClient.java          # ZLM HTTP API 客户端
│   └── audio/
│       └── G711a.java                 # G.711a 编解码
├── sip/
│   ├── SipRequestBuilder.java         # 请求构造（SDP/XML/MANSRTSP）
│   ├── SipResponseBuilder.java        # 响应构造（401/200）
│   └── SdpParser.java                 # SDP 应答解析
└── websocket/
    └── VoiceWebSocketHandler.java     # 对讲音频 WebSocket

src/main/resources/
├── static/
│   ├── index.html / live.html / playback.html / voice.html /
│   │   voice-talk.html / login.html   # 页面（业务脚本内联于 HTML）
│   ├── css/style.css
│   └── js/                            # auth.js / common.js / mpegts.min.js / hls.min.js
├── application.yml                    # 配置
└── logback.xml                        # 日志（含 MediaService DEBUG 开关）
```

---

## 6. SIP 信令详解与命令结构

### 6.1 传输层与接收管线

- **Netty UDP**：`UdpSipServer` 单线程 `NioEventLoopGroup(1)` + `NioDatagramChannel`，`SO_REUSEADDR`，绑定 `sip.server.host:port`（默认 0.0.0.0:5060）；
- **发送**：所有外发报文（INVITE/ACK/BYE/MESSAGE/SUBSCRIBE/INFO/200）都经同一个 channel 发出，**源端口 5060**；
- **解析**：`SipMessage.parse` 按行拆 start-line + headers，`Content-Length` 定 body 边界；**body 保留原始字节**，`bodyAsXml()` 时才按 **GB18030** 解码（国标 XML 编码）；
- **线程模型**：Netty 单线程收包 → `SipRequestHandler.handle()`；路由后同步回响应，**媒体编排异步化**（RTP 看门狗、ffmpeg、端口池都在自己的线程上），绝不阻塞信令线程。

### 6.2 消息分发路由表

`SipRequestHandler`（路由器）：

**请求**（`startsWith(method + " ")`）：

| 方法 | 处理器 | 备注 |
|---|---|---|
| `REGISTER` | RegisterHandler | 401 挑战 / 摘要校验 / 200 OK |
| `MESSAGE` | DeviceMessageHandler | MANSCDP：Keepalive/Catalog/Alarm/RecordInfo/语音广播 |
| `INVITE` | MediaSignalHandler 或 VoiceSignalHandler | 按 `Content-Type`（`application/MANSRTSP` → 语音）与 `m=audio` 判定 |
| `ACK` | MediaSignalHandler + VoiceSignalHandler | 快路径（线程名 `sip-ack`）+ 完整解析 |
| `BYE` | 同上（双处理器都试） | 按 `X-Call-ID` 头定位会话 |
| `SUBSCRIBE` | AlarmSubscribeHandler | 告警订阅 |
| `NOTIFY` | **丢弃** | 对讲 OPTIONS 探测，回 200 后忽略 |
| 其他 | 丢弃 | OPTIONS 等由设备自行处理 |

**响应**（`startsWith("SIP/2.0 ")`）——**一律按 `Call-ID` 匹配会话**：

| 状态码 | 处理 |
|---|---|
| `401` | `RegisterHandler.handleUnauthorized`（存 nonce，设备带 Authorization 重注册） |
| `200` | MediaService.sessions 按 callId 查 → `handleResponse`（解析 SDP → ACK → PLAYING）；否则 VoiceService |
| 其他（BYE/INFO 的 200 等） | 丢弃 |

### 6.3 头域构造规范

`SipRequestBuilder.start()` + `appendCommonHeaders()` 统一构造：

| 头域 | 值 | 说明 |
|---|---|---|
| `Via` | `SIP/2.0/UDP <sip.server.ip>:5060;rport;branch=z9hG4bK-<8hex>` | 外发通告 IP，摄像头须可达 |
| `From` | `<sip:<fromId>@<domain>>;tag=<8hex>` | 域 = `sip.server.domain`（默认 3402000000） |
| `To` | `<sip:<toId>@<domain>>` | 目标（设备/通道/上级） |
| `Call-ID` | `<UUID>` | 全局唯一，会话主键 |
| `CSeq` | `20 INVITE` / `20 ACK` / `<seq+1> BYE` / `2 MESSAGE` / `10 SUBSCRIBE` | 按业务固定编号 |
| `Contact` | `<sip:<serverId>@<ip>:5060>` | 平台联系地址 |
| `Max-Forwards` | `70` | |
| `User-Agent` | `sip-server 2.0.0` | |
| `Subject` | `<channelId>:<ssrc>:0` | 国标点播规范 |
| `Content-Type` | `application/sdp` / `Application/MANSCDP+xml` / `Application/MANSRTSP` | 按 body 类型 |

### 6.4 标识符生成规则

| 标识符 | 规则 | 用途 |
|---|---|---|
| SSRC（实时/下载） | `0` + 8 位随机数字 + 1 位偶数，共 10 位 | 直播/下载 |
| SSRC（回放） | `1` + 8 位随机数字 + 1 位偶数，共 10 位 | 回放 |
| Call-ID | UUID | 会话主键 |
| branch / tag | `z9hG4bK-` / UUID 前 8 位十六进制 | Via / From |
| 录像查询 SN | `AtomicLong` 自增 | RecordInfo 分包聚合 |

### 6.5 注册流程（命令结构）

**第一步：设备无 Authorization 注册** → 平台回 **401** 挑战：

```
REGISTER sip:34020000002000000001@3402000000 SIP/2.0
Via: SIP/2.0/UDP 192.168.8.110:5060;rport;branch=z9hG4bK-3f2a8c1d
From: <sip:34020000001320000001@3402000000>;tag=a1b2c3d4
To: <sip:34020000002000000001@3402000000>
Call-ID: 9c8e7f6a-5b4c-4d3e-8f2a-1b0c9d8e7f6a
CSeq: 1 REGISTER
Contact: <sip:34020000001320000001@192.168.8.110:5060>
Max-Forwards: 70
User-Agent: IP Camera
Expires: 3600
Content-Length: 0
```

401 响应（`SipResponseBuilder.unauthorized`）：

```
SIP/2.0 401 Unauthorized
Via: SIP/2.0/UDP 192.168.8.110:5060;rport;branch=z9hG4bK-3f2a8c1d
From: <sip:34020000001320000001@3402000000>;tag=a1b2c3d4
To: <sip:34020000002000000001@3402000000>
Call-ID: 9c8e7f6a-5b4c-4d3e-8f2a-1b0c9d8e7f6a
CSeq: 1 REGISTER
WWW-Authenticate: Digest realm="3402000000", nonce="<uuid36>", stale=FALSE
Content-Length: 0
```

**第二步：设备带 Authorization 重注册** → 平台校验 → **200 OK**：

```
REGISTER sip:34020000002000000001@3402000000 SIP/2.0
Via: SIP/2.0/UDP 192.168.8.110:5060;rport;branch=z9hG4bK-9d8c7b6a
From: <sip:34020000001320000001@3402000000>;tag=e5f6a7b8
To: <sip:34020000002000000001@3402000000>
Call-ID: 1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d
CSeq: 2 REGISTER
Contact: <sip:34020000001320000001@192.168.8.110:5060>
Authorization: Digest username="34020000001320000001", realm="3402000000",
  nonce="<uuid36>", uri="sip:34020000002000000001@3402000000",
  response="<32位十六进制>", algorithm=MD5
Max-Forwards: 70
Expires: 3600
Content-Length: 0
```

摘要校验（`RegisterHandler`）：

```
md5(username : realm : password) == response
```

- 即 GB28181 简化摘要（两段式），nonce 不参与计算，仅作挑战记录；
- 未配置 `sip.server.password` 时不做挑战，任意注册直接 200 OK。

200 OK 响应（`SipResponseBuilder.okRegister`）：

```
SIP/2.0 200 OK
Via: ...（原请求头透传）
From: <sip:34020000001320000001@3402000000>;tag=e5f6a7b8
To: <sip:34020000002000000001@3402000000>
Call-ID: 1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d
CSeq: 2 REGISTER
Contact: <sip:34020000001320000001@192.168.8.110:5060>;expires=3600;+sip.instance="<urn:uuid>"
Date: Wed, 15 Oct 2026 09:30:00 +0800
Content-Language: zh-CN
Expires: 3600
User-Agent: sip-server 2.0.0
Content-Length: 0
```

**注销**：`Expires: 0` → 200 OK → `registry.unregister`。注册成功后设备按心跳周期（默认 180s）发 MESSAGE Keepalive，超时标记离线。

### 6.6 心跳与目录（MESSAGE）

心跳（设备 → 平台）：

```
MESSAGE sip:34020000002000000001@3402000000 SIP/2.0
Via: SIP/2.0/UDP 192.168.8.110:5060;rport;branch=z9hG4bK-4a5b6c7d
From: <sip:34020000001320000001@3402000000>;tag=f6a7b8c9
To: <sip:34020000002000000001@3402000000>
Call-ID: <uuid>
CSeq: 2 MESSAGE
Content-Type: Application/MANSCDP+xml
Content-Length: <n>

<?xml version="1.0" encoding="GB2318"?>
<Notify>
  <CmdType>Keepalive</CmdType>
  <SN>1</SN>
  <DeviceID>34020000001320000001</DeviceID>
  <Status>OK</Status>
</Notify>
```

- 处理：刷新 `lastHeartbeat`；设备未注册时自动注册（设备重启后的自愈）；
- 目录为**设备上报型**：不主动发查询，设备周期性上报 Catalog（或响应任意 MESSAGE 时回带），解析 `<Item>` 列表更新 `DeviceRegistry`，同时回 200 OK 携带平台目录 XML（`catalogService.buildCatalogXml`，带 `<CmdType>Device</CmdType>` + `sum/num`）；
- `Device.addr` 懒提取：用设备信令地址初始化一次，后续心跳只更新时间戳；
- XML 编码声明为 `encoding="GB2318"`（兼容写法），实际字节流按 **GB18030** 编解码，设备普遍容忍。

### 6.7 点播 INVITE（直播 / 回放 / 对讲）

**直播 INVITE**（`SipRequestBuilder.invite` + `buildPlaySdp`）：

```
INVITE sip:34020000001310000001@3402000000 SIP/2.0
Via: SIP/2.0/UDP 192.168.8.104:5060;rport;branch=z9hG4bK-2b3c4d5e
From: <sip:34020000001320000001@3402000000>;tag=6f7a8b9c
To: <sip:34020000001310000001@3402000000>
Call-ID: <uuid>
CSeq: 20 INVITE
Contact: <sip:34020000002000000001@192.168.8.104:5060>
Max-Forwards: 70
User-Agent: sip-server 2.0.0
Subject: 34020000001310000001:0123456789:0
Content-Type: application/sdp
Content-Length: <n>

v=0
o=34020000001320000001 0 0 IN IP4 192.168.8.104
s=Play
c=IN IP4 192.168.8.104
t=0 0
m=video 30000 RTP/AVP 96
a=recvonly
a=rtpmap:96 PS/90000
y=0123456789
```

**回放 INVITE**（`playbackInvite`）：`s=Playback`，SDP 增加 `u=<channelId>:<ssrc>` 与 `t=<startEpoch> <endEpoch>` 时间轴（来自录像查询结果的 startTime/endTime，秒级）。

**下载 INVITE**（`inviteForDownload`）：回放式 SDP 追加 `a=downloadspeed:4`。

**语音对讲 INVITE**（`VoiceService.startTalk`）：

```
v=0
o=<deviceId> <ssrc> <ssrc> IN IP4 <serverIp>
s=Play
t=0 0
m=audio <audioPort> RTP/AVP 8
a=sendrecv
a=rtpmap:8 PCMA/80000
y=<ssrc>
```

（无 `c=` 行；端口来自语音端口池 30100–30150）

**设备 200 OK 应答**（`SdpParser` 解析）：

```
SIP/2.0 200 OK
...
Contact: <sip:34020000001310000001@192.168.8.110:5060>
Content-Type: application/sdp

v=0
o=34020000001310000001 200 200 IN IP4 192.168.8.110
s=Play
c=IN IP4 192.168.8.110
t=0 0
m=video 40000 RTP/AVP 96
a=sendonly
a=rtpmap:96 PS/90000
y=0987654321
```

提取字段：`host`（c= 优先，回退 o=）、`port`（m=video/audio）、`ssrc`（`y=`）、`payloadType`（rtpmap）、`mediaType`（96=PS / 97=MPEG4 / 98=H264；音频 8=PCMA / 0=PCMU）、回放时 `startTime/endTime`（t=）。

**ACK**（`ack`，CSeq 20 ACK，From/To/Call-ID 与 INVITE 完全一致，Contact 同 Contact，Content-Length: 0）。

**BYE**（`bye`，CSeq = 原序号+1，From/To/Call-ID 不变，Content-Length: 0）——由平台主动停止或看门狗触发。

### 6.8 MANSRTSP INFO（下载倍速兜底）

会话建立后异步下发（`SipRequestBuilder.mansrtspScale`，Content-Type: Application/MANSRTSP，fire-and-forget 不等应答）：

```
INFO sip:34020000001310000001@3402000000 SIP/2.0
...
Content-Type: Application/MANSRTSP
Content-Length: <n>

MANSRTSP: SCALE 4
CSeq: 1
Range: npt=<start>-<end>
```

`download-mansrtsp-play=true` 时改用 `MANSRTSP: PLAY`（部分设备只认 PLAY）。设备不支持倍速时按 1x 推流，下载链路与进度算法均兼容。

### 6.9 录像查询（RecordInfo）

查询（`SipRequestBuilder.recordInfoQuery`，`application/MANSCDP+xml`）：

```
<?xml version="1.0" encoding="GB2318"?>
<Query>
  <CmdType>RecordInfo</CmdType>
  <SN>3</SN>
  <DeviceID>34020000001320000001</DeviceID>
  <StartTime>2026-10-15T08:49:08</StartTime>
  <EndTime>2026-10-15T08:51:08</EndTime>
  <Secrecy>0</Secrecy>
  <Type>all</Type>
</Query>
```

聚合（`RecordQueryHandler`，设备多包上报）：

- 按 `SN` 聚合（`ConcurrentHashMap<Long, PendingQuery>`），乱序到达按 SN 排序后解析；
- 8s 查询超时（`QUERY_TIMEOUT_MS`），200 包上限（`MAX_PACKETS`）；
- 完成条件：`SumNum` 收齐，或末包无 `<Item>`；
- 响应 MESSAGE 经 `DeviceMessageHandler` 识别 RecordInfo 后转本处理器；
- 重放/重复上报时原样回 200 OK，不重复完成；
- 结果标记 `partial`（超时未收齐）。

### 6.10 告警订阅（SUBSCRIBE / NOTIFY）

订阅（`AlarmSubscribeHandler.subscribe`，`/api/alarm/subscribe` 触发）：

```
SUBSCRIBE sip:34020000001320000001@3402000000 SIP/2.0
Via: SIP/2.0/UDP 192.168.8.104:5060;rport;branch=z9hG4bK-3c4d5e6f
From: <sip:34020000002000000001@3402000000>;tag=7a8b9c0d
To: <sip:34020000001320000001@3402000000>
Call-ID: sub-<uuid>
CSeq: 10 SUBSCRIBE
Event: presence
Expires: 3600
Accept: application/manscdp+xml
Contact: <sip:34020000002000000001@192.168.8.104:5060>
Max-Forwards: 70
User-Agent: sip-server 2.0.0
Content-Length: 0
```

- 设备 200 OK 携带 `Expires` → 以**设备返回值**为准；
- 自动续订周期 `max(30, expires/2)` 秒（默认 300s），`scheduledExecutor` 60s 周期扫描（daemon 线程）；
- NOTIFY（Event: presence，XML body）：

```
<?xml version="1.0" encoding="GB2318"?>
<Notify>
  <CmdType>Alarm</CmdType>
  <SN>4</SN>
  <DeviceID>34020000001320000001</DeviceID>
  <AlarmPriority>1</AlarmPriority>
  <AlarmTime>2026-10-15T09:30:00</AlarmTime>
  <AlarmDescription>MotionDetect</AlarmDescription>
</Notify>
```

- 提取 `notify-cmd-type` == Alarm → `DeviceMessageHandler.handleAlarm` 记录日志；
- NOTIFY 一律回 200 OK，body 回带 `<notify-cmd-type>Alarm</notify-cmd-type>`；
- `/api/alarm/unsubscribe` 发 `Expires: 0` 退订并取消续订任务。

### 6.11 语音广播（MANSCDP Broadcast MESSAGE）

`/api/voice/broadcast/start` 触发（`SipRequestBuilder.broadcastMessage`）：

```
MESSAGE sip:34020000001320000001@3402000000 SIP/2.0
...
Content-Type: Application/MANSCDP+xml
Content-Length: <n>

<?xml version="1.0" encoding="GB2318"?>
<Notify>
  <CmdType>Broadcast</CmdType>
  <SN>2</SN>
  <SourceID>34020000002000000001</SourceID>
  <TargetID>34020000001310000001</TargetID>
</Notify>
```

`/stop` 时 TargetID 置空下发停止。

### 6.12 响应匹配机制

所有响应一律按 **Call-ID** 定位会话：

- 401 → `RegisterHandler.pendingChallenges`（ConcurrentHashMap）→ 设备带 Authorization 重注册时**交叉核对**（Map 里无该 callId 的记录会打警告但仍校验）；最终无 Authorization 的 REGISTER → `404 Not Found`；
- 200 → MediaService.sessions 按 callId 查 → `handleResponse`（解析 SDP → ACK → PLAYING）；查不到再试 VoiceService；
- BYE/INFO 的 200 → 丢弃（fire-and-forget 语义）；
- 边缘兜底：200 早于会话 Map 登记完成时，`pendingSdp` 仅记录 SSRC（ACK 丢弃，设备可能超时重传）。

### 6.13 SIP 定时器汇总

| 定时器 | 值 | 说明 |
|---|---|---|
| 心跳超时 | 180s（`heartbeat-timeout`） | 设备离线判定 |
| 告警续订周期 | max(30, expires/2) 秒 | SUBSCRIBE 到期前续订 |
| 告警扫描周期 | 60s | scheduledExecutor |
| 录像查询超时 | 8s | RecordInfo 聚合 |
| ffmpeg 就绪等待 | 10s | 回放 |
| 回放结束看门狗 | 15s | 无 RTP 判定结束 |
| 下载空闲看门狗 | 15s（首包 60s） | 无 RTP 主动 BYE |
| 下载文件就绪等待 | 180s（+终态 30s） | FlvController |

---

## 7. 问题与解决方法

### 7.1 本轮修复（local 模式收尾链路）

#### 问题 1：下载的 MP4 无法播放（moov atom not found）

- **现象**：ffprobe 报 `moov atom not found` + `Invalid data found when processing input`；文件 65MB 全是 mdat 裸流，任何播放器打不开；
- **根因**：`MediaService.cleanup()` 顺序写反——先 `p.destroy()` 后 `rtpReceiver.stop()`。Windows 上 `destroy()` 即 `TerminateProcess`，ffmpeg 瞬间死亡；而下载用 `-movflags +faststart`，ffmpeg 必须**先收到 stdin EOF**，才会 flush 编码队列 → 写 moov → 二次重写文件把 moov 挪到头部。进程被杀，三步全不发生，`.part` 改名后就是没有 moov 的损坏文件。看门狗收尾、正常停止、用户停止，走的都是这条路径；
- **验证**：ffprobe/ffmpeg 全量解码实锤；比对服务进程启动时间确认复现文件均产自旧代码；
- **修复**：调换顺序——`rtpReceiver.stop()`（关 stdin 给 EOF）→ `waitFor(6s/60s)` → `destroyForcibly` 仅作超时兜底；确认全代码库无第二处强杀 ffmpeg 的路径，所有停止路径汇聚同一 `cleanup()`。

#### 问题 2：SinkWriter 阻塞写时 interrupt 无效，stdin 永远不关

- **根因**：`close()` 原来只置标志位 + `interrupt()`。写线程若阻塞在 `out.write()`（ffmpeg 消费慢、管道满），`interrupt` **无法唤醒阻塞写**，stdin 不关 → ffmpeg 收不到 EOF → 不 flush/不写 moov；
- **修复**：`close()` 增加直接 `out.close()` 兜底（与写线程 finally 的 close 重复执行无害，幂等）。

#### 问题 3：下载进度百分比"一会变大一会变小"

- **根因**：两条。① 阶段 1 有两个不同分母的进度源——ffmpeg 输出时间/录像时长（真实）vs 已耗时/预期推流时长（估算）；设备不按 4x 推流而降 1x 时，估算先冲到 95%，真实进度接上时跌回，轮询失败的瞬间还会在两源间反复横跳；② 阶段 2（文件传输）`setDlBar(0)` 从 95% 一夜回到 0% 再重爬；
- **修复**：① `setDlBar` 单调钳制（`dlMaxPct` 只升不降）；② 双源**取较小值**——按协商倍速推流时两者恒等，设备降速时自动以真实为准；空窗期估算封顶 10%，防止估算先冲高被钳制冻结；③ 阶段 2 映射 95%→100%，文字百分比与进度条一致。

#### 问题 4：遮罩层进度字体"太深看不见"

- **根因**：浏览器缓存了**旧版 style.css**（dl 遮罩样式块是未提交的新增，git 里最后一次提交还是 9-20）。旧版没有 `.dl-card` 白底、没有 `.dl-pct` 配色 → 遮罩卡片透明，深色文字直接叠在 `rgba(0,0,0,.45)` 黑蒙层上。页面其他地方新旧样式一致，所以只有遮罩"露馅"；
- **修复**：样式链接加版本参数 `?v=20261015`，URL 改变后浏览器必须重新拉取，缓存问题确定性解决。

### 7.2 重构期问题（local 引擎）

| # | 问题 | 解决方案 |
|---|---|---|
| 1 | 单端口共享，多会话只能靠 SSRC 区分，设备 SSRC 复用即串流 | **每会话独立 UDP 端口**（RtpPortPool），socket 专属于单会话，包直接归属，无需 SSRC 解复用 |
| 2 | ffmpeg 消费慢时阻塞 RTP 接收线程，卡死整个端口 | **SinkWriter 有界队列 + dropOldest**：慢下游只丢帧（overflows 计数），永不阻塞接收 |
| 3 | 播放器断连后 ffmpeg 进程泄漏 | FlvBroadcast 最后一个 Reader detach → `stopIfNoClients` 停会话 → ffmpeg EOF 退出 |
| 4 | 两个浏览器同时打开时重复 bootstrap ffmpeg | `broadcastLock` 串行化；未就绪返回 503 + `Retry-After: 1`（前端自动重试） |
| 5 | 设备静默停推但不发 BYE，下载永远卡着 | **15s 下载空闲看门狗**（首包宽限 60s）→ 主动 BYE 收尾 |
| 6 | 回放放到尽头设备不发任何信号 | **15s 回放结束看门狗** → 强制 BYE + `markStopped(-1)` |
| 7 | ffmpeg 启动即死（编解码器缺失等）无感知 | `ffmpegCrashed` 标志（stderr EOF + waitFor 失败），清理时记录，前端 504 |
| 8 | `stopSet` 无界增长（长跑内存泄漏） | 有界 FIFO（`LinkedHashSet` + 上限 4096，满则淘汰最旧） |
| 9 | 端口池耗尽时行为不明 | 快速失败 `-1` → MediaService 回 502，错误明确 |
| 10 | 外部依赖 ZLMediaKit | 媒体面抽象为模式适配（local/zlm），`sip.media.mode` 一键切换，信令层不变 |

### 7.3 1.0 时代问题（已在 2.0 解决）

| # | 问题 | 解决方案 |
|---|---|---|
| 1 | 无注册鉴权，任意设备可注册 | 401 挑战 + GB28181 简化摘要校验 + 404 兜底 |
| 2 | 设备离线无感知 | 心跳超时（180s）标记离线；未注册设备 MESSAGE 自动注册（设备重启自愈） |
| 3 | 录像查询多包乱序丢失 | RecordInfo 按 SN 聚合 + 8s 超时 + partial 标记 |
| 4 | 告警订阅无续订，订阅过期即失效 | SUBSCRIBE Event: presence + 300s 自动续订（以设备 Expires 为准） |
| 5 | 语音对讲单端口冲突 | 语音端口池（30100–30150）每会话独立端口 |
| 6 | `UdpSipServer` 与 `SipRequestHandler` 循环依赖 | `@Lazy` 注入打破循环 |
| 7 | GB18030 XML 解析乱码 | body 保留原始字节，`bodyAsXml()` 统一按 GB18030 解码 |
| 8 | 下载进度假死（到顶不动） | 真实进度：ffmpeg `time=` 解析 + 空闲看门狗主动收尾 |

---

## 8. 重构后优势

1. **零第三方媒体服务依赖**：local 模式不依赖 ZLMediaKit/nginx-rtmp，一个 jar + 一个 ffmpeg 即可运行，部署从"三组件"降为"单进程"；
2. **端口级会话隔离**：每会话独立 UDP 端口（100 路并发），从根上消除 1.0 的 SSRC 串流；端口池统一分配/回收，耗尽行为明确（502）；
3. **文件完整性保证**：所有停止路径统一"先断输入、再等退出、强杀兜底"，下载的 MP4 必带 moov 索引 + faststart，可任意播放器打开、可边下边播；
4. **背压安全**：SinkWriter 有界队列 + 丢帧策略，慢客户端/慢磁盘绝不反压到 RTP 接收，媒体链路不会互相拖垮；
5. **真实进度反馈**：ffmpeg `time=` 解析 + 单调不回退的双源取小算法，下载进度全程可信、观感稳定；
6. **低资源直播**：直播/回放视频流 copy 直通（PS→FLV 转封装），CPU 占用极低，单机可跑满端口池上限；
7. **完善的自愈体系**：心跳离线检测、设备静默检测（15s）、回放结束检测（15s）、ffmpeg 崩溃检测、文件就绪等待，异常场景全部有兜底路径；
8. **信令/媒体分层清晰**：handler 按职责单一化（注册/消息/媒体信令/语音信令/录像/告警），MediaService 门面收敛会话生命周期，新增业务只需加 handler；
9. **双模式可切换**：local/zlm 同一套门面接口，`sip.media.mode` 配置切换，方便对比验证与灰度迁移；
10. **配置驱动**：端口池、倍速、空闲阈值、ffmpeg 路径、告警续订周期全部配置化，适配不同厂商设备行为差异。

---

## 9. HTTP API 一览

### 页面接口（DeviceController，`/api/**`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/login` | 页面登录 |
| POST | `/api/logout` | 退出 |
| GET | `/api/auth/check` | 登录态校验 |
| GET | `/api/devices` | 在线设备列表 |
| GET | `/api/status` | 服务状态（SIP/媒体/端口池） |
| GET | `/api/media` | 全部会话快照 |
| POST | `/api/play/start` | 直播点播 |
| POST | `/api/play/stop` | 停止直播 |
| GET | `/api/play/sessions` | 直播会话列表 |
| POST | `/api/playback/start` | 录像回放 |
| POST | `/api/playback/stop` | 停止回放 |
| GET | `/api/playback/sessions` | 回放会话列表 |
| POST | `/api/download/start` | 录像下载（返回文件名/大小/状态） |
| GET | `/api/records/query` | 录像查询 |
| GET | `/api/records` | 录像文件列表 |
| POST | `/api/voice/start` | 语音对讲 |
| POST | `/api/voice/stop` | 停止对讲 |
| GET | `/api/voice/sessions` | 对讲会话列表 |
| POST | `/api/alarm/subscribe` | 告警订阅 |
| POST | `/api/alarm/unsubscribe` | 退订 |
| GET | `/api/alarm/subscriptions` | 订阅列表 |

### 流媒体接口（FlvController）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/live/{deviceId}/{channelId}.flv` | 直播 FLV 流（多客户端共享一 ffmpeg） |
| GET | `/playback/{deviceId}/{channelId}/{start}/{end}.flv` | 回放 FLV 流 |
| GET | `/api/download/file?deviceId=&channelId=&filename=` | 下载 MP4（等待收尾最长 180s，Content-Length 流式返回） |
| GET | `/api/download/status?deviceId=&channelId=` | 下载进度 `{active, size, outTimeSec, updatedAt}` |
| GET | `/api/download/list` | 下载目录文件列表 |

### Open API（OpenApiController，`/api/v1/**`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/devices` | 设备列表 |
| POST | `/api/v1/play/start` / `/api/v1/play/stop` | 直播点播/停止 |
| POST | `/api/v1/playback/start` / `/api/v1/playback/stop` | 回放/停止 |
| POST | `/api/v1/download/start` | 录像下载 |
| POST | `/api/v1/records/query` | 录像查询 |
| GET | `/api/v1/play/live/url` | 直播播放地址（local 返回 `/api/flv/...`，zlm 返回 webRTC 地址） |

鉴权：请求头 `X-API-Key: <key>`（`sip.openapi.api-keys` 配置，仅拦截 `/api/v1/**`，页面接口不受影响）。

### WebSocket

| 路径 | 说明 |
|---|---|
| `/ws/voice` | 对讲音频通道（G.711a 上行/下行） |

---

## 10. 配置说明

`application.yml` 关键配置：

```yaml
sip:
  server:
    server-id: 34020000002000000001   # 平台国标编号（20位）
    host: 0.0.0.0                     # SIP 监听地址
    port: 5060                        # SIP 端口
    ip: 192.168.8.104                 # 外发通告 IP（摄像头须可达）
    domain: 3402000000                # SIP 域（默认 server-id 前10位）
    password: ''                      # 注册密码（空则不挑战）
    heartbeat-timeout: 180            # 心跳超时（秒）
  media:
    mode: local                       # local | zlm
    local:
      port-pool-start: 30000          # RTP 端口池起
      port-pool-end: 30099            # RTP 端口池止
      ffmpeg-path: ffmpeg             # ffmpeg 可执行文件（PATH 或绝对路径）
      download-dir: ./downloads       # 下载目录
      download-speed: 4               # 下载倍速（SDP a=downloadspeed）
      download-mansrtsp-play: false   # INFO 用 PLAY 替代 SCALE
      download-idle-seconds: 15       # 下载空闲判定（秒）
      hls-dir: ./hls
    zlm:                              # zlm 模式参数
      http-url: http://127.0.0.1:80
      secret: ...
  voice:
    enabled: true
    port-pool-start: 30100            # 语音端口池起
    port-pool-end: 30150              # 语音端口池止
  openapi:
    enabled: true
    api-keys: [ your-key ]
```

日志（logback.xml）：默认 INFO；`com.example.sip.service.media` 包可开 DEBUG 输出 ffmpeg stderr 全文与会话状态细节，排查媒体链路问题时打开。

---

## 11. 前端页面

纯静态 HTML（无构建步骤，业务脚本内联于各 HTML，`js/` 仅存放公共库）：

| 页面 | 功能 |
|---|---|
| `index.html` | 设备管理主页（设备/通道列表、目录） |
| `live.html` | 实时预览（flv.js 播放 FLV） |
| `playback.html` | 录像回放 + 下载（两阶段进度遮罩：转码/传输） |
| `voice.html` | 语音广播（Broadcast MESSAGE 控制） |
| `voice-talk.html` | 语音对讲（WebSocket 音频收发） |
| `login.html` | 登录页 |
| `js/auth.js` + `js/common.js` | 登录态与公共请求封装 |
| `js/mpegts.min.js` / `js/hls.min.js` | flv.js / hls.js 播放器库 |

**下载进度遮罩**（playback.html）：阶段 1 轮询 `/api/download/status`（2s 间隔）展示 ffmpeg 真实输出进度（封顶 95%）；阶段 2 用 fetch 流式读取 `/api/download/file`，按 Content-Length 映射 95%→100%；全程单调不回退。

---

## 12. 已知限制与后续方向

1. **端口容量**：端口池默认 100 路（30000–30099），超限回 502；大规模部署需扩大范围或做会话排队；
2. **下载重编码开销**：下载用 libx264 ultrafast 重编码，多路并发下载时 CPU 是瓶颈；后续可评估 copy + Annex-B 修正方案；
3. **报警仅记录日志**：告警 NOTIFY 当前只打日志，未落库/未推送 WebSocket；
4. **页面脚本内联**：业务逻辑内联在 HTML 中（`js/*-page.js` 为历史遗留引用，已不存在），后续可拆分为模块化脚本；
5. **语音单端口模式**：`VoiceProperties` 当前仅多端口模式（端口池），单端口兼容字段预留未启用；
6. **回放拖动**：回放 FLV 不支持进度拖动（国标云端回放无 seek 信令），只能整段播放；
7. **README 中 `html` 结构为人工维护**，代码演进后需同步更新本文件。

---

*文档版本：2.0 ｜ 对应代码：local 模式重构版（含 moov 收尾修复 + 进度单调化 + CSS 缓存修复）*
