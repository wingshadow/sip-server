# ZLMediaKit 固定 30002 多路 GB/T 28181 WebRTC 播放问题排查与修复

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

# 5. 第二个关键问题：固定 30002 不能继续按普通 `openRtpServer` 使用

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

# 6. 最终修复方案

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

# 7. 最终 Session 保存方式

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

# 8. WebRTC URL 的修复

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

# 9. 停播逻辑也必须调整

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

# 10. 什么时候关闭 30002

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

# 11. `findStreamByRtpPort()` 的兼容修复

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

# 12. 为什么之前多端口模式马上可以播放

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

# 13. WebRTC ICE 不是根因

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

# 14. `sessionJson` 每 5 秒出现一次不是重复点播

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

# 15. 最终正确链路

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

# 16. 最终字段关系

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

# 17. 本次问题的核心结论

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

# 18. 后续修改时的注意事项

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
