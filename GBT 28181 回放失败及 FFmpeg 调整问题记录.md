# GB/T 28181 回放失败及 FFmpeg 调整问题记录

## 1. 问题背景

在实现 GB/T 28181-2022 摄像机录像回放功能时，SIP 回放流程已经能够正常建立，但是浏览器无法正常播放回放视频。

整体链路：

```text
浏览器
  ↓
/api/playback/start
  ↓
SIP Playback INVITE
  ↓
海康摄像机
  ↓
RTP
  ↓
MPEG-PS
  ↓
FFmpeg
  ↓
FLV
  ↓
mpegts.js
  ↓
浏览器
```

问题主要出现在：

```text
RTP → MPEG-PS → FFmpeg → FLV
```

这一阶段。

## 2. 初始现象

SIP 回放 INVITE 能够正常发送，摄像机也能够返回 RTP 数据。

RTP 数据经过解析后可以看到：

```text
00 00 01 ba
```

这是 MPEG-PS 的典型 Pack 起始码，说明摄像机发送的数据确实是 MPEG-PS。

同时 FFmpeg 能够识别输入：

```text
Input #0, mpeg, from 'pipe:0':
```

并识别出：

```text
Video: h264
Audio: pcm_alaw
```

说明：

- SIP 信令正常
- 摄像机回放正常建立
- RTP 数据能够收到
- MPEG-PS 数据能够被 FFmpeg 识别

但是 FFmpeg 输出 FLV 时出现异常。

## 3. 初始 FFmpeg 配置

最初采用 H.264 直接复制方式：

```text
-f mpeg
-i pipe:0
-map 0:v:0
-c:v copy
-f flv
pipe:1
```

核心思路是：

```text
摄像机 H.264
    ↓
直接 copy
    ↓
FLV
```

理论上可以避免重新编码，降低 CPU 占用。

## 4. FFmpeg 报错

实际运行过程中出现：

```text
Invalid NAL unit 0
```

以及：

```text
left block unavailable
error while decoding MB
concealing errors
```

同时出现：

```text
DTS ... out of order
```

以及：

```text
Timestamps are unset in a packet for stream 0
Packet is missing PTS
Error submitting a packet to the muxer: Invalid argument
```

最终 FFmpeg 退出：

```text
exit -22
```

## 5. 问题分析

从日志可以判断，问题不是单纯的 SIP 信令问题。

FFmpeg 已经成功识别 MPEG-PS，并能够识别 H.264，但是输入数据中的视频帧存在解析/时间戳方面的问题。

尤其是：

```text
Packet is missing PTS
```

对于：

```text
-c:v copy
```

模式比较敏感。

摄像机回放 RTP → MPEG-PS → FFmpeg 的过程中，部分 MPEG-PS/H.264 数据的时间戳信息不能满足 FLV 封装器的要求。

因此：

```text
H.264 直接 copy
        ↓
FLV muxer
        ↓
PTS/DTS 不满足要求
        ↓
Error submitting a packet
        ↓
FFmpeg 退出
```

## 6. 调整方案

为了先验证视频数据链路是否正常，将 FFmpeg 从：

```text
H.264 直接复制
```

调整为：

```text
H.264 解码
    ↓
重新编码
    ↓
FLV
```

调整后的 FFmpeg 参数：

```text
ffmpeg
-loglevel info
-fflags +genpts
-f mpeg
-i pipe:0
-map 0:v:0
-c:v libx264
-preset ultrafast
-tune zerolatency
-an
-flvflags no_duration_filesize
-f flv
pipe:1
```

## 7. 关键参数说明

### 7.1 `-fflags +genpts`

```text
-fflags +genpts
```

让 FFmpeg 在输入缺少 PTS 时尝试生成时间戳。

这对于摄像机回放 MPEG-PS 数据尤其重要。

------

### 7.2 `-c:v libx264`

从：

```text
-c:v copy
```

调整为：

```text
-c:v libx264
```

不再直接复制摄像机的 H.264 数据，而是：

```text
MPEG-PS
  ↓
FFmpeg 解码 H.264
  ↓
重新编码 H.264
  ↓
FLV
```

重新编码过程中 FFmpeg 可以重新建立更加规范的时间戳和视频帧。

------

### 7.3 `-preset ultrafast`

```text
-preset ultrafast
```

回放属于实时转码场景，因此优先考虑降低 CPU 编码开销和延迟。

`ultrafast` 可以减少编码计算量。

------

### 7.4 `-tune zerolatency`

```text
-tune zerolatency
```

减少编码缓冲，使视频更适合实时播放。

------

### 7.5 `-an`

```text
-an
```

暂时关闭音频。

摄像机音频为：

```text
pcm_alaw
8000 Hz
mono
64 kb/s
```

本次排查阶段先只处理视频：

```text
视频
 ↓
H.264
 ↓
FLV
```

这样可以排除音频编码/封装对问题定位的干扰。

后续可以根据实际需求重新加入音频。

## 8. 调整后的结果

调整 FFmpeg 后，原来的：

```text
Packet is missing PTS
Error submitting a packet to the muxer
exit -22
```

不再导致 FFmpeg 立即退出。

FFmpeg 开始持续输出：

```text
frame=3862
fps=23
q=18.0
time=00:02:48.12
bitrate=3950.7kbits/s
speed=0.98x
```

说明：

```text
摄像机
 ↓
RTP
 ↓
MPEG-PS
 ↓
FFmpeg
 ↓
H.264 解码
 ↓
H.264 重新编码
 ↓
FLV
```

已经可以持续运行。

## 9. 浏览器播放验证

FFmpeg 正常输出 FLV 后，浏览器端 mpegts.js 日志：

```text
[MSEController] > MediaSource onSourceOpen
[FLVDemuxer] > Parsed onMetaData
[FLVDemuxer] > Parsed AVCDecoderConfigurationRecord
[MSEController] > Received Initialization Segment
```

最终：

```text
[PLAYBACK] playing
```

说明浏览器已经成功播放。

## 10. 最终验证结果

最终完整链路：

```text
GB28181 RecordInfo
        ↓
获取录像记录
        ↓
Playback INVITE
        ↓
摄像机开始回放
        ↓
RTP
        ↓
MPEG-PS
        ↓
FFmpeg
        ↓
H.264 解码
        ↓
libx264 重新编码
        ↓
FLV
        ↓
mpegts.js
        ↓
MediaSource
        ↓
浏览器播放
```

已经验证成功。

## 11. 本次调整的核心结论

### 原方案

```text
MPEG-PS
 ↓
H.264
 ↓
-c:v copy
 ↓
FLV
```

问题：

```text
PTS/DTS 不规范
 ↓
FLV muxer 无法正常封装
 ↓
FFmpeg exit -22
```

### 调整后

```text
MPEG-PS
 ↓
FFmpeg 解码
 ↓
libx264
 ↓
重新生成规范的视频时间戳
 ↓
FLV
```

结果：

```text
FFmpeg 持续运行
 ↓
FLV 持续输出
 ↓
mpegts.js 正常解析
 ↓
浏览器正常播放
```

## 12. 当前 FFmpeg 配置

当前验证通过的配置：

```java
List<String> cmd = new ArrayList<>(Arrays.asList(
        "ffmpeg",
        "-loglevel", "info",
        "-fflags", "+genpts",
        "-f", "mpeg",
        "-i", "pipe:0",
        "-map", "0:v:0",
        "-c:v", "libx264",
        "-preset", "ultrafast",
        "-tune", "zerolatency",
        "-an",
        "-flvflags", "no_duration_filesize",
        "-f", "flv",
        "pipe:1"
));
```

## 13. 后续优化

当前方案以“稳定播放”为优先，后续可以继续优化：

### 13.1 恢复音频

目前使用：

```text
-an
```

关闭音频。

如果需要声音，可以进一步处理：

```text
pcm_alaw
    ↓
AAC
    ↓
FLV
```

例如：

```text
-c:a aac
-b:a 64k
```

------

### 13.2 评估是否可以恢复 `-c:v copy`

当前重新编码能够正常播放，但 CPU 消耗明显高于 H.264 copy。

后续可以单独测试：

```text
-c:v copy
```

配合：

```text
-fflags +genpts
```

以及其他时间戳参数，确认是否可以在保证 FLV 时间戳正常的情况下避免重新编码。

如果无法稳定解决，则继续采用 `libx264`。

------

### 13.3 增加 FFmpeg 异常处理

建议监控：

```text
FFmpeg 启动
FFmpeg 首帧
FFmpeg 输出字节数
FFmpeg 进程退出
RTP 超时
FLV 无数据超时
```

避免出现：

```text
SIP 回放已经结束
但 FFmpeg 进程仍然存在
```

或者：

```text
摄像机没有 RTP
FFmpeg 一直等待
浏览器一直加载
```

## 14. 当前状态

**回放 FFmpeg 问题：已解决。**

最终验证：

```text
✓ RecordInfo 正常
✓ Playback INVITE 正常
✓ RTP 正常
✓ MPEG-PS 正常
✓ FFmpeg 正常
✓ H.264 转码正常
✓ FLV 输出正常
✓ mpegts.js 正常
✓ 浏览器正常播放
✓ 停止回放正常
```

当前回放功能可以进入下一阶段优化，不再需要继续排查基础的 FFmpeg 播放链路问题。