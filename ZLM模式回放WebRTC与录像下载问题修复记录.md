# ZLM 模式回放 WebRTC 与录像下载问题修复记录

> 本文记录 ZLMediaKit（ZLM）媒体模式下两块能力的改造过程：**回放页 WebRTC 化**与**录像下载（MP4 落盘）**，包含每一步的问题表现、根因、修复方案与实测结论。直播侧改造见《ZLM固定30002多路GB28181_WEBRTC问题排查与修复.md》，本文与其衔接。

---

## 1. 问题背景

固定 UDP `30002` 多路复用改造完成后，直播（点播）WebRTC 播放已通。剩余两块未闭环：

1. **回放**：`playback.html` 走 mpegts.js 播放 HTTP-FLV，实测无法出图；
2. **下载**：`playback.html` 点"下载"后在 ZLM 模式下**只按回放处理，不产生 MP4 文件**，前端遮罩最终提示"文件未生成"。

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

**后端 `DeviceController.java`**：新增端点，并把公共逻辑抽成私有方法（仅"会话不存在"措辞按场景区分）：

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

下载请求被降级成回放：没有 ffmpeg、没有 `.part` 文件，前端遮罩轮询 30s 后报"文件未生成"。同时 `MediaService.downloadFile(deviceId, channelId)` 查的是 `MediaService.sessionsByKey`——**zlm 模式下该表恒为空**，即使文件存在也永远返回 null。

### 4.2 根因

两层：

1. 媒体消费端缺失：local 模式用"RTP PS 管道直喂 ffmpeg"，zlm 模式 RTP 由 ZLM 收走，平台进程拿不到原始 PS，必须换个取流方式；
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

`MediaService.getOrStartPlayback/getOrStartDownload` 的复用判断查 `MediaService.sessionsByKey`，zlm 模式下是空表——导致"重复点同一条录像每次都重建会话"（BYE + 重新 INVITE，是摄像机宕机的典型诱因）。现在 zlm 分支统一走 `ZlmMediaService.startPlaybackOrDownload`，复用判断在其内部完成：

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

### 4.6 进度语义：用 ffmpeg `time=` 而非"录像时长/倍速"

设备若不支持 `a=downloadspeed` 会静默降回 1x 推流，此时按"录像时长/4"估算进度会过早到顶假死。ffmpeg 的 `time=` 是**已转码的录像时长**，无论设备按几倍速推流都准确，故以它为唯一进度信号。

### 4.7 收尾路径（全部收敛，无泄漏）

| 触发 | 路径 |
|---|---|
| 设备推流自然结束 | ffmpeg EOF → finalize 改名 → `stop()`（BYE + closeStreams + STOPPED） |
| 用户中途停止 | `stopInternal` → `cleanup` → DOWNLOAD 分支等 ffmpeg 退出 → finalize |
| MediaSource 等待超时 / ffmpeg 启动失败 | `stop()` → `cleanup` → finalize（`.part` 不存在时空操作） |
| 平台停机 | `onShutdown` 遍历 cleanup + `ffmpegExecutor.shutdownNow()` |

注意 ffmpeg **没有 stdin 句柄**（输入是 HTTP-FLV 不是管道），不能像 local 模式那样关 stdin 触发 EOF；靠的是上层先 `closeStreams` 注销 MediaSource、HTTP 连接随之断开。`finalizeZlmDownload` 幂等，多条路径重复调用安全。

### 4.8 前端零改动

`playback.html` 的 `downloadRecord`、遮罩文案（"录像下载较慢…4 倍速…设备不支持时自动降速"）、`.part` 轮询、`awaitFinalDownloadFile`（播放结束时间 +90s 内拿到 `.part` 即传）全部原样复用，因为后端契约（`downloadUrl` / `downloadFileName` / `downloadSpeed` / 状态端点）与 local 完全一致。

---

## 5. 工程坑：编辑落盘与增量编译假象（本次实录）

改造后期出现过一次**假成功**，记录以防再犯：

- `replace_in_file` 对常量区的一次编辑返回成功，但内容**实际未落盘**（后续 search_content 找不到常量）；
- 此时 `mvn compile` 仍报 **BUILD SUCCESS**——增量编译认为 class 未过期，没有真正重编，掩盖了缺失。

**对策**（后续编辑一律执行）：

1. 每次批量编辑后，用 `search_content` 抽查关键定义**确实在文件里**，不轻信工具返回值；
2. 验证编译用 `mvn clean compile` 全量，不用增量；
3. `read_lints` 的"cannot be resolved"与 mvn 结果矛盾时，**以文件实际内容为准**。

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
- 若后续支持"多路同时下载"，`ffmpegExecutor` 已是缓存线程池无需改，但要注意磁盘 IO 与 CPU 峰值，必要时加并发信号量。
- 不要再把 `MediaService.sessionsByKey` 当作 zlm 模式的会话来源；新增加载/统计类功能时，先确认数据在哪个服务。
