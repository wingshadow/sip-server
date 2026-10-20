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
21. [代码阅读指南](#21-代码阅读指南)
22. [四大功能调用关系（点播 / 回放 / 下载 / 语音）](#22-四大功能调用关系点播--回放--下载--语音)
23. [录像回放/下载摄像机压力优化](#23-录像回放下载摄像机压力优化)
24. [ZLM 模式实现：架构、模式与代码细节](#24-zlm-模式实现架构模式与代码细节)
25. [ZLM 模式问题排查与修复记录](#25-zlm-模式问题排查与修复记录)
26. [语音对讲实现原理（前端 + 后端）](#26-语音对讲实现原理前端--后端)
27. [语音通话音质改造方案（voice-talk 对讲链路）](#27-语音通话音质改造方案voice-talk-对讲链路)

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

> 实现坑（历史修复）：`SinkWriter.close()` 只置标志位 + `interrupt()` **不能唤醒阻塞在 `out.write()` 的写线程**——管道满且 ffmpeg 消费慢时 stdin 永远不关，ffmpeg 收不到 EOF，下载场景下不 flush 编码队列、不写 moov，产出的 MP4 损坏（`moov atom not found`）。因此 `close()` 必须直接调 `out.close()` 兜底（与写线程 finally 的 close 重复执行无害，幂等）。收尾顺序见 5.5.5 / 12.6。

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

**为什么必须补这段数据（缺什么会怎样）**：FLV 不是裸流，而是带状态的 tag 序列，播放器的解析链严格按 tag 顺序推进：

```text
[FLV Header 9B]  "FLV" 签名 + 版本 + flags
[PreviousTagSize0 = 0]
[Tag0: onMetaData]       宽高 / 帧率 / 编码格式
[Tag1: AVC seq header]   SPS + PPS，解码器的"开机钥匙"
[Tag2: 首帧（通常 IDR）]
[Tag3...: 实时帧]        每个 tag 后还跟 4 字节 PreviousTagSize
```

中途加入的客户端如果只从实时位置收数据，会缺四样东西：

1. **FLV Header（最容易补，但确实必需）**：严格解析器（ffplay 等）第一件事就是校验 `"FLV"` 签名，不认直接拒播；flv.js 的 demuxer 也靠它进入解析状态。
2. **AVC Sequence Header（这才是关键，缺了必黑屏）**：H.264 的 SPS（分辨率 / profile / level / 帧场编码）和 PPS（熵编码方式 / 参考帧管理）不在每个视频帧里重复携带，只在序列开始时出现一次；ffmpeg 把 RTP 流转封装成 FLV 时，seq header 也只写一次（在最前面）。中途接入的客户端拿不到 SPS/PPS，解码器无法初始化——哪怕是 IDR 关键帧也解不了，典型表现就是解码报错后一直无画面。
3. **onMetaData（锦上添花）**：缺了它播放器不知道 width/height/framerate，flv.js 能勉强解，但时长 / seek 行为异常。
4. **关键帧对齐**：即使有了 SPS/PPS，实时数据若正处在 P 帧中间，缺参考帧，要等下一个 IDR 才出画面（GB28181 IPC 一般 1~2s 一个 GOP，会黑 / 花屏几秒）。

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

**为什么不是给新客户端重开一路 ffmpeg**：自研链路是单路 ffmpeg 吃设备 RTP，`FlvBroadcast` 的设计目标就是多客户端共享一个 stdout。每来一个新客户端就重启 ffmpeg 意味着：重新等设备推流（秒级延迟）、CPU 翻倍、设备侧可能还要重新协商。因此把流最开头那段字节缓存在内存里即可。

代码对应：`readLoop` 每次读到 ffmpeg stdout 数据后先 `appendBootstrap()`，把最开头的一段（默认累计 64KB 判定就绪、上限 2MB）存入内存，这段天然包含 FLV Header + Metadata + seq header + 开头一小段帧；`subscribe()` 则在 `broadcastLock` 保护下原子完成"入列 + 投递 bootstrap 快照"，保证新客户端队列顺序恒为 `[bootstrap..., 实时...]`（乱序问题详见 5.7.2）。

**两个边界**：

- bootstrap 存的是"流开头"而不是"最近一个完整 GOP"，所以新客户端先看一小段从流起点开始的数据、再跳到实时。直播场景可接受；要做到严格无缝，需要缓存最近一个 GOP 并对齐 IDR，复杂度高不少。
- 64KB 的就绪阈值是经验值：header + metadata + seq header 通常只有几百字节到几 KB，64KB 基本保证已经拿到 seq header，同时还多带了一点开头数据兜底。

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

核心原因：AVC Sequence Header（SPS/PPS）只在流开头出现一次，中途接入的客户端缺它，即使收到 IDR 关键帧也无法解码；FLV Header 与 onMetaData 同样缺失。完整分析（tag 序列、四类缺失、为何不重开 ffmpeg、边界）见 5.7.1。

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

回放与点播共用同一条转码管道（`MediaService.buildFfmpeg` 非 DOWNLOAD 分支，`MediaService.java:1122-1134`）：

```bash
ffmpeg -f mpeg -i pipe:0 \
  -map 0:v:0 -map 0:a:0? \
  -c:v copy -c:a aac -b:a 64k \
  -flvflags no_duration_filesize \
  -f flv pipe:1
```

历史演进与设计取舍：

```text
设备历史录像可能跨多个文件片段
    ↓
拼接处 PTS/DTS 不连续
    ↓
-c:v copy 直封时播放器时间轴异常（花屏/跳变）
    ↓
曾采用 -fflags +genpts + libx264 -preset ultrafast -tune zerolatency 重编码规避（CPU 代价高）
    ↓
现行方案回到 copy 直封（CPU 优先），跨片段异常依赖看门狗兜底（见 12.5）
```

> 排障提示：`-c:v copy` 下 FLV muxer 对缺失 PTS 敏感，历史上设备侧时间戳不规范时报过 `Packet is missing PTS → Error submitting a packet to the muxer → exit -22`；遇到回放花屏/时间轴跳变先查录像片段边界。DOWNLOAD（离线下载）是另一套参数（libx264 + crf + faststart 落 MP4），见第 5 章。

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

---

# 21. 代码阅读指南


> 本项目代码由 AI 大量生成，本文档的目标是：**让第一次读这份代码的人（或 AI）在最短时间内建立正确的心智模型**，知道每个文件负责什么、按什么顺序读、核心链路在代码里怎么走、哪些约定不能违反。
>
> 与已有文档的分工：
>
> | 文档 | 视角 |
> |------|------|
> | `README.md` | 项目总览：功能、API、部署、已知问题（11.x 节是踩坑索引）、培训篇（第 17 章） |
> | `TECH.md` | 实现深度：逐模块剖析、时序图、并发设计、设计决策（本章即其第 21 章） |
> | **本章（第 21 章）** | **代码走读：文件地图、阅读顺序、调用链行号锚点、代码约定** |
> | 第 22 章 | 功能视角：点播/回放/下载/语音四条业务全链路调用关系，含 zlm 模式（ZLM 收流 → WebRTC）专链 |
> | 第 24-25 章 | ZLM 模式架构与排障实录 |
> | `协议参考手册.md` | 协议标准：GB/T 28181（第一部分）/ RTP/RTCP（第二部分）/ G.711（第三部分）/ Digest 鉴权（第四部分）/ 级联设计（第五部分） |

---

## 1. 五分钟建立全局观

### 1.1 启动链（进程起来后世界是什么样）

```text
SipServerApplication.main()
  └─ Spring 扫描 com.example.sip 下所有 @Component/@Service/@Configuration
       ├─ UdpSipServer.afterPropertiesSet()   → Netty NioDatagramChannel 监听 UDP sip.server.port（5060）
       ├─ MediaService / VoiceService / ...   → 就绪，持有各自的会话 Map（ConcurrentHashMap，内存态）
       └─ FlvBroadcast / ZlmMediaService      → 媒体分发设施就绪

此后一切由两类事件驱动：
  ① 设备 UDP 报文到达 5060 → UdpSipServer.channelRead → SipRequestHandler.handle → *Handler → *Service
  ② 浏览器 HTTP/WS 请求     → *Controller / VoiceWebSocketHandler → *Service → UdpSipServer.send 主动发信令
```

### 1.2 一张图看数据流

```text
摄像头 ──UDP 5060 SIP 信令──▶ Netty(UdpSipServer) ──▶ SipRequestHandler ──▶ 各 Handler ──▶ 各 Service
                                                                                    │
浏览器 ──HTTP/WS──▶ Controller / VoiceWebSocketHandler ────────────────────────────┘（Service 回调发 INVITE/BYE）

视频下行：设备 RTP(UDP 动态端口) ─▶ RtpReceiver ─▶ ffmpeg(PS→FLV) ─▶ FlvBroadcast ─▶ 浏览器
语音下行：设备 RTP(UDP/TCP 会话端口) ─▶ VoiceRtpEndpoint ─▶ G711a 解码 ─▶ VoiceWebSocketHandler ─▶ 浏览器
语音上行：浏览器 PCM(WS) ─▶ G711a 编码 ─▶ VoiceRtpEndpoint.send ─▶ 设备
```

**核心心智模型：本项目没有"框架魔法"，就是一个 Netty UDP 收包器 + 一堆内存 Map 会话表 + 主动拼 SIP 报文发出去。** 所有业务最终都落到「拼报文 / 收报文 / 管 socket / 管 ffmpeg 进程」四件事上。

---

## 2. 包结构与文件地图

按 `src/main/java/com/example/sip/` 下的包列出（行数为近似值，以仓库为准）。

### 2.1 入口与信令层（读码第一站）

| 文件 | 行数 | 职责 | 阅读优先级 |
|------|------|------|-----------|
| `SipServerApplication.java` | 12 | Spring Boot 入口，仅启动 | ★ 一眼过 |
| `netty/UdpSipServer.java` | 102 | UDP 5060 收发；`send()` 是全项目唯一主动发信令出口 | ★★★ 必读 |
| `handler/SipRequestHandler.java` | 261 | 信令路由器：解析 → 按方法/CSeq 分发 → 兜底响应；**入口先拦截双 CRLF 保活**（见 4.5） | ★★★ 必读 |
| `handler/SipChannelHolder.java` | 29 | 持有当前 Netty Channel（UDP 无连接，每包更新） | ★ 工具类 |
| `handler/SipResponder.java` | 108 | 响应发送辅助（自动回显 Via/From/To/Call-ID/CSeq） | ★★ |
| `handler/SipUtil.java` | 209 | 报文解析/构造工具函数集合 | ★★ 查用 |
| `handler/RegisterHandler.java` | 168 | REGISTER 注册鉴权 → `DeviceRegistry` | ★★ |
| `handler/DeviceMessageHandler.java` | 279 | MANSCDP MESSAGE：心跳/目录(分页聚合)/告警/录像分发 | ★★ |
| `handler/AlarmSubscribeHandler.java` | 425 | SUBSCRIBE/NOTIFY 告警订阅 | ★ 按需 |
| `handler/RecordQueryHandler.java` | 238 | RecordInfo 录像查询多包聚合 | ★ 按需 |
| `handler/MediaSignalHandler.java` | 65 | 视频媒体信令（点播/回放 INVITE/ACK/BYE/响应）薄转发层 | ★★★ |
| `handler/VoiceSignalHandler.java` | 94 | 语音信令薄转发层 → `VoiceService` | ★★★ |
| `model/SipMessage.java` | 144 | 精简 SIP 报文模型（首部 + body，body 按 ISO-8859-1 保字节） | ★★★ 必读 |
| `sip/SipRequestBuilder.java` | 919 | **所有主动发出的 SIP 报文拼装**（INVITE/ACK/BYE/MESSAGE…） | ★★★ 必查 |
| `sip/SipResponseBuilder.java` | 168 | 响应拼装（RFC 3261 首部回显规则） | ★★ |
| `sip/SdpParser.java` | 167 | SDP 解析（IP/端口/PT/SSRC/setup 角色），视频+音频通用 | ★★★ 必读 |
| `parser/RecordInfoParser.java` | 120 | RecordInfo XML → `RecordInfo` | ★ 按需 |

### 2.2 业务服务层（体系主干）

| 文件 | 行数 | 职责 | 阅读优先级 |
|------|------|------|-----------|
| `service/DeviceRegistry.java` | 112 | 设备注册表（内存 Map，心跳保活） | ★★★ |
| `service/CatalogService.java` | 60 | 目录查询应答 XML 生成 | ★★ |
| `service/AuthService.java` | 106 | 页面登录 token 鉴权 | ★ 按需 |
| `service/media/MediaService.java` | 1163 | **视频媒体总入口**：直播/回放/下载三合一，INVITE 协商、ffmpeg 生命周期、看门狗 | ★★★ 核心 |
| `service/media/RtpPortPool.java` | 120 | 媒体 UDP 端口池（30000-30099，可配） | ★★ |
| `service/media/RtpReceiver.java` | 614 | 设备 RTP 接收、按 SSRC/源/ Call-ID 分发、首包日志、统计 | ★★★ |
| `service/local/FlvBroadcast.java` | 506 | FLV 实时广播（订阅者管理、慢客户端剔除） | ★★★ |
| `service/voice/VoiceService.java` | 1058 | **语音总入口**：广播 + 对讲 + 设备直呼，SDP 协商、收发调度、统计 | ★★★ 核心 |
| `service/voice/VoiceRtpEndpoint.java` | 525 | 语音 RTP 端口池 + UDP/TCP 收发（含 RFC 4571 成帧） | ★★★ 核心 |
| `service/audio/G711a.java` | 159 | G.711 A-law 编解码（纯查表） | ★★ |
| `service/zlm/ZlmMediaService.java` | 940 | ZLMediaKit 媒体面托管（zlm 模式） | ★ 按需 |
| `service/zlm/ZlmApiClient.java` | 515 | ZLM HTTP API 客户端 | ★ 按需 |

### 2.3 对外接口层

| 文件 | 行数 | 职责 |
|------|------|------|
| `controller/DeviceController.java` | 433 | 管理页 API：`/api/devices`、`/api/play/*`、`/api/playback/*`、`/api/voice/*`、`/api/records/*` |
| `controller/FlvController.java` | 439 | FLV 拉流入口：`/live/{dev}/{ch}.flv`、`/playback/...flv`、下载文件/状态 |
| `controller/LoginController.java` | 101 | 页面登录 |
| `api/controller/OpenApiController.java` | 375 | 对外 Open API（`/api/v1/**`，X-API-Key 鉴权） |
| `api/auth/OpenApiAuthInterceptor.java` | 94 | API Key 拦截器 |
| `api/exception/*`、`api/dto/*` | — | Open API 异常映射与 DTO |
| `websocket/VoiceWebSocketHandler.java` | 166 | 语音上下行 WebSocket 通道（`/ws/voice`） |
| `config/*` | — | 配置绑定（`SipServerProperties`/`MediaProperties`/`VoiceProperties`…）、页面鉴权过滤器、静态资源映射 |

### 2.4 模型层

| 文件 | 说明 |
|------|------|
| `model/Device.java` | 接入设备（国标编码、IP、心跳时间戳） |
| `model/Channel.java` | 设备通道（目录查询返回项） |
| `model/StreamSession.java` | 视频会话：覆盖 LIVE/PLAYBACK/DOWNLOAD 三类业务 + 完整状态机 |
| `model/VoiceSession.java` | 语音会话：状态机 `BROADCASTING→AWAIT_INVITE→INVITED→TALKING→STOPPED`（`VoiceSession.java:23`），RTP seq/ts、音频缓冲、协商结果 |
| `model/RecordInfo.java` / `RecordInfoQuery.java` | 录像查询应答模型 |

---

## 3. 推荐阅读顺序（四轮，约半天）

**第一轮：骨架（~1h）** —— 先搞清楚"一个报文怎么进来的"
`SipServerApplication` → `UdpSipServer`（注意 `@Lazy` 注入 `SipRequestHandler` 打破循环，见其类注释）→ `SipRequestHandler.handle()`（`SipRequestHandler.java:72`，按方法/CSeq 分发的总枢纽）→ `SipMessage`（报文长什么样）→ `SipChannelHolder` + `SipResponder`（怎么发回去）。

**第二轮：信令闭环（~1.5h）** —— 设备怎么上线、怎么点播
`RegisterHandler` → `DeviceRegistry`（设备表就一个 Map）→ `DeviceMessageHandler`（心跳/目录/告警）→ `CatalogService`。然后 `MediaSignalHandler`（65 行，全是转发）→ `MediaService.startLive()`（`MediaService.java:139`）→ `RtpPortPool` → `RtpReceiver`。

**第三轮：媒体到浏览器（~1.5h）** —— 视频怎么播起来的
`RtpReceiver`（收包分发 + 首包日志）→ `MediaService` 里 ffmpeg 启动段（搜 `startFfmpeg`）→ `FlvBroadcast`（订阅/分发/慢客户端剔除）→ `FlvController.liveFlv()`（`FlvController.java:91`）。zlm 模式只需知道 `MediaService` 退化为门面、媒体面交给 `ZlmMediaService`。

**第四轮：语音对讲（~2h，本项目踩坑最多、协议最拧巴的模块）**
`VoiceSignalHandler` → `VoiceService.startTalk()`（`VoiceService.java:267`，平台主动对讲）→ `VoiceService.handleInviteResponse()`（`VoiceService.java:793`，**TCP/UDP 传输判定的关键**）→ `VoiceRtpEndpoint.bind/send/receiveLoop` → `G711a` → `VoiceWebSocketHandler`。广播路径（`startBroadcastTalk`，`VoiceService.java:336`）与对讲共用同一套媒体设施，差异只在信令方向。

---

## 4. 核心链路代码走读（带行号锚点）

### 4.1 设备注册与心跳

```text
设备 REGISTER
  → UdpSipServer.channelRead0 → SipRequestHandler.handle (:72) → case "REGISTER" (:101)
  → RegisterHandler.handle (:69)
      ├─ resolvePassword(deviceId)：device-passwords[id] → ["*"] → password（:91）
      ├─ 配了密码且无 Authorization → 401 + WWW-Authenticate(realm/nonce/stale)（:93-98）
      │     nonce 按设备编号记录，TTL=sip.server.nonce-ttl-seconds（默认 300s，:196-202）
      ├─ 带 Authorization → verifyDigest (:147)：nonce 归属/时效校验 → HA1/HA2/response 重算
      │     → SipDigestAuth 常量时间比对（:188）→ 失败回 401（nonce 过期时 stale=true）
      └─ 通过 → registry.register(:129) / expires<=0 时 unregister(:114)
  → 200 OK 带 Date + Expires（SipResponseBuilder.buildRegisterOk :120）
设备 MESSAGE(Keepalive)
  → DeviceMessageHandler：刷新 lastHeartbeat；超时（sip.server.heartbeat-timeout）判离线
设备 MESSAGE(Catalog)
  → handleCatalog：SumNum>0 按 deviceId#SN 分页聚合（mergeCatalog :183），
    收齐 SumNum 条才整体覆盖设备通道（防后到分片冲掉先到通道）；
    TTL 30s 超时按已收部分落地；SumNum 缺失/为 0 按单包全量
```

**读这段要搞清的**：`DeviceRegistry` 是全项目唯一"设备真相源"；`SipChannelHolder` 每包更新是为了 BYE/INVITE 能回到设备最新来源地址。注意本项目有**两层 keep-alive**：本节的应用层 MANSCDP 心跳（管在线/离线状态）与传输层双 CRLF 保活（管 NAT 映射，是平台主动信令的命脉，见 4.5），两层互不替代。注册 Digest 鉴权的协议机制与设备侧配置见 `README.md` 11.16；`SipDigestAuth.java`（:35-53）的四个公式（HA1/HA2/有无 qop）与 RFC 2617 一一对应，`uri`/`realm` 一律以设备回带值为准是兼容国标设备的关键（`RegisterHandler.java:142-143` 注释）。

### 4.2 实时点播（local 模式）

```text
POST /api/play/start → DeviceController → MediaService.getOrStartLive (:150)
  → RtpPortPool.acquire 拿独占 UDP 端口 → new StreamSession(LIVE)
  → SipRequestBuilder 拼 INVITE（SDP 通告该端口 + SSRC）→ UdpSipServer.send
设备 200 OK → SipRequestHandler.handleResponse (:230) → MediaSignalHandler → MediaService.handleResponse (:365)
  → SdpParser 解析 answer（IP/端口/PT）→ 发 ACK → 启动 RtpReceiver(端口) + ffmpeg(PS→FLV)
  → FlvBroadcast.waitReady → 会话 TALKING/READY
浏览器 GET /live/{dev}/{ch}.flv → FlvController.liveFlv (:91) → MediaService.getFlvBroadcast → FlvBroadcast 持续写 FLV tag
停止：FlvController 检测客户端断开 → MediaService.stopIfNoClients (:303) → BYE + 释放端口/杀 ffmpeg
```

**坑位预告**：200 OK 后设备可能立即 BYE（海康严格校验）、ACK 载荷必须回显 answer 协商结果——详见 `README.md` 11.1/11.2/11.4。

### 4.3 语音对讲（重点，含 2026-10 最终结论）

```text
POST /api/voice/start → DeviceController (:281) → VoiceService.startTalk (:195)
  → VoiceRtpEndpoint.acquirePort + bind（DatagramSocket + 同端口 ServerSocket 双监听）
  → SipRequestBuilder.buildTalkSdp：m=audio <port> RTP/AVP 8 + a=sendrecv（UDP！）
  → INVITE → 设备 200 OK → VoiceService.handleInviteResponse (:720)
       ├─ SdpParser 解析 answer
       ├─ 传输判定：answer 为 TCP/RTP/AVP → session.tcp=true（走 TCP 回退）；
       │            为 RTP/AVP → tcp=false（UDP，正常路径）
       └─ ACK → TALKING；启动 voice-send 线程（20ms/160B PCMA 节奏）
上行：浏览器 PCM(WS) → VoiceService.pushAudio (:417) → G711a.encode → VoiceRtpEndpoint.send (:141)
       → UDP：DatagramSocket 发往 answer 的 audioIp:audioPort
       → TCP：按 RFC 4571（$ + 通道 + 2字节长度）写入设备主动连入的连接
下行：设备 RTP → VoiceRtpEndpoint.receiveLoop (:399) → VoiceService.onDeviceAudio
       → G711a.decode → PCM → VoiceWebSocketHandler 二进制帧 → 浏览器 AudioContext
停止：BYE + release（关 socket、还端口、清会话）
```

**海康 IPC 实测结论（2026-10-16，README 11.14 有完整版）**：该设备 TCP 模式下能建连、下行能播，但**麦克风音频永不上行**（只在 TCP 连接上发 pt=11/45B/~10s 一包的异常小包）；改 UDP 后双向正常。因此 `buildTalkSdp` 固定发 `RTP/AVP`，同时保留"设备答 TCP 就回退 TCP"的兼容逻辑。

**读这段要搞清的**：① `handleInviteResponse` 是全链路传输模式的唯一分叉点；② 判定设备是否真在上行，看 `RTP统计` 日志的「收包」增长与「包率/包长/pt」三要素，别信解码峰值（异常包盲解 G711a 会有非零峰值的假象）。

### 4.4 语音广播（喊话）

```text
VoiceService.startBroadcastTalk (:336) → MESSAGE(Broadcast) → 设备反向 INVITE
  → VoiceSignalHandler.handleInvite → VoiceService.handleDeviceInvite (:706)
  → 平台 200 OK（answer SDP 通告会话端口）→ 设备 ACK → TALKING
```

与对讲的区别仅在**信令方向**：广播是设备 INVITE、平台做 UAS 应答；媒体面完全共用 `VoiceRtpEndpoint`。

### 4.5 传输层 CRLF 保活（RFC 5626 §5.1，平台主动信令的命脉）

```text
设备周期性 ping：双 CRLF（4 字节 \r\n\r\n）
  → SipRequestHandler.handle (:72) 第一站拦截——先于任何 SIP 解析
  → isCrlfKeepAlive (:141)：2~4 字节且全部为 CR/LF → 判定为保活
  → channelHolder.set(ctx.channel())：顺带刷新"活跃通道"（UDP 无连接，每包更新）
  → SipResponder.sendRaw (:68)：回单 CRLF（\r\n）pong，成功不打 INFO 日志
  → return：不进 SipMessage.parse
```

**为什么必须有**：设备大多在 NAT 后。注册成功后，平台向设备主动发信令（点播 INVITE、目录 MESSAGE、BYE、告警 SUBSCRIBE）全部依赖从注册/心跳报文来源学到的映射地址。NAT 映射租期通常仅 15~60s：设备的 ping 刷新出方向租期，平台的 pong 穿过同一条映射完成双向续租（Full/Restricted Cone NAT），同时满足 RFC 5626「对端必须回 pong 确认 flow 存活」的要求。**映射一过期，平台所有主动信令石沉大海**——现象是设备"在线"却对任何请求都没有响应。

**判定规则（`isCrlfKeepAlive`，:141-151）——长度窗 2~4 是刻意收紧的**：

| 收到的字节 | 处理 |
|---|---|
| `\r\n\r\n`（4B） | ping → 回单 CRLF pong |
| `\r\n`（2B） | 设备回的 pong → 同样应答（无害，且维持映射） |
| `\n\n`/`\r\r` 等 2~4 字节全 CR/LF 组合 | 兼容不同设备栈写法 |
| 其它任何报文（含 4 字节二进制保活） | 不拦截 → 走正常 SIP 解析 |

下界 2 排除单字节杂包，上界 4 把其它协议栈的短二进制保活挡在门外。

**两层 keep-alive 的分工（互不替代，别混为一谈）**：

| 层 | 载体 | 周期量级 | 作用 | 代码 |
|---|---|---|---|---|
| 传输层 | 双 CRLF ping / 单 CRLF pong | ~15~30s | 保 NAT 映射 + flow 存活；**不更新在线状态** | 本节（`SipRequestHandler:78`） |
| 应用层 | MANSCDP `MESSAGE(CmdType=Keepalive)` | ~60s | 刷新 `lastKeepaliveTime`，驱动在线/离线超时判定 | `DeviceMessageHandler:96` → `DeviceRegistry:48`（下线判定 :106） |

**踩过的坑（已修）**：4 字节 `\r\n\r\n` 早年直接进 SIP 解析失败，每包一条 WARN「无法解析的SIP报文: 4 字节」，设备一多日志被刷屏。现两级防护：① CRLF 包在入口直接拦截，应答日志降为 DEBUG（:81）；② 其余不可解析报文按来源限流，10s 最多一条 WARN（`warnUnparseableThrottled`，:157-166，间隔常量 `UNPARSEABLE_WARN_INTERVAL_MILLIS` 见 :37）。

**读这段要搞清的**：保活应答走 `SipResponder.sendRaw` 而非统一写出口 `write`——15~30s 一条的周期，INFO 日志会淹业务日志；保活包还兼任"活跃通道"刷新器（:79），没有它 UDP 无连接场景下主动外发会找不到可用 channel。

---

## 5. 代码约定与 AI 生成代码阅读注意

### 5.1 先认日志，再读代码

本项目日志前缀就是模块地图，读代码时 grep 前缀比 grep 类名更快：

| 前缀 | 模块 |
|------|------|
| `[28181]` | 信令链路（SipRequestHandler/SipResponder/各 Handler） |
| `[VOICE]` | 语音服务（VoiceService/VoiceRtpEndpoint） |
| `[VoiceWS]` | 语音 WebSocket（VoiceWebSocketHandler） |
| `[ffmpeg-<callId>]` | 转码进程 |
| `[FLV]` | FLV 广播 |
| `[ZLM]` | ZLMediaKit 媒体面 |

### 5.2 大文件导航法

几个超大类（`MediaService` 1163 行、`VoiceService` 1058 行、`SipRequestBuilder` 919 行、`ZlmMediaService` 940 行）都是「一个公开方法 = 一个用例」的写法。读法：先 grep `public .*(` 列出方法清单当目录，再按需跳读；类头上的 javadoc 通常已经把设计意图写全了（本项目注释密度高，**类注释即设计文档**）。

### 5.3 不可违反的硬约定

1. **两个端口池不能重叠**：媒体 `sip.media.local.port-pool-*`（默认 30000-30099）与语音 `sip.voice.port-pool-*`（默认 30100-30150），配置改范围时先查 `application.yml`（`application.yml:16`、`:55` 两处有醒目警告注释）。
2. **会话 Map 的 key 约定**：业务侧统一 `deviceId:channelId`，信令侧按 `callId` 查；跨层传会话优先用 `VoiceSession`/`StreamSession` 对象而非 key 字符串。
3. **SDP 通告 IP 必须是设备可达地址**：`sip.server.ip`（双网卡机器必查，媒体地址与信令地址分离的坑都出在这）。
4. **ACK 载荷必须回显 answer 协商结果**（PT/IP/端口），设备按 ACK 一致性校验。
5. **内存态**：所有表在 `ConcurrentHashMap`，重启即失效，不要假设有持久化。

### 5.4 Spring 装配的两个特殊点

- `UdpSipServer` 用 `@Lazy` 注入 `SipRequestHandler`：Service → UdpSipServer → Handler → Service 构成环，`@Lazy` 打破循环（`UdpSipServer.java:40` 有注释说明）。
- 媒体/语音服务用 `InitializingBean`/`@PreDestroy`/`ContextClosedEvent` 管理 socket 与 ffmpeg 子进程生命周期，停机走 `destroy()`/`onShutdown()`，不要绕过。

### 5.5 排查问题时的固定动作

1. 先看日志前缀定位模块；语音问题直接看 `[VOICE] RTP统计`（5s 一条，已发送/收包/目标/tcp 四个字段）。
2. 对照 `README.md` 第 11 章「已知问题与解决方案」——本项目踩过的坑基本都在册（点播 BYE、海康静默丢包、对讲 TCP 不上行等）。
3. 信令层问题用 `SipRequestHandler` 的完整报文打印（请求/响应/ACK/BYE 都打全文）。
4. 媒体层问题记住三层独立：SIP 层（INVITE 成功）、设备推流层（RtpReceiver 收到包）、播放层（ffmpeg/FLV 有字节）——三层状态互不蕴含。

---

## 6. 想改 X 功能 → 去哪个文件

| 诉求 | 去处 |
|------|------|
| 改 SIP 报文任何字段（头/SDP/Subject） | `sip/SipRequestBuilder.java` |
| 改对讲 SDP（传输模式、PT、SSRC） | `SipRequestBuilder.buildTalkSdp`（当前 UDP，勿直接改回 TCP，见 README 11.14） |
| 改点播/回放 SDP | `SipRequestBuilder` 中对应 build 方法 + `MediaService` 构造会话段 |
| 改设备注册/心跳/目录逻辑 | `handler/RegisterHandler`、`handler/DeviceMessageHandler`、`service/DeviceRegistry` |
| 改 CRLF 保活/NAT 映射行为 | `handler/SipRequestHandler.isCrlfKeepAlive`（:141）+ `handler/SipResponder.sendRaw`（:68），原理见 4.5 |
| 改 RTP 接收/分发/统计 | `service/media/RtpReceiver.java` |
| 改 FLV 分发/慢客户端策略 | `service/local/FlvBroadcast.java` |
| 改语音收发节奏/缓冲/静音策略 | `service/voice/VoiceService.java`（RTP_INTERVAL_MILLIS 等常量在类头） |
| 改语音 TCP 成帧/端口池 | `service/voice/VoiceRtpEndpoint.java` |
| 改浏览器侧语音交互 | `websocket/VoiceWebSocketHandler.java` + `src/main/resources/static/voice.html` |
| 加 HTTP 接口 | `controller/DeviceController.java`（内部）/ `api/controller/OpenApiController.java`（对外） |
| 改配置项 | `config/*Properties.java` + `application.yml` 同步 |

---

## 7. 排坑索引

按现象直接跳 `README.md` 第 11 章对应小节：

| 现象 | 小节 |
|------|------|
| FLV 黑屏/起播失败 | 11.1 |
| 200 OK 后设备立即 BYE（海康） | 11.2 / 11.4（现场清单 12.4） |
| 对讲建连后秒 BYE（TCP 封帧） | 11.12 |
| 对讲设备"收不到"音频（UDP 源端口） | 11.13 |
| **对讲设备麦克风不上行** | **11.14（UDP 才上行，最终结论）** |
| zlm WebRTC 播放失败 | 11.15 |
| 回放花屏/时间轴跳变 | `TECH.md` 12.6（PS 多段录像 PTS 边界 + genpts 历史方案） |
| 下载进度假死 / `.part` 不落定 / 文件播不了 | `TECH.md 第 22 章` 第 3、4 节（看门狗兜底 + 为何必须等 ffmpeg 退出） |
| 日志被「无法解析的SIP报文」刷屏；设备"在线"却不应答主动信令（NAT 映射失效） | 4.5（传输层 CRLF 保活） |

> 维护约定：改完代码若影响了上述链路行为，请同步更新 `README.md` 第 11 章与本文档第 4 章的锚点。

---

# 22. 四大功能调用关系（点播 / 回放 / 下载 / 语音）


> 本文按**业务功能**横向切全项目的调用链：每个功能从浏览器 HTTP/WS 入口出发，经 Service、SIP 信令、RTP 媒体，直到出口，每一步给出 `文件:行号` 锚点。zlm 模式（设备 PS → ZLM 收流 → WebRTC）是点播/回放共用的媒体面变体，单列为第 2 节。
>
> 与其它文档的分工：
>
> | 文档 | 视角 |
> |------|------|
> | **本文** | **功能视角**：四大业务的全链路调用关系 |
> | `TECH.md 第 21 章` | 模块视角：文件地图、阅读顺序、代码约定 |
> | `协议参考手册.md 第二部分` | RTP/RTCP 协议细节 |
> | `协议参考手册.md 第三部分` | 语音编解码算法 |
> | `TECH.md 第 26 章` | 语音全链路深度（重采样/收发节奏/TCP-UDP） |
| `TECH.md 第 24 章` | zlm 模式架构与代码专题 |
| `TECH.md 第 25 章` | zlm 排障实录：固定 30002 多路复用 / 回放 WebRTC / 录像下载 |
>
> 图例：`→` 同步调用；`⇒` 报文/事件驱动；`⟳` 定时任务/看门狗；`⇐` 数据回流。
> 本文所有行号于 2026-10-20 对照当前源码逐项核实。

---

## 0. 两类驱动事件与共用基础设施

全项目只有两类事件入口，四大功能都是它们的下游：

```text
① 设备 UDP 报文（5060）
   UdpSipServer.channelRead0
     → SipRequestHandler.handle (:69)
        ├─ 双 CRLF 保活拦截 → SipResponder.sendRaw pong（:72-77，指南 4.5）
        ├─ 请求分发：REGISTER→RegisterHandler (:103) ｜ MESSAGE→DeviceMessageHandler (:106)
        │            INVITE→ 语音 :186 / 视频 :191（按 SDP 媒体类型分流：m=audio→语音、
        │                    m=video→视频、无 SDP 按语音广播，判定 :184-198）
        │            ACK→VoiceSignalHandler (:112) ｜ BYE→ 语音 :203 / 视频 :207
        │            OPTIONS (:118) ｜ SUBSCRIBE/NOTIFY→AlarmSubscribeHandler (:121/:124)
        └─ 响应分发 handleResponse (:233)：INVITE→ 语音 handleInviteResponseIfOwned (:253)
                                        否则视频 mediaSignalHandler.handleResponse (:256)
                                        SUBSCRIBE (:258) ｜ MESSAGE→语音 (:261)

② 浏览器 HTTP/WS
   *Controller / VoiceWebSocketHandler → *Service → UdpSipServer.send（全项目唯一主动发信令出口）
```

| 共用设施 | 职责 | 锚点 |
|---|---|---|
| `netty/UdpSipServer` | UDP 5060 收发；`send()` 唯一主动出口 | `UdpSipServer.java` |
| `sip/SipRequestBuilder`（895 行） | **所有主动报文拼装**（INVITE/ACK/BYE/MESSAGE/SDP） | `SipRequestBuilder.java` |
| `sip/SdpParser` | answer SDP 解析（IP/端口/PT/SSRC/setup） | `SdpParser.java` |
| `handler/SipResponder` + `SipChannelHolder` | 响应回发 + 当前 channel 持有（UDP 无连接每包更新） | `handler/` |
| `service/DeviceRegistry` | 设备注册表（唯一"设备真相源"），在线/离线判定 | `DeviceRegistry.java` |
| `service/media/RtpPortPool` | 视频媒体端口池 30000-30099（与语音池 30100-30150 **不可重叠**） | `RtpPortPool.java` |
| `service/media/RtpReceiver` | 设备 RTP 统一接收：每会话独占端口 + 专属 socket/收包线程（**端口级归属**，SSRC 仅诊断日志）、`attachSink` 外挂消费 | `RtpReceiver.java:59/293` |

**媒体分流的岔口在 `RtpReceiver.attachSink`**：视频把 ffmpeg stdin 挂上去（`MediaService.java:1064`），语音由 `VoiceRtpEndpoint` 独立收（不经过 RtpReceiver）。

### 0.1 设备寻址：注册表 host:port 是主动信令的唯一依据

注册的核心产出就是设备的 `host:port`，后续所有主动信令都靠它寻址。UDP 无连接，平台发 INVITE 时没有"回复地址"可用，只能查注册表。

**注册时怎么记**：`RegisterHandler.handle`（:69）收到 REGISTER 后，地址取自两处，**优先 Contact 头、回退 UDP 源地址**（:118-127）：

```text
String[] hp = parseContactHostPort(req.getHeader("Contact"));   // sip:id@host:port
String host = hp[0] != null ? hp[0] : sender.getAddress().getHostAddress();
int port = contactPort > 0 ? contactPort : sender.getPort();     // Contact 缺端口时用源端口
registry.register(deviceId, host, port);                          // :129
```

`sender` 是 Netty 每包的源地址，由 `UdpSipServer.channelRead0` 构造 SipMessage 时带入（`SipMessage.getSender()`）。最终存进 `DeviceRegistry.register`（:33-45）的 `Device.host/port`——即第 0 节设施表中的"设备真相源"。

两个保持地址新鲜的机制：

- **心跳/目录应答也刷新**：`DeviceMessageHandler.handleKeepalive`（:122）与 Catalog 应答（:166）用报文源地址调 `registry.keepalive`（:48-63），设备 NAT 映射变了（IP/端口漂移）能自动跟上，不用重新注册
- **超时只标记离线不删除**：`checkTimeout`（:101-111，每 10s）超时置 `online=false`，地址保留，重新心跳即恢复

**INVITE 时怎么用**：建会话时把地址带进 `StreamSession` → 发报文时取出拼目标地址：

```text
MediaService.doStartLive (:224) / startPlaybackOrDownload (:283)
  → new StreamSession(..., d.getHost(), d.getPort(), ...)（:238-240 / :326-328）
  → sendInvite (:957)
      → SipRequestBuilder.invite(props, s.getDeviceHost(), s.getDevicePort(), ...)（:965-968）
         回放/下载同左（playbackInvite :978-982）
      → sipServer.send(new InetSocketAddress(host, port), invite)（:986-988）
```

语音链同理：`VoiceService` 同样从 registry 取设备地址后建会话发 INVITE。

**一个容易混淆的点**：`SipChannelHolder` 只持有"最近一个 Netty Channel"，UDP 单通道服务所有设备，它解决的是**回包走哪个 socket**，不是设备寻址依据。INVITE 这类平台主动请求的寻址始终以注册表里的 `host:port` 为准。

### 0.2 通道复用：sessionsByKey 的 1:1 语义

"通道复用"指**媒体会话级复用**，不是网络连接复用：同一路通道（`deviceId+channelId`）已有活会话时，后续请求直接返回该会话，**不再向设备发 INVITE 拆建**。`sessionsByKey`（键 = `key(deviceId, channelId)`）表达的正是 **1:1 语义：一路通道同时只允许一个会话**。判定逻辑两处：

- 点播 `getOrStartLive`（:214-222）：同 key 会话存在且状态非 `STOPPED` → 直接返回
- 回放/下载 `startPlaybackOrDownload`（:283，`force=false` 时生效，:288-298）：同 key + **时间范围逐秒相同**（:294-295）+ **类型相同**（:296）→ 返回旧会话；任一不满足则停旧建新（:301-304）

各入口的策略差异：

| 入口 | 调用 | 策略 |
|---|---|---|
| `POST /api/play/start`（DeviceController:208） | `startLive`（:203） | **停旧建新**（用户显式重开） |
| `GET /live/…flv`（FlvController:92） | `getOrStartLive`（:214） | 幂等复用 |
| `POST /api/playback/start`（DeviceController:310） | `getOrStartPlayback`（:270） | 幂等复用 |
| `GET /playback/…flv`（FlvController:109） | 同上（:119） | 幂等复用 |
| `POST /api/download/start`（DeviceController:358） | `getOrStartDownload`（:277） | 幂等复用 |
| OpenApi `/api/v1/playback/start`（OpenApiController:155） | `force ? startPlayback : getOrStartPlayback`（:166-169） | 可强制可幂等 |

**为什么必须复用**：① 播放器会反复打接口——flv.js 重连、进度轮询、多标签页同时打开，每次新建 = 连环 INVITE，会把设备信令压垮；② 旧会话媒体流还在跑——新建会话端口/SSRC 全变，前端已拿到的播放/下载地址全部失效。

**两个强约束**：① **类型必须一致**（:291-292 注释）：DOWNLOAD 是 MP4 落盘、没有 FLV 分发，PLAYBACK 请求若复用它，前端拿到空播放地址直接黑屏；② **时间范围逐秒相同**：回放差 1 秒都不算同一片段，必须新建。

zlm 模式同样语义，只是委托 `ZlmMediaService` 自行维护（`doStartLive` → `getOrStartSession` :231-232；回放/下载 → `startPlaybackOrDownload` :314-322），幂等判定在 `ZlmMediaService.startPlaybackOrDownload`（:337-344）。

### 0.3 会话内命令 vs 会话外信令

会话（SIP Dialog + 媒体通道）建好后，平台可在同一会话内下发多条命令，不需要拆建重连——这正是"复用"的意义：命令始终作用于同一路推流。

| 命令 | 构造/调用点 | 用途 |
|---|---|---|
| `ACK` | `SipRequestBuilder.ack`（:426），`onInviteOk` 回发 | 建流三步握手最后一步 |
| `BYE` | `SipRequestBuilder.bye`（:437），`doStop` 调用 | 主动停止会话 |
| `INFO` 保活 | `SipRequestBuilder.info`（:470）→ `scheduleSessionKeepalive`（:578） | 周期发 INFO 防 NAT 老化；**仅 LIVE** 且 `session-keepalive-enabled`（:579-581） |
| `MANSRTSP` 播放控制 | `SipRequestBuilder.mansrtspInfo`（:512）→ `mansrtspPause`/`mansrtspPlay`/`mansrtspScale` | `pause`/`resume`/`scale`/`seek` |

播放控制的 HTTP 入口是 `POST /api/playback/control`（DeviceController:559-565）→ `MediaService.playbackControl`（:736-807），设备以 200 OK 应答。下载会话另有一条自动链路：`sendDownloadControl`（:690）在建流后按 `downloadSpeed` 配置补发 `SCALE`（倍速），`download-mansrtsp-play` 开启时先补发 `PLAY`——这是设备忽略 SDP `a=downloadspeed` 时的兜底（详见第 4 节）。

**三条约束**：

1. **必须带 To-tag**：`onInviteOk` 记录 200 OK 的 To-tag（:512），后续 ACK/BYE/INFO 都靠它匹配 Dialog，缺了设备不认同一会话
2. **仅 PLAYBACK 可控**：`playbackControl` 显式拒绝 zlm 会话（:738-741，Dialog 由 `ZlmMediaService` 托管）与非 PLAYBACK 类型（:746-749）；DOWNLOAD 会话一发 PAUSE 设备就停推 RTP，`.part` 直接落定
3. **命令走信令、不走媒体端口**：RTP 通道是单向的（设备→平台），所有控制都经 SIP INFO/MESSAGE 从设备信令地址（注册时登记的 host:port）发出——`playbackControl` 的发送方式（:782-783）与 `sendInvite`（:986-988）同出一辙

**对比：不在这个"通道"里的命令**（会话外信令，与 Dialog 无关，任何时候都能发，按设备 ID 寻址、不依赖任何活会话）：目录查询、录像检索（MESSAGE/MANSCDP，`RecordQueryHandler.queryRecords` :93）、告警订阅（`AlarmSubscribeHandler.subscribeAlarm` :81）、语音广播（`SipRequestBuilder.broadcastMessage`，`VoiceService` :254 调用）。

---

## 1. 点播（实时直播 LIVE）

```text
浏览器 live.html
  ├─ POST /api/play/start ──▶ DeviceController.startPlay (:209) ──▶ MediaService.startLive (:203)
  └─ GET  /live/{dev}/{ch}.flv ──▶ FlvController.liveFlv (:92) ──▶ MediaService.getOrStartLive (:214)
       （FLV 拉流入口自带幂等：已有会话直接复用，不拆建）

MediaService.startLive (:203)
  → synchronized(lockOf("deviceId:channelId")) 停旧建新（:205-209）
  → new StreamSession(LIVE) + rtpReceiver.acquirePort() (:240) 拿独占 UDP 端口 + SSRC/FromTag/Branch
  → sendInvite (:957)
      → SipRequestBuilder.invite (:135)：SDP 通告本地端口 + SSRC(y=)
      → UdpSipServer.send → 设备
      → rtpReceiver.register (:991)：分配独占 UDP 端口、绑定专属 socket 与收流线程，按 Call-ID 索引
      ⟳ INVITE 超时 10s（:69）+ 重传 {0.5,1,2,4}s（INVITE_RETRY_DELAYS :81）

设备 200 OK ⇒ SipRequestHandler.handleResponse (:233)
  → MediaSignalHandler.handleResponse (:47) → MediaService.handleResponse (:447)
    → onInviteOk (:487)
        → SdpParser.parse(answer)：登记 peerSdp/peerPayload；SSRC 不被覆盖（:492-495）
        → startMediaPipeline (:1058)
            → buildFfmpeg (:1082)：-f mpeg pipe:0 → -map 0:v:0/0:a:0? → -c:v copy -c:a aac
                               → -flvflags no_duration_filesize → -f flv pipe:1
            → rtpReceiver.attachSink (:293)：设备 RTP → SinkWriter 队列 → ffmpeg stdin
            → ffmpegExecutor.submit(drainFfmpegLog) (:1072)：stderr 按行打印（排障唯一依据）
        → sipServer.send(ACK) (:505)：ACK 载荷回显 answer 协商结果（硬约定）
        → 状态 PLAYING + setToTag（后续 INFO/BYE 依赖）

设备 RTP(PS over UDP) ⇒ RtpReceiver → attachSink → ffmpeg(PS→FLV) → FlvBroadcast 广播
浏览器 ⇐ FlvController.liveFlv (:92) → streamFlv → FlvBroadcast 持续写 FLV tag（mpegts.js 播放）

停止：
  ├─ POST /api/play/stop (:221) → MediaService.stop (:353) → BYE + cleanup (doStop :411)
  └─ FLV 最后观看者断开 → FlvController → MediaService.stopIfNoClients (:384)
        → 直播：无观看者立即 BYE 收尾（:407-408）
        → 回放/下载：仍有观众保留；5s 宽限（FLV_GRACE_MILLIS :80，:396-405）
zlm 模式：startLive 委托 ZlmMediaService（分发点 MediaService:231-233）；完整链路见本文第 2 节
```

**点播链的三个坑位**：① 200 OK 后设备可能立即 BYE（海康严格校验）；② ACK 必须回显 answer；③ 看门狗 `scheduleStreamWatchdog`（`onInviteOk` 调度 :517）：首包 60s 未到即收尾——详见 `README.md` 11.1/11.2/11.4。

---

## 2. ZLM 模式：设备 PS → ZLM 收流 → WebRTC 生成（点播/回放共用）

`media-mode: zlm` 时媒体面**不经过本服务的 ffmpeg/FlvBroadcast**：SIP 信令仍由本服务出面谈，但 INVITE SDP 通告的是 **ZLM 的地址**，设备把 RTP 直接推给 ZLM，再由 ZLM 向浏览器分发。点播与回放的信令、收流、WebRTC 生成机制完全同构，仅 SSRC 规则与 SDP 时间范围不同。

```text
浏览器 live.html / playback.html
  → POST /api/play/start (:209) / /api/playback/start (:311)
      → MediaService 按 mediaProps.isZlm() 分发（:231-233 / :314-322）
          → ZlmMediaService.startPlay (:231) / startPlaybackOrDownload (:322)
              → ensureFixedRtpServer (:391)：全项目共用一个多路复用 RTP Server
                  FIXED_RTP_SERVER_ID="gb28181"（:93），固定端口 zlm.rtp-port=30002
                  ZlmApiClient.openRtpServerMultiplex (:80)：port=30002 + tcp_mode=0 + 0.0.0.0
              → toZlmStreamId (:419)：SSRC 十进制 → 8 位大写十六进制
                  （0777716362 → 2E5B028A，即 ZLM 实际 MediaSource stream）
              → SipRequestBuilder.inviteViaZlm (:149) / playbackInviteViaZlm (:258)
                  SDP 的 c=/m= 通告 **ZLM 的 media-ip:30002**（buildZlmPlaySdp :181）
                  ——设备把 RTP 直接推给 ZLM，不经过本服务
              → sipServer.send → 设备；scheduleRetransmit (定义 :1015) 重传 + 总超时

设备 200 OK ⇒ SipRequestHandler.handleResponse (:233)
  → MediaSignalHandler.handleResponse (:47) → MediaService.handleResponse (:447)
    → zlm 分支（:452）→ ZlmMediaService.handleResponse (:1048)
        → ACK → 状态 PLAYING（仅代表信令协商完成，媒体流尚未建立）

设备 RTP(PS over UDP) ⇒ ZLM 30002 多路复用（按 SSRC 分流）
  → ZLM 注册为 MediaSource：rtp/<8位hex>（app=rtp，即 zlm.rtp-app）
本服务确认就绪：ZlmMediaService.isMediaReady (:1334) → ZlmApiClient.hasStream (:182)
  → getMediaList(app=rtp) 轮询（设备 RTP 到达 + PS 解封装约 1~2s）

浏览器取 WebRTC 信令地址：
  → GET /api/play/webrtc-url (:249) / /api/playback/webrtc-url (:261)
      → DeviceController.webrtcPlayUrl (:250)
          → mediaService.waitZlmReady(s, 15s)（:1449）：轮询 ZLM getMediaList 直到 MediaSource 注册
          → mediaService.zlmWebrtcUrl (:1468) → ZlmMediaService.webrtcUrl (:1282)
              http://<play-host>:<http-port>/index/api/webrtc?app=rtp&stream=<8位hex>&type=play
              （playHost 未配置时从 api-base 截取，:1305）
  → live.html playWebrtc (调用 :196 / 定义 :295) / playback.html playWebrtc (调用 :322 / 定义 :871)
      → new ZLMRTCClient.Endpoint({zlmsdpUrl: 该地址, recvOnly:true})（ZLMRTCClient.js setRemoteDescription :7896）
          → pc.createOffer() → setLocalDescription → HTTP POST offer 到 zlmsdpUrl（SDP candidate 改写包装 :4721-4742）
          → ZLM 回 answer SDP（type=play 即取 rtp/<stream> 流）→ setRemoteDescription
          → ICE/DTLS 完成后 ZLM 以 WebRTC 推 H.264 + PCMA，浏览器原生解码出画

停止：POST /api/play/stop (:221) / /api/playback/stop (:333) → MediaService.stop (:353)
      → ZlmMediaService.stop (:364) → stopInternal (:345/:366/:571) → cleanup (:615)
          → close_streams(app=rtp, stream=<8位hex>) 只关本会话媒体流；
            **绝不 closeRtpServer**——30002 公共端口所有会话共用（:613-614 注释）
```

**为什么 zlm 模式选 WebRTC 而不是 FLV**：设备 PS 内嵌 G.711a(PCMA) 音频，ZLM 转 HTTP-FLV 后 mpegts.js/MSE 不支持该编码（`CodecUnsupported` 直接中断整个播放）；PCMA 是 WebRTC 合法编码，浏览器原生可解，音视频都能出（`DeviceController:240-247` 注释、live.html:147 注释）。

**三个关键映射（错一个就黑屏）**：

| 环节 | 值 | 锚点 |
|---|---|---|
| INVITE SDP 通告 | `zlm.media-ip:zlm.rtp-port(30002)` | `SipRequestBuilder.buildZlmPlaySdp` (:181) |
| RTP Server 标识 | 固定 `gb28181`（所有会话共用 30002，靠 SSRC 分流） | `ZlmMediaService:93`、`:391` |
| MediaSource stream | `toZlmStreamId(ssrc)` = 8 位大写 hex；播放地址必须用它，**不是** requestStreamId | `:419`、`webrtcUrl` (:1282) |

**zlm 模式下载**（`startPlaybackOrDownload(download=true)`，:347-354）：信令与收流和回放完全相同，末端由本服务另起 ffmpeg 从 ZLM 拉 HTTP-FLV（`localFlvUrl` :781）离线转码 MP4 落盘（`buildZlmDownloadFfmpeg` :730，参数与 local 下载一致），前端契约不变（`/api/download/status` + `/api/download/file`）；ffmpeg 退出即收尾改名（`awaitZlmFfmpegExit` :852）。

---

## 3. 回放（历史录像 PLAYBACK）

回放 = 点播链 + 时间范围参数。前置动作是**录像检索**（决定回放哪个片段）：

```text
浏览器 playback.html
  → POST /api/records/query (:386) ──▶ DeviceController.queryRecords (:387)
      → RecordQueryHandler.queryRecords (:93) ──▶ MESSAGE(RecordInfo) 主动查询
      ⇐ 设备分包应答 ⇒ RecordQueryHandler 多包聚合 → RecordInfoParser.parse (parser/RecordInfoParser.java:28) → 片段列表
```

主链（与点播逐环节同构，差异仅 4 处，下文标 ★）：

```text
浏览器 playback.html
  ├─ POST /api/playback/start ──▶ DeviceController.startPlayback (:311) ──▶ MediaService.getOrStartPlayback (:270)
  └─ GET  /playback/{dev}/{ch}/{start}/{end}.flv ──▶ FlvController.playbackFlv (:109) ──▶ 同上
        （FLV 入口同样幂等复用）

MediaService.startPlaybackOrDownload (:283)   ★① 回放/下载共用私有方法，type=PLAYBACK, force=false
  → 复用判定：同 key + 同时间范围 + 状态非 STOPPED + 类型一致（:288-298）
      ★② DOWNLOAD 会话绝不被 PLAYBACK 误复用（媒体形态不同：FLV 实时 vs MP4 落盘）
  → new StreamSession(PLAYBACK) + setPlaybackStart/End + newPlaybackSsrc (:332)   ★③
  → sendInvite (:957)
      → SipRequestBuilder.playbackInvite (:241)：SDP 带播放时间范围，speed=0（回放不加速）  ★④
  → 200 OK ⇒ handleResponse (:447) → onInviteOk (:487) → startMediaPipeline (:1058)
      → buildFfmpeg 走 else 分支 (:1122-1134)：与直播完全相同（-c:v copy → FLV pipe:1）
      → FlvBroadcast 分发 ⇐ 浏览器

播放中控制（不拆建，MANSRTSP over INFO，详见 §0.3）：
  POST /api/playback/control ──▶ DeviceController (:559) ──▶ MediaService.playbackControl (:736)
      pause/resume/scale/seek，仅 PLAYBACK + PLAYING 状态可用

停止：POST /api/playback/stop (:333) → MediaService.stop (:353)
      无观看者 5s 宽限（stopIfNoClients :396-405）
zlm 模式：GET /api/playback/webrtc-url (:261)，完整链路同第 2 节（仅 SSRC 规则不同，信令构造与等待语义同点播）
```

**回放专有设施——看门狗 `scheduleStreamWatchdog`**（定义 :543，`onInviteOk` 调度 :517，三段规则见 :543-570），按 `STREAM_IDLE_CHECK_SECONDS` 周期检查 `rtpReceiver.lastPacketAt`（:620）：

| 情形 | 判定 | 动作 |
|---|---|---|
| 首包超过 `max(60, 4×空闲阈值)`s 未到 | 设备未推流 | 主动收尾（:624-629） |
| 推流中超过 `download-idle-seconds` 无新包 | 设备静默停推（不发 BYE） | 主动 BYE → ffmpeg 优雅退出 → `.part` 落定（:633-638） |
| 超过 10 倍预期时长（下限 30min）仍在推 | 设备无限灌流 | 强制收尾 |

---

## 4. 下载（录像文件 DOWNLOAD）

下载与回放**信令完全相同**（都走 `playbackInvite`），差别全在媒体出口：回放是 FLV 实时分发，下载是 MP4 落盘 + 文件服务。

```text
浏览器 playback.html
  → POST /api/download/start ──▶ DeviceController.startDownload (:359)
      → parseTime(startTime/endTime) 校验（DeviceController:595；仅拒 end < start，:366-368）
      → MediaService.getOrStartDownload (:277) → startPlaybackOrDownload (:283, type=DOWNLOAD)
          → 会话带 downloadFileName (:337) + downloadUrl=/api/download/file?... (:338)
          → sendInvite (:957) → playbackInvite + speed=downloadSpeed
              ★ SDP a=downloadspeed 倍速协商（`buildPlaybackSdp` :276，追加行 :292-295；
                倍速选择在 sendInvite :970-982，GB/T 28181-2022；设备不支持按 1x 推流）
      → 200 OK ⇒ onInviteOk (:487)
          → sendDownloadControl (:690)：可选下发 MANSCDP mansrtsp-play 播放控制
              （download-mansrtsp-play 配置，:700-708）
          → startMediaPipeline (:1058) → buildFfmpeg DOWNLOAD 分支 (:1094-1121)：
              libx264 + preset/crf（download-preset/crf，默认 medium/26）+ -g 50
              + -c:a aac 32k + -movflags +faststart → -f mp4 → <downloadDir>/<name>.part 落盘
              stdout 二进制静默丢弃（drainQuietly :1075）
      → 状态 PLAYING

设备推流结束 BYE ⇒ MediaSignalHandler.handleBye (:56) → MediaService.handleDeviceBye
  → ffmpeg 退出写 moov → finalizeDownload：.part → 正式文件名
  （设备静默停推不发 BYE 时，由回放看门狗空闲判定兜底，见第 3 节）

浏览器进度轮询：
  → GET /api/download/status ──▶ FlvController.downloadStatus (:185)
      返回 ffmpeg 已输出的媒体秒数 + .part 字节数（真实进度，:180-183 注释）
最终文件下载：
  → GET /api/download/file ──▶ FlvController.downloadFile (:142)
      → awaitFinalDownloadFile（FlvController:233）：先等 .part 出现（会话已启动），再等 ffmpeg 退出落定
      → 带 Content-Length 整文件传输（:155-170）
      ★ 为什么不能边下边传：mp4 关闭时回写修补文件头（mdat 尺寸、faststart 重排 moov），
        流式拼装会得到"大小相同但内容错位"的文件（:130-138 注释，实测复现）
  → GET /api/download/list (:285)：已下载文件清单
```

**下载链两个反直觉点**：① 进度不能用"录像时长/倍速"估算——设备不按倍速推流时估算会过早到顶假死，必须看 ffmpeg 输出时间；② 必须等 ffmpeg 完全退出再传文件，`.part` 中途不可播。

---

## 5. 语音（对讲 + 广播 VOICE）

### 5.1 对讲（平台主动 INVITE）

```text
浏览器 voice.html / voice-talk.html
  → POST /api/voice/start ──▶ DeviceController.startVoice (:426) ──▶ VoiceService.startTalk (:274)
      → VoiceRtpEndpoint.acquirePort + bind：DatagramSocket + 同端口 ServerSocket 双监听
      → SipRequestBuilder.buildTalkSdp：m=audio <port> RTP/AVP 8 + a=sendrecv（固定 UDP，README 11.14）
      → INVITE → 设备 200 OK ⇒ VoiceService.handleInviteResponse (:800)
          → SdpParser 解析 answer（首个 200 OK 生效，重传统用协商结果并重发 ACK，:811-817）
          → 设备媒体地址协商：audioIp 优先取 m=audio 媒体级 c=，缺失回退会话级 c=（:834-848）；
            0.0.0.0 / 端口 0 视为未协商，留待设备 RTP 到达后按源地址学习兜底
            （地址规则注释见 VoiceRtpEndpoint:142-155）
          → 传输判定分叉：TCP/RTP/AVP → session.tcp=true（回退）；RTP/AVP → UDP
          → 发送 ACK（:872-878）→ enterTalking（:885）→ 启动 voice-send 线程（20ms / 160B PCMA 节奏）

上行（浏览器 → 设备）：
  浏览器 PCM(16bit LE) ──WS /ws/voice──▶ VoiceService.pushAudio (:497)
    → G711a.encode (:523) → VoiceRtpEndpoint.send (:156)
        → UDP：DatagramSocket 发往 answer 的 audioIp:audioPort
        → TCP：RFC 4571 成帧（$ + 通道 + 2字节长度）写入设备主动连入的连接
  发送线程每 20ms VoiceSession.pollAlawFrame(160)（VoiceSession.java:390）取帧；
  无数据填静音帧 0xD5（createSilenceFrame :486，PCMA_SILENCE :41）

下行（设备 → 浏览器）：
  设备 RTP ⇒ VoiceRtpEndpoint.receiveLoop (:414) → VoiceService.onDeviceAudio (:630)
    → G711a.decode (:658) → PCM(16bit LE) → VoiceWebSocketHandler 二进制帧 → 浏览器 AudioContext

停止：POST /api/voice/stop (:445) → VoiceService.stop (:409) → BYE + release（关 socket、还端口、清会话）
```

### 5.2 广播（喊话，信令方向相反）

```text
VoiceService.startBroadcastTalk (:343) → MESSAGE(Broadcast) → 设备反向 INVITE
  ⇒ VoiceSignalHandler.handleInvite → VoiceService.handleDeviceInvite (:713)
    → 平台 200 OK（answer SDP 通告会话端口）⇒ 即进入 TALKING（enterTalking :795）
    → 设备 ACK ⇒ handleDeviceAck (:899)：仅置 ackReceived 确认（:904-905），不参与媒体协商
```

广播与对讲**媒体面完全共用** `VoiceRtpEndpoint`，差异只在信令方向：广播是设备 INVITE、平台做 UAS 应答。编解码细节见《协议参考手册.md》第三部分，传输判定与踩坑见《TECH.md》第 26 章/`README.md` 11.12-11.14。

---

## 6. 四条链横向对比

| 维度 | 点播 | 回放 | 下载 | 语音 |
|---|---|---|---|---|
| HTTP 入口 | `POST /api/play/start` (:209) / `GET /live/…flv` (:91) | `POST /api/playback/start` (:311) / `GET /playback/…flv` (:108) | `POST /api/download/start` (:359) | `POST /api/voice/start` (:426) |
| 对外开放入口 | `/api/v1/live/start`（OpenApiController:110） | `/api/v1/playback/start`（:155，force 可强制新建） | 无（OpenApi 未开放下载入口） | `/api/v1/voice/talk/start`（:222）/ `voice/broadcast`（:203） |
| Service 入口 | `startLive` (:203) | `getOrStartPlayback` (:270) | `getOrStartDownload` (:277) | `startTalk` (:274) |
| 会话模型 | `StreamSession(LIVE)` | `StreamSession(PLAYBACK)` | `StreamSession(DOWNLOAD)` | `VoiceSession` |
| INVITE 构造 | `invite` (:135) | `playbackInvite` (:241, speed=0) | `playbackInvite` (:241) + `a=downloadspeed`（`buildPlaybackSdp` :292-295） | `buildTalkSdp`（RTP/AVP 8） |
| 200 OK 处理 | `onInviteOk` (:487) | 同左 | 同左 + `sendDownloadControl` (:690) | `handleInviteResponse` (:800) |
| 媒体管道 | ffmpeg→FLV pipe:1 | 同点播 | ffmpeg→MP4 `.part` 落盘 (:1092-1119) | `VoiceRtpEndpoint` 裸 RTP |
| 数据分发 | `FlvBroadcast` | `FlvBroadcast` | 文件 + `/api/download/file` (:142) | WebSocket 二进制帧 |
| 编解码 | 视频 copy + aac | 同左 | libx264(CRF26) + aac | G711a 双向 |
| 停止条件 | 无观看者即停 (:384) | 无观看者 5s 宽限 (:396-405) | 设备 BYE / 看门狗兜底 (:543) | 显式 stop (:409) |
| 复用策略 | 停旧建新 | 同 key+同时间范围复用 (:288-298) | 同左（类型强校验） | 单会话互斥 |
| zlm 模式 | 委托 `ZlmMediaService`，WebRTC 取流 (:231)，链路见第 2 节 | 同左 (:314) | ZLM 收流 + 本服务拉 HTTP-FLV 转 MP4 (:311-322) | 不涉及 |

---

## 7. 关键锚点索引（改代码前先查这张表）

| 诉求 | 去处 |
|---|---|
| 改任何 SIP 报文字段/SDP | `sip/SipRequestBuilder.java` |
| 改点播/回放/下载的会话生命周期 | `MediaService.startPlaybackOrDownload`（:283）、`onInviteOk`（:487）、`cleanup`（:1290） |
| 改 ffmpeg 命令（FLV/MP4 参数） | `MediaService.buildFfmpeg`（:1082） |
| 改 RTP 接收/分发/端口绑定 | `RtpReceiver.register`（:59）、`attachSink`（:293） |
| 改 FLV 分发/慢客户端剔除 | `service/local/FlvBroadcast.java` |
| 改回放/下载看门狗 | `MediaService.scheduleStreamWatchdog`（:543） |
| 改下载落盘/倍速/进度 | `sendDownloadControl`（:690）、`FlvController.downloadStatus`（:185） |
| 改语音收发节奏/缓冲/静音 | `VoiceService.java`（pushAudio :497、onDeviceAudio :630） |
| 改语音 TCP 成帧/端口池 | `VoiceRtpEndpoint.java` |
| 加 HTTP 接口 | `DeviceController`（内部）/ `OpenApiController`（对外 `/api/v1/**`） |
| 改 SIP 请求分发路由 | `SipRequestHandler.handle`（:69）/ `handleResponse`（:233） |
| 改设备寻址/心跳刷新 | `RegisterHandler.handle`（:118-127）登记；`DeviceRegistry.register`（:33）/ `keepalive`（:48） |
| 改会话复用判定 | `MediaService.getOrStartLive`（:214）、`startPlaybackOrDownload`（:288-298）；zlm 侧 `ZlmMediaService.startPlaybackOrDownload`（:337-344） |
| 改回放控制（MANSRTSP） | `MediaService.playbackControl`（:736）、`SipRequestBuilder.mansrtspInfo`（:512） |
| 改 ZLM 收流/端口/WebRTC 地址 | `ZlmMediaService.ensureFixedRtpServer`（:391）、`toZlmStreamId`（:419）、`webrtcUrl`（:1282）；SDP 通告在 `SipRequestBuilder.inviteViaZlm`（:149） |
| 改 ZLM HTTP API 调用 | `service/zlm/ZlmApiClient.java`（openRtpServerMultiplex :80、getMediaList :158、findStreamByRtpPort :209） |

---

## 8. 排坑入口

| 现象 | 去处 |
|---|---|
| FLV 黑屏/起播失败 | `README.md` 11.1 |
| 200 OK 后设备立即 BYE（海康） | `README.md` 11.2 / 11.4 |
| 回放花屏/时间轴跳变/`.part` 不落定 | `TECH.md` 12.5/12.6（看门狗三段兜底 + ffmpeg PTS 历史方案）+ 本文第 3、4 节 |
| WebRTC 黑屏 / ICE 协商失败 | 本文第 2 节（三个关键映射）+ 检查 ZLM `[rtc]` 端口 8000 的 UDP 是否放行（live.html:333） |
| zlm 30002 端口冲突 / 多路复用异常 | `TECH.md 第 25 章` 第一部分 |
| zlm 回放 WebRTC / 录像下载问题 | `TECH.md 第 25 章` 第二部分 |
| 对讲建连后秒 BYE / 麦克风不上行 | `README.md` 11.12-11.14 + `TECH.md 第 26 章` |
| 设备"在线"却不应答主动信令 | `TECH.md 第 21 章` 4.5（CRLF 保活） |

> 维护约定：四条链的入口/锚点随代码演进会漂移（本轮已修正 `TECH.md 第 21 章` 4.3/4.4 的语音锚点），改完信令或媒体链路请同步本文第 6、7 节。

---

# 23. 录像回放/下载摄像机压力优化


> **问题来源与定位**：该现象最初在**另一个项目调用 WVP + ZLMediaKit** 时发现——回放/下载摄像机压力大、宕机、超时。经归因确认：这是**国标回放的固有负载 + 平台使用参数问题，跨方案存在**（WVP+ZLM、自研链路、设备 SDK 概莫能外），并非某一家实现缺陷。本文档分两部分：第 1~2 章是跨方案的根因与降压原则；第 4 章起是本项目（自研 local 链路）的具体改造清单，利用自研可控性做到 WVP+ZLM 做不到的粒度。
>
> 方案原则：**先配置（零代码）验证主因，再代码改造建立设备保护闸**。每项均可独立回滚。
> 相关文件：`service/media/MediaService.java`、`controller/DeviceController.java`、`controller/FlvController.java`、`handler/RecordQueryHandler.java`、`config/MediaProperties.java`、`static/playback.html`、`sip/SipRequestBuilder.java`（MANSRTSP 命令构造）
> 关联内容：WVP+ZLM 场景下同一根因模型的调用方侧排查与处置，见 README.md 第 17 章「专题：WVP+ZLM 运维」。

---

## 1. 现象与影响

| 现象 | 直接后果 |
|------|---------|
| 回放/下载发起后摄像机无响应、重启 | 会话建立失败，页面卡"请求中" |
| 下载中途设备掉线 | RTP 中断，`.part` 无法落定，进度假死 |
| 设备恢复后长时间不再响应 INVITE | 需人工重启摄像机（参考 README 11.11 大华同类现象） |
| 录像查询时间窗过大时页面转圈 | 设备扫描 SD 卡索引，CPU 峰值，协议栈无响应 |

### 1.1 跨方案根因模型（为什么换方案解决不了）

摄像机执行回放时要做的事，任何平台都逃不掉：

```
设备侧固有负载 = 从 SD卡/存储读录像 + 解封装/解码 + 重新 RTP 封包发送
```

INVITE 一发，设备就要起一条回放流——这是物理负担。平台能控制的只是"**怎么使用设备**"：倍速开多少、同时几路、信令拆建多频繁、时间窗多宽。因此：

- WVP+ZLM 压设备，根因同样在倍速/并发/信令行为（且框架内部行为不可调，只能靠调用方策略）
- 自研链路压设备，根因一样，但**所有使用参数都在自己代码里，能调到最保守**
- 结论：自研的价值不是"压力更小"，而是"可控"；固有负载只能靠"少用、慢用、错峰用"来管理

### 1.2 跨方案降压清单（按有效性排序）

| 手段 | 说明 |
|------|------|
| 倍速降到 1~2 | 最有效，设备负载线性下降 |
| 每设备同时 ≤1 路回放/下载 | 调用方排队/串行，别并发撒任务 |
| 窄时间窗 | 录像查询按小时段查，下载分段下 |
| 错峰 | 批量下载放凌晨，避开设备业务高峰 |
| 走 NVR 不直连 IPC | 通过 NVR 通道回放，压力落在 NVR 存储与转封装上（架构级解法） |
| 设备选型 | 确需 IPC 本地高频取流，选本地回放能力 ≥4 路的机型 |

### 1.3 设备路数配额模型（一次 INVITE = 一路）

**概念澄清**：摄像机规格里的“几路”不是软件并发，是设备侧**同时可建立的媒体流上限**——且多数设备预览与回放分开计数（如“预览 4 路、回放/下载 2 路”，以厂家规格表为准）。软件并发是服务端能同时发起多少会话；设备路数是天花板。NVR 占的、平台占的，都从设备同一个配额池里扣。

**一次 INVITE = 一路**：一次回放/下载会话 = 一个 Call-ID + 一个 SSRC + 设备侧一条解码/发送链路。

| 情况 | 算几路 |
|------|--------|
| 同一会话内 PLAY+RANGE 改片段（seek，见 5.1.1） | 一路（SSRC/Call-ID 不变） |
| INVITE 事务重传（同 Call-ID 重发） | 一路（设备按 Call-ID 匹配，不开新流） |
| 多播放器看同一路（ZLM 流名命中复用） | 一路（不新增 INVITE） |
| 新 Call-ID 的 INVITE（换片段、重新发起） | **新的一路**——旧的不 BYE 就泄漏一路 |
| 级联（WVP→NVR→IPC） | 两级各一路：上级消耗 NVR 一路，NVR 消耗 IPC 一路 |
| 语音对讲/广播 | 独立 INVITE，另算一路（音频流） |

**配额核算公式**：

```
同时存活（未 BYE、未超时回收）的 INVITE 会话数 ≤ 设备回放配额
```

一路的代价从 INVITE 成功**持续到 BYE（或设备侧超时回收）**——调用方只关页面不发 BYE，代价就累积，这是泄漏的根源。

**两条“占满”路径**（WVP 侧表象相同：INVITE 发出、200 OK 不回、SIP 事务超时）：

| 路径 | 机理 | 曲线 |
|------|------|------|
| 配额拒绝型 | 回放流配额被存活 INVITE 占满，第 N+1 个 INVITE 设备直接不应答 | 固定次数必现 |
| 整体拖死型 | 路数未满，但 N 路回放（尤其倍速 4）把 CPU/IO/协议栈打满，连心跳都不回 | 越用越慢，直到设备判离线、全线超时 |

**平台侧无复用、无感知**：GB28181 没有“配额已满请排队”也没有“我要复用你那条流”的协商原语；配额满时设备行为由厂家定（不应答 / 踢旧流 / 486 Busy）。平台（含 WVP）到超时都不知道是配额问题。唯一可能“复用”的是同源播放——同一通道同一回放源的第二个播放者命中 ZLM 已有流名则挂载播放，不新增设备流（版本相关，以实测为准）；不同取流路径（NVR 一条 + 下载一条）永远不可能互相复用。

**拓扑差异**：IPC 直连平台时，平台自己消耗 IPC 配额；IPC 挂 NVR 下、平台从 NVR 取流时，平台只跟 NVR 通信，IPC 满不满对平台透明——表现为 NVR 不应答。核算 NVR 场景要数两级账。

---

## 2. 根因分析（按嫌疑排序）

压力不在传输层，在**平台对设备的信令行为设计**。以下按代码现状逐一说明。

### 2.1 头号嫌疑：下载默认 4 倍速

`config/MediaProperties.java:67` `downloadSpeed = 4`，双通道下发：

1. INVITE SDP 携带 `a=downloadspeed:4`（`MediaService.java:696-706`）
2. ACK 后立即经 SIP INFO（MANSRTSP）下发 `SCALE 4`（`MediaService.java:417` → `sendDownloadControl`，`MediaService.java:522-547`）

低端 IPC 的 4 倍速回放 = **4 倍速率读 SD 卡 + 解码 + 重封装 + RTP 发送**，CPU 直接拉满；不支持该属性的设备被强制倍速后行为异常。这是"宕机"的典型机理。WVP 默认倍速为 1。

**`downloadspeed` 语义澄清（重要，避免误解）**：它是**时间轴倍速**，不是"文件压缩率"：

| | 1 倍速 | 4 倍速 |
|------|--------|--------|
| 传输耗时（1h 录像） | 60 分钟 | **15 分钟** |
| 收到的内容 | 完整 1h 录像 | 完整 1h 录像（**内容一样**） |
| 文件大小 | 码率 × 3600s | 码率 × 3600s（**基本不变**） |

倍速的唯一价值是缩短传输时间（批量拉历史录像场景）；文件体积由「设备编码码率 × 录像时长」决定，倍速不参与。**文件没变小、设备先垮了**——低端机上倍速下载是纯亏。想减小体积见第 6 章转码调优。

### 2.2 会话拆建风暴

管理 API 的 `/api/playback/start`、`/api/download/start` 均走 **force 新建**：

- `controller/DeviceController.java:185` `mediaService.startPlayback(...)` → `MediaService.java:212-268`
- 每次请求：`doStop(旧会话)`（**发 BYE**）→ 分配端口 → **发新 INVITE**

前端重复点击、页面重载、失败重试都会触发拆建。设备每建一次回放会话要开文件 + 解码器 + 发送器，频繁拆建容易卡死。

注意：**幂等接口已存在但从未被使用**——`MediaService.java` `getOrStartPlayback()`（同设备 + 通道 + 时间范围则复用），管理 API 与 OpenAPI 都没调它。

> 该根因已通过 5.1 幂等化修复（管理 API / OpenAPI / FLV 入口均改走幂等）；"时间范围不同仍拆建"是有意的保守设计——协议上可用会话内 MANSRTSP PLAY+RANGE 改片段，但受设备支持度约束暂不实施，备选方案见 5.1.1。

### 2.3 INVITE 5 次重传

`MediaService.java:76` `INVITE_RETRY_DELAYS = {500, 1000, 2000, 4000}`，配合 10s 超时（`MediaService.java:768-777`）：设备高负载无响应时，10 秒内补发 **5 个 INVITE**，对已过载设备雪上加霜。

### 2.4 无每设备并发上限

端口池 100 个（`MediaProperties.java:41-49`）只是**服务端容量**，设备侧无任何保护：多人同时对同一 IPC 回放/下载 → N 路并发回放流。IPC 硬件通常只支持 1~2 路回放抽取，超限直接拒服务或重启。

### 2.5 录像查询无约束

`handler/RecordQueryHandler.java:93-130`：

- 查询 XML `Type=all`（`RecordQueryHandler.java:231`），时间窗由前端自选（可能查一整天）→ 设备扫描 SD 卡索引，分几百包 MESSAGE 回传（`MAX_PACKETS=200` 兜底），协议栈 CPU 峰值高；
- 无同设备串行化：连点两次查询 = 双倍扫描。

### 2.6 看门狗空闲判定偏激进 → 放大 2.2/2.3

`MediaProperties.java:88` `downloadIdleSeconds = 15`：设备网络抖动或倍速切换停顿超 15s → 平台主动 BYE（`MediaService.java:483-488`）→ 前端重连 → 再 INVITE，形成"BYE→重连→INVITE"压力循环。

---

## 3. 方案总览

| 级别 | 内容 | 成本 | 生效方式 |
|------|------|------|---------|
| **P0** | 倍速 4→1、空闲阈值 15→30s | 5 分钟 | 改 yml，重启 |
| **P1** | 会话幂等化、每设备并发上限、查询串行+限窗、代码默认值对齐 | ~1 天 | 代码 |
| **P2** | INVITE 重传收敛、SCALE 延迟下发、前端防重入 | ~0.5 天 | 代码 |
| **P3** | README 11.17 排坑节、yml 注释 | 0.5 天 | 文档 |

**建议验证路径**：先只做 P0 在低端 IPC 上复现下载——若不再宕机，说明 2.1 是主因；若仍有，再上 P1（并发上限与幂等才是真正的设备保护闸）。

---

## 4. P0：配置调整（零代码，立即生效）

`src/main/resources/application.yml`：

```yaml
sip:
  media:
    local:
      # 下载倍速：4 对低端 IPC 压力极大（4 倍读卡+解码+发送），默认改 1。
      # 大文件下载嫌慢可试 2；设备不支持时按 1x 推流，仍可正常完成。
      download-speed: 1
      # RTP 空闲阈值：15s 偏激进，设备抖动/倍速切换即误 BYE，改 30s。
      download-idle-seconds: 30
```

改完重启即可验证。观察：设备是否重启、下载能否完成、日志 INVITE/BYE 频次。

---

## 5. P1：代码改造（设备保护）

### 5.1 回放/下载会话幂等化——已实施

**目标**：同设备 + 通道 + 时间范围的重复请求复用现有会话，不拆建。

| 文件 | 改动（已实施） |
|------|------|
| `MediaService.java` | 新增 `getOrStartDownload`（`getOrStartPlayback` 同款，内部 `startPlaybackOrDownload(..., DOWNLOAD, false)`）；复用条件增加**类型一致**判断（`s.getType() == type`）——回放（FLV 分发）与下载（MP4 落盘）媒体形态不同，DOWNLOAD 会话不能被 PLAYBACK 请求误复用 |
| `DeviceController.java` | `/api/playback/start` → `getOrStartPlayback`；`/api/download/start` → `getOrStartDownload`（默认幂等，无 force 出口，需要重置时先调 stop） |
| `OpenApiController.java` + `api/dto/PlaybackStartRequest.java` | 默认幂等；请求体加 `force`（默认 `false`），显式 `true` 时走旧 `startPlayback` 强制拆建 |

**语义保证**（`MediaService.java` `startPlaybackOrDownload` 的复用条件）：时间范围不同仍正常拆建（用户主动切换回放片段不受影响；协议上可用会话内 PLAY+RANGE 复用命令通道改片段，但受设备支持度约束暂不实施，备选见 5.1.1）；LIVE 会话不复用回放（类型不一致即不命中）；已 STOPPED 的会话不复用。

#### 5.1.1 延伸：会话内 seek 复用命令通道（备选，未实施）

**协议原理**：INVITE 建立的 SIP Dialog 存续期间，Call-ID / From-tag / To-tag / SSRC 全不变，可用 SIP INFO 承载 MANSRTSP `PLAY range:npt="<新start>-<新end>"` 让设备跳到新时间片段重新起推——设备不关旧流、不开新流，信令成本为零，是比"拆建"更轻的片段切换方式。

**项目现状：命令通道已具备，缺调用方**。

| 能力 | 位置 | 状态 |
|------|------|------|
| Dialog 内 INFO 发送（复用 Call-ID/tags，CSeq 单调递增） | `SipRequestBuilder.mansrtspInfo()` | 已有 |
| `PLAY range:npt="start-end"` 命令构造 | `SipRequestBuilder.mansrtspPlay()` | 已有 |
| SCALE 倍速（建会话时下发） | `MediaService.sendDownloadControl()` | 已有 |
| 切换片段时 seek 复用会话 | 无调用方 | 未实现 |

注：`download-mansrtsp-play` 配置仅让"必须先收到 PLAY 才推流"的设备在建会话时补发一次 PLAY，**不是**会话中途 seek。

**为什么默认不做**（`sendDownloadControl()` 注释中"时间跳变"风险之外的补齐）：

| 约束 | 说明 |
|------|------|
| 设备支持度 | 规范支持 ≠ 设备支持。低端 IPC / 旧固件对会话中途 PLAY+RANGE 的常见反应：不回 200 OK（平台干等超时）、回了但不 seek（时间轴照旧）、直接断流 |
| npt 语义各家不一 | 本项目 npt 填秒级 epoch（与 SDP `t=` 一致），但有的设备按相对秒解析、有的要求 `clock=` 格式——解析错即跳错时间 |
| 下载场景无解 | MP4 正落盘旧片段数据，中途 seek 会让新旧片段混入同一文件；换文件名重开管道等于新建下载会话 |
| 时间跳变 | 设备从新 RANGE 重推后媒体时间戳跳变，播放端表现为进度条乱跳（代码注释 528-530 已提示） |

**若要实现的建议形态**（按设备型号灰度，不建议全量）：

1. 只做 **PLAYBACK**（FLV 实时分发可重置时间基准），DOWNLOAD 保持拆建
2. 配置开关默认关（如 `playback-seek-reuse: false`），仅在验证过支持度的设备型号上开启
3. 切换片段时先尝试会话内 PLAY+RANGE，**等待 200 OK + 首包时间戳跳变确认**；超时或断流则回退拆建（5.1 幂等逻辑兜底）
4. 前端 FLV 广播层重置时间基准，否则 seek 后播放器时间轴错乱

### 5.2 每设备媒体会话并发上限

**目标**：同一设备的媒体会话（直播 + 回放 + 下载合计）超过上限时**拒绝新 INVITE**，从平台侧保护设备。

```java
// config/MediaProperties.java Local 新增
/** 单设备媒体会话并发上限（直播/回放/下载合计，0=不限）。
 *  IPC 硬件通常只支持 1~2 路回放抽取，超限会拒服务或重启。 */
private int maxSessionsPerDevice = 1;
```

检查点（两处，均在 synchronized 块内、发 INVITE 前）：

- `MediaService.java:216` `startPlaybackOrDownload` 开头
- `MediaService.java:160` `doStartLive` 开头

```java
// 计数口径：sessionsByKey 中同 deviceId 且 state != STOPPED 的会话数
int active = (int) sessionsByKey.values().stream()
        .filter(s -> s.getDeviceId().equals(deviceId)
                && s.getState() != StreamSession.State.STOPPED)
        .count();
if (max > 0 && active >= max) {
    throw new DeviceBusyException(
        "设备媒体会话已达上限(" + max + ")，请先停止现有会话: " + deviceId);
}
```

异常与响应码：

- 新增 `api/exception/DeviceBusyException.java`（extends `IllegalStateException`）
- `api/GlobalExceptionHandler.java` 新增 `@ExceptionHandler(DeviceBusyException.class)` → **409** + message（管理页面与 OpenAPI 均受益，前端直接弹提示）

### 5.3 录像查询串行化与时间窗约束

`handler/RecordQueryHandler.java`：

| 改动 | 说明 |
|------|------|
| per-device 查询锁 | `ConcurrentHashMap<String, ReentrantLock>`，同设备查询天然串行，杜绝双倍扫描 |
| 时间窗上限 | 新增配置 `maxQuerySpanHours`（默认 24，0=不限）；`(end-start)/3600 > max` → 抛 `IllegalArgumentException("查询时间窗超过 Nh，请缩小范围")`（现有 handler 返回 400，前端提示） |
| 最小查询间隔 | 新增配置 `queryMinIntervalSeconds`（默认 2）；同设备间隔未到 → 抛 `IllegalArgumentException("查询过于频繁，请稍候")`，防连点 |

配置挂 `RecordQueryHandler` 构造注入（或复用 `SipServerProperties` 扩展，实施时任选，建议前者改动面小）。

### 5.4 代码默认值对齐

`MediaProperties.java:67` `downloadSpeed` 默认 4 → **1**，注释补充低端机风险说明，防止新增部署再次踩坑（P0 改的是运行 yml，此处改代码默认值，两者最终一致）。

---

## 6. 下载文件体积优化（转码参数调优）——已实施

**背景**：用户诉求"不改变分辨率，通过 ffmpeg 转码减小文件大小"。排查发现下载管道**本来就在转码**（`MediaService.java` `buildFfmpeg` 下载分支 `-c:v libx264`），问题在参数没调优：

| 旧参数问题 | 后果 |
|-----------|------|
| 未指定 `-crf`/`-b:v` | libx264 隐式默认 CRF 23，偏保守，体积大 |
| `-preset ultrafast` | 压缩效率最低档，同 CRF 下比 medium 体积大 30~50% |
| 音频 64k | 监控人声 32k 足够 |

**已实施的改动**（代码 + 配置 + 注释，三处同步）：

| 文件 | 改动 |
|------|------|
| `config/MediaProperties.java` | 新增 `download-preset`（默认 `medium`）、`download-crf`（默认 `26`）；setter 做白名单/范围校验（非法 preset 让配置加载即失败，避免拼进 ffmpeg 命令行）；`downloadSpeed` 注释补"倍速≠压缩"语义说明 |
| `MediaService.java` `buildFfmpeg` 下载分支 | `-preset <配置> -crf <配置> -profile:v high -g 50`，音频 `-b:a 32k` |
| `application.yml` | 新增两个配置项及注释（倍速语义、CRF 档位说明） |

**参数档位参考**：

| 档位 | 参数 | 适用 |
|------|------|------|
| 均衡（当前默认） | `-preset medium -crf 26 -profile:v high -g 50` | 日常下载 |
| 体积优先 | `-preset slow -crf 28` | 存档场景，1080p 可压到 1~1.5 Mbps |
| 多路并发 | `-preset veryfast`（或 `faster`） | CPU 预算紧张时退档 |

**预期效果**（1080p 监控、源 2~4 Mbps）：相比旧参数（CRF 23 + ultrafast）文件体积降 **40~55%**，画质损失在监控内容上几乎不可见。`-g 50` 让播放 seek 更快，`+faststart` 保留。

**CPU 账**：下载是离线转码（设备推完后 ffmpeg 继续写文件），`medium` 每路约 0.5~1 核，不会成为瓶颈；仅多路并发下载需要考虑退 preset 档或改硬件编码（`h264_qsv`/`h264_nvenc`，需自行改造 `-c:v`）。

**与设备压力的关系**：转码发生在服务端，摄像机侧零额外负担；配合倍速降到 1，是"设备减负 + 文件变小"的双赢。ZLM 模式下下载经 ZLM 分发，此优化不适用（需在 ZLM 侧或下载完成后二次压缩，见第 12 章）。

---

## 7. P2：信令收敛与前端防重入

### 7.1 INVITE 重传收敛

`MediaService.java:76`：

```java
// 原：{500, 1000, 2000, 4000}（5 次，总窗口 ~10s）
private static final long[] INVITE_RETRY_DELAYS = {500, 2000};
```

10s INVITE 超时保留（覆盖重传 + 设备处理时间）。依据：RFC 3261 Timer A 500ms 起倍增至 4s 即可；设备过载时多发 INVITE 无益。

### 7.2 倍速 SCALE 延迟下发

**现状**：`onInviteOk`（`MediaService.java:417`）在 ACK 后**立即**下发 INFO SCALE——此时设备刚开始起推，双通道倍速请求（SDP 协商 + INFO）叠加冲击。

**改为**：首个 RTP 包到达后再下发，且仅一次：

1. `StreamSession` 或 `MediaService` 增加 `scaleSent` 标志（AtomicBoolean CAS 保证一次）
2. `watchdogTick`（`MediaService.java:460`）首次检测到 `lastPacketAt > 0` 时调用 `sendDownloadControl(s)`
3. `onInviteOk` 中的 `sendDownloadControl(s)` 调用移除（或保留 `download-mansrtsp-play` 分支逻辑，仅 SCALE 延迟）

**收益**：设备先以 1x 稳定起推，再经会话内 INFO 提速；SDP 协商已生效的设备不受影响（INFO 本就是兜底）。

### 7.3 前端防重入（`static/playback.html`）

| 位置 | 改动 |
|------|------|
| 录像查询提交（约 203 行） | in-flight 期间禁用按钮，完成后恢复 |
| 开始回放（约 268 行） | 同上 |
| 开始下载（约 366 行） | 同上 |

配合 5.1 幂等化，重复点击不再产生任何信令。实施时按行定位（行号为当前值，以实际为准）。

---

## 8. P3：文档与配置模板

- README 新增 11.17 节「回放/下载导致摄像机压力大或宕机」：现象、跨方案根因（倍速/拆建风暴/并发/查询）、处置顺序（先 P0 验证再 P1）
- `application.yml` 注释：`download-speed` 低端机风险说明、`max-sessions-per-device` 说明
- 本文档登记进 README 第 2 章文件树

---

## 9. 配置项汇总

| 配置项 | 现值 | 目标值 | 级别 |
|--------|------|--------|------|
| `sip.media.local.download-speed` | 4 | **1** | P0（yml）+ P1.4（代码默认值） |
| `sip.media.local.download-idle-seconds` | 15 | **30** | P0 |
| `sip.media.local.download-preset` | 无（硬编码 ultrafast） | **新增，默认 medium（已实施）** | 第 6 章 |
| `sip.media.local.download-crf` | 无（隐式 23） | **新增，默认 26（已实施）** | 第 6 章 |
| `sip.media.local.max-sessions-per-device` | 无 | **新增，默认 1**（0=不限） | P1 |
| 录像查询时间窗上限 `max-query-span-hours` | 无 | **新增，默认 24**（0=不限） | P1 |
| 录像查询最小间隔 `query-min-interval-seconds` | 无 | **新增，默认 2** | P1 |
| `INVITE_RETRY_DELAYS`（代码常量） | `{500,1000,2000,4000}` | `{500,2000}` | P2 |

---

## 10. 验证方案

**环境**：低端 IPC（海康/大华低端机，SD 卡录像）1 台 + 已知有压力的 1 小时录像片段。

| 步骤 | 操作 | 通过标准 |
|------|------|---------|
| 0 基线 | 当前配置下载该片段 | 记录：设备是否重启、下载耗时、文件大小、平台日志 INVITE/BYE 次数 |
| 1 转码效果 | 同一片段下载，对比新旧文件大小 | 体积降 40~55%，播放画质可接受 |
| 2 P0 | 改 yml 两值重启后重复 | 不再重启即可确认 2.1 为主因 |
| 3 幂等 | 回放中连续点击 5 次"开始回放" | `/api/playback/sessions` 会话数不变、无新 INVITE 日志 |
| 4 并发上限 | 开第二个浏览器同时回放同设备 | 第二请求返回 409 + 明确提示，设备无异常 |
| 5 查询约束 | 查 48h 窗口；连点查询按钮 | 48h 返回 400 提示缩小范围；连点第二次返回"过于频繁" |
| 6 P2 | 倍速 2 下载 + 观察 SCALE 时序 | 日志显示首包到达后才下发 SCALE；设备完成下载 |

---

## 11. 风险与回滚

| 项 | 风险 | 回滚 |
|----|------|------|
| 倍速 4→1 | 大录像下载耗时变长（1h 录像下载约 1h） | yml 调回 4（不推荐）或折中 2 |
| 幂等化 | 无（时间范围不同仍拆建，属必要操作） | 改回 `startPlayback`/`startDownload` 单行 |
| 并发上限 1 | "直播同时看回放"场景被拒（这正是要防的） | 配置调 2，或 0 关闭 |
| 查询限窗 24h | 跨天查询用户需缩小范围 | 配置调大或 0 |
| 重传收敛 | 极端弱网下会话建立略慢（~3s vs ~10s） | 改回常量，单行 |
| SCALE 延迟 | 不支持 SDP 协商的设备提速晚几秒（等首包） | 移除延迟逻辑即可 |
| 转码 preset 提档 | 多路并发下载时服务端 CPU 上升（每路 0.5~1 核） | `download-preset` 退 `veryfast`/`faster`，或改硬件编码 |

每项独立，可分批上线、独立回滚。

---

## 12. ZLM 模式说明

`mode: zlm` 时媒体面（RTP 收流/分发）转交 ZLMediaKit，设备压力从"平台 ffmpeg"转移，但**INVITE 信令仍由本服务发往设备**（`MediaService.java:238-243` 委托 `ZlmMediaService`），因此：

- 2.1 倍速（SDP `a=downloadspeed` 仍随 INVITE 下发）、2.3 重传次数、2.4 设备侧并发——**在 zlm 模式同样存在**，P0/P1.2 适用；
- 第 6 章转码调优是 local 链路 ffmpeg 管道的优化，zlm 模式不适用——ZLM 侧可在拉流代理配置转码参数，或下载完成后在服务端二次压缩（`ffmpeg -i in.mp4 -c:v libx264 -crf 26 -c:a aac out.mp4`）；
- 实施 P2.1/P2.2 时需同步检查 `ZlmMediaService` 的 INVITE 构造与重传逻辑（其会话管理与 local 模式不同，幂等化语义需单独评估）。

---

*方案状态：**已实施：第 6 章转码参数调优（代码 + 配置 + 校验）、5.1 会话幂等化（管理 API / OpenAPI / FLV 入口）**；P0 为运行配置（yml 模板已含推荐值，部署侧执行）；P1.2~P1.4、P2、P3 待实施。建议按 P0 → 验证 → P1 → P2 → P3 顺序推进。*

---

# 24. ZLM 模式实现：架构、模式与代码细节


> 本文系统描述 `sip.media.mode=zlm` 下的实现：模式划分、分层架构、核心机制与代码级细节。问题排查视角的实录见《TECH.md》第 25 章（第一部分：固定 30002 多路 WebRTC 直播；第二部分：回放 WebRTC 与录像下载）。

---

## 1. 模式划分：local 与 zlm 二选一

`MediaProperties`（`sip.media.*`）用 `mode` 字段区分两种媒体面实现，整个系统的媒体行为以此为唯一开关：

```java
public enum Mode { LOCAL, ZLM }
public boolean isZlm() { return mode == Mode.ZLM; }
```

| 维度 | local（自研链路） | zlm（ZLMediaKit） |
|---|---|---|
| RTP 收流 | `RtpReceiver` 每会话独占 UDP 端口（池 30000–30099） | ZLM 固定端口 30002 **Multiplex** 单端口多路 |
| 媒体管道 | RTP PS → `pipe:0` → ffmpeg → FLV/MP4 | ZLM 解封装注册 MediaSource；本服务按需再拉流 |
| 直播分发 | `FlvBroadcast` 内存广播（自建 ws-flv） | 302 重定向到 ZLM（flv/ws-flv/hls） |
| 浏览器播放 | mpegts.js（FLV/MSE） | WebRTC（直播/回放）+ FLV 兜底 |
| 回放/下载 | ffmpeg 直收 RTP PS | 回放：ZLM 分发；**下载：本服务 ffmpeg 从 ZLM HTTP-FLV 拉流转 MP4** |
| 会话归属 | `MediaService` 自己的 `sessionsByKey` | `ZlmMediaService` 自己的 `sessionsByKey` |
| 倍速控制 | SDP `a=downloadspeed` + SIP INFO MANSRTSP | SDP `a=downloadspeed`（协商后由设备自行推流） |

**关键架构决策：门面 + 委托**。`MediaService` 是所有控制器唯一的入口，内部按 `mediaProps.isZlm()` 分流：

- local 分支：走自己的会话表、RtpReceiver、ffmpeg 管道（`startMediaPipeline`）；
- zlm 分支：**转发给 `ZlmMediaService`**，本类的 `sessionsByKey`/`sessionsByCallId` 在 zlm 模式下恒为空。

```java
// MediaService.startPlaybackOrDownload（简化）
if (mediaProps.isZlm()) {
    return zlmMediaService.startPlaybackOrDownload(deviceId, channelId,
            startTime, endTime, type == StreamSession.Type.DOWNLOAD);
}
```

同类委托点：`stop` / `handleResponse` / `handleDeviceBye` / `getSession(deviceId, channelId)` / `sessions()` / `downloadFile(deviceId, channelId)` / `zlmFlvUrl` / `zlmWebrtcUrl` / `waitZlmReady` 等。**教训（已修复）**：任何新增加载查询若漏了委托，zlm 模式下查空表会得到 null——下载端点与 FLV 重定向都曾踩中。

### 配置清单（`application.yml`）

```yaml
sip:
  media:
    mode: zlm
    local:            # 下载转码参数两模式共用
      ffmpeg-path: ffmpeg
      download-dir: ./downloads
      download-speed: 4        # SDP a=downloadspeed
      download-preset: medium  # x264 preset（白名单校验）
      download-crf: 26         # 恒定质量（范围校验）
    zlm:
      api-base: http://127.0.0.1:9092   # 服务端访问 ZLM（API + ffmpeg 拉流）
      secret: <ZLM api.secret>
      media-ip: 192.168.8.104           # SDP 通告 IP：设备把 RTP 推到这里（需设备可达）
      http-port: 9092
      play-host: 192.168.8.104           # 浏览器可达地址（缺省取 api-base host）
      rtp-port: 30002                    # 固定复用端口
      rtp-app: rtp
```

`download-preset`/`download-crf` 在 `MediaProperties.Local` 的 setter 里做白名单/范围校验，非法值直接让配置加载失败，避免把错误参数拼进 ffmpeg 命令行。

---

## 2. 总体架构

```
┌──────────────────────────────────────────────────────────────┐
│ 控制器层                                                      │
│  DeviceController（会话/播放/回放/下载/WebRTC 地址）           │
│  FlvController（FLV 302 重定向 / 下载文件 / 下载状态 / 列表）  │
├──────────────────────────────────────────────────────────────┤
│ 门面层  MediaService（统一入口，按 mode 分流）                 │
│    ├─ local: RtpReceiver + ffmpeg 管道 + FlvBroadcast         │
│    └─ zlm : 全部委托 ↓                                        │
├──────────────────────────────────────────────────────────────┤
│ ZLM 实现层  ZlmMediaService                                   │
│    ├─ 会话管理（双索引 + 通道锁 + 状态机 + 重传/超时）          │
│    ├─ 固定 30002 Multiplex RTP Server 生命周期                 │
│    ├─ 媒体地址构造（flv / ws-flv / hls / webrtc / 本机 flv）   │
│    └─ 下载管道（等 MediaSource → ffmpeg 拉流 → MP4 收尾）      │
├──────────────────────────────────────────────────────────────┤
│ 信令层  SipRequestBuilder（inviteViaZlm / playbackInviteViaZlm  │
│         / buildZlmPlaySdp / buildZlmPlaybackSdp / ack / bye）  │
├──────────────────────────────────────────────────────────────┤
│ 媒体面  ZlmApiClient（HTTP /index/api/*，secret 鉴权）          │
│           ↓ openRtpServerMultiplex / close_streams /           │
│            getMediaList / closeRtpServer                       │
│         ZLMediaKit（rtp app，SSRC → MediaSource）              │
└──────────────────────────────────────────────────────────────┘
        │ SIP INVITE/ACK/BYE                    ▲ RTP（设备推流）
        ▼                                        │
      设备 ───────────────────────────────────→ UDP 30002 → ZLM
```

前端（`static/`）：`live.html`（直播 WebRTC）、`playback.html`（回放 WebRTC + 下载遮罩）、`voice-talk.html`（对讲，不经过 ZLM 模式分流）。

---

## 3. 核心机制

### 3.1 固定 30002 Multiplex RTP Server

整个服务生命周期内**只有一个** RTP Server，端口来自配置（30002）：

```java
private static final String FIXED_RTP_SERVER_ID = "gb28181";  // 控制标识，非播放流名
private volatile int fixedRtpPort = 0;

private synchronized int ensureFixedRtpServer() {
    if (fixedRtpPort > 0) { return fixedRtpPort; }
    int port = zlm.openRtpServerMultiplex(FIXED_RTP_SERVER_ID);  // port=30002, tcp_mode=0
    if (port <= 0) { throw new IllegalStateException("ZLM固定RTP复用服务器启动失败: 30002"); }
    fixedRtpPort = port;
    return fixedRtpPort;
}
```

ZLM API 侧（`ZlmApiClient.openRtpServerMultiplex`）：

```text
openRtpServerMultiplex?port=30002&stream_id=gb28181&tcp_mode=0&local_ip=0.0.0.0
```

生命周期规则（**与单路停止相反**）：

| 时机 | 动作 |
|---|---|
| 单路停播 / 拆建 / 设备 BYE | 只 `close_streams(app=rtp, stream=<zlmStreamId>)`，**绝不 closeRtpServer** |
| 平台停机（`onShutdown`） | 先逐路 BYE + cleanup，最后 `closeRtpServer(gb28181)`，`fixedRtpPort=0` |

Multiplex 模式下 ZLM 按 RTP SSRC 区分不同摄像机，多个会话共享一个 UDP 端口。

### 3.2 三层标识（本项目最容易踩错的地方）

```text
ssrc               = SIP/RTP SSRC（SDP y=，由平台生成并通告，不可改）
zlmRequestStreamId = 固定 RTP Server 的控制标识（恒为 "gb28181"）
zlmStreamId        = ZLM 实际 MediaSource stream（SSRC 的 8 位大写十六进制）
```

```java
private String toZlmStreamId(String ssrc) {   // 0777716362 → 2E5B028A
    long value = Long.parseLong(ssrc);
    if (value < 0 || value > 0xFFFFFFFFL) { throw new IllegalArgumentException(...); }
    return String.format("%08X", value);
}
```

`zlmStreamId` 是一切媒体操作（播放地址、关流、下载拉流、MediaSource 就绪判断）的唯一依据；`ssrc` 只出现在 SIP 信令与 RTP 层面。

### 3.3 SIP 信令流

`ZlmMediaService.startInternal(...)` 是唯一建会话入口（直播/回放/下载共用）：

```text
生成 callId(UUID 无横线) + SSRC（回放用 newPlaybackSsrc）
  → ensureFixedRtpServer()（首次打开 30002）
  → toZlmStreamId(ssrc)
  → 构造 StreamSession（zlm=true, requestStreamId=gb28181, zlmStreamId=…）
  → 双索引登记（sessionsByCallId / sessionsByKey，先登记防 200 OK 竞速）
  → INVITE（playback ? playbackInviteViaZlm : inviteViaZlm）
  → scheduleRetransmit（退避重传 + 总超时兜底清理）
```

SDP 要点（`SipRequestBuilder.buildZlmPlaySdp` / `buildZlmPlaybackSdp`）：

- `m=video <30002> RTP/AVP 96`、`a=rtpmap:96 PS/90000`、`y=<ssrc>`、`c=IN IP4 <media-ip>`；
- 回放 SDP 追加 `u=` 时间范围与 `a=recvonly`；
- 下载场景（ZLM 模式）的倍速协商经 `playbackInviteViaZlm` 的 SDP 完成（与 local 相同的 `a=downloadspeed` 生成逻辑）。

200 OK 处理（`handleResponse`，持通道锁）：

```text
校验 sessionsByCallId 中仍是同一会话（防过期响应）
  → 记录 To-tag / Contact / 应答 SDP
  → 回 ACK，state=PLAYING
  （4xx-6xx → 摘索引 + cleanup）
```

设备主动 BYE（`handleDeviceBye`）→ 摘双索引 + `cleanup`。平台主动停止（`stop`/`stopInternal`）→ 摘双索引 + `sendByeQuietly` + `cleanup`。

### 3.4 媒体分发：就绪等待与四类地址

**就绪判断**——所有媒体操作前必须确认 ZLM 已注册 MediaSource：

```java
public boolean isMediaReady(StreamSession s) {
    return zlm.hasStream(rtpApp, s.getZlmStreamId());   // getMediaList?app=rtp&stream=2E5B028A
}
```

`MediaService.waitZlmReady(s, ms)` 与 `ZlmMediaService.waitMediaReady` 是同一语义的轮询（300ms 间隔，会话 STOPPED 提前放弃）。

**地址构造**（`ZlmMediaService`）：

| 方法 | 地址 | 消费者 | host 来源 |
|---|---|---|---|
| `flvUrl` | `http://<host>:<httpPort>/rtp/<stream>.live.flv` | FlvController 302 目标 | `zlmHost()` |
| `wsFlvUrl` | `ws://<playHost>:<httpPort>/rtp/<stream>.live.flv` | 前端 ws-flv 播放器 | `playHost()` |
| `hlsUrl` | `http://<host>:<httpPort>/rtp/<stream>/hls.m3u8` | 低延迟容忍场景 | `zlmHost()` |
| `webrtcUrl` | `http://<host>:<httpPort>/index/api/webrtc?app=rtp&stream=<stream>&type=play` | ZLMRTCClient（WHEP） | `zlmHost()` |
| `localFlvUrl`（私有） | `http://<apiBase>/rtp/<stream>.live.flv` | **本服务 ffmpeg 下载拉流** | `api-base` |

两个 host 不可混用：

- `zlmHost()` = `play-host`（未配则从 api-base 截 host）+ `http-port`，面向浏览器；
- `api-base` 面向本服务进程（API 调用 + ffmpeg 拉流）。`play-host` 可能是 NAT 映射后的外部 IP，本机反而不可达——下载管道必须用 `api-base`。

**FLV 重定向**（`FlvController.redirectToZlm`）：会话不存在 → 404"请先开始播放"；等 MediaSource 超时 → 504 与"未播放"区分；就绪 → `sendRedirect(flvUrl)` 302 交给 ZLM。zlm 模式下平台不代理媒体流，只做信令与地址。

**WebRTC**（`DeviceController`）：

```text
GET /api/play/webrtc-url       → 直播（公共实现 webrtcPlayUrl）
GET /api/playback/webrtc-url   → 回放
```

两个端点共用私有方法 `webrtcPlayUrl(deviceId, channelId, notStartedMsg)`：查会话 → 校验 zlm 模式 → `waitZlmReady` → 返回 `webrtcUrl`。前端 `live.html`/`playback.html` 用 `ZLMRTCClient.Endpoint` 收发信令，浏览器原生解码（H.264/H.265 + PCMA，取决于浏览器支持）。

### 3.5 录像下载（ZLM 模式独有链路）

入口 `ZlmMediaService.startPlaybackOrDownload(..., download=true)`：

```java
synchronized (lockOf(key)) {
    // 幂等：会话存活 + 时间范围一致 + 同类型 → 直接复用，不拆建
    // （重复 BYE+INVITE 是摄像机宕机的典型诱因；类型必须一致，
    //   DOWNLOAD 不能被 PLAYBACK 误复用，反之亦然）
    StreamSession exist = sessionsByKey.get(key);
    if (exist != null && exist.getState() != STOPPED
            && exist.getPlaybackStart() == startTime
            && exist.getPlaybackEnd() == endTime
            && (exist.getType() == Type.DOWNLOAD) == download) {
        return exist;
    }
    stopInternal(deviceId, channelId);
    StreamSession s = startInternal(d, deviceId, channelId, true, startTime, endTime);
    if (download) {
        s.setType(Type.DOWNLOAD);
        s.setDownloadFileName(buildDownloadFileName(channelId, startTime, endTime));
        s.setDownloadUrl("/api/download/file?deviceId=...&channelId=...");
        startZlmDownload(s);
    }
    return s;
}
```

**下载管道**（`ffmpegExecutor` 缓存线程池，守护线程，三件任务）：

```text
runZlmDownload(s)
  ├─ waitMediaReady(s, 15s)          等 ZLM 注册 MediaSource；超时 → stop() 收尾
  ├─ buildZlmDownloadFfmpeg(s)       启动失败 → stop() 收尾
  ├─ submit drainZlmFfmpegLog        消费 stderr：进度行解析 time= → outTimeSec
  └─ submit awaitZlmFfmpegExit       进程退出 = 设备推流结束 → finalize + stop()
```

ffmpeg 命令（`buildZlmDownloadFfmpeg`）：

```text
ffmpeg -timeout 10000000 \
  -i http://<api-base>/rtp/<zlmStreamId>.live.flv \
  -map 0:v:0 -map 0:a:0? \
  -c:v libx264 -preset <download-preset> -crf <download-crf> \
  -profile:v high -g 50 \
  -c:a aac -b:a 32k \
  -movflags +faststart -f mp4 \
  <download-dir>/rec_<channel>_<start>_<end>.mp4.part
```

与 local 模式下载的**唯一差别是输入源**（HTTP-FLV vs RTP PS 管道），转码参数逐项一致。设计细节：

- `-timeout 10000000`（微秒，10s）：防 ZLM 异常时 ffmpeg 永久挂起；
- **不加 `-reconnect`**：HTTP-FLV 断开即设备推流结束，重连只会拖住收尾；
- 输入是 HTTP 流无 stdin 句柄，**不能像 local 那样关 stdin 触发 EOF**——结束信号来自 ZLM 断开连接；
- `-map 0:a:0?` 可选音频映射：PS 内嵌 PCMA 透传后统一重编码 AAC 保兼容；
- `.part` 后缀：收尾前不可被下载端点看到完整文件。

**进度**：`drainZlmFfmpegLog` 过滤刷屏进度行，仅解析 `time=`（已转码录像时长）写入 `ffmpegOutTimeSec`——`/api/download/status` 返回它作为真实进度（设备不支持倍速按 1x 推流时同样准确，按"时长/倍速"估算会假死到顶）。

**收尾五条路径**（全部收敛到 `finalizeZlmDownload` + 会话清理，finalize 幂等）：

| 触发 | 路径 |
|---|---|
| 设备推流自然结束 | ffmpeg EOF → `awaitZlmFfmpegExit` finally：finalize 改名 + `stop()`（BYE + closeStreams + STOPPED） |
| 用户中途停止 | `stop` → `stopInternal` → `cleanup`：先 closeStreams（断流）→ DOWNLOAD 分支 `stopZlmDownloadFfmpeg` 等优雅退出 → finalize |
| MediaSource 等待超时 | `runZlmDownload` → `stop()` → cleanup（`.part` 不存在，finalize 空操作） |
| ffmpeg 启动失败 | 同上 |
| 平台停机 | `onShutdown` 逐会话 cleanup + `ffmpegExecutor.shutdownNow()` |

`stopZlmDownloadFfmpeg` 等 60s 优雅退出（覆盖 flush 编码队列 + 写 moov + faststart 重排，2688x1520 大文件实测可达数十秒），超时才 `destroyForcibly`（此时文件已损坏，仅防泄漏）。`finalizeZlmDownload`：`.part` → 正式 MP4；Windows 上浏览器"边下边传"持锁导致 `Files.move` 失败时退化为 `Files.copy`；最终文件已存在（重复下载同时间段）时清理 `.part` 残留。

**前端契约零改动**：`/api/download/start`（`DeviceController`）→ 遮罩轮询 `/api/download/status` → `.part` 出现后 `/api/download/file` 等最终文件（回放结束时间 + 90s 宽限）→ 整文件传输（`Content-Length` 真实百分比）。zlm 与 local 返回结构完全一致。

### 3.6 会话管理

- **双索引**：`sessionsByCallId`（SIP 路由：响应/BYE）与 `sessionsByKey`（业务定位：deviceId:channelId）；
- **通道级锁**：`lockOf(key)`（`ConcurrentHashMap.computeIfAbsent` 的 Object 监视器）保证同通道点播/停止/信令处理互斥；`handleResponse`/`handleDeviceBye` 内用 `sessionsByCallId.get(callId) != s` 双检丢弃过期会话的响应；
- **状态机**：`INVITING → PLAYING → STOPPED`（`stopInternal` 摘索引时未协商会话也直接清理）；
- **会话表 API**：`getSession(callId)` / `getSessionByKey` / `sessions()` / `hasSession` ——zlm 模式下控制器一律经 `MediaService` 委托过来；
- **定时任务**：单线程 `scheduler`（zlm-invite-retransmit）承担 INVITE 重传与总超时；`ffmpegExecutor`（zlm-ffmpeg-download）承担下载管道三任务。均守护线程，停机 `shutdownNow`。

### 3.7 ZLM API 封装（`ZlmApiClient`）

统一 GET `/index/api/{api}?secret=...&k=v...`（JDK `HttpClient`，连接 3s / 读 5s），Jackson 解析，`code==0` 为成功：

- `openRtpServerMultiplex(streamId)` / `closeRtpServer(streamId)`
- `closeStreams(app, stream)`（force=1）
- `getMediaList(app[, stream])` / `hasStream(app, stream)`（data 非空即存在）
- `findStreamByRtpPort(app, port[, requestStreamId])`——按 `originSock.local_port` 反查实际 stream（`port` 兼容字段兜底）

**错误策略**：所有异常/`code!=0` 返回失败值（-1/false/null）并记日志，**不向上抛**——媒体面故障不应炸断 SIP 线程。

### 3.8 语音对讲（模式无关，zlm 下开箱即用）

**架构结论：对讲不经过 ZLM，也不经过 MediaService 的任何链路**。对讲媒体始终是「浏览器 ⇄ 平台 ⇄ 设备」的平台直连 RTP：

```text
上行：浏览器 mic PCM(8k/16bit) → WebSocket /ws/voice → VoiceService.pushAudio
      → G711a.encode → 160B/20ms → RTP → 设备（200 OK 协商的 audioIp:audioPort）
下行：设备 RTP → VoiceRtpEndpoint 会话独占 UDP 端口（30100–30150）
      → G711a.decode → PCM → WebSocket 二进制帧 → 浏览器
```

为什么不走 ZLM：ZLM 的 GB28181 RTP Server 是**收流方**（接收设备推流），而对讲要求平台**向设备主动发 RTP**；ZLM 也没有"把实时麦克风音频转为 GB28181 对讲流发给设备"的能力（`startSendRtp` 之类 API 的输入必须是 ZLM 内部已有流）。因此 zlm 模式下对讲零 ZLM 依赖，代码零分支——`VoiceService`/`VoiceRtpEndpoint`/`VoiceWebSocketHandler`/`VoiceSignalHandler` 全链路不引用 `MediaProperties`。

zlm 模式唯一的适配点是 **SDP 通告 IP**（`VoiceService.announceIp()`）：

| 模式 | 通告 IP | 理由 |
|---|---|---|
| zlm | `media.zlm.media-ip`（空则回退 `sip.server.ip`） | 与点播/回放/下载 SDP 同一口径，设备侧已验证可达 |
| local | `sip.server.ip` | 传统口径 |

三种 SDP 全部走该口径：对讲 INVITE（`buildTalkSdp`）、广播 INVITE（`buildBroadcastInviteSdp`）、设备反向 INVITE 的 200 OK answer（`buildAudioAnswerSdp`）。多网卡/NAT 场景（SIP 与媒体走不同地址）下这是对讲可通的必要条件。

**端口防重叠**（`checkPortOverlap`，启动时一次）：语音池 vs zlm 固定 RTP 端口（30002）或 local 媒体池（30000–30099），重叠即 `log.error` 暴露配置错误——否则表现为对讲绑定失败或与视频流 SSRC 互窜。

**对讲的三种形态**（`/api/voice/start?mode=`）：`talk`（平台主动 INVITE，`s=Talk` + `a=sendrecv`）、`broadcast`（MESSAGE 通知 + `s=Broadcast` + `a=sendonly`，单向喊话，绕开部分设备把对讲误判为点播的问题）、`legacy-broadcast`（设备反向 INVITE 推流）。三种形态与媒体模式均无关。

---

## 4. 代码地图

| 文件 | 职责 |
|---|---|
| `config/MediaProperties.java` | mode 开关 + local/zlm 配置（preset/crf 校验） |
| `service/media/MediaService.java` | 统一门面：local 全链路实现 + zlm 委托 + 下载查询委托 + `waitZlmReady` |
| `service/zlm/ZlmMediaService.java` | zlm 模式核心：会话/信令/30002 生命周期/地址构造/下载管道 |
| `service/zlm/ZlmApiClient.java` | ZLM HTTP API 客户端 |
| `sip/SipRequestBuilder.java` | `inviteViaZlm`/`playbackInviteViaZlm`/SDP 构造/ack/bye |
| `model/StreamSession.java` | 会话模型（ssrc、zlm 双 streamId、playback 时间、ffmpeg 句柄、outTimeSec、下载文件名） |
| `controller/DeviceController.java` | 播放/回放/下载/WebRTC 地址端点 + 语音对讲端点（`/api/voice/*`，模式无关） |
| `controller/FlvController.java` | FLV 302 重定向、下载文件/状态/列表 |
| `static/live.html`、`static/playback.html` | WebRTC 播放 + 下载遮罩 |
| `service/voice/VoiceService.java` | 语音对讲/广播（模式无关；zlm 模式通告 IP 取 media-ip） |
| `service/voice/VoiceRtpEndpoint.java` | 语音 RTP 端点（每会话独占 UDP 端口 30100–30150，TCP/UDP 自适应） |
| `websocket/VoiceWebSocketHandler.java` | 浏览器上行 PCM 通道（`/ws/voice`） |
| `handler/VoiceSignalHandler.java` | 设备侧语音信令路由（INVITE/ACK/BYE/响应） |
| `static/voice.html`、`static/voice-talk.html` | 语音控制台 + 对讲页（麦克风采集/下行播放/电平表） |

---

## 5. 设计决策与取舍

| 决策 | 理由 |
|---|---|
| 门面 + 委托而非策略接口 | 改动面最小；zlm 模式只需在 `MediaService` 每个入口加一个 if，local 代码零风险 |
| 302 重定向而非代理媒体流 | 平台不承担 GB 级媒体转发开销；浏览器直连 ZLM 延迟最低 |
| 固定单端口 Multiplex | 设备只需记住一个端口；会话增长不消耗端口池（local 池上限 100 路） |
| 幂等复用判断带会话类型 | 回放（实时分发）与下载（落盘）末端形态不同，互复用会导致前端拿到空播放地址/误读他人文件 |
| 下载进度用 ffmpeg `time=` | 设备静默降速 1x 时，倍速估算会过早到顶假死；真实转码时长与推流速率无关 |
| ffmpeg 优雅退出 60s 才强杀 | moov/faststart 重排大文件需数十秒；提前强杀 = 损坏 MP4 |
| `-timeout` 10s、不加 reconnect | 前者防挂起；后者保证"断开=结束"语义，收尾不被重连拖住 |
| API 失败吞异常返回默认值 | 媒体面抖动不炸断 SIP 信令线程 |

---

## 6. 已知边界

1. **浏览器 WebRTC 播 H.265** 依赖浏览器版本；旧版浏览器需 ZLM 侧转码 H.264（配置改动，代码不动）。
2. **设备倍速降级静默**：不支持 `a=downloadspeed` 时按 1x 推流，下载完成但耗时 = 录像时长；前端遮罩文案已按此语义。
3. **local 端口池上限**：30000–30099 共 100 路，与语音池（30100 起）不重叠；zlm 模式不受此限。
4. **ZLM 必须先于推流就绪**：设备 RTP 到达前 `getMediaList` 查不到 stream，所有"就绪等待"逻辑都因此存在（404/504 的区分也基于此）。
5. **下载目录磁盘占用**：`.part` 在收尾前持续增长；多路并发下载注意磁盘 IO 与 x264 CPU 峰值（`download-preset` 可调 veryfast/faster）。

---

# 25. ZLM 模式问题排查与修复记录


> 本文汇总 ZLMediaKit（ZLM）媒体模式下的问题排查与功能改造记录，分两部分：
>
> - **第一部分：固定 30002 多路 GB/T 28181 WebRTC 直播**——固定 RTP 端口多路复用改造的问题排查与修复（三层标识混用根因、`openRtpServerMultiplex`、SSRC↔MediaSource 映射、停播生命周期）
> - **第二部分：回放 WebRTC 与录像下载**——回放页 WebRTC 化与 MP4 离线下载两项改造（PCMA 不可播根因、ffmpeg 从 ZLM 拉流、幂等/进度/收尾设计）
>
> 架构视角的完整说明见《TECH.md》第 24 章，本文是问题排查视角的实录。

---

## 第一部分：固定 30002 多路 GB/T 28181 WebRTC 直播

## 1. 问题背景

项目使用 ZLMediaKit（ZLM）作为媒体服务器，GB/T 28181 摄像机通过 SIP INVITE 向平台推送 RTP，平台希望满足：

- ZLM 固定使用 UDP `30002` 接收视频 RTP；
- 多个摄像机共用同一个 RTP 端口；
- 不再为每一路点播动态分配不同 RTP 端口；
- 浏览器通过 ZLM WebRTC 播放；
- 同时兼容点播、回放以及后续多摄像机场景。

原来的多端口模式播放基本可以立即成功。改为固定 `30002` 后，SIP 协商能够成功，但浏览器无法通过 WebRTC 播放。

---

## 2. 最初表现

浏览器 F12 日志能够看到：

```text
[WEBRTC] createOffer
[WEBRTC] setLocalDescription
[WEBRTC] ICE gathering: gathering
[WEBRTC] ICE gathering: complete
[WEBRTC] POST SDP: http://127.0.0.1:9092/index/api/webrtc?...&stream=0777716362&type=play
```

当时浏览器看起来像是一直停在 `fetch()`，没有继续打印 HTTP 响应。

后来查看 ZLM 日志，发现 WebRTC 请求实际上已经到达 ZLM，并且 ZLM 返回了：

```json
{
    "code": -400,
    "msg": "stream not found"
}
```

因此 WebRTC 的 `createOffer`、ICE gathering、本地 SDP 生成本身没有问题，真正的问题发生在 **ZLM 查询媒体流阶段**。

---

## 3. 最关键的 ZLM 日志

ZLM 已经成功收到摄像机 RTP，并且已经注册 MediaSource：

```text
媒体注册:fmp4://__defaultVhost__/rtp/2E5B028A
stream: rtp://__defaultVhost__/rtp/2E5B028A
codec info: PCMA[8000/1/16] H265[2688/1520/25]
媒体注册:rtmp://__defaultVhost__/rtp/2E5B028A
媒体注册:ts://__defaultVhost__/rtp/2E5B028A
媒体注册:rtsp://__defaultVhost__/rtp/2E5B028A
媒体注册:hls://__defaultVhost__/rtp/2E5B028A
```

这说明：

```text
摄像机
  ↓
RTP 30002
  ↓
ZLM
  ↓
MediaSource 已建立
```

视频编码也已经正常识别：

```text
H265 2688x1520 25fps
PCMA 8000Hz
```

所以问题不是“摄像机没推流”、也不是“RTP 30002 完全收不到数据”。

---

## 4. 真正暴露问题的对比

Java 的 Session 当时记录的是：

```text
requestStreamId=0777716362
actualStream=0777716362
zlmStreamId=0777716362
ssrc=0777716362
```

浏览器因此请求：

```text
/index/api/webrtc?app=rtp&stream=0777716362&type=play
```

但是 ZLM 实际注册的 MediaSource 是：

```text
rtp/2E5B028A
```

并且：

```text
0777716362（十进制）= 2E5B028A（十六进制）
```

所以最终变成：

```text
浏览器请求：
stream=0777716362

ZLM 实际存在：
stream=2E5B028A

结果：
code=-400
stream not found
```

### 重要结论

这里不能简单理解成“两个字符串只是显示格式不同”。

对于 WebRTC 查询来说，ZLM 实际 MediaSource 的 `stream` 必须与 WebRTC 请求使用的 `stream` 一致。

因此 Java 不能仅仅把自己保存的 `ssrc` 当成“ZLM 实际 MediaSource stream”返回给前端。

---

## 5. 第二个关键问题：固定 30002 不能继续按普通 `openRtpServer` 使用

原先固定端口方案类似：

```java
public int openRtpServer(String streamId, String ssrc) {
    return callInt(
            "openRtpServer",
            "port", "30002",
            "stream_id", streamId,
            "tcp_mode", "0"
    );
}
```

这个模式的核心特点是：

```text
一个 RTP Server
    ↕
一个固定 stream_id
```

当目标是：

```text
一个固定端口 30002
    ↓
多个摄像机
    ↓
多个 SSRC
    ↓
多个 MediaSource
```

就应该使用 ZLM 的 RTP Multiplex 接收模式：

```text
openRtpServerMultiplex
```

最终采用的设计是：

```text
30002
  ↓
openRtpServerMultiplex
  ↓
多个 GB/T 28181 RTP/SSRC
  ↓
ZLM 根据 SSRC 区分实际流
```

---

## 6. 最终修复方案

## 6.1 ZlmApiClient：固定 30002 使用 Multiplex

增加：

```java
public int openRtpServerMultiplex(String streamId) {
    return callInt(
            "openRtpServerMultiplex",
            "port", "30002",
            "stream_id", streamId,
            "tcp_mode", "0",
            "local_ip", "0.0.0.0"
    );
}
```

其中：

```text
stream_id = gb28181
port      = 30002
```

这里的 `gb28181` 是固定 RTP Server 的标识，**不是某一路摄像机实际播放的 MediaSource stream**。

---

## 6.2 ZlmMediaService：30002 只启动一次

新增固定 RTP Server 状态：

```java
private static final String FIXED_RTP_SERVER_ID = "gb28181";

private volatile int fixedRtpPort = 0;
```

通过：

```java
private synchronized int ensureFixedRtpServer() {
    if (fixedRtpPort > 0) {
        return fixedRtpPort;
    }

    int port = zlm.openRtpServerMultiplex(FIXED_RTP_SERVER_ID);

    if (port <= 0) {
        throw new IllegalStateException(
                "ZLM固定RTP复用服务器启动失败: 30002"
        );
    }

    fixedRtpPort = port;

    return port;
}
```

因此平台生命周期内只有一个：

```text
UDP 30002 Multiplex RTP Server
```

不再为每一次点播重复创建普通 RTP Server。

---

## 6.3 SIP 的 SSRC 保持原值不变

例如：

```text
y=0777716362
```

这个值属于 SIP / GB/T 28181 媒体协商，不能因为 ZLM 内部的 MediaSource 名称变化而修改。

因此：

```text
SIP SSRC = 0777716362
```

继续保持不变。

---

## 6.4 ZLM MediaSource stream 使用 SSRC 对应的实际名称

当前设备实际在 ZLM 中注册为：

```text
2E5B028A
```

因此 Session 需要同时保存：

```text
ssrc
zlmRequestStreamId
zlmStreamId
```

三者职责不同：

| 字段 | 示例 | 含义 |
|---|---|---|
| `ssrc` | `0777716362` | SIP / RTP SSRC |
| `zlmRequestStreamId` | `gb28181` | 固定 Multiplex RTP Server 的控制标识 |
| `zlmStreamId` | `2E5B028A` | ZLM 实际 MediaSource stream |

不要再把这三个字段强制设置成相同的值。

---

## 6.5 SSRC 转实际 ZLM stream

当前 ZLM 的实际媒体流表现为 SSRC 的十六进制形式，因此增加：

```java
private String toZlmStreamId(String ssrc) {
    long value;

    try {
        value = Long.parseLong(ssrc);
    } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
                "无效SSRC: " + ssrc,
                e
        );
    }

    if (value < 0 || value > 0xFFFFFFFFL) {
        throw new IllegalArgumentException(
                "SSRC超出32位范围: " + ssrc
        );
    }

    return String.format("%08X", value);
}
```

例如：

```text
0777716362
    ↓
2E5B028A
```

注意：

> 这个转换只用于得到当前 ZLM 实际 MediaSource stream，不能修改 SIP SDP 中的 `y=`。

---

## 7. 最终 Session 保存方式

最终采用：

```java
String ssrc = playback
        ? SipRequestBuilder.newPlaybackSsrc()
        : SipRequestBuilder.newSsrc();

int rtpPort = ensureFixedRtpServer();

String zlmStreamId = toZlmStreamId(ssrc);

StreamSession s = new StreamSession(
        callId,
        deviceId,
        channelId,
        d.getHost(),
        d.getPort(),
        rtpPort
);

s.setSsrc(ssrc);
s.setPlayback(playback);
s.setZlm(true);
s.setZlmRequestStreamId(FIXED_RTP_SERVER_ID);
s.setZlmStreamId(zlmStreamId);
s.setState(StreamSession.State.INVITING);
```

最终对应关系：

```text
固定 RTP Server
    stream_id = gb28181
    port      = 30002

当前摄像机
    SIP SSRC  = 0777716362

ZLM 实际 MediaSource
    stream    = 2E5B028A
```

---

## 8. WebRTC URL 的修复

WebRTC 必须使用实际 MediaSource stream：

```java
public String webrtcUrl(StreamSession s) {
    String stream = s.getZlmStreamId();

    if (stream == null || stream.isBlank()) {
        return null;
    }

    return zlmHost()
            + "/index/api/webrtc?app="
            + mediaProps.getZlm().getRtpApp()
            + "&stream="
            + stream
            + "&type=play";
}
```

修复后：

```text
正确：
/index/api/webrtc?app=rtp&stream=2E5B028A&type=play
```

而不是：

```text
错误：
/index/api/webrtc?app=rtp&stream=0777716362&type=play
```

---

## 9. 停播逻辑也必须调整

固定 30002 是公共 RTP Server：

```text
设备 A ─┐
设备 B ─┼─> 30002 Multiplex
设备 C ─┘
```

因此停止设备 A 时，不能关闭整个 30002，否则设备 B、C 也会受到影响。

单路停播只关闭对应 MediaSource：

```java
zlm.closeStreams(
        mediaProps.getZlm().getRtpApp(),
        s.getZlmStreamId()
);
```

不要在普通单路停播中执行：

```java
zlm.closeRtpServer(...);
```

---

## 10. 什么时候关闭 30002

只有平台整体关闭时，才关闭公共 RTP Server：

```java
if (fixedRtpPort > 0) {
    zlm.closeRtpServer(FIXED_RTP_SERVER_ID);
    fixedRtpPort = 0;
}
```

因此生命周期变成：

```text
平台启动
    ↓
创建 30002 Multiplex RTP Server
    ↓
设备 A 推流 → A MediaSource
设备 B 推流 → B MediaSource
设备 C 推流 → C MediaSource
    ↓
停止 A → 只关闭 A MediaSource
停止 B → 只关闭 B MediaSource
    ↓
平台关闭
    ↓
关闭 30002 RTP Server
```

---

## 11. `findStreamByRtpPort()` 的兼容修复

之前代码主要检查：

```java
item.path("port")
```

但 ZLM `getMediaList` 返回媒体源时，实际 RTP Socket 的本地端口可能位于：

```text
originSock.local_port
```

因此最终查询逻辑优先检查：

```java
int localPort = item
        .path("originSock")
        .path("local_port")
        .asInt(-1);
```

并保留 `port` 作为兼容字段：

```java
int legacyPort = item.path("port").asInt(-1);
```

这样可以根据 ZLM 真正注册的媒体源找到 `actualStream`，避免 Java 自己猜测。

---

## 12. 为什么之前多端口模式马上可以播放

多端口模式的特点是每一路点播单独创建 RTP Server，例如：

```text
摄像机 A → UDP 32001
摄像机 B → UDP 32002
摄像机 C → UDP 32003
```

每一路端口与会话关系清晰，原来的链路本身是正常的，因此：

```text
INVITE
 ↓
RTP
 ↓
ZLM MediaSource
 ↓
WebRTC
```

通常可以很快建立。

问题主要出现在切换到固定单端口后，仍然按照“单流 RTP Server”的思路管理端口与 stream，导致公共 RTP Server、实际 MediaSource、Session 中保存的 stream ID 没有严格分层。

---

## 13. WebRTC ICE 不是根因

前端为了防止浏览器一直等待 ICE gathering，增加了日志和超时：

```text
createOffer
setLocalDescription
ICE gathering
POST SDP
```

这一步有助于排查，但最终证明：

```text
ICE gathering = complete
```

之后浏览器确实已经向 ZLM 发出了：

```text
POST /index/api/webrtc
```

因此 ICE gathering 不是这次 `stream not found` 的根因。

---

## 14. `sessionJson` 每 5 秒出现一次不是重复点播

日志中曾出现：

```text
15:08:28
15:08:33
15:08:38
15:08:43
15:08:49
15:08:54
15:08:59
```

这是前端：

```javascript
setInterval(refresh, 5000);
```

导致 `/api/play/sessions` 每 5 秒轮询一次。

因此：

```text
[ZLM] sessionJson
```

不是重复发送 INVITE，也不是重复开启直播。

真正的 SIP INVITE 只在点播操作时发送，后续 5 秒一次的 `sessionJson` 属于页面状态刷新。

---

## 15. 最终正确链路

最终稳定链路如下：

```text
                       SIP
摄像机 ─────────────────────────> 平台
  │                                 │
  │                                 │ INVITE SDP
  │                                 │ y=0777716362
  │                                 │ m=video 30002
  │                                 ↓
  │                           ZLM Multiplex
  │                              UDP 30002
  │                                 │
  │ RTP SSRC=0777716362             │
  └────────────────────────────────>│
                                    │
                                    ↓
                             MediaSource
                             rtp/2E5B028A
                                    │
                                    ↓
                              WebRTC API
                                    │
                   stream=2E5B028A  │
                                    ↓
                                  浏览器
```

---

## 16. 最终字段关系

对于本次实际案例：

```text
设备：
34020000001320000002

固定 RTP：
30002

SIP SSRC：
0777716362

ZLM RTP Server：
gb28181

ZLM MediaSource：
2E5B028A

WebRTC：
/index/api/webrtc?app=rtp&stream=2E5B028A&type=play
```

---

## 17. 本次问题的核心结论

这次问题不是单一的“WebRTC 播放失败”，而是固定 RTP 端口改造后出现了**三层标识混用**：

```text
RTP Server ID
    ↓
MediaSource stream
    ↓
SIP SSRC
```

最终需要严格区分：

```text
固定 Multiplex RTP Server
    = gb28181 / 30002

SIP SSRC
    = 0777716362

ZLM 实际 MediaSource
    = 2E5B028A
```

并遵循：

```text
SIP 使用 SSRC
ZLM RTP Server 使用固定 Multiplex
WebRTC 使用实际 MediaSource stream
单路停止只关闭 MediaSource
平台关闭才关闭 30002
```

这样就实现了：

> **一个固定 UDP 30002 端口 + 多摄像机 RTP 复用 + ZLM WebRTC 播放。**

---

## 18. 后续修改时的注意事项

以后不要再把下面几个值直接混用：

```text
ssrc
requestStreamId
zlmStreamId
```

尤其不要因为某次日志看到：

```text
ssrc=0777716362
```

就直接认为：

```text
zlmStreamId=0777716362
```

最终应该以 ZLM 实际 `MediaSource` 查询结果为准。

同时，固定 30002 方案不能恢复成“每个会话执行一次普通 `openRtpServer(30002, ...)`”的管理方式；固定单端口多路场景必须保持 Multiplex 的生命周期模型。

---

## 第二部分：回放 WebRTC 与录像下载

## 1. 问题背景

固定 UDP `30002` 多路复用改造完成后，直播（点播）WebRTC 播放已通。剩余两块未闭环：

1. **回放**：`playback.html` 走 mpegts.js 播放 HTTP-FLV，实测无法出图；
2. **下载**：`playback.html` 点“下载”后在 ZLM 模式下**只按回放处理，不产生 MP4 文件**，前端遮罩最终提示“文件未生成”。

两块的根因不同，但最终都收敛到同一个结论：**回放/下载与直播在 ZLM 侧是同一条链路（同一固定 RTP Server、同一 `rtp` app、同样注册为 MediaSource，只有 SSRC 不同），差异只在末端消费方式。**

---

## 2. 问题一：回放 mpegts 播放失败

### 2.1 表现

浏览器 F12 报 `CodecUnsupported`，播放中断。设备实际编码（ZLM 日志）：

```text
codec info: PCMA[8000/1/16] H265[2688/1520/25]
```

### 2.2 根因

- **音频**：设备 PS 流内嵌 G.711a（PCMA），mpegts.js/MSE 的解码器白名单不含 PCMA，且音频轨报错会**中断整个播放**（不是仅丢声音）；
- **视频**：H.265 2688x1520@25 交给 wasm 解码也是重负载，高分辨率下掉帧明显。

### 2.3 方案

zlm 模式回放改走 **WebRTC**：浏览器原生栈解码（PCMA 是 WebRTC 合法音频编码），与直播完全同构。local 模式保持 mpegts FLV 不变。

### 2.4 修复内容

**后端 `DeviceController.java`**：新增端点，并把公共逻辑抽成私有方法（仅“会话不存在”措辞按场景区分）：

```java
@GetMapping("/api/play/webrtc-url")        // 直播（原有，改为门面）
@GetMapping("/api/playback/webrtc-url")    // 回放（新增）

private Map<String, Object> webrtcPlayUrl(String deviceId, String channelId,
                                          String notStartedMsg) {
    // 会话不存在 → error（按直播/回放给不同提示）
    // 非 zlm 模式 / 非 zlm 会话 → error
    // waitZlmReady 超时 → error（等 ZLM 注册 MediaSource）
    // 就绪 → { webrtcUrl: mediaService.zlmWebrtcUrl(s) }
}
```

**前端 `playback.html`**：

- 引入 `ZLMRTCClient.js`；
- `playRecord` 按模式分流：`isZlmMode()` → 请求 `/api/playback/webrtc-url` 后 `playWebrtc()`；否则保持原 `playFlv()`；
- 新增 `playWebrtc()`（与 `live.html` 同款）：`ZLMRTCClient.Endpoint` + `recvOnly` + 三个事件监听（远端流到达 / offer-answer 失败 / ICE 错误，均把状态写回地址栏）；
- `destroyPlayer` 按 `playerKind` 释放：`rtc` 用 `close()`，`mpegts` 用 `destroy()`，避免销毁路径错配。

### 2.5 关键点

回放会话在 zlm 模式下与直播**共用全部媒体基础设施**：

```text
固定 RTP Server (gb28181 / 30002 Multiplex)
  ↓
设备按回放 SSRC 推流（newPlaybackSsrc）
  ↓
ZLM 按 SSRC 注册 MediaSource（stream = SSRC 十六进制）
  ↓
webrtcUrl() 取 session.zlmStreamId  → 天然通用
```

---

## 3. 问题二：WebRTC 信令地址误用 http→ws 转换（改造中引入，已修正）

### 3.1 表现

`playWebrtc()` 初版对信令地址调用了 `toPlayableUrl()`，该函数是 **ws-flv 专用**的 `http://→ws://` 转换。WHEP 信令必须是 `http://`，转换后 ZLMRTCClient 无法完成 offer/answer 交换。

### 3.2 修复

信令地址直接透传，不过任何转换：

```javascript
function playWebrtc(url){
    destroyPlayer();
    if(!url){ return; }
    player = new ZLMRTCClient.Endpoint({
        element: $('video'),
        zlmsdpUrl: url,     // 必须保持 http，不能走 toPlayableUrl
        ...
    });
}
```

---

## 4. 问题三：ZLM 模式下载不产生 MP4

### 4.1 表现

`MediaService.startPlaybackOrDownload` 的 zlm 分支：

```java
if (mediaProps.isZlm()) {
    log.warn("[28181] ZLM 模式不支持 MP4 下载，按回放处理");
    return zlmMediaService.startPlayback(deviceId, channelId, startTime, endTime);
}
```

下载请求被降级成回放：没有 ffmpeg、没有 `.part` 文件，前端遮罩轮询 30s 后报“文件未生成”。同时 `MediaService.downloadFile(deviceId, channelId)` 查的是 `MediaService.sessionsByKey`——**zlm 模式下该表恒为空**，即使文件存在也永远返回 null。

### 4.2 根因

两层：

1. 媒体消费端缺失：local 模式用“RTP PS 管道直喂 ffmpeg”，zlm 模式 RTP 由 ZLM 收走，平台进程拿不到原始 PS，必须换个取流方式；
2. 会话查询端错位：zlm 模式会话全部在 `ZlmMediaService` 维护，`MediaService` 的下载查询/状态端点全部落空。

### 4.3 方案设计

回放 INVITE 不变（设备把回放 PS 推给 ZLM，ZLM 注册 `rtp/<ssrc>` MediaSource），末端由**平台进程的 ffmpeg 从 ZLM HTTP-FLV 拉流**，离线转码 MP4 落盘：

```text
playback.html 点"下载"
  ↓ POST /api/download/start
DeviceController.startDownload
  ↓
MediaService.startPlaybackOrDownload (type=DOWNLOAD)
  ↓ zlm 分支委托
ZlmMediaService.startPlaybackOrDownload(download=true)
  ↓ 回放 INVITE（SDP 指向 ZLM 固定 RTP 端口）
设备推回放 PS → ZLM 30002 → MediaSource(rtp/<ssrc>)
  ↓ 等 MediaSource 就绪（≤15s）
ffmpeg -i http://<api-base>/rtp/<ssrc>.live.flv
  → x264( preset/crf/profile/g ) + aac → MP4 .part
  ↓ 前端轮询 /api/download/status（进度 = ffmpeg time= 已转码时长）
  ↓ 文件就绪后 /api/download/file 流式传输
设备推流结束 → ZLM 断流 → ffmpeg EOF
  ↓
.part 改名正式 MP4 + 会话收尾（BYE + closeStreams + STOPPED）
```

**为什么这样设计**：

- 前端契约零改动：遮罩、`.part` 轮询、进度语义、Range 传输全部复用 local 模式；
- 取流地址用 `media.zlm.api-base`（sip-server 本机实际地址），**不能用 `play-host`**——那是给浏览器的可达地址，可能是 NAT 映射 IP，本机不可达；
- 不加 `-reconnect`：HTTP-FLV 断开即设备推流结束，重连只会拖住收尾；
- `-timeout 10000000`（10s，微秒）：防 ZLM 侧异常时 ffmpeg 永久挂起。

### 4.4 实现清单

**`MediaService.java`（两处委托）**：

```java
// 1) 发起：zlm 分支从"按回放处理"改为委托
if (mediaProps.isZlm()) {
    return zlmMediaService.startPlaybackOrDownload(
            deviceId, channelId, startTime, endTime,
            type == StreamSession.Type.DOWNLOAD);
}

// 2) 查询：downloadFile 加 zlm 委托，否则永远 null
if (mediaProps.isZlm()) {
    StreamSession s = zlmMediaService.getSessionByKey(deviceId, channelId);
    if (s == null || s.getType() != StreamSession.Type.DOWNLOAD) {
        return null;
    }
    return downloadFile(s);
}
```

**`ZlmMediaService.java`（下载管道，新增）**：

| 方法 | 职责 |
|---|---|
| `startPlaybackOrDownload(..., download)` | 统一入口；幂等复用判断；下载时置 DOWNLOAD 类型/文件名/`downloadUrl` 并启动管道 |
| `runZlmDownload` | 等 MediaSource → 起 ffmpeg → 挂 stderr 消费与退出等待 |
| `waitMediaReady` | 轮询等 ZLM 注册 MediaSource（15s 上限；会话 STOPPED 提前放弃） |
| `buildZlmDownloadFfmpeg` | 组装 ffmpeg：`-timeout 10s -i <api-base>/rtp/<stream>.live.flv -map 0:v:0 -map 0:a:0? -c:v libx264 -preset <download-preset> -crf <download-crf> -profile:v high -g 50 -c:a aac -b:a 32k -movflags +faststart -f mp4 <dir>/<file>.part` |
| `localFlvUrl` | 本机拉流地址（api-base，区别于给浏览器的 play-host） |
| `drainZlmFfmpegLog` | 消费 stderr：进度行静默解析，错误特征行 WARN，其余 INFO |
| `trackZlmFfmpegOutTime` | 解析 `time=` 写入 `outTimeSec`（前端 `/api/download/status` 的真实进度） |
| `awaitZlmFfmpegExit` | ffmpeg EOF → `finalizeZlmDownload` → `stop()` 收尾会话 |
| `stopZlmDownloadFfmpeg` | 用户主动停止路径：等 ffmpeg 优雅退出（60s）再收尾 |
| `finalizeZlmDownload` | `.part` → 正式 MP4；改名失败退化为复制（Windows 占用）；幂等 |
| `buildDownloadFileName` | `rec_<通道>_<起>_<止>.mp4`（与 local 同格式，`+8` 时区） |

另有三处集成：

1. `cleanup()` 内 DOWNLOAD 分支：关闭 ZLM 媒体流后等 ffmpeg 退出并收尾（顺序保证 ZLM 先断流、ffmpeg 才能 EOF）；
2. `onShutdown()`：`ffmpegExecutor.shutdownNow()`（线程池全部守护线程，不阻塞停机）；
3. 常量：`DOWNLOAD_MEDIA_READY_WAIT_MILLIS=15_000`、`FFMPEG_DOWNLOAD_WAIT_MILLIS=60_000`、`DL_NAME_TIME`、`FFMPEG_TIME_PATTERN`；字段 `ffmpegExecutor`（缓存线程池，名 `zlm-ffmpeg-download`）。

### 4.5 顺手修复：ZLM 模式幂等复用判断失效

`MediaService.getOrStartPlayback/getOrStartDownload` 的复用判断查 `MediaService.sessionsByKey`，zlm 模式下是空表——导致“重复点同一条录像每次都重建会话”（BYE + 重新 INVITE，是摄像机宕机的典型诱因）。现在 zlm 分支统一走 `ZlmMediaService.startPlaybackOrDownload`，复用判断在其内部完成：

```java
StreamSession exist = sessionsByKey.get(key(deviceId, channelId));
if (exist != null
        && exist.getState() != StreamSession.State.STOPPED
        && exist.getPlaybackStart() == startTime
        && exist.getPlaybackEnd() == endTime
        && (exist.getType() == StreamSession.Type.DOWNLOAD) == download) {
    return exist;   // 同类型同时间段才复用，不拆建
}
```

**类型必须一起判断**：下载（落盘）与回放（实时分发）末端形态不同，DOWNLOAD 不能被 PLAYBACK 请求误复用，反之亦然。

### 4.6 进度语义：用 ffmpeg `time=` 而非“录像时长/倍速”

设备若不支持 `a=downloadspeed` 会静默降回 1x 推流，此时按“录像时长/4”估算进度会过早到顶假死。ffmpeg 的 `time=` 是**已转码的录像时长**，无论设备按几倍速推流都准确，故以它为唯一进度信号。

### 4.7 收尾路径（全部收敛，无泄漏）

| 触发 | 路径 |
|---|---|
| 设备推流自然结束 | ffmpeg EOF → finalize 改名 → `stop()`（BYE + closeStreams + STOPPED） |
| 用户中途停止 | `stopInternal` → `cleanup` → DOWNLOAD 分支等 ffmpeg 退出 → finalize |
| MediaSource 等待超时 / ffmpeg 启动失败 | `stop()` → `cleanup` → finalize（`.part` 不存在时空操作） |
| 平台停机 | `onShutdown` 遍历 cleanup + `ffmpegExecutor.shutdownNow()` |

注意 ffmpeg **没有 stdin 句柄**（输入是 HTTP-FLV 不是管道），不能像 local 模式那样关 stdin 触发 EOF；靠的是上层先 `closeStreams` 注销 MediaSource、HTTP 连接随之断开。`finalizeZlmDownload` 幂等，多条路径重复调用安全。

### 4.8 前端零改动

`playback.html` 的 `downloadRecord`、遮罩文案（“录像下载较慢…4 倍速…设备不支持时自动降速”）、`.part` 轮询、`awaitFinalDownloadFile`（播放结束时间 +90s 内拿到 `.part` 即传）全部原样复用，因为后端契约（`downloadUrl` / `downloadFileName` / `downloadSpeed` / 状态端点）与 local 完全一致。

---

## 5. 工程坑：编辑落盘与增量编译假象（本次实录）

改造后期出现过一次**假成功**，记录以防再犯：

- `replace_in_file` 对常量区的一次编辑返回成功，但内容**实际未落盘**（后续 search_content 找不到常量）；
- 此时 `mvn compile` 仍报 **BUILD SUCCESS**——增量编译认为 class 未过期，没有真正重编，掩盖了缺失。

**对策**（后续编辑一律执行）：

1. 每次批量编辑后，用 `search_content` 抽查关键定义**确实在文件里**，不轻信工具返回值；
2. 验证编译用 `mvn clean compile` 全量，不用增量；
3. `read_lints` 的“cannot be resolved”与 mvn 结果矛盾时，**以文件实际内容为准**。

---

## 6. 最终链路总览

```text
┌─ 直播 ─────────────────────────────────────────────┐
│ INVITE(live SSRC) → ZLM MediaSource → WebRTC       │
├─ 回放 ─────────────────────────────────────────────┤
│ INVITE(playback SSRC) → ZLM MediaSource → WebRTC   │
│   （/api/playback/webrtc-url，信令必须 http 透传）   │
├─ 下载 ─────────────────────────────────────────────┤
│ INVITE(playback SSRC) → ZLM MediaSource            │
│   → ffmpeg HTTP-FLV 拉流 → x264+aac → MP4 .part    │
│   → EOF 改名 → /api/download/file 流式传输          │
└────────────────────────────────────────────────────┘
```

同一设备同一时间范围，回放与下载的唯一差别是会话 `type`（PLAYBACK / DOWNLOAD），媒体面完全复用。

---

## 7. 核心结论

1. **回放/下载与直播在 ZLM 侧是同一条链路**，差异只在末端消费：WebRTC 给浏览器，ffmpeg 拉流给 MP4。这是本次能用最小改动补齐两块能力的根本原因。
2. **平台进程取流一律用 `api-base`**，给浏览器的地址用 `play-host`，两者不可混用（本机可达性 vs 外网可达性）。
3. **会话状态单一数据源**：zlm 模式全部会话在 `ZlmMediaService`，`MediaService` 侧任何查询都必须委托，否则空表恒 null。
4. **进度用真实转码时长**（ffmpeg `time=`），不用倍速估算——设备静默降速时估算会假死。
5. **幂等复用要带类型**：同通道同时间段，DOWNLOAD 与 PLAYBACK 不可互复用。
6. **收尾路径必须全部收敛**：自然结束 / 用户停止 / 超时 / 启动失败 / 停机，五条路径都走到 finalize + 会话清理，`finalize` 幂等是安全前提。

---

## 8. 后续注意事项

- 浏览器 WebRTC 播放 H.265 依赖浏览器版本支持；遇到旧版 Chrome 无法播时，退化路径是 ZLM 侧转码为 H.264（配置改动，代码不用动）。
- 下载目录所在盘需要留有足够空间（2688x1520 数分钟录像即达百 MB 级；`download-crf`/`download-preset` 可在 `application.yml` 的 `media.local` 下调）。
- 若后续支持“多路同时下载”，`ffmpegExecutor` 已是缓存线程池无需改，但要注意磁盘 IO 与 CPU 峰值，必要时加并发信号量。
- 不要再把 `MediaService.sessionsByKey` 当作 zlm 模式的会话来源；新增加载/统计类功能时，先确认数据在哪个服务。

---

# 26. 语音对讲实现原理（前端 + 后端）


> 适用范围：`sip-server` GB/T 28181-2022 语音对讲 / 语音广播。
> 阅读对象：需要维护、排障或扩展对讲链路的开发者。
> 关联文档：《TECH.md》第 24 章（3.8 节为对讲的模式适配摘要）。

---

## 1. 总览

### 1.1 是什么

浏览器麦克风 ⇄ 本平台 ⇄ 摄像机扬声器/麦克风的**实时双向音频**。平台在中间完成三件事：

1. **信令协商**：用 SIP INVITE/SDP 与设备约定音频参数（编码 PCMA、SSRC、传输方式、设备收音频的地址端口）；
2. **音频转码**：浏览器侧 PCM ⇄ 设备侧 G.711A(PCMA)；
3. **RTP 收发**：上行（平台→设备）与下行（设备→平台）各一条 RTP 流，20ms 一帧。

### 1.2 三种形态

`POST /api/voice/start` 的 `mode` 参数决定走哪条设备侧路径：

| mode | 信令路径 | SDP | 方向 | 用途 |
|---|---|---|---|---|
| `talk`（默认） | 平台主动 INVITE → 设备 200 OK → 平台 ACK | `s=Talk` + `a=sendrecv` | 双向 | 实时对讲 |
| `broadcast` | 平台先发广播 MESSAGE 通知，再 INVITE → 200 OK → ACK | `s=Broadcast` + `a=sendonly` | 平台→设备单向 | 喊话；绕开部分设备把对讲 INVITE 误判为点播的问题 |
| `legacy-broadcast` | 平台发广播 MESSAGE → **设备反向 INVITE** → 平台 200 OK(answer) | answer SDP `s=Play` | 设备→平台 | 旧版广播（设备推流方向） |

> 为什么要有 `broadcast` 形态：部分设备（含海康 IPC）把对讲 INVITE 误判为点播——200 OK 回 `s=Play`、`a=filesize:0`，并向平台端口发 PS 视频流，既不播平台音频也不上行麦克风。广播语义（平台单向喊话、设备播放）在设备侧是独立代码路径，可绕开该误判。

### 1.3 架构结论：与媒体模式（local/zlm）无关

```
        ┌─────────────────────────── 浏览器 ───────────────────────────┐
        │ 麦克风 PCM ──► /ws/voice ──►                 ──► 扬声器 PCM   │
        └─────────────────────────────┬─────────────────────────────────┘
                                      │ WebSocket（二进制帧，8kHz/16bit/LE）
                                      ▼
┌─────────────────────────────────────────────────────────────────────┐
│ 平台 sip-server                                                     │
│  VoiceService ── SIP INVITE/ACK/BYE（SipRequestBuilder）──► 设备     │
│      │ 上行: PCM→G711A→RTP(20ms) ─────────────────────────► 设备     │
│      └ 下行: 设备RTP→G711A→PCM ───────────────────────────► WS       │
│  VoiceRtpEndpoint（每会话独占 UDP 端口 30100–30150 + TCP 监听）       │
└─────────────────────────────────────────────────────────────────────┘
```

- 对讲媒体**不经过 MediaService / ZLMediaKit / RtpReceiver**，local 与 zlm 模式代码零分支；
- ZLM 不应进入对讲链路：ZLM 的 RTP Server 是**收流方**，对讲要求平台**向设备主动发 RTP**，ZLM 无此能力；
- 模式唯一差异是 SDP 通告 IP（`VoiceService.announceIp()`）：zlm 模式取 `media.zlm.media-ip`（与点播/回放同一设备可达口径），local 模式取 `sip.server.ip`。

### 1.4 组件清单

| 组件 | 文件 | 职责 |
|---|---|---|
| VoiceService | `service/voice/VoiceService.java` | 会话生命周期、状态机、信令协调、G711 转码、RTP 发送循环 |
| VoiceRtpEndpoint | `service/voice/VoiceRtpEndpoint.java` | 每会话独占 UDP 端口 + TCP 监听、RTP 收发、RFC 4571 成/解帧 |
| VoiceWebSocketHandler | `websocket/VoiceWebSocketHandler.java` | 浏览器上行 PCM 通道 `/ws/voice`、上行限流日志 |
| VoiceSignalHandler | `handler/VoiceSignalHandler.java` | 设备侧 SIP 消息路由（INVITE/ACK/BYE/响应）分流到 VoiceService |
| VoiceSession | `model/VoiceSession.java` | 会话模型：dialog 信息、RTP seq/ts、双向音频缓冲、诊断统计 |
| SipRequestBuilder（语音部分） | `sip/SipRequestBuilder.java` | INVITE/MESSAGE/ACK/BYE/三种 SDP 构造 |
| G711a | `service/audio/G711a.java` | A-law 编解码（ITU-T G.711，13 段折线整数算法） |
| RtpPortPool | `service/media/RtpPortPool.java` | 端口池（语音池与媒体池复用同一实现，名字区分） |
| WebSocketConfig | `config/WebSocketConfig.java` | 注册 `/ws/voice` handler |
| DeviceController（语音段） | `controller/DeviceController.java` | `/api/voice/start|stop|sessions` |
| 前端 | `static/voice.html`（控制台）、`static/voice-talk.html`（对讲页） | 发起/停止会话、麦克风采集、下行播放、电平表 |

---

## 2. 协议基础

### 2.1 对讲（talk）信令时序

```
浏览器                平台 VoiceService              设备
  │ POST /api/voice/start │                              │
  │──────────────────────►│ acquirePort() 绑定独占端口   │
  │                       │ INVITE (s=Talk, sendrecv,   │
  │                       │  m=audio <port> RTP/AVP 8)  │
  │                       │─────────────────────────────►│
  │                       │◄───────────── 200 OK (SDP:  │
  │                       │              设备收音频 IP:port,│
  │                       │              可能应答 TCP)    │
  │                       │ 解析 SDP 协商 audioIp:audioPort│
  │                       │  ACK ───────────────────────►│
  │ WS 连接 /ws/voice     │                              │
  │──────────────────────►│ registerWebSocket            │
  │                       │ 进入 TALKING，启动 RTP 发送循环│
  │ PCM 二进制帧 ─────────►│ G711A→RTP(20ms) ───────────►│ 设备扬声器
  │                       │◀──────────── RTP(PCMA) ──────│ 设备麦克风
  │◄──── PCM 二进制帧     │ G711A decode                  │
  │ POST /api/voice/stop  │ BYE ────────────────────────►│
  │ 或设备 BYE ──────────►│ 释放端口/关闭 WS              │
```

要点：

- **Subject 头**：`通道ID:SSRC,平台ID:0`。SSRC 是设备把 RTP 流绑定到本次会话的依据；旧格式（`通道ID:平台ID,1,0`）不含 SSRC，部分设备据此把请求按"监听"处理并回 `a=sendonly`；
- **200 OK 可能被设备周期性重传**（未收到有效 ACK 时）：平台对每个重传都重发 ACK，但 SDP 协商只在首个 200 OK 执行（`markInviteNegotiated()` 幂等）；
- **设备 200 OK 的 To 头若漏带 tag**（非 RFC 3261）：其 ACK 匹配会失败，表现为设备周期性重传 200 OK，日志显式告警。

### 2.2 平台主动 INVITE 的 SDP（对讲）

```
v=0
o=<平台ID> 0 0 IN IP4 <通告IP>
s=Talk
c=IN IP4 <通告IP>
t=0 0
m=audio <会话独占端口> RTP/AVP 8
a=sendrecv
a=rtpmap:8 PCMA/8000
y=<SSRC>
f=v/////a/1/8/1
```

| 字段 | 值 | 含义 / 理由 |
|---|---|---|
| `o=` / `c=` | 通告 IP | 设备把 RTP 推到该地址（zlm 模式 = `media.zlm.media-ip`） |
| `s=` | `Talk` | 对讲标准会话名（WVP 同值）；广播用 `Broadcast` |
| `m=audio` | 会话独占端口 + `RTP/AVP 8` | PCMA；端口来自语音端口池，多路对讲互不冲突 |
| `a=sendrecv` | 双向 | 广播形态为 `a=sendonly` |
| `y=` | SSRC | 设备上行 RTP 的流标识 |
| `f=` | `v/////a/1/8/1` | 纯音频流媒体参数行（视频段留空），WVP 对讲同样携带 |

设备 200 OK 应答 `TCP/RTP/AVP` 时，平台自动切 TCP（设备按 `a=setup:active` 主动连入平台端口）；应答 `RTP/AVP` 则回退 UDP。以应答为准（RFC 3264）。

### 2.3 传输协商：UDP 与 TCP 双通道

平台对每个会话**同时**准备两种传输（同端口号，TCP/UDP 协议栈不同互不冲突）：

| SDP | 传输通告 | 说明 |
|---|---|---|
| 对讲 INVITE（`buildTalkSdp`） | `RTP/AVP 8`（UDP） | 设备 200 OK 应答 TCP 时平台自动切 TCP |
| 广播 INVITE（`buildBroadcastInviteSdp`） | `TCP/RTP/AVP 8` + `a=setup:passive` + `a=connection:new` | 设备按 RFC 4145 主动连入平台端口 |
| 设备反向 INVITE 的 200 OK answer（`buildAudioAnswerSdp`） | 按设备 offer 对应答：offer 为 TCP 则答 `TCP/RTP/AVP` + `a=setup:passive` + `a=connection:new`，否则答 `RTP/AVP` | 以 offer 为准 |

协商规则（RFC 3264，**以设备应答为准**）：

- 设备 200 OK 应答 `TCP/RTP/AVP` → `session.tcp=true`，RTP 走设备主动连入的 TCP 连接；应答 `RTP/AVP` → 回退 UDP；
- **TCP 路径**：RTP 按 **RFC 4571 成帧**：`$` + 1 字节通道号(0) + 2 字节大端长度 + RTP 包；
- **设备 200 OK 应答可能含多条 `m=audio` 且均带 `a=connection:new`**（RFC 4145：每条 m-line 一条独立连接）——设备会为"接收平台音频"与"发送设备音频"分别建连。平台**保留该会话全部 TCP 连接**：下行帧写到所有连接（由设备取用对应的），上行从所有连接读取；
- 两条路径的上行 RTP 包最终汇合到同一处理（`onDeviceAudio`），传输差异对上层透明。

---

## 3. 后端实现原理

### 3.1 会话状态机

```
                 startTalk/startBroadcastTalk
                        │
                        ▼
   BROADCASTING ──设备反向INVITE到达──┐        INVITED ──200 OK + ACK──► TALKING
   (legacy 广播等设备来)             │        (已发 INVITE)                  │
                        │            ▼            │                          │
                        │      (匹配广播会话)      │                          │
                        └────────► TALKING ◄──────┘                          │
                                  │                                         │
                          用户停止/BYE/超时/异常                              │
                                  ▼                                         ▼
                               STOPPED ◄─────────────────────────────────────┘
```

| 状态 | 进入条件 | 退出条件 |
|---|---|---|
| `BROADCASTING` | `start()`（legacy-broadcast）发出 MESSAGE 后 | 设备 INVITE 到达→TALKING；60s 超时→STOPPED |
| `INVITED` | 平台 INVITE 已发送 | 200 OK + ACK→TALKING；10s 无应答/4xx-6xx→STOPPED |
| `TALKING` | 媒体链路建立（ACK 后或设备 INVITE 应答后） | 停止/BYE/异常→STOPPED |
| `STOPPED` | 终态 | — |

超时任务跑在 `voice-timeout` 调度线程（2 线程守护）；每个会话的发送循环是独立守护线程 `voice-send-<key>`。

### 3.2 多端口模型（为什么每会话独占端口）

- 旧版全局固定端口（30001）只支持单路对讲，多设备互窜；
- 现在每会话从 **RtpPortPool 语音池**（默认 30100–30150，容量 51 = 最大并发会话数）`acquireRotational()` 轮转分配一个端口，构造 `VoiceSession` 后 `bind()` 独立 `DatagramSocket` + 独立接收线程 `voice-rtp-<key>`；
- SDP `m=audio` 通告该端口，设备直推；
- 会话停止 `release()`：关 Socket/TCP、归还端口、端口可回收；
- 启动时 `checkPortOverlap()` 校验语音池与当前模式媒体端口不重叠（zlm 固定 30002 / local 池 30000–30099），重叠则 `log.error` 暴露配置错误。

### 3.3 上行音频链路（浏览器 → 设备）

```
WS 二进制帧(PCM 8k/16bit/LE)
  → VoiceWebSocketHandler.handleBinaryMessage
      · 参数校验（deviceId/channelId）
      · 上行限流日志（每 2s：累计字节 + 块率，应≈31 块/s）
  → VoiceService.pushAudio
      · 仅 TALKING 状态接收
      · 诊断：统计 PCM 峰值（peak≈0 = 麦克风静音/无权限）
      · G711a.encode（2:1 压缩）→ session.offerAlaw() 进 audioBuffer（上限 2s，超限整体丢弃防延迟累积）
  → sendLoop（每 20ms）
      · pollAlawFrame(160)：取 160 字节 = 20ms PCMA
      · 取不到 → createSilenceFrame()（0xD5 填充）——保证 20ms 节奏不断流，设备侧听到"数字静音"而非卡顿
      · buildRtpPacket → rtpEndpoint.send
  → VoiceRtpEndpoint.send
      · TCP：RFC 4571 成帧写到全部活跃连接
      · UDP：发往 audioIp:audioPort（200 OK 协商地址）
      · 目标无效 → 跳过本次发送并每会话告警一次（严禁盲发设备 SIP 地址）
```

**RTP 包构造**（`buildRtpPacket`，12 字节固定头 + 160 字节载荷）：

| 偏移 | 字段 | 值 |
|---|---|---|
| 0 | V/P/X/CC | `0x80`（V=2） |
| 1 | M/PT | `8`（PCMA，以 SDP 协商值为准） |
| 2–3 | seq | `nextSeq()`，16-bit 自然溢出 |
| 4–7 | timestamp | `nextTimestamp()`，8kHz 每帧 +160 |
| 8–11 | SSRC | 会话 SSRC（Subject/`y=` 同值） |

### 3.4 下行音频链路（设备 → 浏览器）

```
设备 RTP（UDP 到会话端口 / TCP 连接）
  → VoiceRtpEndpoint.receiveLoop / tcpReadLoop
      · 端口级探针计数 packetsReceived（任何到达端口的包都计数，含被过滤的）
      · 首包日志（src、len）；TCP 前 40 包逐包诊断（pt/seq/ts/ssrc/间隔/hex 头）
      · len < 12 丢弃；RTP 包 → listener.onAudioRtp
  → VoiceService.onDeviceAudio
      · 仅 TALKING 处理
      · 首包日志（设备 RTP 头 SSRC）
      · 源地址学习：audioIp 未协商时从 RTP 源地址登记（兜底）
      · 剥离 RTP 头 → G711a.decode → 8kHz/16bit/LE PCM
      · 诊断：2s 周期统计解码包数 + 峰值（peak=0 = 数字静音）
      · offerIncomingPcm（缓冲，上限 2s）+ broadcastPcm 推送全部 WS 连接
  → 浏览器 BinaryMessage → playIncomingAudio → AudioBufferSource 播放
```

### 3.5 信令协商细节

**平台主动 INVITE 的 200 OK**（`handleInviteResponse`）：

1. 重传幂等：`markInviteNegotiated()` 首个执行协商，重传仅重发 ACK；
2. 传输协商：应答 `TCP/RTP/AVP` → `tcp=true`（设备 ACK 后主动连入）；
3. RTP 目标：媒体级 `c=`（audioIp）优先，缺失回退会话级 `c=`（sessionIp），端口取 `m=audio`；`0.0.0.0`/端口 0 视为未协商，留给源地址学习兜底；
4. PT 取应答 PCMA 值；
5. 记录 deviceTag、peerContact（BYE 的 Request-URI 用 Contact 裸 URI）；
6. 4xx–6xx：打印完整响应（部分设备在 Warning 头/正文给拒绝原因）并停止。

**设备反向 INVITE**（`handleDeviceInvite`，legacy 广播 / 设备直呼）：

- 按 `channelId` 找 `BROADCASTING` 会话；找不到则按"设备直呼"新建会话（deviceId 未知时用 sender:channelId 为键）；
- 传输以 **offer 为准**（设备给 TCP 就答 TCP）；
- SSRC 严格取 SDP `y=` 解析值；
- 200 OK answer（`buildAudioAnswerSdp`）：`m=audio <会话端口>` + `a=setup:passive`（TCP 时）+ `a=sendonly` + `f=v/////a/1/8/1`；
- 端口池耗尽 → 返回 false，上层回 `486 Busy Here`。

**BYE 双路径**（`doStop`）：

- 平台主动 INVITE 的会话（TALKING/INVITED）：`voiceBye`，Request-URI 用 200 OK Contact 裸 URI（带尖括号会被部分设备静默丢弃），`CSeq: 2 BYE`；
- 广播流程已建 dialog（BROADCASTING 且有 broadcastCallId）：`buildBroadcastBye`，Call-ID 用设备侧 INVITE 的；
- 设备已主动 BYE 时平台**不再回发**（`sendBye=false`）；
- 无论哪条路径：摘会话索引 → 取消超时 → 置 STOPPED → `rtpEndpoint.release`（关 Socket/TCP + 归还端口）→ 关闭全部 WebSocket（CloseStatus 4500 + 原因）。

### 3.6 WebSocket 通道

- 注册：`WebSocketConfig` → `/ws/voice`，允许跨域；
- 握手校验 `deviceId`/`channelId`（缺失以 1008 POLICY_VIOLATION 拒绝），按 `deviceId:channelId` 注册到 VoiceService；
- 上行：二进制帧 = PCM，`pushAudio`；
- 下行：`broadcastPcm` 向该会话全部 WS 连接推 `BinaryMessage`（发送时对单连接加锁防并发写）；
- 会话停止/WS 断开双向清理，无泄漏。

---

## 4. 前端实现原理（voice-talk.html）

### 4.1 页面流转与上下文传递

```
voice.html（控制台）
  POST /api/voice/start {deviceId, channelId?, mode}
  → sessionStorage: voiceWsUrl / voiceDeviceId / voiceChannelId
                    / voiceBroadcast / voiceCallId / voiceLocalPort
  → location.href = /voice-talk.html
voice-talk.html
  读 sessionStorage（缺失回退 voice.html）
  页面加载即连接 WS（不等用户手势——浏览器要求麦克风必须有用户手势）
  用户点"开启麦克风"才 getUserMedia
```

`sessionStorage` 而非 URL query：Call-ID/端口等信息不宜暴露在地址栏，且刷新页面不丢失上下文。

### 4.2 麦克风采集链

```
getUserMedia({audio:{channelCount:1, echoCancellation:true,
                     noiseSuppression:true, autoGainControl:false}})
  → MediaStreamSource → ScriptProcessorNode(1024, 1, 1)
      onaudioprocess:
        input(48kHz Float32) → resampleTo8k(线性插值) → floatToPcm16 → ws.send(pcm.buffer)
  → gain=0 哑节点 → destination
```

关键参数的理由：

| 参数 | 理由 |
|---|---|
| `autoGainControl: false` | AGC 会把正常说话声推到 Int16 削顶（日志 peak=32767），G.711 压扩后表现为持续爆音/失真 |
| `echoCancellation: true` | 设备扬声器外放时抑制回声 |
| `ScriptProcessorNode(1024)` | 兼容性优先（AudioWorklet 需单独模块文件）；1024 样本 @48kHz ≈ 21ms 回调一次 |
| gain=0 哑节点 | 不直连 destination 会把麦克风送回扬声器造成啸叫；但部分浏览器需挂到 destination 才会被拉取音频线程，哑节点兼顾两者 |
| 线性插值重采样 | 48k→8k 简单够用（语音带宽 4k）；避免引入重采样依赖 |

### 4.3 上行发送与"后台节流"陷阱

- 每块 PCM 直接 `ws.send(pcm.buffer)`，服务端 G711 编码后按 20ms 节奏发设备——**上行块率由浏览器决定，下行 RTP 节奏由服务端决定**；
- Chrome 对后台标签页的 ScriptProcessor 有 **1Hz 节流**：块率跌到 ~1 时，设备每秒只收到 1 块真实语音，其余全是服务端补的 A-law 静音帧——表现为"摄像机没有声音"；
- 前端对策：每秒计算块率（48kHz/1024 应 ≈45 块/s），`document.hidden || rate < 10` 时显示醒目警告，提示保持页面前台。

### 4.4 下行播放与电平表

- `ws.onmessage` 收到 ArrayBuffer → `new Int16Array` → `createBuffer(1, len, 8000)` → `/32768` 归一化填 Float32 → `AudioBufferSourceNode.start()`；
- 每个 WS 帧独立创建一个 BufferSource（简单、无抖动累积；块率 ~50/s 可接受）；
- 电平表：取帧内最大绝对值驱动宽度，100ms 后归零；上行同理但用原始 input 峰值。

### 4.5 会话信息轮询

`setInterval(refreshSession, 1000)` 拉 `/api/voice/sessions`，按 deviceId+channelId 找到本会话，渲染状态/SSRC/PT/设备 RTP 地址/已发包数，并按 state 驱动状态灯（TALKING 绿、INVITED/BROADCASTING 黄、STOPPED 红）。

---

## 5. 关键设计决策

| # | 决策 | 理由 |
|---|---|---|
| 1 | 对讲不走 ZLM/local 媒体链路 | ZLM RTP Server 是收流方，无"向设备主动发对讲 RTP"能力；平台直连是 WVP 同款标准做法 |
| 2 | 每会话独占端口（多端口） | 多设备同时对讲互不干扰；固定端口模式只支持单路 |
| 3 | G711A 而非 OPUS/AAC | GB/T 28181-2011 规定音频载荷就是 G.711a；设备侧无协商空间 |
| 4 | 20ms 固定节奏 + 静音补帧 | 设备解码器期待恒定包率；缓冲取空时补 0xD5 静音帧而非暂停发送，避免设备侧卡顿/杂音 |
| 5 | 传输以设备应答为准（UDP/TCP 自适应） | RFC 3264；海康/大华 IPC 对讲普遍走 TCP passive（设备主动连入） |
| 6 | 保留全部 TCP 媒体连接 | 设备应答常含多条 `m=audio` + `a=connection:new`，收/发可能是不同连接；只留第一条会"上行通、下行无声" |
| 7 | 200 OK 重传幂等 + 仍重发 ACK | 重传即设备未认可 ACK 的信号，重发是对抗手段；协商只做一次防状态被覆盖 |
| 8 | RTP 目标必须协商/学习，严禁盲发 SIP 地址 | 设备 SIP 端口（5060）不收媒体，盲发即"无声"；源地址学习兜底覆盖 SDP 不含有效地址的设备 |
| 9 | 上行缓冲超限整体丢弃 | 实时语音宁可丢不可堆——堆积会把延迟越拉越大 |
| 10 | 三层诊断日志（限流） | 20ms/帧的场景下无差别打日志会拖垮节奏：限流告警 + 首包一次 + 周期统计 |

---

## 6. 配置与端口

```yaml
sip:
  voice:
    enabled: true            # 语音对讲总开关
    port-pool-start: 30100   # 语音 RTP 端口池（含）
    port-pool-end: 30150     # 容量 51 = 最大并发语音会话数
```

| 端口/区间 | 用途 | 备注 |
|---|---|---|
| 5060 | SIP（UDP） | `sip.server.port` |
| 30000–30099 | local 模式媒体端口池 | 与语音池不重叠 |
| 30002 | zlm 模式 ZLM 固定 RTP 端口 | 与语音池不重叠 |
| 30100–30150 | 语音对讲端口池 | 每会话独占一个 |
| 8080 | HTTP/WebSocket | `/ws/voice` |

启动日志会打印语音池、当前媒体模式与 SDP 通告 IP；端口重叠时 `log.error` 显式报错。

---

## 7. 诊断日志速查

| 日志 | 含义 |
|---|---|
| `统一语音服务初始化: ... 通告IP=... 端口池=[30100..30150]` | 启动配置确认（含 zlm/local 模式与通告 IP） |
| `对讲INVITE已发送 / 广播INVITE已发送 / 广播MESSAGE已发送` | 信令已发出 |
| `设备200OK原文` / `设备拒绝对讲` | 协商全文 / 4xx-6xx 原因 |
| `200OK传输协商 / 200OK协商RTP目标 / 设备INVITE协商RTP目标` | 传输方式与设备收音频地址 |
| `设备TCP媒体连接已建立 / 会话端口收到首个UDP包` | 设备媒体通道建立证据 |
| `RTP实际发送目标` | 平台→设备的实际投递地址（抓包比对用） |
| `RTP发送线程启动/退出`、`RTP统计`（5s） | 已发送持续增长 = 上行 RTP 存在；`收包` = 端口级探针 |
| `上行音频电平: peak=0` | 浏览器麦克风静音/无权限（数字静音） |
| `下行设备音频统计: 解码peak=0` | 设备发的是数字静音（设备麦克风问题） |
| `上行音频: ≈N 块/s（应≈31）` | WS 上行节奏（低于 10 多半是页面被后台节流） |
| `TCP媒体连接尚未建立` | 设备未按 setup:active 连入（等 ACK 后才连） |

---

## 8. 故障排查表

| 症状 | 可能原因 | 定位 |
|---|---|---|
| 对讲无声，`RTP统计` 收包=0、无"收到设备RTP首包" | 设备麦克风静音/未授权；SDP 被设备拒绝 | 看 `设备拒绝对讲` / 设备 Web 端音频设置 |
| 收包增长但无"收到设备RTP首包" | 收到的是非 RTP 杂包（端口探针计数含过滤） | 抓包或看 TCP 前 40 包诊断 |
| 上行无声，`上行音频电平 peak=0` | 浏览器麦克风静音/无权限/AGC 削顶 | 检查页面权限与 HTTPS/localhost 环境 |
| 上行时断时续，`块率≈1` | 页面被浏览器后台节流 | 保持页面前台（前端有警告条） |
| TCP 形态"上行通、下行无声" | 设备多连接场景只保留了一条 | 确认代码保留全部 tcpConns（历史坑） |
| 对讲被设备当成点播（回 s=Play、发 PS 视频流） | 设备对 `s=Talk` 的误判 | 改用 `mode=broadcast` |
| 设备周期性重传 200 OK | 设备未收到有效 ACK（To tag 缺失等） | 看 `设备200OK的To头未携带tag` 告警 |
| 端口绑定失败 | 端口池耗尽或被占用 | `端口绑定失败，归还端口` / 调整 `port-pool-*` |
| zlm 模式对讲不通但 local 通 | SDP 通告 IP 与设备可达媒体地址不一致 | 确认 `media.zlm.media-ip` 配置；启动日志的 `通告IP=` |

---

## 9. 代码地图

| 文件 | 关键入口 |
|---|---|
| `service/voice/VoiceService.java` | `startTalk` / `startBroadcastTalk` / `start` / `pushAudio` / `sendLoop` / `onDeviceAudio` / `handleInviteResponse` / `handleDeviceInvite` / `doStop` / `announceIp` |
| `service/voice/VoiceRtpEndpoint.java` | `acquirePort` / `bind` / `send` / `receiveLoop` / `acceptLoop` / `tcpReadLoop` / `release` |
| `websocket/VoiceWebSocketHandler.java` | `afterConnectionEstablished` / `handleBinaryMessage` |
| `handler/VoiceSignalHandler.java` | `handleInvite` / `handleAck` / `handleBye` / `handleInviteResponseIfOwned` |
| `model/VoiceSession.java` | `offerAlaw` / `pollAlawFrame` / `offerIncomingPcm` / `nextSeq` / `nextTimestamp` / `markInviteNegotiated` |
| `sip/SipRequestBuilder.java` | `voiceInvite` / `broadcastInvite` / `broadcastMessage` / `voiceAck` / `voiceBye` / `buildTalkSdp` / `buildBroadcastInviteSdp` / `buildAudioAnswerSdp` |
| `service/audio/G711a.java` | `encode` / `decode` |
| `static/voice.html` | `startVoice(mode)` / `enterVoice(s)` |
| `static/voice-talk.html` | `startTalk` / `stopTalk` / `startMicrophone` / `sendPcm` / `playIncomingAudio` / `resampleTo8k` |

---

# 27. 语音通话音质改造方案（voice-talk 对讲链路）


> **背景**：WVP+ZLM 的语音通话"降噪特别好、很清晰"，自研 local 链路对讲（`voice-talk.html`）相对发闷、有底噪、偶发断续。
> **结论先行**：差距**不在传输层，在浏览器端音频处理链**——WVP+ZLM 走浏览器原生 WebRTC（NetEq 抖动缓冲 + APM：NS/AGC/AEC + Opus 宽带），自研是「WS 裸 PCM → 每帧直接 BufferSource 起播」，无抖动缓冲、无降噪、无增益控制。
> **本方案目标**：不动服务端、不动 SIP/RTP 协议（PCMA 8k 窄带由设备协商定死），纯前端链路补齐 WebRTC 同款处理，把听感拉到可接受水平。

---

## 1. 差距根因（代码锚点）

| 环节 | WVP+ZLM（WebRTC） | 自研现状（`voice-talk.html`） |
|------|------|------|
| 抖动缓冲 | NetEq 自适应 | **无**：每帧到达即 `createBufferSource().start()`（`:604-609`） |
| 降噪 NS | WebRTC NS | 下行无任何处理 |
| AGC | WebRTC AGC | 下行无；上行浏览器 AGC 因削顶爆音被禁（`:444-446`） |
| 重采样 | 多相滤波+抗混叠 | **线性插值**（`:619-647`），48k→8k 无低通，高频折叠成噪声 |
| 编解码 | Opus 宽带 | G.711 A-law 8k 窄带（**协议定死，不在本方案范围**） |
| 处理线程 | 专用实时线程 | `ScriptProcessor` 在主线程（已废弃，后台标签页被 1Hz 节流） |

三个可修的点按收益排序：**① 下行无抖动缓冲（断续/爆音主因）→ ② 下行无降噪/限幅（底噪主因）→ ③ 上行无抗混叠重采样（毛糙主因）**。

---

## 2. 改造范围与原则

- **只动前端 3 个文件**，服务端 Java / `application.yml` **零改动**；SIP/RTP/WS 协议不变。
- 保留 `?legacyAudio=1` 开关：任何一步出问题可秒回退旧链路，不用重新发版。
- 处理逻辑全部下沉到 **AudioWorklet 实时线程**，不占主线程。
- 先用「能量门限降噪」验证收益，不够再上谱减/RNNoise（见第 8 节）。

---

## 3. 目标链路架构

```text
【下行】设备 RTP → 服务端 G711a 解码 → WS 二进制帧(8k PCM16, 20ms/帧)
        → postMessage ─▶ [PcmPlayerWorklet] 环形缓冲(~1s)
                        → 按 128 样本/量子 时钟驱动消费
                        → 噪声门限(降噪) → tanh 软限幅(替代 AGC)
                        → AudioContext.destination（48k context，浏览器高质量重采样）

【上行】getUserMedia(48k, EC/NS 开、AGC 关)
        → [MicProcessorWorklet] 攒 1024 样本
        → 31 抽头 FIR 低通(截止 3.4k) + 6 倍抽取 → 8k
        → 自研 AGC(目标 -18dBFS, 限幅 0.7) → postMessage
        → 主线程 sendPcm() → WS → 服务端 G711a 编码 → RTP → 设备
```

**关键设计决策：AudioContext 固定 48kHz**（`new AudioContext({sampleRate: 48000})`）。
系统默认采样率可能是 44.1k（44.1/8=5.5125 非整数，抽取难做）；固定 48k 后上下行重采样比例都是整数 6。浏览器负责把 worklet 输出高质量重采样到硬件率。老浏览器不支持指定 sampleRate 时回落系统默认 + 旧线性插值。

---

## 4. 详细设计

### 4.1 下行播放：`static/js/pcm-player-worklet.js`（新增）

**主线程侧（voice-talk.html 改造点）**：

1. `playIncomingAudio(arrayBuffer)` 改为：Int16→Float32 后 `playerNode.port.postMessage(pcm, [pcm.buffer])`（转移零拷贝）；删除 `createBufferSource().start()` 直接播放逻辑（旧函数保留为 `playIncomingAudioLegacy`，仅 legacy 开关走）。
2. 起播门限：ring 内样本 ≥ 80ms（640 样本）才置 `playing=true` 开始消费；会话建立后的前几帧只攒缓冲，**解决起播爆音**。
3. worklet 通过 port 回传：`{type:'stats', bufferedMs, underrun, overrun}`（1s 周期）、`{type:'meter', peak}`（100ms 周期）→ 主线程更新 UI。

**Worklet 内部**：

```js
// 环形缓冲：Float32Array(8192) ≈ 1s @8k，读写指针按样本推进
// process(inputs, outputs, params)：每次 128 量子
for (let i = 0; i < 128; i++) {
    if (readIdx !== writeIdx) {           // 有数据
        s = ring[readIdx]; readIdx = (readIdx+1) % ring.length;
    } else {                              // underrun：补静音并计数
        s = 0; underrun++;
    }
    // ① 噪声门限（见下）
    // ② tanh 软限幅：|s| > 0.7 时 Math.tanh(s*0.8)/0.8 量级软削
    out[i] = s;
}
```

**噪声门限（谱减的轻量替代）**：
- 噪声底估计：对每 20ms 帧算短时能量，跟踪 500ms 滑动窗口的最小值统计（`noiseFloor`）。
- 门限：`gate = noiseFloor * 4`（约 +6dB）；帧能量低于 gate 时该帧增益 `0.15`（-16dB，不完全静音避免断续感），高于则增益 1.0。
- 平滑：gain attack 10ms / release 250ms 逐样本插值，**消除"呼吸效应"**（门限降噪最常见的毛刺感来源）。

**限幅**：`y = x > 0.7 ? 0.7 + (1-0.7)*Math.tanh((x-0.7)/(1-0.7)) : x`（分段 tanh，阈值 0.7 留 3dB 动态余量）。

### 4.2 上行采集：`static/js/mic-processor-worklet.js`（新增）

**主线程侧**：
1. `audioContext = new AudioContext({sampleRate: 48000})`（`:450`）。
2. `createScriptProcessor` → `await audioContext.audioWorklet.addModule('/js/mic-processor-worklet.js')` + `new AudioWorkletNode(ctx, 'mic-processor')`；`onaudioprocess` 逻辑整体搬进 worklet。
3. worklet `process()` 内攒满 1024 样本再处理一次（保持现有 ~21ms/包的节奏，服务端无感知），结果 postMessage 回主线程 → 现有 `sendPcm()` 不变。
4. `getUserMedia` 约束保持 `echoCancellation:true, noiseSuppression:true, autoGainControl:false`（自研 AGC 替代浏览器 AGC）。

**Worklet 内部**：

```js
// FIR 系数运行时生成（避免手写系数表）：31 抽头，截止 3.4kHz @48k，Hann 窗
//   h[n] = sinc(2*fc*(n-15)/fs) * 0.5*(1-cos(2πn/30)), 归一化到单位增益
// 每 6 个样本做一次 31 抽头卷积 → 输出 1 个 8k 样本（先低通后抽取，抗混叠）

// 自研 AGC（替代被禁用的浏览器 autoGainControl，吸取削顶教训）：
//   目标 RMS -18dBFS；attack 5ms / release 300ms 平滑跟随；
//   增益限制 [0.5, 4.0]；输出超 0.7 再走 tanh 软限幅。
```

### 4.3 诊断面板与开关（voice-talk.html）

- 新增展示行：`播放缓冲 xx ms`、`underrun x / overrun y`、`采样率 48000→8000`（复用现有状态面板样式）。
- `?legacyAudio=1` 或 `addModule` 失败：走旧路径（`createScriptProcessor` + 裸 BufferSource 播放），保证可用性兜底。

---

## 5. 改动清单

| 文件 | 类型 | 内容 |
|------|------|------|
| `src/main/resources/static/js/pcm-player-worklet.js` | **新增** | 下行播放 worklet：ring buffer + 起播门限 + 噪声门限 + tanh 限幅 + stats/meter 回传 |
| `src/main/resources/static/js/mic-processor-worklet.js` | **新增** | 上行采集 worklet：FIR 低通 + 6 倍抽取 + 自研 AGC + 攒帧 1024 |
| `src/main/resources/static/voice-talk.html` | 修改 | 播放链路（4.1 主线程侧）、麦克风链路（4.2 主线程侧）、48k context、诊断面板、legacy 开关 |
| 服务端 Java / application.yml | — | **零改动** |

---

## 6. 明天测试清单（验收）

按顺序勾选，任一项不过即记录现象：

**功能回归（先验这个，音质后验）**
- [ ] 对讲建立：下行能听到设备端声音，上行设备能听到笔记本麦克风（双向通）
- [ ] 停止/重连/BYE 后无残留播放（静音）、无报错（控制台 + `logs/server.err.log`）
- [ ] 广播页 `voice.html` 不受影响（本次未改，确认无回归）

**音质**
- [ ] 连续播放 **5 分钟**：无累积爆音、无变调/变速（漂移）
- [ ] 同一环境下对比旧版（`?legacyAudio=1` 快速切换）：底噪明显降低
- [ ] 上行：设备侧反馈音量稳定、无削顶爆音（重点回归 11.x 踩过的 AGC 坑）

**弱网**
- [ ] Chrome DevTools 限速 200kbps 或丢包 5% 跑 2 分钟：不出现明显断续/啸叫
- [ ] 看诊断面板：缓冲深度在 40~200ms 区间波动，underrun ≤ 1 次/分钟，overrun 偶发

**兼容**
- [ ] Edge / Chrome 各跑一轮；控制台无 AudioWorklet 相关报错

---

## 7. 风险与回滚

| 风险 | 应对 |
|------|------|
| AudioWorklet 不支持（老 Safari < 14.1） | `addModule` 失败自动回落 legacy 路径 |
| 静态资源缓存导致改动不生效 | Ctrl+F5 强刷；worklet 是新增文件无缓存问题 |
| 门限降噪引入"呼吸效应"或吞字 | 调 release（250ms→400ms）或门限倍数（4→6）；仍不行回落 gate 直通（增益 1.0） |
| 48k context 在某些声卡上有兼容问题 | 回落系统默认采样率 + 旧线性重采样（legacy 分支已含） |
| 整体回退 | URL 加 `?legacyAudio=1` 即时回退；或 git revert 上述 3 个文件 |

---

## 8. 后续（不在本次范围）

- **P2：语音也走 ZLM 媒体面**（`VoiceService` INVITE 的 SDP 指向 ZLM `openRtpServer`，浏览器 WebRTC 播放）——直接获得 Opus 宽带 + WebRTC APM 全链路，与视频 zlm 模式架构一致；代价是语音依赖 ZLM 在线，local 保留为降级。约 2-3 天，待本次前端改造效果验证后再决定。
- 若门限降噪效果不够：升级 **MCRA 谱减** 或引入 **RNNoise WASM**（worklet 内加载，CPU 开销可接受）。
- 设备侧若确认支持 G.722/其他 PT 可重新协商（海康该型号大概率不支持，优先级低）。

---

*方案状态：待实施（2026-10-17 计划）*
