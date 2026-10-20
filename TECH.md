# sip-server 技术文档

> GB/T 28181-2022 摄像头接入与实时预览服务器 — 深度技术文档
>
> 面向需要理解/修改本项目的开发者，逐模块剖析实现细节。拉流（点播/媒体）链路为第 5 章，历史回放为第 12 章。

## 目录

1. [项目概述](#1-项目概述)
2. [总体架构](#2-总体架构)
3. [信令接入层（UAS 侧）](#3-信令接入层uas-侧)
4. [报警订阅（SUBSCRIBE/NOTIFY）](#4-报警订阅subscribenotify)
5. [拉流模块（重点）](#5-拉流模块重点)
6. [数据模型](#6-数据模型)
7. [HTTP API 明细](#7-http-api-明细)
8. [配置项](#8-配置项)
9. [线程模型总览](#9-线程模型总览)
10. [关键问题与设计决策](#10-关键问题与设计决策)
11. [边界与限制](#11-边界与限制)
12. [历史回放（PlaybackService）](#12-历史回放playbackservice)
13. [录像查询（MANSCDP RecordInfo）](#13-录像查询manscdp-recordinfo)
14. [ZLMediaKit 媒体面（zlm 模式）](#14-zlmediakit-媒体面zlm-模式)
15. [兼容性与后续演进建议](#15-兼容性与后续演进建议)
16. [当前系统核心链路总结](#16-当前系统核心链路总结)
17. [当前项目最重要的技术结论](#17-当前项目最重要的技术结论)
18. [附录：设备端音频配置备注](#18-附录设备端音频配置备注2026-09-17)
19. [语音广播与对讲（VoiceBroadcastService）](#19-语音广播与对讲voicebroadcastservice)
20. [文档维护说明](#20-文档维护说明)

---

## 1. 项目概述

基于 **Spring Boot 3.3.5 + Netty 4.1 + 外部 ffmpeg 进程** 的国标（GB/T 28181-2022）摄像头接入服务器。

核心能力闭环：

```text
SIP 信令接入（注册/心跳/目录/点播/报警）
        ↓
RTP/PS 媒体接收（动态 UDP 端口）
        ↓
ffmpeg 解封装转码（PS → FLV，H.264 直封 + 音频转 AAC）
        ↓
HTTP-FLV 实时预览（mpegts.js，~1s 延迟）
```

* **无数据库 / 无中间件**：设备表、会话表全部内存态（`ConcurrentHashMap`），重启清空。
* **单通道单会话**：同 `deviceId:channelId` 同时只允许一路点播。
* **定位**：轻量级单机接入/联调服务器。
* **媒体模式**：当前支持 `local` 自研媒体链路，可选 `zlm` ZLMediaKit 媒体链路。
* **实时点播与历史回放**：两者均通过 GB/T 28181 SIP INVITE 建立媒体会话，但 SDP 与会话生命周期存在差异。

### 1.1 技术选型

| 类别       | 选型                         | 理由                            |
| -------- | -------------------------- | ----------------------------- |
| 信令网络     | Netty `NioDatagramChannel` | 单 Channel 收发 UDP SIP，5060 端口  |
| 媒体接收     | JDK `DatagramSocket`       | 每次点播一个独立 socket，动态端口，与会话同生命周期 |
| 转码       | 外部 ffmpeg 进程               | PS demux 能力 + 进程隔离            |
| HTTP-FLV | 原生 Servlet 长连接             | chunked 推送，无流媒体服务器依赖          |
| 播放器      | mpegts.js                  | 浏览器 MSE 解 FLV                 |
| 可选媒体服务器  | ZLMediaKit                 | 可替换 local 自研媒体处理链             |

---

## 2. 总体架构

```text
                  摄像头 / 国标设备（GB/T 28181）
                        │
        ┌───────────────┴────────────────┐
   SIP 信令 UDP 5060                RTP/PS 媒体 UDP 动态端口
        │                                   │
        ▼                                   ▼
┌──────────────────┐              ┌─────────────────────────┐
│   UdpSipServer   │              │      RtpReceiver        │
│  (Netty UDP)     │              │  · DatagramSocket/端口   │
└────────┬─────────┘              │  · RTP 头解析/丢包估算   │
         │                        │  · SinkWriter 异步分发   │
         ▼                        └───────────┬─────────────┘
┌──────────────────┐                          │ RTP 载荷(PS)
│ SipRequestHandler│                          ▼
│  方法分发/响应    │              ┌─────────────────────────┐
│  报警订阅管理    │              │   ffmpeg 进程（外部）     │
└──┬────┬─────────┘              │  stdin ← PS 字节流       │
   │    │                        │  stdout → FLV 字节流     │
   ▼    ▼                        │  stderr → 日志线程       │
Registry PlayService             └───────────┬─────────────┘
(设备表) (点播会话)                            │ stdout
        │                                     ▼
        │                        ┌─────────────────────────┐
        │                        │     FlvBroadcast         │
        │                        │  · bootstrap 缓存        │
        │                        │  · Subscriber 广播      │
        │                        └───────────┬─────────────┘
        ▼                                    │
┌──────────────────────────────────────────────────────────┐
│              Spring MVC HTTP :8080                        │
│  /api/*（设备/点播/报警）  /live/*.flv（FLV 长连接）  /（页面）│
└──────────────────────────────────────────────────────────┘
```

### 2.1 数据流向（四个平面）

| 平面  | 通道                       | 数据                                                           |
| --- | ------------------------ | ------------------------------------------------------------ |
| 信令面 | UDP 5060（Netty）          | SIP：REGISTER/MESSAGE/INVITE/ACK/BYE/SUBSCRIBE/NOTIFY/OPTIONS |
| 媒体面 | UDP 动态端口（DatagramSocket） | RTP/PS 包                                                     |
| 转码面 | 进程管道 stdin/stdout        | PS 字节流进 → FLV 字节流出                                           |
| 分发面 | HTTP 长连接                 | FLV chunk 推送所有订阅者                                            |

---

## 3. 信令接入层（UAS 侧）

`UdpSipServer`（Netty）收到 UDP 报文后交 `SipRequestHandler.handle()` 统一分发。

### 3.1 分发逻辑

```text
SipMessage.parse(data)
   ├─ method 为空 → 丢弃
   ├─ 状态行（SIP/2.0 开头）→ handleSipResponse()
   │     ├─ CSeq 含 SUBSCRIBE → handleSubscribeResponse()
   │     └─ 其他 → PlayService / PlaybackService / ZlmMediaService
   └─ 请求行 → 按 method 分发：
         REGISTER / MESSAGE / INVITE / ACK / BYE / OPTIONS / NOTIFY
```

### 3.2 各方法处理

| 方法                    | 处理                                                                | 关键点                         |
| --------------------- | ----------------------------------------------------------------- | --------------------------- |
| `REGISTER`            | `DeviceRegistry.register/unregister`                              | Expires=0 注销；Contact 解析设备地址 |
| `MESSAGE (Keepalive)` | `DeviceRegistry.keepalive`                                        | 未知设备自动登记                    |
| `MESSAGE (Catalog)`   | `CatalogService.buildCatalogXml`                                  | GB2312 XML 响应               |
| `MESSAGE (Alarm)`     | `handleAlarm`                                                     | 报警字段逐项记录                    |
| `INVITE`（设备发起）        | 开 RTP 端口 + 200 OK+SDP                                             | 设备主动推流场景                    |
| `BYE`                 | `PlayService.handleDeviceBye` / `PlaybackService.handleDeviceBye` | 按 Call-ID 匹配会话并清理           |
| `NOTIFY`              | `handleNotify`                                                    | 报警上报                        |
| `OPTIONS`             | 200 OK                                                            | —                           |
| 其他                    | 400 Bad Request                                                   | —                           |

### 3.3 关键实现细节

* **SipMessage 模型**：body 以 `ISO-8859-1` 无损保存原始字节，需要时再按照 GB18030 等编码解析。
* **状态行解析**：直接从原始字节第一行解析 SIP 状态码。
* **XML 安全**：`DocumentBuilderFactory` 启用 XXE 防护。
* **sipChannel 缓存**：收到 SIP 报文时保存 Netty Channel，使服务器主动发送 SUBSCRIBE 等请求时可以复用正确的通信通道。

---

## 4. 报警订阅（SUBSCRIBE/NOTIFY）

服务器作为 UAC 主动向设备订阅报警。

### 4.1 订阅生命周期

```text
POST /api/alarm/subscribe
        ↓
subscribeAlarm(deviceId)
        ↓
发送 SUBSCRIBE
        ↓
设备 200 OK
        ↓
active=true
        ↓
设备 NOTIFY
        ↓
handleNotify()
        ↓
解析 Alarm XML
        ↓
业务层处理 / MQTT 等
```

定时续订：

```text
@Scheduled(60s)
    ↓
检查剩余有效期
    ↓
< 300s
    ↓
原 Dialog 内重新发送 SUBSCRIBE
```

### 4.2 关键点

* 续订使用原 Call-ID。
* 携带设备返回的 To Tag。
* CSeq 持续递增。
* 200 OK 与 `Subscription-State` 均用于校准订阅有效期。
* `terminated` 后移除本地订阅状态。

---

# 5. 拉流模块（重点）

拉流 = **服务器向摄像头发起实时点播 INVITE，并接收 RTP/PS 媒体流，再通过 ffmpeg 转成 FLV，最终使用 HTTP-FLV 对外提供播放。**

涉及：

* `PlayService`
* `RtpReceiver`
* `FlvBroadcast`
* `FlvController`
* `SipRequestBuilder`

---

## 5.1 点播完整时序

```text
浏览器
  │
  │ GET /live/{device}/{channel}.flv
  ▼
FlvController
  │
  │ restartSession()
  ▼
PlayService
  │
  ├─ 停止旧会话
  ├─ 创建 RTP UDP 端口
  ├─ 创建 Call-ID / Branch / Tag / SSRC
  ├─ 注册 StreamSession
  └─ 发送 INVITE
           │
           ▼
        摄像头
           │
           │ 200 OK + SDP
           ▼
      PlayService
           │
           ├─ 启动 ffmpeg
           ├─ attach RTP Sink
           ├─ 创建 FlvBroadcast
           ├─ ACK
           └─ PLAYING
                    │
                    ▼
               RTP / PS
                    │
                    ▼
              RtpReceiver
                    │
                    ▼
                SinkWriter
                    │
                    ▼
              ffmpeg stdin
                    │
              PS → FLV
                    │
              ffmpeg stdout
                    ▼
             FlvBroadcast
                    │
                    ▼
             HTTP-FLV 浏览器
```

---

## 5.2 PlayService 会话管理与并发控制

### 5.2.1 核心数据结构

```text
sessionsByCallId
    ConcurrentHashMap<callId, StreamSession>

sessionsByKey
    ConcurrentHashMap<deviceId:channelId, StreamSession>

flvBroadcasts
    ConcurrentHashMap<callId, FlvBroadcast>

playLocks
    ConcurrentHashMap<deviceId:channelId, Object>
```

### 5.2.2 四个入口方法

| 方法                  | 调用方               | 行为           |
| ------------------- | ----------------- | ------------ |
| `startPlay`         | `/api/play/start` | 停旧 → 建新      |
| `stopPlay`          | `/api/play/stop`  | BYE → 清理     |
| `getOrStartSession` | 内部复用              | PLAYING 复用   |
| `restartSession`    | FLV 请求            | 强制停止旧会话并重新建立 |

### 5.2.3 并发安全

所有同一通道的操作都通过：

```java
synchronized (lockOf(deviceId, channelId))
```

保证：

```text
请求 A
    ↓
获取 deviceId:channelId 锁
    ↓
停止旧会话
    ↓
创建新会话
    ↓
释放锁

请求 B
    ↓
等待锁
    ↓
看到新的会话
```

避免多个浏览器同时刷新时创建多个 SIP 会话。

---

## 5.2.4 startPlay 与 getOrStartSession：local 模式重复会话问题的演进与结论

`DeviceController.playStart`（POST /api/play/start）与 `OpenApiController`（POST /api/v1/live/start）共用同一处双模式判断（`playbackStart` 与 `/api/v1/playback/start` 为回放同构）：

```java
StreamSession s = mediaProps.isZlm()
        ? zlmMediaService.getOrStartSession(deviceId, channelId)  // zlm：幂等复用
        : playService.startPlay(deviceId, channelId);             // local：停旧建新
```

### 会话获取的三种语义

| 方法 | 语义 | 当前使用方 |
| ---- | ---- | --------- |
| `startPlay` / `startPlayback` | **停旧建新**：无条件停旧会话，发起新 INVITE | playStart / playbackStart 的 local 分支 |
| `getOrStartSession` / `getOrStartPlayback` | **幂等复用**：PLAYING 直接复用（回放需同区间），无则新建 | FlvController 拉流、zlm 分支 |
| `restartSession` | **强制重建**：拆会话（BYE）再建新 | FlvController 旧方案，已注释弃用 |

### 问题演进：双 INVITE → 残留重复 1 次会话

**阶段一（FlvController 用 restartSession）**：先调点播接口再拉 FLV，设备被 INVITE 两次——接口建会话①，拉流瞬间①被拆、重建会话②，会话①纯属浪费。

**阶段二（FlvController 已改复用，现状）**：拉流改用 `getOrStartSession`，直接复用①不再重建，双 INVITE 的第二段已消除。但残留重复的一段来自接口侧——`startPlay` 停旧建新：

```text
① POST /api/play/start → startPlay()：停旧建新（INVITE）→ 会话①
② GET /live/**.flv → getOrStartSession()：复用①（不再 INVITE）
③ 刷新页面/再次调 playStart → startPlay() 又停旧建新（重复 1 次会话）
   —— 且拆掉正被浏览器 FLV 消费的会话①，广播中断重建
```

### 结论（方案已定，代码暂未改）

> **去掉 `playStart` / `playbackStart` 中的 `isZlm()` 三元判断，统一直接采用 zlm 分支的幂等复用语义（`getOrStartSession` / 同区间 `getOrStartPlayback`），重复会话问题即全部解决。**

统一后无论何种模式：已有 PLAYING 会话直接复用（0 次 INVITE），无会话才新建（1 次），接口与拉流端点操作**同一个会话**，全程仅一次 INVITE——刷新页面、重复调用接口均不再重复建会话。相较旧"强制重建换新鲜流"设计：

* 重复 INVITE 全部消除（设备侧压力与会话面板抖动同步消失）；
* 接口不再拆掉正在被 FLV 广播消费的会话（startPlay 拆会话导致画面中断）；
* 双模式行为统一，接口语义收敛为"确保有会话"。

> 当前仅记录方案：FlvController 已先行切换复用，`DeviceController` / `OpenApiController` 的三元判断保留待改。

---

## 5.3 INVITE 重传与超时清理

UDP SIP 不可靠，因此 INVITE 不能只发送一次。

当前设计：

```text
INVITE
  │
  ├─ +500ms
  ├─ +1s
  ├─ +2s
  ├─ +4s
  │
  └─ +10s Timer B
          ↓
      INVITING 仍未成功
          ↓
        cleanup()
```

每次重传前检查：

```text
state == INVITING
&&
sessionsByCallId.get(callId) == session
```

如果已经收到 200 OK、已经停止或已经被新会话替换，则不再重传。

### 5.3.1 SSRC 随机化

每次 INVITE 使用新的 SSRC。

目的：

* 避免设备残留会话；
* 避免新旧 RTP 流被设备认为是同一会话；
* 尤其兼容部分大华设备对旧会话状态较敏感的情况。

实时点播和历史回放可以使用不同首位约定，例如：

```text
实时点播：0xxxxxxxxx
历史回放：1xxxxxxxxx
```

具体格式以当前 `SipRequestBuilder` 实现为准。

---

## 5.4 200 OK 处理与媒体链路启动

收到设备 200 OK 后：

```text
① 校验 Call-ID / Session 是否仍然有效
② 保存 To Tag
③ 保存 Contact
④ 解析 Answer SDP
⑤ 启动 ffmpeg
⑥ attachSink()
⑦ 创建 FlvBroadcast
⑧ 发送 ACK
⑨ state = PLAYING
```

### 为什么 ffmpeg 必须在 ACK 前准备好

设备收到 ACK 后可能立即发送 RTP。

如果：

```text
ACK
  ↓
设备立即推 RTP
  ↓
ffmpeg 还没启动
```

则首个 PS 包可能丢失，尤其是：

* PS Pack Header
* PAT/PMT
* H.264 SPS/PPS
* 首帧

最终表现为浏览器无法播放。

因此当前顺序必须保持：

```text
ffmpeg
 ↓
attachSink
 ↓
FlvBroadcast
 ↓
ACK
 ↓
PLAYING
```

---

# 5.5 RtpReceiver — RTP/PS 接收与分发

### 5.5.1 Session

```java
sessions: ConcurrentHashMap<Integer, Session>
```

每个 RTP UDP 端口对应一个 Session：

```text
Session
 ├─ DatagramSocket
 ├─ packets
 ├─ bytes
 ├─ lostPackets
 ├─ lastSeq
 ├─ payloadType
 ├─ ssrc
 └─ sinks
```

### 5.5.2 RTP 接收

```text
DatagramSocket.receive()
        ↓
RTP Header 解析
        ↓
得到 Payload
        ↓
SinkWriter.offer()
        ↓
ffmpeg stdin
```

RTP Header：

```text
12 字节基础头
+ CSRC
+ Extension
```

解析：

* Version
* Padding
* Extension
* CSRC Count
* Marker
* Payload Type
* Sequence
* Timestamp
* SSRC

### 5.5.3 PS 判断

正常 GB/T 28181 RTP/PS 流通常可以看到：

```text
00 00 01 BA
```

即 MPEG-PS Pack Header。

这可以作为设备是否真正推送 PS 数据的快速诊断依据。

---

## 5.5.4 SinkWriter

结构：

```text
RTP 收包线程
      │
      │ copy
      ▼
ArrayBlockingQueue
      │
      ▼
sink-write-<port>
      │
      ▼
ffmpeg stdin
```

队列满时：

```text
丢弃最旧数据
保留最新数据
```

不能让 RTP 收包线程阻塞。

否则：

```text
RTP 收包阻塞
    ↓
UDP 内核缓冲堆积
    ↓
丢包
    ↓
PS 损坏
    ↓
ffmpeg 解码失败
```

---

## 5.5.5 RTP Session 正确释放顺序

这里是当前 local 媒体链非常重要的实现细节。

正确顺序：

```text
detachSink(port)
        ↓
关闭 ffmpeg stdin
        ↓
SinkWriter 停止
        ↓
ffmpeg 收到 EOF / 由上层结束
        ↓
关闭 DatagramSocket
        ↓
sessions.remove(port)
```

`RtpReceiver.stop()` 必须先拿到 Session，再执行 `detachSink()`。

错误实现：

```java
Session s = sessions.remove(port);
detachSink(port);
```

因为 `detachSink()` 内部如果通过：

```java
sessions.get(port)
```

获取 Session，此时已经拿不到对象，导致 Sink 没有真正解除。

正确实现：

```java
public void stop(int port) {
    Session s = sessions.get(port);
    if (s == null) {
        return;
    }
    detachSink(port);
    s.socket.close();
    sessions.remove(port, s);
    log.info("[28181] 媒体接收端口已释放: port={} packets={} bytes={}",
            port, s.packets, s.bytes);
}
```

这是一个典型的：

> **先业务解绑，再删除容器对象**

原则。

---

# 5.6 ffmpeg 转码进程管理

## 5.6.1 实时点播命令

```bash
ffmpeg -loglevel info \
  -f mpeg -i pipe:0 \
  -map 0:v:0 -map 0:a:0? \
  -c:v copy \
  -c:a aac -b:a 64k \
  -flvflags no_duration_filesize \
  -f flv pipe:1
```

说明：

* `pipe:0`：stdin
* `pipe:1`：stdout
* `-f mpeg`：PS/MPEG-Program Stream demuxer
* H.264：直接 copy
* 音频：统一转换 AAC
* 无音频时 `?` 允许正常工作
* stdout 必须保持纯 FLV 二进制数据

---

## 5.6.2 三流严格分离

| 流      | 用途              |
| ------ | --------------- |
| stdin  | RTP/PS → ffmpeg |
| stdout | ffmpeg → FLV    |
| stderr | ffmpeg 日志       |

必须：

```java
processBuilder.redirectErrorStream(false);
```

不能：

```java
redirectErrorStream(true);
```

否则：

```text
ffmpeg 日志
+
FLV 二进制
        ↓
stdout
        ↓
播放器
        ↓
FLV Probe 失败
```

---

## 5.6.3 FFmpeg 资源释放

正常停止：

```text
① detachSink(port)
② 关闭 ffmpeg stdin
③ 停止/销毁 ffmpeg
④ 等待进程退出
⑤ rtpReceiver.stop(port)
```

Windows 下还需要考虑 ffmpeg 子进程：

```java
ffmpeg.descendants().forEach(ProcessHandle::destroyForcibly);
ffmpeg.destroyForcibly();
```

原因是 Windows 下某些 ffmpeg 启动方式可能产生子进程，仅销毁父进程不能保证整个进程树结束。

---

# 5.7 FlvBroadcast — HTTP-FLV 广播器

`FlvBroadcast` 负责：

```text
ffmpeg stdout
      ↓
readLoop
      ↓
bootstrap
      ↓
Subscriber
      ↓
HTTP-FLV
```

### 5.7.1 Bootstrap

FLV 播放器必须从：

```text
FLV Header
↓
Metadata
↓
AVC Sequence Header
↓
首帧
↓
实时数据
```

开始解析。

所以需要保存流开始部分：

```text
bootstrap
    ↓
新客户端加入
    ↓
先发送 bootstrap
    ↓
再发送实时数据
```

当前参数：

| 参数                      |    值 |
| ----------------------- | ---: |
| `CHUNK_SIZE`            | 8192 |
| `QUEUE_SIZE`            |  300 |
| `BOOTSTRAP_MAX_SIZE`    |  2MB |
| `BOOTSTRAP_WAIT_MILLIS` | 3000 |
| `BOOTSTRAP_READY_SIZE`  | 64KB |

---

## 5.7.2 broadcastLock

必须保证：

```text
Bootstrap
    ↓
实时数据
```

不能出现：

```text
实时数据
    ↓
Bootstrap
```

因此：

```java
synchronized (broadcastLock) {
    subscribers.add(sub);
    sub.offer(bootstrapData);
}
```

同时 readLoop 广播也使用同一个锁。

---

# 5.7.3 重要：FlvBroadcast.close() 不能同步关闭 flvIn

这是当前 local 回放停止问题中最关键的修复点之一。

旧实现：

```java
@Override
public void close() {
    if (!closed.compareAndSet(false, true)) {
        return;
    }

    for (Subscriber sub : subscribers) {
        sub.close();
    }

    subscribers.clear();

    synchronized (bootstrap) {
        bootstrap.notifyAll();
    }

    try {
        flvIn.close();
    } catch (IOException ignored) {
    }
}
```

问题在于：

```java
flvIn.close();
```

虽然从语义上看就是：

> 关闭 ffmpeg stdout。

但是在 Windows 环境下，`flvIn` 通常对应 `ProcessPipeInputStream`。

与此同时：

```text
flv-read-xxx
    ↓
flvIn.read()
```

可能正在阻塞等待 ffmpeg stdout。

如果 HTTP 停止线程同步执行：

```java
flvIn.close();
```

可能出现：

```text
HTTP stop
   ↓
FlvBroadcast.close()
   ↓
flvIn.close()
   ↓
阻塞
   ↓
cleanup() 后续代码无法执行
```

于是日志表现为：

```text
关闭广播 1
关闭广播 2
关闭广播 3

然后没有：
关闭广播 4
cleanup 完成
RTP 端口释放
FFmpeg 结束
```

这就是此前实际测试中出现：

> 回放停止后仍然传输，再点击其他回放失败，甚至需要重启应用才能恢复。

---

## 5.7.4 正确的 FlvBroadcast.close()

`FlvBroadcast` 不应该负责同步关闭 ffmpeg stdout。

推荐：

```java
@Override
public void close() {
    if (!closed.compareAndSet(false, true)) {
        return;
    }

    log.info("[FLV] 关闭广播: callId={} subscribers={}",
            session.getCallId(), subscribers.size());

    for (Subscriber sub : subscribers) {
        sub.close();
    }

    subscribers.clear();

    synchronized (bootstrap) {
        bootstrap.notifyAll();
    }
}
```

即：

> `FlvBroadcast.close()` 只负责停止广播和客户端，不直接同步关闭 `flvIn`。

---

## 5.7.5 为什么删除 flvIn.close() 后 stdout 仍然能够关闭

`readLoop()` 使用：

```java
try (InputStream in = flvIn) {
    while (!closed.get()) {
        int n = in.read(buf);
        ...
    }
}
```

因此 stdout 最终仍然由：

```text
try-with-resources
```

负责关闭。

完整生命周期变成：

```text
PlaybackService
    ↓
关闭 Sink
    ↓
关闭 ffmpeg stdin
    ↓
FFmpeg 退出
    ↓
stdout EOF
    ↓
flv-read 线程 read() 返回
    ↓
try-with-resources 自动关闭 flvIn
    ↓
readLoop finally
    ↓
closeReader()
```

因此并不是：

> 删除 `flvIn.close()` 后不关闭 stdout。

而是：

> **把 stdout 的关闭责任从同步 stop 路径转移给负责读取 stdout 的线程生命周期。**

这样可以避免 HTTP stop 线程被 Windows Pipe close 阻塞。

---

# 5.8 FlvController — HTTP-FLV 端点

接口：

```text
GET /live/{deviceId}/{channelId}.flv
```

流程：

```text
restartSession()
    ↓
等待 PLAYING
    ↓
getFlvBroadcast()
    ↓
subscribe()
    ↓
HTTP 200
    ↓
poll()
    ↓
write()
    ↓
flush()
```

客户端断开：

```text
unsubscribe()
    ↓
最后一个客户端？
    ↓
cleanup()
```

`X-Accel-Buffering: no` 用于避免反向代理缓存导致实时流延迟。

---

# 5.9 慢消费者保护

两级队列：

| 层级 | 队列                | 满策略 |
| -- | ----------------- | --- |
| L1 | RTP → ffmpeg      | 丢最旧 |
| L2 | ffmpeg → HTTP 客户端 | 丢最旧 |

原则：

> 慢客户端不能阻塞 RTP 收包线程，也不能阻塞 ffmpeg stdout 读取线程。

---

# 6. 数据模型

## 6.1 StreamSession

| 字段              | 类型      | 说明                             |
| --------------- | ------- | ------------------------------ |
| `callId`        | String  | SIP Call-ID                    |
| `branch`        | String  | Via Branch                     |
| `fromTag`       | String  | From Tag                       |
| `toTag`         | String  | 设备 To Tag                      |
| `peerContact`   | String  | 设备 Contact                     |
| `peerSdp`       | String  | 设备 SDP                         |
| `peerPayload`   | String  | RTP Payload                    |
| `ssrc`          | String  | 本次会话 SSRC                      |
| `invitePacket`  | byte[]  | INVITE 原始报文                    |
| `inviteRetries` | int     | 重传次数                           |
| `state`         | enum    | `INVITING / PLAYING / STOPPED` |
| `localPort`     | int     | RTP 接收端口                       |
| `deviceHost`    | String  | 设备地址                           |
| `devicePort`    | int     | 设备 SIP 端口                      |
| `ffmpeg`        | Process | FFmpeg 进程                      |
| `hlsUrl`        | String  | 历史遗留字段                         |
| `zlm`           | boolean | 是否 ZLM 模式                      |
| `zlmStreamId`   | String  | ZLM Stream ID                  |

---

## 6.2 Device

```text
deviceId
host
port
lastKeepalive
status
```

---

## 6.3 RtpReceiver.Session

```text
socket
packets
bytes
lostPackets
lastSeq
payloadType
ssrc
sinks
```

---

# 7. HTTP API 明细

| 方法   | 路径                                          | 参数                           | 说明       |
| ---- | ------------------------------------------- | ---------------------------- | -------- |
| GET  | `/api/devices`                              | —                            | 设备列表     |
| GET  | `/api/status`                               | —                            | 服务状态     |
| GET  | `/api/media`                                | —                            | RTP 媒体状态 |
| GET  | `/api/play/sessions`                        | —                            | 点播会话     |
| POST | `/api/play/start`                           | deviceId/channelId           | 开始实时点播   |
| POST | `/api/play/stop`                            | deviceId/channelId           | 停止实时点播   |
| GET  | `/live/{deviceId}/{channelId}.flv`          | 路径参数                         | 实时 FLV   |
| POST | `/api/alarm/subscribe`                      | deviceId                     | 订阅报警     |
| POST | `/api/alarm/unsubscribe`                    | deviceId                     | 取消报警     |
| POST | `/api/playback/start`                       | deviceId/channelId/start/end | 开始历史回放   |
| POST | `/api/playback/stop`                        | deviceId/channelId           | 停止历史回放   |
| GET  | `/api/playback/sessions`                    | —                            | 回放会话     |
| GET  | `/live/{deviceId}/{channelId}/playback.flv` | 路径参数                         | 历史回放 FLV |
| GET  | `/hls/**`                                   | —                            | 遗留 HLS   |

---

# 8. 配置项

| 键                              | 当前值                    | 说明        |
| ------------------------------ | ---------------------- | --------- |
| `server.port`                  | 8080                   | HTTP 端口   |
| `sip.server.id`                | `34020000002000000001` | 服务器国标编码   |
| `sip.server.host`              | `0.0.0.0`              | SIP 监听地址  |
| `sip.server.port`              | 5060                   | SIP UDP   |
| `sip.server.ip`                | `192.168.8.104`        | 对外通告 IP   |
| `sip.server.heartbeat-timeout` | 180                    | 心跳超时      |
| `logging.file.name`            | `./logs/sip.log`       | 日志        |
| `app.hls-dir`                  | `./hls`                | 遗留 HLS 目录 |

> `sip.server.ip` 是部署时必须检查的配置。该地址会进入 SIP Contact / SDP，设备必须能够访问。

---

# 9. 线程模型总览

| 线程                  |       数量 | 职责                 |
| ------------------- | -------: | ------------------ |
| `rtp-recv-<port>`   |    每端口 1 | RTP 接收             |
| `sink-write-<port>` | 每 Sink 1 | RTP → ffmpeg stdin |
| `ffmpeg-<callId>`   |    每会话 1 | FFmpeg stderr      |
| `flv-read-<callId>` |    每会话 1 | stdout → FLV       |
| `invite-retransmit` |        1 | INVITE 重传          |
| Netty NIO           |      2~4 | SIP                |
| 心跳巡检                |        1 | 设备在线状态             |
| 报警续订                |        1 | SUBSCRIBE 续订       |
| HTTP/Tomcat         |       池化 | HTTP + FLV         |

---

# 10. 关键问题与设计决策

## 10.1 为什么使用外部 ffmpeg

主要原因：

1. PS 解封装能力成熟；
2. H.264/AAC/G.711 等格式兼容性好；
3. 进程隔离；
4. FFmpeg 崩溃不会直接拖垮 JVM；
5. 后续可以根据设备编码动态调整参数。

---

## 10.2 ffmpeg 参数调优

| 参数                               | 作用          |
| -------------------------------- | ----------- |
| `-f mpeg`                        | PS demux    |
| `-map 0:a:0?`                    | 音频可选        |
| `-c:v copy`                      | H.264 视频零编码 |
| `-c:a aac`                       | 音频统一为 AAC   |
| `-flvflags no_duration_filesize` | 避免直播管道等待    |
| `-loglevel info`                 | 诊断日志        |

---

# 10.3 大华二次启动点播无响应

曾出现：

```text
第一次点播
    ↓
正常

平台重启
    ↓
再次 INVITE
    ↓
大华没有响应
    ↓
INVITING
```

主要兼容性因素：

```text
平台异常退出
    ↓
没有 BYE
    ↓
设备仍认为旧会话存在
    ↓
新 INVITE / SSRC 可能被设备忽略
```

当前采用：

* 随机 SSRC；
* INVITE 重传；
* Timer B 超时；
* 服务关闭时主动 BYE；
* 条件删除；
* 迟到 200 OK 防御。

---

# 10.4 stderr 混入 FLV

曾经使用：

```java
redirectErrorStream(true)
```

导致：

```text
stderr 日志
+
stdout FLV
```

混在一起。

最终：

```text
mpegts.js
    ↓
FLV Probe
    ↓
失败
```

必须保持：

```java
redirectErrorStream(false)
```

---

# 10.5 Bootstrap

新客户端不能直接从实时位置开始。

必须：

```text
FLV Header
Metadata
AVC Sequence Header
首帧
实时数据
```

所以使用 Bootstrap。

---

# 10.6 慢消费者

两个有界队列：

```text
RTP
 ↓
L1 SinkWriter
 ↓
ffmpeg
 ↓
L2 Subscriber
 ↓
Browser
```

两个队列都不能无限增长。

---

# 10.7 设备地址学习

设备地址优先：

```text
REGISTER Contact
```

失败时：

```text
UDP source address
```

心跳也允许自动登记未知设备，解决：

```text
平台重启
    ↓
设备没有重新 REGISTER
    ↓
直接发送 Keepalive
```

---

# 10.8 历史回放停止后仍在传输的问题

这是当前历史回放最重要的兼容性问题之一。

### 现象

```text
开始回放
    ↓
正常播放
    ↓
点击停止
    ↓
SIP BYE 已发送
    ↓
浏览器停止
```

但设备仍可能继续发送 RTP。

如果此时再次：

```text
开始另一个回放
```

可能出现：

```text
第二次 INVITE
    ↓
失败 / 无响应 / 播放失败
```

甚至：

```text
必须重启应用
```

才能恢复。

---

## 10.8.1 实际问题定位

此前通过日志定位到：

```text
BYE
↓
cleanup
↓
FlvBroadcast.close()
↓
关闭广播 1
↓
关闭广播 2
↓
关闭广播 3
```

之后没有继续出现：

```text
关闭广播 4
cleanup 完成
RTP 端口释放
```

说明停止流程卡在：

```java
flvIn.close();
```

而不是：

```text
SIP BYE
```

也不是：

```text
RtpReceiver.stop()
```

---

# 10.9 为什么 flvIn.close() 会造成停止卡住

`flvIn` 来源于：

```text
ffmpeg.getInputStream()
```

在 Windows 环境通常对应进程管道。

同时：

```text
flv-read-xxx
```

线程正在：

```java
flvIn.read()
```

等待 ffmpeg stdout。

如果另一个线程同步调用：

```java
flvIn.close();
```

就可能出现：

```text
stopPlayback()
    ↓
cleanup()
    ↓
FlvBroadcast.close()
    ↓
flvIn.close()
    ↓
Windows Pipe close
    ↓
阻塞
```

由于 `cleanup()` 没有执行完：

```text
RTP Session
FFmpeg
UDP Socket
StreamSession
```

都可能继续存在。

所以用户看到的就是：

> “回放已经停止了，但后台还在传输。”

---

# 10.10 FlvBroadcast 与 FFmpeg 的职责边界

应该明确职责：

### FlvBroadcast

负责：

```text
stdout read
bootstrap
subscriber
HTTP FLV 广播
```

不负责：

```text
FFmpeg 生命周期
```

### PlaybackService / PlayService

负责：

```text
启动 FFmpeg
停止 FFmpeg
关闭 stdin
destroy process
destroy descendants
等待退出
```

因此：

```text
FlvBroadcast.close()
```

只负责：

```text
closed=true
关闭 Subscriber
notify bootstrap
```

不要同步执行：

```java
flvIn.close();
```

---

# 10.11 正确停止流程

local 模式推荐：

```text
stopPlayback()
      │
      ▼
发送 SIP BYE
      │
      ▼
cleanup()
      │
      ├─ 从 session map 条件删除
      │
      ├─ FlvBroadcast.close()
      │
      ├─ detachSink()
      │       ↓
      │    关闭 ffmpeg stdin
      │
      ├─ destroy ffmpeg descendants
      │
      ├─ destroy ffmpeg
      │
      └─ rtpReceiver.stop()
              ↓
          UDP socket close
```

与此同时：

```text
flv-read
   ↓
等待 stdout EOF
   ↓
FFmpeg 退出
   ↓
read 返回 EOF
   ↓
try-with-resources 自动关闭 flvIn
```

这样停止流程不会因为：

```java
flvIn.close()
```

同步阻塞。

---

# 10.12 为什么海康没有暴露同样的问题

这里不能简单理解为：

> 海康正确，大华错误。

更准确的理解是：

> **GB/T 28181-2022 统一了协议交互方式，但并不要求不同厂商内部的录像、媒体、Socket、进程和资源释放实现完全一致。**

例如：

```text
平台发送 BYE
    │
    ├─ 海康
    │    └─ 设备可能较快停止媒体发送
    │
    └─ 大华
         └─ 设备可能仍有一段时间继续发送 RTP
```

对于平台来说：

```text
BYE
```

只是 SIP 会话层面的结束信号。

平台自己的：

```text
RTP Socket
FFmpeg
Pipe
FlvBroadcast
HTTP
```

仍然必须独立完成资源释放。

因此：

> 不能把“设备停止媒体的速度”当成“平台资源是否释放完成”的判断条件。

---

# 10.13 GB/T 28181-2022 为什么设备实现仍然不同

标准主要统一：

```text
SIP 信令
MANSCDP XML
SDP
RTP/PS
设备编码
目录
录像查询
点播
回放
报警
```

但设备内部仍然可以不同：

```text
录像文件组织
录像检索数据库
RTP 推流线程
SIP 状态机
BYE 处理时机
Socket 关闭时机
异常重试策略
SSRC 管理
媒体缓存
多段录像拼接方式
```

所以实际项目中：

```text
同一份 GB/T 28181
        ↓
海康
大华
宇视
天地伟业
其他厂商
        ↓
仍然需要兼容性适配
```

这是国标平台开发中非常正常的情况。

---

# 10.14 平台必须以自身资源状态作为最终判断

例如：

错误方式：

```text
发送 BYE
↓
认为设备一定停止
↓
直接删除 Session
```

正确方式：

```text
发送 BYE
↓
关闭平台自身媒体链
↓
停止 FFmpeg
↓
关闭 RTP Socket
↓
确认 Session 清理
```

设备后续即使再发：

```text
RTP
```

也只能进入：

```text
无对应媒体 Session
```

而不会影响下一次播放。

---

# 11. 边界与限制

> 下表针对默认 `local` 自研媒体链；`zlm` 模式下 RTP 接收、转码、分发由 ZLMediaKit 承担。

| 项           | 现状              | 说明                   |
| ----------- | --------------- | -------------------- |
| H.265 预览    | ❌               | 当前 FLV 直封链主要针对 H.264 |
| 音频          | G.711/AAC → AAC | ffmpeg 统一处理          |
| 会话持久化       | ❌               | 内存态                  |
| 分布式         | ❌               | 单机                   |
| TLS/SIP 安全  | ❌               | 当前未完整实现              |
| TCP SIP     | ❌               | 当前主要 UDP             |
| 设备主动 INVITE | 部分支持            | 收流链路未完整接入            |
| HLS         | 遗留              | 当前主要 FLV             |
| RecordInfo  | 已验证解析/查询        | 不同厂商存在 Channel ID 差异 |
| ZLM 热切换     | ❌               | 需要重启                 |
| 回放并发        | 单通道单会话          | 同通道不允许多个回放会话         |
| H.265 FLV   | ❌               | 当前前端播放链路限制           |

---

# 12. 历史回放（PlaybackService）

历史回放与实时点播最大的区别：

```text
实时点播
    ↓
当前实时媒体

历史回放
    ↓
设备本地录像
    ↓
指定 StartTime / EndTime
```

两者都使用：

```text
SIP INVITE
↓
RTP/PS
↓
RtpReceiver
↓
FFmpeg
↓
FLV
```

---

## 12.1 与实时点播的差异

| 维度       | 实时点播                                   | 历史回放                                            |
| -------- | -------------------------------------- | ----------------------------------------------- |
| Subject  | `<channelId>:<serverId>,<channelId>:0` | `Playback:<channelId>:<serverId>,<channelId>:0` |
| SDP `s=` | `Play`                                 | `Playback`                                      |
| SDP `t=` | `t=0 0`                                | `t=start end`                                   |
| `u=`     | 通常无实际时间意义                              | 携带回放资源                                          |
| SSRC     | 实时约定                                   | 回放约定                                            |
| 数据来源     | 摄像头实时编码                                | 设备录像                                            |
| 结束       | 平台或设备                                  | 通常设备推完后 BYE                                     |
| 浏览器刷新    | 重建                                     | 重建                                              |
| 视频编码     | H.264 copy                             | 当前采用 H.264 重编码                                  |

---

# 12.2 历史回放时序

```text
浏览器
   │
   │ startTime / endTime
   ▼
PlaybackService
   │
   ├─ 创建 RTP Port
   ├─ 创建 Call-ID
   ├─ 创建回放 SSRC
   └─ INVITE
          │
          ▼
       摄像头
          │
          │ 200 OK
          ▼
 PlaybackService
          │
          ├─ FFmpeg
          ├─ attachSink
          ├─ FlvBroadcast
          ├─ ACK
          └─ PLAYING
                   │
                   ▼
                RTP/PS
                   │
                   ▼
                FFmpeg
                   │
                   ▼
                  FLV
                   │
                   ▼
                浏览器
```

---

# 12.3 回放结束方式

正常情况下设备可能：

```text
录像播放完成
    ↓
设备发送 BYE
    ↓
handleDeviceBye()
    ↓
cleanup()
```

但平台主动停止时：

```text
浏览器停止
    ↓
PlaybackService.stopPlayback()
    ↓
发送 BYE
    ↓
平台 cleanup()
```

因此必须同时支持：

```text
设备主动结束
+
平台主动结束
```

---

## 12.3.1 第三种结束方式：设备静默停推与看门狗兜底

部分设备**不发 BYE、RTP 直接断流**（信令无任何动作）：

```text
设备静默停推（不发 BYE）
    ↓
看门狗 watchdogTick 按 RTP 收包状态判定
    ↓
平台主动 BYE 收尾（同平台主动停止链路）
```

**媒体流看门狗**（`MediaService.scheduleStreamWatchdog` / `watchdogTick`）：回放/下载会话就绪时注册，仅 `isPlayback()` 启用（直播无预期结束时间，不启用）；每 `STREAM_IDLE_CHECK_SECONDS=3s` 一次，数据源为 `RtpReceiver.lastPacketAt(callId)`：

| 判定 | 条件 | 默认参数 |
| --- | --- | --- |
| 首包超时 | `lastPacketAt<=0` 且启动超过 | `max(60, 4×idle)` = 60s |
| 推流中断 | 距末包 ≥ `download-idle-seconds` | 15s |
| 灌流超限 | 运行超过 | `max(1800, 10×预期时长)` |

- 首包宽限 4 倍：200OK 后设备需读卡+解码+起推
- 灌流上限 10 倍而非 3 倍：设备可能无视 `a=downloadspeed` 协商按 1x 重推（1h 录像倍速 4 预估 900s，1x 实际 3600s，3 倍预估会误杀）
- 每次 tick 先做会话有效性双保险（实例比对 + PLAYING 状态），整体 try-catch，单次异常不杀调度线程
- 执行模型：tick 挂在 `monitorScheduler`（显式 `ScheduledThreadPoolExecutor`，2 线程，`removeOnCancelPolicy=true` 取消即出队）；命中后的 `doStop`（BYE + ffmpeg 最长 60s 优雅等待 + 文件改名）提交 `monitorActionExecutor`（定长 4 + 有界队列 512 + CallerRuns 兜底），监控线程只判定不干重活

触发后走 `doStop` → 主动 BYE → ffmpeg SIGTERM 优雅退出（最多等 60s 写 moov+faststart）→ `finalizeDownload` 把 `.part` 改名，已传部分仍可播放。完整排障与调参见 README 11.18。

---

# 12.4 PlaybackService 入口

| 方法                     | 作用       |
| ---------------------- | -------- |
| `startPlayback()`      | 开始回放     |
| `stopPlayback()`       | 停止回放     |
| `getOrStartPlayback()` | 相同时间范围复用 |
| `restartPlayback()`    | 强制重新建立   |
| `handleDeviceBye()`    | 设备主动结束   |

---

# 12.5 回放区间不能随意复用

例如：

```text
12:00 ~ 12:10
```

和：

```text
13:00 ~ 13:10
```

不能复用同一个回放会话。

因为设备已经建立的 RTP 流对应的是原时间区间。

所以：

```text
deviceId
+
channelId
+
startTime
+
endTime
```

共同决定回放会话。

---

# 12.6 回放 FFmpeg

当前回放使用重编码：

```bash
ffmpeg -loglevel info \
  -fflags +genpts \
  -f mpeg -i pipe:0 \
  -map 0:v:0 -map 0:a:0? \
  -c:v libx264 \
  -preset ultrafast \
  -tune zerolatency \
  -c:a aac \
  -b:a 64k \
  -ar 8000 \
  -ac 1 \
  -flvflags no_duration_filesize \
  -f flv pipe:1
```

主要考虑：

```text
设备历史录像
    ↓
可能包含多段录像
    ↓
PTS/DTS 不连续
    ↓
直接 copy 可能导致播放器时间轴异常
```

因此当前采用：

```text
genpts
+
libx264
```

提高历史回放的兼容性。

代价：

> CPU 消耗高于视频 copy。

---

# 12.7 回放停止的完整资源释放

当前 local 模式：

```text
PlaybackService
      │
      ├─ SIP BYE
      │
      ├─ FlvBroadcast.close()
      │
      ├─ detachSink()
      │
      ├─ 关闭 FFmpeg stdin
      │
      ├─ destroy FFmpeg
      │
      └─ RtpReceiver.stop()
```

特别注意：

```text
FlvBroadcast.close()
```

**不要同步执行：**

```java
flvIn.close();
```

stdout 的关闭由：

```text
FFmpeg 退出
↓
stdout EOF
↓
flv-read
↓
try-with-resources
```

完成。

---

# 12.8 回放客户端断开

如果浏览器断开：

```text
HTTP disconnect
    ↓
finally
    ↓
unsubscribe()
    ↓
判断是否最后一个客户端
    ↓
是
    ↓
stopIfNoClients()
```

避免：

```text
浏览器已经关闭
+
设备继续回放
+
FFmpeg 继续运行
+
RTP 继续传输
```

形成空跑。

---

# 13. 录像查询（MANSCDP RecordInfo）

RecordInfo 用于：

```text
客户端
    ↓
指定设备 / 通道
    ↓
指定开始时间
    ↓
指定结束时间
    ↓
查询录像列表
```

---

# 13.1 标准查询结构

```xml
<?xml version="1.0" encoding="GB18030"?>
<Query>
    <CmdType>RecordInfo</CmdType>
    <SN>1</SN>
    <DeviceID>34020000001320000002</DeviceID>
    <StartTime>2026-09-17T12:55:00</StartTime>
    <EndTime>2026-09-17T14:55:00</EndTime>
    <Type>time</Type>
</Query>
```

---

# 13.2 实际大华设备兼容性重点

当前实际测试大华设备：

```text
设备：
34020000001320000002
```

查询录像时发现：

> **大华的 RecordInfo 查询必须使用实际录像通道的 Channel ID / 通道号作为 `<DeviceID>`，不能简单认为 `<DeviceID>` 永远就是摄像机设备 ID。**

这是项目实际兼容性结论。

例如：

```text
平台设备 ID
34020000001320000002
```

并不代表：

```xml
<DeviceID>34020000001320000002</DeviceID>
```

一定能够查询到录像。

实际应使用：

```text
设备对应通道 ID
```

例如：

```xml
<DeviceID>实际通道号/通道编码</DeviceID>
```

具体值以设备返回的 Catalog / 通道信息为准。

---

# 13.3 当前大华测试结果

成功查询：

```text
sumNum=28
```

设备返回：

```text
10 个响应包
```

最终：

```text
28 条录像
```

其中包含：

```text
14:19:00 ~ 14:27:00
Type=time
```

以及：

```text
15:04:04 ~ 15:05:00
Type=alarm
```

说明设备录像查询及分页汇总链路已经能够正常工作。

---

# 13.4 时间格式

当前实际测试成功格式：

```text
yyyy-MM-dd'T'HH:mm:ss
```

例如：

```text
2026-09-17T12:55:00
```

不要随意改成：

```text
2026-09-17 12:55:00
```

部分设备虽然可能接受，但大华当前测试以 ISO 风格时间成功。

---

# 13.5 时间戳转换注意事项

当前 Java 时间戳使用：

```text
epoch seconds
```

如果：

```java
startTime
endTime
```

本身保存的是 Unix 秒：

```java
Instant.ofEpochSecond(timestamp)
```

不要误用：

```java
Instant.ofEpochMilli(timestamp)
```

否则会产生：

```text
1970-01-xx
```

一类错误时间。

---

# 13.6 Type

实际设备测试：

```xml
<Type>time</Type>
```

查询正常。

设备录像可能返回：

```text
time
alarm
manual
```

因此平台不能假设所有录像都是 `time`。

---

# 13.7 RecordInfo 字段

| XML        | 字段         | 说明                |
| ---------- | ---------- | ----------------- |
| DeviceID   | deviceId   | 查询资源/通道编码         |
| Name       | name       | 录像名称              |
| FilePath   | filePath   | 设备内部路径            |
| Address    | address    | 录像地址              |
| StartTime  | startTime  | 开始时间              |
| EndTime    | endTime    | 结束时间              |
| Secrecy    | secrecy    | 涉密标识              |
| Type       | type       | time/alarm/manual |
| RecorderID | recorderId | 录像机编码             |
| FileSize   | fileSize   | 文件大小              |

---

# 13.8 厂商兼容性原则

RecordInfo 不建议直接写成：

```text
camera.deviceId = query.DeviceID
```

更合理的模型是：

```text
Device
 └─ Channels
       ├─ Channel ID
       ├─ Name
       ├─ Status
       └─ Parent Device ID
```

查询：

```text
Device
+
Channel
+
StartTime
+
EndTime
```

最终：

```text
RecordInfo.DeviceID = Channel.channelId
```

这样可以同时兼容：

```text
海康
大华
NVR
多通道设备
```

---

# 14. ZLMediaKit 媒体面（zlm 模式）

ZLMediaKit 可以替换：

```text
RtpReceiver
FFmpeg
FlvBroadcast
```

组成：

```text
SIP Server
    ↓
SIP INVITE
    ↓
摄像头
    ↓
RTP/PS
    ↓
ZLMediaKit
    ↓
WebRTC（前端主链路）/ HTTP-FLV / WS-FLV / HLS（API 保留）
```

---

# 14.1 local 与 zlm

| 维度            | local        | zlm                    |
| ------------- | ------------ | ---------------------- |
| RTP 接收        | RtpReceiver  | ZLM                    |
| PS 处理         | FFmpeg       | ZLM                    |
| 转码            | FFmpeg       | ZLM                    |
| FLV           | FlvBroadcast | ZLM                    |
| HTTP-FLV      | 本服务          | ZLM                    |
| WS-FLV        | ❌            | ZLM                    |
| HLS           | 遗留           | ZLM                    |
| WebRTC        | ❌            | ZLM                    |
| FFmpeg 进程     | 每路一个         | 不需要本服务启动               |
| Pipe close 风险 | 存在           | 本服务不直接操作 FFmpeg stdout |

---

# 14.2 ZLM 模式为什么不会出现当前 flvIn.close() 问题

当前问题的根本条件是：

```text
Java
 ↓
Process
 ↓
ProcessPipeInputStream
 ↓
flvIn.close()
```

而 ZLM 模式：

```text
Java
 ↓
HTTP API
 ↓
ZLMediaKit
 ↓
RTP
 ↓
ZLM 内部媒体处理
```

Java 不再持有：

```text
ffmpeg Process
```

也就不存在：

```text
ProcessPipeInputStream.close()
```

这一层资源竞争。

因此：

> 当前发现的 `flvIn.close()` 阻塞问题属于 local 自研媒体链路，不属于 GB/T 28181 本身的问题。

---

# 14.3 ZLM 点播流程

```text
PlayService
    ↓
openRtpServer()
    ↓
得到 RTP Port
    ↓
INVITE
    ↓
设备 200 OK
    ↓
ACK
    ↓
设备 RTP → ZLM
    ↓
ZLM
    ↓
WebRTC（SDP over HTTP 信令 + RTP/RTCP over UDP）
    ↓
浏览器 RTCPeerConnection
```

Java 不启动 FFmpeg。

> 2026-09-20 起 zlm 模式**直播与回放的浏览器播放统一走 WebRTC**（见 14.7）；
> `flvUrl` / `wsFlvUrl` / `hlsUrl` 仍由 API 返回供第三方集成，前端主链路不再使用。

---

# 14.4 ZLM 资源释放

```text
closeRtpServer()
        ↓
close_streams()
        ↓
发送 BYE
        ↓
删除 Session
```

---

# 14.5 配置

```yaml
sip:
  media:
    mode: zlm
    zlm:
      api-base: http://127.0.0.1:9092
      secret: 035c73f7-bb6b-4889-a7e1-661d2a614950
      media-ip: 192.168.8.104
      http-port: 9092
      play-host: 192.168.8.104
      rtp-app: rtp
```

---

# 14.6 模式隔离

```text
local
    ↓
PlayService
    ↓
RtpReceiver
    ↓
FFmpeg
    ↓
FlvBroadcast

zlm
    ↓
ZlmMediaService
    ↓
ZLMediaKit
```

两个媒体面不应该同时处理同一个会话。

切换模式需要重启服务。

---

# 14.7 ZLM 模式 WebRTC 播放（直播与回放，2026-09-20）

zlm 模式下浏览器播放**直播与历史回放均走 WebRTC**：延迟从 HTTP-FLV 的 ~1s 级降到亚秒级，浏览器原生解码（无需 mpegts.js / MSE）。local 模式不受影响（仍 FLV/HLS）。

## 信令与地址

`ZlmMediaService.webrtcUrl(session)` 生成 ZLM 内置 WebRTC 信令端点，随点播/回放 start 响应返回：

```text
http://<playHost>:<httpPort>/index/api/webrtc?app=rtp&stream=<ssrc十进制>&type=play
```

* 走 ZLM **HTTP API 端口**（与 openRtpServer 同一入口），媒体面为 RTP/RTCP over UDP；
* 协议为 ZLM 自有"HTTP SDP 信令"（WHEP 风格）：POST 请求体为 SDP offer（`Content-Type: application/sdp`），响应为 JSON `{code, sdp, type}`，`code=0` 即成功，`sdp` 为 answer——**不是标准 WHEP**（WHEP 用 201 + Location 头）。

## 前端建连流程（openWebRtcPlayer）

```text
new RTCPeerConnection()
        ↓ addTransceiver('video'/'audio', {direction:'recvonly'})
createOffer() → setLocalDescription(offer)
        ↓
waitIceGatheringComplete()   ★ 必须：等 ICE 候选收集完成（非 trickle）
        ↓
POST SDP offer（application/sdp）→ /index/api/webrtc
        ↓
{code:0, sdp: answer} → setRemoteDescription(answer)
        ↓
ontrack → video.srcObject = stream
```

关键点：

* **必须等 ICE gathering complete 再 POST**：ZLM 不支持 trickle ICE，收到的 offer 必须已含完整 candidates；提前 POST 会因 SDP 无候选导致建连失败；
* ZLM 返回的是 JSON 而非裸 SDP，前端校验 `code===0` 且 `sdp` 以 `v=` 开头后 setRemoteDescription；
* `closeLocalPlayer()` 统一销毁 hls / mpegts / rtcPeer 三种播放器，WebRTC 关闭即 `RTCPeerConnection.close()`。

## 直播与回放的差异

| 环节        | 直播                                             | 回放                                                             |
| --------- | ---------------------------------------------- | ---------------------------------------------------------------- |
| 拉流时机      | start 响应返回即直接 `openWebRtcPlayer`                | **先 `waitPlaybackReady` 再建连**：轮询 `/api/playback/status`（20 次 × 300ms）等 `ready=true` |
| 原因        | ZLM 收流即注册流，起播窗口短                                | **SIP PLAYING ≠ ZLM 流 READY**：设备回放出流慢，流未注册就 POST 会被 ZLM 拒绝（code≠0） |
| 幂等复用      | `getOrStartSession`：PLAYING 会话直接复用              | 每次强制重建会话（回放必须从头播）                                                  |
| 前端 FLV 按钮 | `startFlv` zlm 模式下转调 `startPlay`（即同样走 WebRTC）    | 同左                                                                 |

`/api/playback/status` 返回 `exists / state / sipReady / ready`，`ready` 由 `isMediaReady()`（ZLM `getMediaList` 查流存在）判定——SIP 会话状态与媒体流状态是两层，前端以 `ready` 为准。

## 部署依赖与注意事项

* **浏览器须可达 ZLM 的 UDP 媒体端口**：WebRTC 媒体走 ICE host candidates，浏览器与 ZLM 同网段（或有正确 externIP 配置）方可连通；跨网段需 ZLM 配置 `rtc.externIP`；
* ZLM 需启用 RTC 支持（编译期含 webrtc，配置中 rtc 端口段开放）；
* 音频链路：设备 G.711A → ZLM 自动转 Opus 封装进 WebRTC（浏览器可解），前端 `video.muted` 控制声音开关；
* `webrtcUrl` 中 `stream` 为 SSRC 十进制字符串，与 `openRtpServer` 的 streamId 一致——国标流标识贯穿信令与媒体两层。

---

# 15. 兼容性与后续演进建议

## 15.1 设备模型增加 Channel

当前项目如果进一步支持更多厂商，建议：

```text
Device
    │
    ├─ deviceId
    ├─ host
    ├─ port
    │
    └─ Channel
         ├─ channelId
         ├─ name
         ├─ parentId
         └─ status
```

不要把：

```text
Device ID
```

和：

```text
Channel ID
```

设计成永远相同。

---

## 15.2 PlaybackService 与 PlayService

当前两个服务共用：

```text
RtpReceiver
FFmpeg
FlvBroadcast
StreamSession
```

但信令构造分开：

```text
PlayService
    ↓
实时 INVITE

PlaybackService
    ↓
历史 INVITE
```

这样可以避免实时点播和历史回放的 SDP 语义相互污染。

---

## 15.3 媒体资源释放统一化

后续可以抽象：

```text
MediaSessionResource
```

统一管理：

```text
RTP Port
FFmpeg
Sink
Broadcast
ZLM Stream
```

实现：

```text
resource.close()
```

统一释放。

但当前项目规模下，不强制要求马上抽象，保持 `PlayService` / `PlaybackService` 清晰即可。

---

## 15.4 平台停止不要依赖设备行为

必须遵循：

```text
平台发送 BYE
        ↓
平台立即清理自己的资源
        ↓
设备停止 RTP 是异步结果
```

而不能：

```text
等待设备停止 RTP
        ↓
再释放本地资源
```

否则某些设备继续发送 RTP 时会拖住平台资源。

---

## 15.5 local 模式适用场景

local 模式适合：

* 单机部署；
* 摄像头数量较少；
* 联调；
* 需要完全控制 SIP + RTP + FFmpeg；
* 希望减少外部组件。

---

## 15.6 ZLM 模式适用场景

ZLM 模式适合：

* 多路并发；
* 多协议输出；
* HTTP-FLV；
* WS-FLV；
* HLS；
* WebRTC；
* 媒体分发；
* 后续扩大平台规模。

---

# 16. 当前系统核心链路总结

## 16.1 实时点播

```text
浏览器
 ↓
FlvController
 ↓
PlayService
 ↓
SIP INVITE
 ↓
摄像头
 ↓
RTP/PS
 ↓
RtpReceiver
 ↓
SinkWriter
 ↓
FFmpeg
 ↓
FlvBroadcast
 ↓
HTTP-FLV
 ↓
mpegts.js
```

---

## 16.2 历史回放

```text
浏览器
 ↓
PlaybackService
 ↓
RecordInfo / 指定时间段
 ↓
SIP INVITE
 ↓
摄像头录像
 ↓
RTP/PS
 ↓
RtpReceiver
 ↓
FFmpeg
 ↓
FlvBroadcast
 ↓
HTTP-FLV
 ↓
mpegts.js
```

---

## 16.3 历史回放停止

```text
浏览器停止
      ↓
PlaybackService.stopPlayback()
      ↓
发送 BYE
      ↓
FlvBroadcast.close()
      ↓
detachSink()
      ↓
关闭 FFmpeg stdin
      ↓
FFmpeg 退出
      ↓
stdout EOF
      ↓
flv-read 自动关闭 flvIn
      ↓
FFmpeg destroy
      ↓
RtpReceiver.stop()
      ↓
UDP Port 释放
      ↓
StreamSession STOPPED
```

核心原则：

> **停止操作不能被 `flvIn.close()` 阻塞。**

---

# 17. 当前项目最重要的技术结论

### 17.1 SIP 会话结束 ≠ 媒体资源立即结束

```text
BYE
```

只是 SIP 层结束。

平台仍必须处理：

```text
RTP
FFmpeg
Pipe
Broadcast
HTTP
Socket
```

---

### 17.2 GB/T 28181-2022 ≠ 厂商内部实现完全一致

标准统一协议。

厂商仍可能在：

```text
录像
RTP
BYE
SSRC
Socket
状态机
资源释放
RecordInfo
```

方面存在差异。

因此项目必须采用：

> **标准协议 + 厂商兼容适配**

的设计方式。

---

### 17.3 Dahua RecordInfo 必须关注 Channel ID

实际大华设备测试已经证明：

```text
查询录像
    ↓
不能简单使用 Camera Device ID
    ↓
应使用实际录像 Channel ID / Channel Number
```

因此后续录像查询 API 应以：

```text
deviceId
+
channelId
+
startTime
+
endTime
```

为基本参数。

---

### 17.4 local 模式的 FFmpeg 生命周期必须由业务服务负责

```text
PlayService
PlaybackService
```

负责：

```text
start
stop
destroy
```

而：

```text
FlvBroadcast
```

负责：

```text
read
cache
broadcast
subscriber
```

职责不要混淆。

---

### 17.5 不要在 HTTP stop 线程同步关闭 ProcessPipeInputStream

尤其 Windows 环境：

```java
flvIn.close();
```

虽然是正常的 close API，但放在：

```text
PlaybackService.cleanup()
```

同步执行可能造成停止线程阻塞。

应该让：

```text
FFmpeg 生命周期
```

驱动：

```text
stdout EOF
```

再由：

```text
try-with-resources
```

完成 stdout 关闭。

---

### 17.6 RTP Session 必须先解绑 Sink，再从 Map 删除

正确：

```text
get Session
 ↓
detachSink
 ↓
close socket
 ↓
remove Session
```

错误：

```text
remove Session
 ↓
detachSink
```

后者会导致 `detachSink()` 找不到 Session。

---

# 18. 附录：设备端音频配置备注（2026-09-17）

> 配合视频前端播放器（mpegts.js / HTTP-FLV），当前测试设备端音频编码设置为 AAC。

## A.1 实时点播

当前：

```text
-c:v copy
-c:a aac
-b:a 64k
```

设备端：

```text
AAC 44.1kHz
```

FFmpeg：

```text
AAC → AAC
```

最终输出：

```text
AAC
```

---

## A.2 历史回放

当前：

```text
-c:a aac
-b:a 64k
-ar 8000
-ac 1
```

因此即使设备源：

```text
AAC 44.1kHz
```

回放输出仍然：

```text
AAC 8kHz
单声道
```

这是当前已知限制。

如果以后需要回放也输出：

```text
44.1kHz
```

可以调整：

```text
-ar 44100
```

或者根据源采样率动态处理。

---

## A.3 SIP 与音频编码关系

当前 INVITE SDP 主要协商：

```text
m=video
a=rtpmap:96 PS/90000
```

音频实际封装在：

```text
PS
 └─ PES
     └─ Audio
```

因此设备从：

```text
G.711a
```

切换到：

```text
AAC
```

不会改变当前视频 SIP SDP 的基本结构。

---

## A.4 老设备兼容

如果设备仍然输出：

```text
G.711a / PCMA
```

FFmpeg 可以：

```text
PS
 ↓
G.711
 ↓
AAC
 ↓
FLV
```

因此不要求所有设备必须提前改成 AAC。

---

# 19. 语音广播与对讲（VoiceBroadcastService）

> 2026-09-18 新增：GB/T 28181-2022 语音广播/对讲全链路。
> 平台发 Broadcast 触发设备反向音频 INVITE，浏览器麦克风音频经 WebSocket 上行，
> 平台 G.711A 编码后以 RTP **直发**设备（不经 ZLM / ffmpeg）。
> 含海康 TCP 模式"建链后 ~100ms 秒 BYE"完整排查记录（根因：RFC 4571 封装）。

## 19.1 链路组成

```text
浏览器 getUserMedia(8kHz/16bit/单声道 PCM)
        ↓ WebSocket 二进制帧 /ws/voice?deviceId=&channelId=
VoiceWebSocketHandler
        ↓ pushAudio()
G711a.encode（PCM → A-law）
        ↓ offer() 入缓冲
RtpSender（20ms 定时，160 字节/帧）
        ↓ buildRtpPacket()（12 字节头 + PCMA 载荷）
设备 audioIp:audioPort（TCP 长连接 或 UDP）
```

涉及类：`VoiceBroadcastService` / `VoiceSession` / `VoiceWebSocketHandler` / `G711a` / `WebSocketConfig` / `DeviceController`。

---

## 19.2 信令时序（设备发起反向 INVITE）

```text
平台                              设备（海康/大华）
   │ ① MESSAGE Broadcast ─────────▶│ GB18030 XML
   │    （SourceID=平台 TargetID=通道）│
   │ ② 200 OK ◀───────────────────│
   │                               │
   │ ③ INVITE（m=audio，含 y=SSRC）◀│ 设备反向发起音频会话
   │ ④ 200 OK + answer SDP ──────▶│ m=audio sendPort
   │    a=setup 与设备角色互补        │ TCP/RTP/AVP 或 RTP/AVP
   │ ⑤ ACK ◀─────────────────────│
   │                               │
   │ TALKING：RTP(PCMA) ──────────▶│ ⑥ 平台按 20ms 节奏直发
   │                               │
   │ ⑦ BYE（平台 stop / 设备主动）◀─▶ 任一方结束
```

---

## 19.3 会话状态机

```text
BROADCASTING（Broadcast 已发，15s 超时）
    ↓ 收到设备音频 INVITE 并应答 200 OK
INVITED（等设备 ACK，15s 超时）
    ↓ 设备 ACK
TALKING（RtpSender 20ms 定时发送）
    ↓ BYE / stop / 超时
STOPPED
```

---

## 19.4 传输模式自动判别（不按厂商硬编码）

按设备 INVITE SDP `m=` 行协议决定传输方式：

| 设备 SDP m= 行        | transport | 建链方式           |
| ------------------- | --------- | ---------------- |
| `TCP/RTP/AVP`（海康） | TCP       | 见下方 setup 角色   |
| `RTP/AVP`（大华）     | UDP       | DatagramSocket 直发 |

TCP 时按 RFC 4145 `a=setup` 决定角色：

```text
设备 setup:active/actpass → 平台监听 sendPort（answer a=setup:passive），
                            设备收到 200 OK 后主动连入
设备 setup:passive       → 平台主动连接设备 m=audio 端口（answer a=setup:active）
```

注意：设备 SDP 的 `audioPort` 与平台 answer 的 `sendPort` 是两端各自的收流端口，不混用。

---

## 19.5 SSRC 必须使用设备 INVITE 的 y=

国标约定 INVITE 的 `y=` 即本会话媒体流 SSRC。语音广播方向与点播相反：

```text
点播：       平台 INVITE 带 y= → 设备按该 SSRC 推流
语音广播：   设备 INVITE 带 y= → 平台必须按该 SSRC 发送
```

海康按 INVITE 的 `y=` 过滤收包，SSRC 不符即判非法流。设备未携带 `y=` 时退回平台随机生成。

---

## 19.6 TCP 承载 RTP 必须按 RFC 4571 封装（秒 BYE 根因）

### 现象

海康设备（`TCP/RTP/AVP` + `setup:active`）信令全通：

```text
Broadcast 200 → 设备 INVITE → 平台 200 OK → ACK → 设备 TCP 连入平台监听端口
```

但 TCP 接入后 **~100ms 内设备主动 BYE**。UDP 模式（大华）同链路正常。

### 排查过程（逐项排除）

| 排查项                                | 结论          |
| ---------------------------------- | ----------- |
| answer SDP（IP/端口/PT/setup 角色）      | 无问题        |
| 无音频看门狗（加静音帧后仍 BYE）            | 已防御，非根因   |
| SSRC 与设备 y= 不一致                  | 修正后仍 BYE，非根因 |
| TCP 封包格式                          | **根因**     |

关键时序特征：**空连接（不发数据）可存活 500ms+，一旦发送字节流 ~100ms 内必 BYE** —— 设备能正常协商建链，一收到数据就解帧失败。

### 根因

GB/T 28181-2022 附录D：TCP 方式传输媒体的 RTP 封装格式参照 **RFC 4571** —— 每个 RTP 包前必须加 **2 字节网络字节序长度前缀**。

平台原实现把 RTP 包裸写 TCP 字节流，按附录D 解帧的海康无法定界包边界：

```text
裸 RTP 字节流 → 无法解帧 → 判非法流 → 首包后百毫秒级 BYE
```

（RTSP interleaved `$`+channel+length 4 字节前缀仅用于 RTSP over TCP，国标设备不识别；代码保留为调试开关 `TCP_INTERLEAVED_FRAMING=false`。）

### 修复

`RtpSender.sendFrame()` TCP 分支：

```text
2 字节长度（网络字节序） + RTP 包
```

UDP 分支不变（DatagramPacket 直接发 RTP 包）。

### 通用结论

> SIP/SDP 协商成功 ≠ 媒体链路可用。TCP 承载 RTP 时，"如何划分包边界"是独立于 SIP 的封装层问题；国标设备按附录D（RFC 4571）解帧，平台必须按同一标准封帧。
> 若后续遇到按 2016 版实现的设备只认裸 RTP 流，可将 TCP 封装做成可配置（`raw` / `rfc4571`）。

---

## 19.7 UDP 源端口必须绑定 answer 声明的 sendPort（~12s 无媒体 BYE 根因）

> 2026-09-18 海康关 TCP（走 UDP）联调发现，与 19.6 并列的 UDP 模式独立坑。

### 现象

海康设备 UDP 模式（INVITE `m=audio ... RTP/AVP`）：信令全通、会话进入 TALKING，但设备侧始终"收不到"音频，约 **12s 后设备发 BYE（无媒体超时）**。平台侧 RTP 已按 20ms 节奏正常发出，目标 IP/端口、SSRC、PT 均正确。

### 根因

UDP 发送 socket 源端口是操作系统随机分配的**临时端口**，而海康按 200 OK answer 声明的 `IP:sendPort` **校验 RTP 源地址**——源端口不符的包被设备**静默丢弃**（不回任何错误）：

```text
RTP 包目标地址正确 + SSRC/PT 正确，但源端口 ≠ answer 声明的 sendPort
        ↓
设备按 answer 的 IP:port 过滤收包 → 静默丢弃 → 一直无有效流
        ↓
约 12s 无媒体超时 → 设备主动 BYE
```

这类问题无任何信令侧反馈，纯靠设备侧抓包或"对比 answer 声明与实际发包源端口"定位。

### 修复

`RtpSender` 构造 UDP 分支：`DatagramSocket` 显式 `bind(sendPort)`（见 `VoiceBroadcastService.java` RtpSender 构造器）。配套要点：

* UDP 发送器在 **200 OK 发出之前**创建并绑定，绑定失败（端口占用）换随机端口重试 3 次——此时 answer 尚未发出，换端口安全；
* TCP 模式同机制：`ServerSocket` 绑定 sendPort，设备按 answer 端口连入。

### 通用结论

> 国标设备普遍按 SDP 声明的地址五元组校验 RTP 收包，不仅校验目标地址，**也校验源地址**。平台直发 RTP 时，UDP 必须绑定 answer 声明的端口作为源端口，不能用随机临时端口。

---

## 19.8 空闲静音帧（TCP/UDP 看门狗防御）

设备 ACK 后立即启动 20ms 发送任务；缓冲不足 160 字节时发 G.711A 静音帧（160 样本全零 PCM 的 A-law 编码，**TCP/UDP 均适用**）：

```text
浏览器首批音频约 500ms 后才到
        ↓
若期间不发任何 RTP
        ↓
设备建链后 ~600ms 收不到数据 → 触发无音频看门狗 → BYE
```

静音帧保证 RTP 连续，真实音频到达后无缝接管。TCP 未建链时缓冲上限 64KB，超出丢弃防积压。

---

## 19.9 平台 answer SDP 要点

* `c=` 必须为平台 SIP IP（`sip.server.ip`）—— 直发模式下媒体由 SIP 平台自身收发，不能误用 ZLM mediaIp；
* `a=sendonly`（平台只发不收）；
* `a=setup` 与设备角色互补（RFC 4145）；
* `f=v/////a/1/8/1`（仅音频 PCMA 8kHz 单声道，部分海康固件校验该行）；
* `y=` 回显设备 INVITE 的 SSRC。

---

## 19.10 HTTP API

| 方法  | 路径                                  | 说明                              |
| --- | ----------------------------------- | ------------------------------- |
| POST | `/api/voice/start?deviceId=&channelId=` | 发 Broadcast，创建语音会话              |
| POST | `/api/voice/stop?deviceId=&channelId=`  | 平台 BYE + 清理（TCP 连接/UDP socket） |
| GET  | `/api/voice/sessions`               | 语音会话列表                          |
| WS   | `/ws/voice?deviceId=&channelId=`   | 浏览器二进制 PCM 上行（8kHz/16bit/LE/单声道） |

---

## 19.11 已知边界

* 音频编码固定 G.711A/8000（PCMA）；设备 SDP 未协商 PCMA 则终止会话；
* ZLM 转发路径（`startSendRtp`）保留但当前未启用，平台直发 RTP 为运行主线；
* 平台关闭（`ContextClosedEvent`）对所有活动语音会话发 BYE；
* `RtpSender` 为 `VoiceBroadcastService` 内部类，与拉流侧 `RtpReceiver`（收）方向相反（发）。

---

# 20. 文档维护说明

本文档以源码实际行为为准。

如果出现：

```text
文档
```

与：

```text
源码
```

不一致：

> **以当前源码为最终准则。**

重大变更建议同步更新：

```text
PlayService
PlaybackService
RtpReceiver
FlvBroadcast
SipRequestBuilder
RecordInfo
ZlmMediaService
```

对应技术章节。

---

*文档基于源码与实际设备联调结果整理。原始版本：2026-09-11；2026-09-17 增补历史回放、录像查询、ZLMediaKit 媒体面，并补充 local 模式回放停止资源释放、FFmpeg Pipe、RTP Session 清理及海康/大华兼容性技术要点；2026-09-18 增补语音广播/对讲全链路（第 19 章），含海康 TCP 模式秒 BYE 排查与 RFC 4571 封装修复，以及 UDP 模式源端口静默丢包（~12s 无媒体 BYE）排查与 sendPort 绑定修复；2026-09-20 zlm 模式直播/回放浏览器播放切换为 WebRTC（新增 14.7）、补充 local 模式重复会话问题分析（5.2.4，FlvController 拉流改复用会话，结论：接口去三元、统一幂等复用语义）、openapi 包按职责重组为 api 子包（controller/auth/config/dto/exception）。*
