# ZLMediaKit（ZLM）模式实现：架构、模式与代码细节

> 本文系统描述 `sip.media.mode=zlm` 下的实现：模式划分、分层架构、核心机制与代码级细节。问题排查视角的记录见《ZLM固定30002多路GB28181_WEBRTC问题排查与修复.md》与《ZLM模式回放WebRTC与录像下载问题修复记录.md》。

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
