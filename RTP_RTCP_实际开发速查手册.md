# RTP / RTCP 实际开发速查与协议解析手册

> 面向：Java 后端、GB28181、RTSP、视频网关、RTP 接收/转发、H.264/H.265 解码开发  
> 目标：能够根据协议格式直接编写 RTP/RTCP 收包、解析、重组、统计和反馈代码。  
> 标准重点：RTP/RTCP RFC 3550、RTP/AVP RFC 3551、H.264 over RTP RFC 6184、HEVC over RTP RFC 7798。

---

## 1. RTP / RTCP 是什么

RTP（Real-time Transport Protocol）用于传输实时媒体数据，例如：

- H.264
- H.265/HEVC
- G.711 PCMA/PCMU
- AAC
- Opus

RTCP（RTP Control Protocol）用于 RTP 会话的控制和统计，例如：

- 丢包统计
- 接收质量
- 网络抖动
- RTP 时间与 NTP 时间映射
- 发送/接收端报告
- 视频关键帧请求
- NACK 重传请求
- 会话参与者信息

最简单的理解：

```text
RTP  = 运货车，负责运视频/音频
RTCP = 运输监控，负责汇报丢包、延迟、抖动、同步、反馈
```

典型结构：

```text
                RTP / RTCP
                    |
          +---------+---------+
          |                   |
         RTP                 RTCP
          |                   |
       媒体数据             控制数据
          |                   |
     H264/H265/AAC       SR/RR/SDES/BYE
```

---

# 2. RTP 在网络协议栈中的位置

常见：

```text
Application
    |
   RTP
    |
   UDP
    |
   IP
    |
 Ethernet
```

因此 RTP 本身通常不负责：

- 建立 TCP 连接
- 用户认证
- 媒体会话协商
- NAT 穿透
- 视频解码

这些工作通常由其他协议完成。

例如：

```text
SIP / RTSP / SDP
       |
       | 协商媒体参数
       v
     RTP/UDP
       |
       v
    H264/H265
```

在 GB28181 中尤其常见：

```text
SIP
 |
 | 注册、INVITE、BYE、MESSAGE
 v
SDP
 |
 | 协商 IP、端口、编码
 v
RTP
 |
 v
PS
 |
 +-- PS Header
 +-- PSM
 +-- PES
       |
       +-- H264
       +-- H265
       +-- G711
```

---

# 3. RTP 基本报文格式

RTP Header 最小长度为 12 字节。

```text
0                   1                   2                   3
0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|V=2|P|X|  CC   |M|     PT      |       Sequence Number       |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                           Timestamp                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                              SSRC                             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                           CSRC list ...                       |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                           Extension ...                       |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                           Payload ...                         |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

最常见的普通 RTP：

```text
12 字节 RTP Header
+
Payload
```

---

# 4. RTP Header 字段详解

## 4.1 Version（V）

长度：

```text
2 bit
```

目前使用：

```text
V = 2
```

因此第一个字节最高两位应该是：

```text
10
```

Java：

```java
int b0 = packet[0] & 0xFF;
int version = (b0 >>> 6) & 0x03;
```

检查：

```java
if (version != 2) {
    // 不是标准 RTP Version 2
}
```

---

## 4.2 Padding（P）

长度：

```text
1 bit
```

表示 RTP Payload 末尾是否存在 Padding。

通常：

```text
P = 0
```

如果：

```text
P = 1
```

则 RTP 数据最后一个字节表示 Padding 长度。

例如：

```text
... Payload ... 00 00 04
```

最后：

```text
04
```

表示最后 4 字节属于 Padding。

开发时：

```java
boolean padding = (b0 & 0x20) != 0;
```

如果存在 Padding：

```java
int paddingLength = packet[packet.length - 1] & 0xFF;
```

然后从 Payload 长度中扣除。

---

## 4.3 Extension（X）

长度：

```text
1 bit
```

表示 RTP Header 后是否存在扩展 Header。

通常：

```text
X = 0
```

如果：

```text
X = 1
```

结构：

```text
RTP Basic Header
       |
       v
Extension Header
       |
       v
Payload
```

扩展 Header 本身包含：

```text
Profile
Length
Extension Data
```

其中 Length 单位通常是 32-bit word。

因此：

```text
extensionBytes = length * 4
```

开发时不能简单写死 Payload 从 12 字节开始。

---

## 4.4 CSRC Count（CC）

长度：

```text
4 bit
```

表示后面存在多少个 CSRC 标识。

常见：

```text
CC = 0
```

如果：

```text
CC = 2
```

则 RTP Header 后面还有：

```text
2 * 4 = 8 字节
```

CSRC。

因此：

```text
RTP Header 实际长度
=
12
+
CC * 4
+
Extension Header 长度
```

---

# 5. Marker（M）

长度：

```text
1 bit
```

Marker 的含义由具体 RTP Payload Format 定义。

对于 H.264 RTP，通常：

```text
M = 1
```

表示当前 RTP 包携带的视频帧结束。

例如：

```text
Frame A

Seq 100  M=0
Seq 101  M=0
Seq 102  M=1

Frame B

Seq 103  M=0
Seq 104  M=1
```

可以利用 Marker 辅助判断：

```text
一帧 RTP 视频数据结束
```

但是不要认为：

```text
M=1 永远等于视频帧结束
```

必须结合具体 Payload Format。

Java：

```java
boolean marker = (b1 & 0x80) != 0;
```

---

# 6. Payload Type（PT）

长度：

```text
7 bit
```

Java：

```java
int payloadType = b1 & 0x7F;
```

常见静态 Payload Type：

| PT | 编码 |
|---:|---|
| 0 | PCMU |
| 3 | GSM |
| 4 | G723 |
| 5 | DVI4 |
| 6 | DVI4 |
| 7 | LPC |
| 8 | PCMA |
| 9 | G722 |
| 10 | L16 |
| 11 | L16 |
| 12 | QCELP |
| 13 | CN |
| 14 | MPA |
| 15 | G728 |
| 16 | DVI4 |
| 17 | DVI4 |
| 18 | G729 |
| 96-127 | 动态 Payload Type |

视频经常看到：

```text
PT = 96
```

但：

> PT=96 不能直接断定是 H.264。

必须查看 SDP，例如：

```text
m=video 10000 RTP/AVP 96
a=rtpmap:96 H264/90000
```

此时：

```text
96 -> H264
clock rate -> 90000
```

---

# 7. Sequence Number

长度：

```text
16 bit
```

范围：

```text
0 ~ 65535
```

每发送一个 RTP 包通常递增：

```text
100
101
102
103
104
```

主要用途：

1. 判断丢包
2. RTP 重排序
3. 判断 RTP 包连续性
4. 生成 RTCP Receiver Report

例如收到：

```text
100
101
103
104
```

说明：

```text
102 可能丢失
```

Java：

```java
int sequence =
        ((packet[2] & 0xFF) << 8)
        | (packet[3] & 0xFF);
```

注意：

```text
65535 -> 0
```

存在回绕。

---

# 8. Timestamp

长度：

```text
32 bit
```

RTP Timestamp 不一定等于系统毫秒时间。

它使用 Payload Format 定义的 Clock Rate。

视频 H.264/H.265 常见：

```text
clock rate = 90000
```

例如：

```text
Timestamp 90000
Timestamp 93000
Timestamp 96000
```

差：

```text
3000
```

时间：

```text
3000 / 90000
= 0.033333 秒
```

约：

```text
33.3 ms
```

也就是约：

```text
30 FPS
```

Java：

```java
long timestamp =
        ((packet[4] & 0xFFL) << 24)
        | ((packet[5] & 0xFFL) << 16)
        | ((packet[6] & 0xFFL) << 8)
        | (packet[7] & 0xFFL);
```

---

# 9. SSRC

SSRC：

```text
Synchronization Source
```

长度：

```text
32 bit
```

作用：

```text
标识一个 RTP 同步源
```

例如：

```text
SSRC = 0x12345678
```

同一个 RTP Session 中可能存在：

```text
视频 SSRC
音频 SSRC
```

因此服务端最好按：

```text
SSRC
```

维护媒体状态。

例如：

```java
Map<Long, RtpSession> sessions;
```

一个 RtpSession 可以保存：

```java
class RtpSession {

    long ssrc;

    int lastSequence;

    long lastTimestamp;

    long receivedPackets;

    long lostPackets;

    long lastReceiveTime;

}
```

---

# 10. CSRC

CSRC：

```text
Contributing Source
```

每个：

```text
32 bit
```

最多：

```text
15 个
```

普通摄像机 RTP 基本很少使用。

如果：

```text
CC = 0
```

直接跳过。

---

# 11. RTP Payload

RTP Header 后面的内容就是 Payload。

例如：

```text
RTP
 |
 +-- Header
 |
 +-- Payload
       |
       +-- H264
       +-- H265
       +-- AAC
       +-- G711
       +-- PS
```

因此：

> RTP 不等于 H.264。

RTP 是传输封装，Payload 才是媒体格式。

---

# 12. RTP Header 长度计算

基础 Header：

```text
12 Bytes
```

CSRC：

```text
CC * 4
```

如果有 Extension：

```text
Extension Header + Extension Data
```

因此：

```text
payloadOffset =
    12
    + CC * 4
    + extensionLength;
```

不能永远：

```java
payloadOffset = 12;
```

这是 RTP 解析中非常常见的 Bug。

---

# 13. RTP Java 基础解析器

推荐先定义：

```java
public class RtpPacket {

    private int version;

    private boolean padding;

    private boolean extension;

    private int csrcCount;

    private boolean marker;

    private int payloadType;

    private int sequenceNumber;

    private long timestamp;

    private long ssrc;

    private int headerLength;

    private byte[] payload;

}
```

基础解析：

```java
public RtpPacket parse(byte[] data, int length) {

    if (length < 12) {
        throw new IllegalArgumentException("RTP packet too short");
    }

    int b0 = data[0] & 0xFF;
    int b1 = data[1] & 0xFF;

    int version = (b0 >>> 6) & 0x03;

    if (version != 2) {
        throw new IllegalArgumentException("Invalid RTP version");
    }

    boolean padding = (b0 & 0x20) != 0;
    boolean extension = (b0 & 0x10) != 0;

    int csrcCount = b0 & 0x0F;

    boolean marker = (b1 & 0x80) != 0;
    int payloadType = b1 & 0x7F;

    int sequenceNumber =
            ((data[2] & 0xFF) << 8)
            | (data[3] & 0xFF);

    long timestamp =
            ((data[4] & 0xFFL) << 24)
            | ((data[5] & 0xFFL) << 16)
            | ((data[6] & 0xFFL) << 8)
            | (data[7] & 0xFFL);

    long ssrc =
            ((data[8] & 0xFFL) << 24)
            | ((data[9] & 0xFFL) << 16)
            | ((data[10] & 0xFFL) << 8)
            | (data[11] & 0xFFL);

    int offset = 12 + csrcCount * 4;

    if (offset > length) {
        throw new IllegalArgumentException("Invalid CSRC");
    }

    if (extension) {

        if (offset + 4 > length) {
            throw new IllegalArgumentException("Invalid RTP extension");
        }

        int extensionLength =
                ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);

        offset += 4 + extensionLength * 4;

        if (offset > length) {
            throw new IllegalArgumentException("Invalid RTP extension length");
        }
    }

    int payloadLength = length - offset;

    if (padding && payloadLength > 0) {
        int paddingLength = data[length - 1] & 0xFF;

        if (paddingLength > payloadLength) {
            throw new IllegalArgumentException("Invalid RTP padding");
        }

        payloadLength -= paddingLength;
    }

    byte[] payload = new byte[payloadLength];

    System.arraycopy(
            data,
            offset,
            payload,
            0,
            payloadLength
    );

    RtpPacket packet = new RtpPacket();

    packet.setVersion(version);
    packet.setPadding(padding);
    packet.setExtension(extension);
    packet.setCsrcCount(csrcCount);
    packet.setMarker(marker);
    packet.setPayloadType(payloadType);
    packet.setSequenceNumber(sequenceNumber);
    packet.setTimestamp(timestamp);
    packet.setSsrc(ssrc);
    packet.setHeaderLength(offset);
    packet.setPayload(payload);

    return packet;
}
```

---

# 14. RTP 接收服务推荐结构

Java 服务端：

```text
UDP Socket
    |
    v
RtpReceiver
    |
    v
RtpPacketParser
    |
    v
RtpSessionManager
    |
    v
PayloadParser
    |
    +------ H264
    |
    +------ H265
    |
    +------ PS
    |
    +------ G711
```

推荐拆成：

```text
RtpReceiver
RtpPacketParser
RtpSession
RtpSessionManager
RtpStatistics
PayloadParser
```

不要把：

```text
UDP接收
RTP解析
H264重组
PS解析
FLV封装
```

全部写进一个类。

---

# 15. RTP Sequence 丢包判断

基本逻辑：

```java
int expected = (lastSequence + 1) & 0xFFFF;

if (sequence != expected) {
    // 可能存在丢包或乱序
}
```

但是实际开发不能简单：

```java
sequence - lastSequence
```

因为存在：

```text
65535 -> 0
```

同时还要考虑：

- UDP 乱序
- 重复包
- 网络延迟
- Sequence 回绕

因此生产环境建议使用：

```text
Sequence Window
+
Jitter Buffer
+
Extended Sequence Number
```

---

# 16. RTP 重排序

UDP 不保证顺序。

实际可能：

```text
100
102
101
103
```

不能立即认为：

```text
101 丢失
```

应该等待一个很短的：

```text
Jitter Buffer
```

例如：

```text
收到 100
收到 102
等待
收到 101
重新排序
```

最终：

```text
100
101
102
```

---

# 17. RTP Timestamp 与视频帧

通常同一视频帧的多个 RTP 包：

```text
Timestamp 相同
```

例如：

```text
Seq=100 TS=90000
Seq=101 TS=90000
Seq=102 TS=90000 M=1
```

下一帧：

```text
Seq=103 TS=93000
Seq=104 TS=93000 M=1
```

所以判断视频帧时可以综合：

```text
Timestamp
Marker
Sequence
NALU/FU-A
```

不要只依赖一个字段。

---

# 18. H.264 RTP

H.264 RTP 主要参考：

```text
RFC 6184
```

常见 RTP Payload：

```text
单 NALU
STAP-A
FU-A
```

实际监控摄像头最常见的是：

```text
单 NALU
FU-A
```

---

# 19. H.264 NALU Header

H.264 NAL Header：

```text
+---------------+
|F|NRI|  Type   |
+---------------+
 1  2     5 bit
```

字段：

```text
F
禁止位

NRI
NAL Reference IDC

Type
NAL Unit Type
```

Java：

```java
int nalHeader = payload[0] & 0xFF;

int forbiddenZeroBit =
        (nalHeader >>> 7) & 0x01;

int nalRefIdc =
        (nalHeader >>> 5) & 0x03;

int nalType =
        nalHeader & 0x1F;
```

---

# 20. H.264 NAL Type 速查

| Type | 含义 |
|---:|---|
| 1 | 非 IDR Slice |
| 2 | Slice 数据 A |
| 3 | Slice 数据 B |
| 4 | Slice 数据 C |
| 5 | IDR Slice |
| 6 | SEI |
| 7 | SPS |
| 8 | PPS |
| 9 | AUD |
| 10 | End of Sequence |
| 11 | End of Stream |
| 12 | Filler |
| 24 | STAP-A |
| 28 | FU-A |

重点：

```text
5  = IDR
7  = SPS
8  = PPS
28 = FU-A
```

---

# 21. H.264 单 NALU

如果：

```text
nalType >= 1
&& nalType <= 23
```

通常可以直接作为一个 NALU。

例如：

```text
RTP Payload:

67 ...
```

表示：

```text
NAL Type = 7
```

即 SPS。

再例如：

```text
65 ...
```

表示：

```text
NAL Type = 5
```

即 IDR。

---

# 22. H.264 FU-A

FU-A：

```text
NAL Type = 28
```

结构：

```text
+---------------+
| FU Indicator  |
+---------------+
| FU Header     |
+---------------+
| Fragment      |
+---------------+
```

FU Indicator：

```text
+---------------+
|F|NRI|   28    |
+---------------+
```

FU Header：

```text
+---------------+
|S|E|R|  Type   |
+---------------+
```

其中：

```text
S = Start
E = End
R = Reserved
Type = 原始 NAL Type
```

---

# 23. FU-A 重组

例如：

```text
RTP Seq=100
S=1 E=0 Type=5

RTP Seq=101
S=0 E=0 Type=5

RTP Seq=102
S=0 E=1 Type=5
```

表示一个完整：

```text
NAL Type = 5
```

重组：

```text
原始 NAL Header
+
Fragment 100
+
Fragment 101
+
Fragment 102
```

原始 NAL Header：

```java
int originalNalHeader =
        (fuIndicator & 0xE0)
        | (fuHeader & 0x1F);
```

---

# 24. FU-A Java 伪代码

```java
int fuIndicator = payload[0] & 0xFF;
int fuHeader = payload[1] & 0xFF;

boolean start = (fuHeader & 0x80) != 0;
boolean end = (fuHeader & 0x40) != 0;

int nalType = fuHeader & 0x1F;

if (start) {

    buffer.reset();

    int nalHeader =
            (fuIndicator & 0xE0)
            | nalType;

    buffer.write(nalHeader);

    buffer.write(
            payload,
            2,
            payload.length - 2
    );

} else {

    buffer.write(
            payload,
            2,
            payload.length - 2
    );
}

if (end) {

    byte[] nalu = buffer.toByteArray();

    // 一个完整 NALU
}
```

生产环境还必须检查：

```text
Sequence 是否连续
Timestamp 是否一致
SSRC 是否一致
是否超时
是否发生新的 Start
```

---

# 25. H.264 关键帧

通常：

```text
NAL Type = 5
```

即：

```text
IDR
```

但播放一个新的视频流，通常不能只拿 IDR。

通常还需要：

```text
SPS
PPS
IDR
```

典型：

```text
SPS
PPS
IDR
P
P
P
...
```

所以 FLV/播放器推流时：

```text
SPS + PPS + IDR
```

非常重要。

---

# 26. H.265 / HEVC RTP

H.265 RTP 参考：

```text
RFC 7798
```

H.265 NAL Header 为：

```text
2 Bytes
```

结构：

```text
+---------------+---------------+
|F| Type | Layer | TID           |
+---------------+---------------+
```

NAL Type：

```java
int nalType =
        (payload[0] >>> 1) & 0x3F;
```

常见：

| Type | 含义 |
|---:|---|
| 19 | IDR |
| 20 | IDR |
| 32 | VPS |
| 33 | SPS |
| 34 | PPS |
| 48 | Aggregation |
| 49 | Fragmentation Unit |

H.265 关键参数：

```text
VPS
SPS
PPS
```

因此：

```text
H264:
SPS + PPS + IDR

H265:
VPS + SPS + PPS + IDR
```

---

# 27. H.264 与 H.265 不要混解析

错误：

```java
int type = payload[0] & 0x1F;
```

这只适用于 H.264 NAL Header。

H.265 应：

```java
int type = (payload[0] >>> 1) & 0x3F;
```

开发中应该先根据：

```text
SDP
配置
Payload Type
GB28181 参数
```

确定编码格式，再调用：

```text
H264Parser
H265Parser
```

---

# 28. RTCP

RTCP 主要用于：

```text
质量统计
时间同步
参与者信息
反馈控制
```

RTCP 通常与 RTP 配套使用。

```text
RTP
 └── 媒体

RTCP
 └── 控制
```

---

# 29. RTCP Header

RTCP Header 固定部分：

```text
4 Bytes
```

格式：

```text
0                   1                   2                   3
0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|V=2|P|    RC   |      PT       |             Length            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

字段：

```text
V
Version

P
Padding

RC
Report Count

PT
Packet Type

Length
长度
```

---

# 30. RTCP Packet Type

常见：

| PT | 名称 | 用途 |
|---:|---|---|
| 200 | SR | Sender Report |
| 201 | RR | Receiver Report |
| 202 | SDES | Source Description |
| 203 | BYE | 离开会话 |
| 204 | APP | 应用扩展 |
| 205 | RTPFB | RTP Feedback |
| 206 | PSFB | Payload-specific Feedback |

实际视频开发最值得关注：

```text
200
201
205
206
```

---

# 31. RTCP Length

RTCP Length 字段不是简单的字节长度。

单位：

```text
32-bit words
```

并且：

```text
总长度 = (Length + 1) * 4
```

例如：

```text
Length = 6
```

则：

```text
Packet Length
=
(6 + 1) * 4
=
28 Bytes
```

这是 RTCP 解析最容易出错的地方之一。

---

# 32. RTCP SR

PT：

```text
200
```

Sender Report。

用于：

```text
发送端统计
时间同步
```

核心结构：

```text
RTCP Header
    |
    +-- SSRC
    |
    +-- NTP Timestamp
    |
    +-- RTP Timestamp
    |
    +-- Sender Packet Count
    |
    +-- Sender Octet Count
    |
    +-- Report Blocks
```

---

# 33. RTCP SR 关键字段

## SSRC

发送源。

```text
SSRC
```

## NTP Timestamp

绝对时间。

```text
NTP
```

## RTP Timestamp

媒体时间。

```text
RTP Timestamp
```

两者建立：

```text
NTP Time
   ↕
RTP Timestamp
```

用于音视频同步、媒体时钟转换。

---

## Sender Packet Count

发送 RTP 包数量。

---

## Sender Octet Count

发送 RTP Payload 字节数量。

---

# 34. RTCP RR

PT：

```text
201
```

Receiver Report。

接收端告诉发送端：

```text
我收到了多少
我丢了多少
网络抖动多少
```

常见字段：

```text
SSRC of source
Fraction Lost
Cumulative Number of Packets Lost
Extended Highest Sequence Number Received
Interarrival Jitter
LSR
DLSR
```

---

# 35. Fraction Lost

8 bit。

表示最近统计区间的丢包比例。

通常：

```text
fractionLost / 256
```

例如：

```text
fractionLost = 64
```

大约：

```text
64 / 256
= 25%
```

---

# 36. Cumulative Number of Packets Lost

累计丢包数量。

例如：

```text
100
```

表示累计丢失约：

```text
100 RTP packets
```

实际实现要注意该字段为有符号 24-bit 表示，不应简单按照普通 32-bit 无符号整数解析。

---

# 37. Extended Highest Sequence Number

表示扩展后的最高 RTP Sequence Number。

因为 RTP Sequence 只有：

```text
16 bit
```

但长期运行需要：

```text
Extended Sequence
```

例如：

```text
65534
65535
0
1
2
```

扩展后可以理解为：

```text
65534
65535
65536
65537
65538
```

---

# 38. Interarrival Jitter

表示 RTP 包到达时间的统计抖动。

单位与 RTP Timestamp Clock Rate 一致。

例如：

```text
clock rate = 90000
```

Jitter：

```text
900
```

约：

```text
900 / 90000
= 10ms
```

Jitter 越大：

```text
网络到达越不稳定
```

---

# 39. RTCP SDES

PT：

```text
202
```

Source Description。

常见信息：

```text
CNAME
NAME
EMAIL
PHONE
LOC
TOOL
NOTE
```

其中最重要的是：

```text
CNAME
```

CNAME 用于标识同步源。

---

# 40. RTCP BYE

PT：

```text
203
```

表示：

```text
参与者离开 RTP Session
```

服务端收到 BYE 后可以：

```text
释放 Session
关闭 RTP 接收
清理缓存
停止 FFmpeg
释放线程
```

---

# 41. RTCP APP

PT：

```text
204
```

Application-defined。

应用可以定义自己的 RTCP 数据。

解析时不要把：

```text
204
```

当成未知错误直接丢弃。

如果业务需要，可以单独处理。

---

# 42. RTPFB

PT：

```text
205
```

RTP Feedback。

常见：

```text
NACK
```

用于：

```text
请求丢失 RTP 包重传
```

典型：

```text
发送端
   |
   | RTP 100
   | RTP 101
   | RTP 102
   |
   X 102 丢失
   |
接收端
   |
   | RTCP NACK
   | Seq=102
   v
发送端
   |
   | RTP 102 重传
   v
接收端
```

---

# 43. PSFB

PT：

```text
206
```

Payload-specific Feedback。

视频中常见：

```text
PLI
FIR
```

---

# 44. PLI

PLI：

```text
Picture Loss Indication
```

意思：

```text
我的视频解码状态出现问题
请给我一个关键帧
```

典型：

```text
播放器
   |
   | RTCP PLI
   v
媒体服务器
   |
   | 请求关键帧
   v
摄像机/编码器
   |
   | IDR
   v
播放器恢复
```

直播系统中非常重要。

---

# 45. FIR

FIR：

```text
Full Intra Request
```

作用：

```text
请求发送完整的帧内编码图像
```

和 PLI 都与关键帧恢复有关，但语义和适用机制不同。

实际项目不要简单把：

```text
PLI = FIR
```

混为一谈。

---

# 46. RTP / RTCP 端口

传统 RTP/RTCP 约定：

```text
RTP  = 偶数端口
RTCP = RTP + 1
```

例如：

```text
RTP
10000

RTCP
10001
```

但现代协议栈也可能：

```text
RTP/RTCP Multiplex
```

即：

```text
RTP + RTCP
      |
      v
同一个 UDP 端口
```

因此开发时不能绝对认为：

```text
偶数 = RTP
奇数 = RTCP
```

最好根据：

```text
会话协商
SDP
Payload Type
协议上下文
```

综合判断。

---

# 47. 如何判断一个 UDP 包是不是 RTP

基本检查：

```text
长度 >= 12
Version == 2
```

然后进一步检查：

```text
PT
Sequence
Timestamp
SSRC
```

但是：

> 不能仅靠 Version=2 就百分百断定是 RTP。

因为 RTCP 同样使用：

```text
Version = 2
```

因此 RTP/RTCP 判断必须结合：

```text
端口
会话上下文
PT
协议协商
```

---

# 48. RTP / RTCP 判断思路

如果 RTP 和 RTCP 使用不同端口：

```text
RTP Port
    |
    +--> RTP Parser

RTCP Port
    |
    +--> RTCP Parser
```

如果复用端口：

```text
UDP
 |
 v
Packet
 |
 +-- 判断 RTP/RTCP
       |
       +-- RTP
       |
       +-- RTCP
```

不要直接：

```java
if (packet[1] >= 200) {
    // RTCP
}
```

因为 RTP PT 和 RTCP PT 的语义完全不同。

---

# 49. RTCP Compound Packet

一个 UDP 包中可以包含多个 RTCP Packet：

```text
UDP
 |
 +-- RTCP SR
 |
 +-- RTCP SDES
 |
 +-- RTCP BYE
```

因此 RTCP 接收不能：

```java
parse(packet);
```

然后认为整个 UDP Datagram 只有一个 RTCP。

应该：

```text
UDP Datagram
    |
    v
RTCP Packet 1
    |
    v
RTCP Packet 2
    |
    v
RTCP Packet 3
```

不断按照 Length 向后解析。

---

# 50. RTCP Java 基础解析

```java
public class RtcpPacket {

    private int version;

    private boolean padding;

    private int reportCount;

    private int packetType;

    private int length;

    private byte[] payload;
}
```

基础：

```java
int b0 = data[offset] & 0xFF;

int version = (b0 >>> 6) & 0x03;

boolean padding =
        (b0 & 0x20) != 0;

int reportCount =
        b0 & 0x1F;

int packetType =
        data[offset + 1] & 0xFF;

int length =
        ((data[offset + 2] & 0xFF) << 8)
        | (data[offset + 3] & 0xFF);

int packetLength =
        (length + 1) * 4;
```

然后：

```java
offset += packetLength;
```

直到：

```java
offset >= datagramLength
```

---

# 51. RTP 解析时必须注意的坑

## 坑 1：Byte 是有符号的

Java：

```java
byte
```

范围：

```text
-128 ~ 127
```

网络协议通常应该使用：

```java
data[i] & 0xFF
```

例如：

```java
int value = data[i] & 0xFF;
```

---

## 坑 2：Timestamp 必须用 long

错误：

```java
int timestamp;
```

虽然 RTP Timestamp 是 32 bit，但 Java int 是有符号。

推荐：

```java
long timestamp;
```

---

## 坑 3：Sequence 会回绕

```text
65535
    ↓
0
```

不能简单：

```java
current - previous
```

---

## 坑 4：UDP 不保证顺序

必须考虑：

```text
乱序
重复
丢包
```

---

## 坑 5：一个 NALU 可能跨多个 RTP

特别是：

```text
FU-A
```

不能：

```text
一个 RTP = 一个 H264 NALU
```

---

## 坑 6：一个视频帧可能包含多个 NALU

例如：

```text
SPS
PPS
IDR
```

所以：

```text
NALU != Frame
```

---

# 52. RTP 到 H264 的完整处理流程

```text
UDP
 |
 v
RTP Header Parse
 |
 +-- SSRC
 +-- Sequence
 +-- Timestamp
 +-- Marker
 +-- PT
 |
 v
H264 Payload
 |
 +-- NAL Type
 |
 +-- 1~23
 |     |
 |     +--> Single NALU
 |
 +-- 28
       |
       +--> FU-A
              |
              +--> Start
              +--> Middle
              +--> End
                    |
                    v
                 完整 NALU
```

---

# 53. RTP 到 PS 的 GB28181 流程

GB28181 视频常见：

```text
UDP
 |
 v
RTP
 |
 v
PS Packet
 |
 +-- PS Header
 |
 +-- System Header
 |
 +-- PSM
 |
 +-- PES
       |
       +-- H264
       +-- H265
       +-- G711
```

因此 GB28181 开发中：

```text
RTP Parser
```

通常并不直接进入：

```text
H264 Parser
```

而是：

```text
RTP
 ↓
PS Parser
 ↓
PES Parser
 ↓
H264/H265
```

---

# 54. GB28181 中 RTP Header 重点

如果当前项目是 GB28181：

最先关注：

```text
Sequence Number
Timestamp
SSRC
Payload
```

因为常见：

```text
RTP Payload
    |
    v
PS
```

因此 RTP 层主要负责：

```text
收包
校验
排序
丢包统计
提取 Payload
```

PS 层负责：

```text
PS 重组
PES 重组
```

编码层负责：

```text
H264/H265 NALU
```

---

# 55. 推荐 Java 项目结构

推荐：

```text
rtp/
├── RtpPacket.java
├── RtpHeader.java
├── RtpPacketParser.java
├── RtpSession.java
├── RtpSessionManager.java
├── RtpReceiver.java
├── RtpStatistics.java
│
├── rtcp/
│   ├── RtcpPacket.java
│   ├── RtcpPacketParser.java
│   ├── RtcpSenderReport.java
│   ├── RtcpReceiverReport.java
│   ├── RtcpSdes.java
│   ├── RtcpBye.java
│   ├── RtcpFeedback.java
│   └── RtcpParser.java
│
├── h264/
│   ├── H264PayloadParser.java
│   ├── H264FuAReassembler.java
│   └── H264NalUnit.java
│
├── h265/
│   ├── H265PayloadParser.java
│   └── H265FuReassembler.java
│
└── ps/
    ├── PsParser.java
    ├── PsHeader.java
    ├── Psm.java
    └── PesPacket.java
```

---

# 56. RTP Session 建议字段

```java
public class RtpSession {

    /**
     * RTP SSRC
     */
    private long ssrc;

    /**
     * Payload Type
     */
    private int payloadType;

    /**
     * 最近接收序列号
     */
    private int lastSequence;

    /**
     * 最近时间戳
     */
    private long lastTimestamp;

    /**
     * RTP 包数量
     */
    private long packetsReceived;

    /**
     * 丢包数量
     */
    private long packetsLost;

    /**
     * 最近接收时间
     */
    private long lastReceiveTime;

    /**
     * RTP Clock Rate
     */
    private int clockRate;

    /**
     * 编码格式
     */
    private String codec;

}
```

---

# 57. RTP 统计指标

生产系统建议统计：

```text
packetsReceived
packetsLost
bytesReceived
lastSequence
highestSequence
duplicatePackets
outOfOrderPackets
jitter
lastTimestamp
bitrate
fps
```

视频系统还建议：

```text
keyFrames
frames
decodeErrors
incompleteFrames
incompleteNalUnits
```

---

# 58. Bitrate 计算

例如：

```text
1 秒收到 500 KB
```

码率：

```text
500 * 8 KB/s
```

换算：

```text
4000 Kbit/s
```

实际建议采用滑动窗口：

```text
1s
5s
10s
```

避免瞬时统计波动太大。

---

# 59. FPS 计算

视频 Timestamp：

```text
TS1 = 90000
TS2 = 93000
```

差：

```text
3000
```

如果：

```text
clockRate = 90000
```

那么：

```text
frameInterval
=
3000 / 90000
=
0.0333s
```

FPS：

```text
1 / 0.0333
≈ 30
```

实际生产环境推荐根据：

```text
Marker
+
Timestamp
```

统计帧率，而不是按 RTP 包数量统计。

---

# 60. Jitter Buffer

网络可能：

```text
RTP 100
RTP 102
RTP 101
RTP 103
```

使用：

```text
Jitter Buffer
```

可以：

```text
100
101
102
103
```

再交给上层。

典型结构：

```text
UDP
 |
 v
RTP Parser
 |
 v
Jitter Buffer
 |
 v
Sequence Reorder
 |
 v
Payload Reassembly
 |
 v
Decoder / Muxer
```

---

# 61. RTP 丢包后的处理策略

不同系统策略不同。

## 实时监控

可以：

```text
丢了就丢
继续播放
```

适合：

```text
摄像头监控
```

## 支持 NACK 的系统

可以：

```text
发现丢包
   |
   v
RTCP NACK
   |
   v
发送端重传
```

## H264 FU-A 丢包

如果：

```text
FU-A
100 Start
101
102 丢失
103 End
```

通常这个 NALU 已经无法完整恢复。

可以：

```text
丢弃当前 NALU
等待下一个完整 NALU
```

如果已经严重影响解码：

```text
请求关键帧
```

---

# 62. 关键帧恢复流程

推荐：

```text
检测到解码异常
       |
       v
等待关键帧
       |
       v
PLI / FIR
       |
       v
发送端
       |
       v
SPS/PPS
       |
       v
IDR
       |
       v
恢复播放
```

对于 GB28181：

```text
PLI/FIR
```

并不一定能直接由摄像机按照 WebRTC 的方式处理。

GB28181 通常需要通过其自身的 SIP/控制机制请求关键帧，具体取决于设备能力和平台实现。

---

# 63. RTP 调试日志建议

不要每个 RTP 包都打 INFO。

建议：

```text
DEBUG:
RTP seq=100 ts=90000 ssrc=1234 pt=96 len=1400 marker=false

INFO:
RTP session created
RTP session closed
SSRC changed
大量丢包
关键帧恢复
```

统计：

```text
每 5 秒输出：

RTP packets=15000
lost=12
lossRate=0.08%
jitter=3.2ms
bitrate=4096kbps
fps=25
```

这样比：

```text
每个 RTP 包 INFO
```

实用得多。

---

# 64. Wireshark 抓包分析

开发 RTP/RTCP 时强烈建议使用 Wireshark。

重点观察：

```text
UDP
 |
 +-- RTP
 |    |
 |    +-- Sequence Number
 |    +-- Timestamp
 |    +-- Marker
 |    +-- SSRC
 |    +-- Payload Type
 |
 +-- RTCP
      |
      +-- SR
      +-- RR
      +-- SDES
      +-- BYE
```

排查问题时优先看：

```text
1. RTP 是否持续到达
2. Sequence 是否连续
3. Timestamp 是否正常
4. SSRC 是否变化
5. PT 是否正确
6. Marker 是否合理
7. Payload 是否符合编码格式
8. 是否存在大量乱序/丢包
```

---

# 65. 常见问题快速定位

## 问题：完全没有画面

检查：

```text
UDP 是否收到
↓
RTP Version 是否为 2
↓
PT 是否正确
↓
Payload 是否正确
↓
PS/H264/H265 是否解析成功
↓
是否拿到 SPS/PPS/VPS
↓
是否拿到 IDR
```

---

## 问题：有声音没画面

重点检查：

```text
视频 SSRC
视频 PT
H264/H265 Payload
SPS/PPS/VPS
IDR
```

---

## 问题：花屏

重点检查：

```text
RTP 丢包
RTP 乱序
FU-A 重组
NALU 拼接
SPS/PPS
Timestamp
```

---

## 问题：几秒后停止

检查：

```text
Sequence 回绕
Timestamp 回绕
Session 超时
UDP Socket
线程异常
FFmpeg Pipe
```

---

## 问题：刚开始黑屏，过一会儿才正常

常见原因：

```text
没有及时拿到 SPS/PPS
没有从关键帧开始
丢失了第一个 IDR
```

推荐：

```text
缓存 SPS/PPS
等待关键帧
SPS/PPS + IDR
```

---

# 66. RTP → FLV 开发重点

如果最终目标：

```text
GB28181
  ↓
RTP
  ↓
PS
  ↓
H264
  ↓
FLV
```

建议内部数据层次保持清晰：

```text
RTP Packet
     ↓
PS Packet
     ↓
PES Packet
     ↓
H264 NALU
     ↓
H264 Frame
     ↓
FLV Video Tag
```

不要：

```text
RTP Packet
     ↓
直接拼 FLV
```

这样后期很难维护。

---

# 67. RTP 数据对象建议

```text
RtpPacket
    |
    +-- header
    |    |
    |    +-- sequence
    |    +-- timestamp
    |    +-- ssrc
    |    +-- payloadType
    |    +-- marker
    |
    +-- payload
```

然后：

```text
RtpPacket
   ↓
PayloadParser
```

根据：

```text
codec
payloadType
```

决定：

```text
PsParser
H264Parser
H265Parser
G711Parser
```

---

# 68. RTP 与 RTCP 开发优先级

如果项目只是：

```text
GB28181 摄像头接入
```

第一阶段优先：

```text
★★★★★ RTP Header
★★★★★ Sequence
★★★★★ Timestamp
★★★★★ SSRC
★★★★★ Payload
★★★★★ PS
★★★★★ H264/H265
★★★★★ FU-A
```

第二阶段：

```text
★★★★☆ RTP 丢包
★★★★☆ RTP 乱序
★★★★☆ Jitter
★★★★☆ RTCP RR
```

第三阶段：

```text
★★★☆☆ RTCP SR
★★★☆☆ RTCP NACK
★★★☆☆ PLI/FIR
★★★☆☆ NTP/RTP 同步
```

---

# 69. 最重要的字段速查

## RTP

```text
Byte 0

V       = bits 7~6
P       = bit 5
X       = bit 4
CC      = bits 3~0

Byte 1

M       = bit 7
PT      = bits 6~0

Byte 2~3

Sequence Number

Byte 4~7

Timestamp

Byte 8~11

SSRC
```

---

## RTCP

```text
Byte 0

V       = bits 7~6
P       = bit 5
RC      = bits 4~0

Byte 1

PT

Byte 2~3

Length
```

RTCP：

```text
Packet Bytes
=
(Length + 1) * 4
```

---

# 70. RTP 常用检查代码

```java
int b0 = data[0] & 0xFF;
int b1 = data[1] & 0xFF;

int version = (b0 >>> 6) & 0x03;
boolean padding = (b0 & 0x20) != 0;
boolean extension = (b0 & 0x10) != 0;
int csrcCount = b0 & 0x0F;

boolean marker = (b1 & 0x80) != 0;
int payloadType = b1 & 0x7F;

int sequence =
        ((data[2] & 0xFF) << 8)
        | (data[3] & 0xFF);

long timestamp =
        ((data[4] & 0xFFL) << 24)
        | ((data[5] & 0xFFL) << 16)
        | ((data[6] & 0xFFL) << 8)
        | (data[7] & 0xFFL);

long ssrc =
        ((data[8] & 0xFFL) << 24)
        | ((data[9] & 0xFFL) << 16)
        | ((data[10] & 0xFFL) << 8)
        | (data[11] & 0xFFL);
```

---

# 71. 常用协议/标准对照

| 协议 | 作用 |
|---|---|
| RFC 3550 | RTP / RTCP |
| RFC 3551 | RTP Audio/Video Profile |
| RFC 6184 | H.264 over RTP |
| RFC 7798 | H.265/HEVC over RTP |
| RFC 4566 | SDP |
| GB/T 28181 | 视频监控联网 |
| SIP | 会话控制/信令 |
| SDP | 媒体参数协商 |

---

# 72. 一张图理解整个视频链路

```text
                    GB28181 / 视频系统
                             |
                    +--------+--------+
                    |                 |
                   SIP               RTP
                    |                 |
             注册/INVITE/BYE          |
                                      v
                                  RTP Header
                                      |
                 +--------------------+--------------------+
                 |                    |                    |
              Sequence            Timestamp             SSRC
                 |                    |                    |
                 +--------------------+--------------------+
                                      |
                                      v
                                   Payload
                                      |
                                      v
                                     PS
                                      |
                         +------------+------------+
                         |            |            |
                     PS Header       PSM           PES
                                                   |
                                  +----------------+----------------+
                                  |                                 |
                                H264                              H265
                                  |                                 |
                           SPS/PPS/IDR                      VPS/SPS/PPS/IDR
                                  |                                 |
                                  +----------------+----------------+
                                                   |
                                                   v
                                                 FLV
                                                   |
                                                   v
                                                播放器


                         RTCP
                           |
              +------------+------------+
              |            |            |
              SR           RR          Feedback
              |            |            |
            同步         丢包/Jitter     NACK/PLI/FIR
```

---

# 73. 实际开发建议

如果从零实现一个 RTP Server，建议按以下顺序：

```text
第 1 步
UDP 接收

第 2 步
RTP Header 解析

第 3 步
打印：
SSRC / Seq / Timestamp / PT / Marker

第 4 步
Sequence 连续性统计

第 5 步
Timestamp/FPS 统计

第 6 步
RTP Payload 提取

第 7 步
PS 重组（GB28181）

第 8 步
PES 重组

第 9 步
H264/H265 NALU 解析

第 10 步
FU-A 分片重组

第 11 步
SPS/PPS/VPS 缓存

第 12 步
关键帧检测

第 13 步
FLV/HLS/RTMP/WebRTC 封装

第 14 步
RTP 丢包/乱序/Jitter 优化

第 15 步
RTCP
```

不要一开始就把：

```text
RTP
RTCP
PS
PES
H264
H265
FLV
FFmpeg
```

全部混在一起实现。

---

# 74. 最终记忆版

开发 RTP 最重要的 6 个东西：

```text
Sequence
Timestamp
SSRC
PT
Marker
Payload
```

开发 H.264 RTP 最重要：

```text
NAL Type
SPS
PPS
IDR
FU-A
```

开发 H.265 RTP 最重要：

```text
NAL Type
VPS
SPS
PPS
IDR
FU
```

开发 RTCP 最重要：

```text
SR
RR
SDES
BYE
NACK
PLI
FIR
```

开发 GB28181 最重要：

```text
SIP
 ↓
SDP
 ↓
RTP
 ↓
PS
 ↓
PES
 ↓
H264/H265
 ↓
FLV/其他媒体封装
```

---

# 75. 一句话速查

```text
RTP：
“这个包是什么？属于谁？第几个？什么时间？里面是什么媒体？”

Sequence：
“第几个包？”

Timestamp：
“媒体时间是多少？”

SSRC：
“哪个流？”

PT：
“Payload 是什么编码？”

Marker：
“当前 RTP 帧/媒体单元是否结束？”

Payload：
“真正的媒体数据。”

RTCP：
“这个 RTP 会话现在网络怎么样？”

SR：
“我是发送端，这是我的发送和时间信息。”

RR：
“我是接收端，这是我的丢包和抖动信息。”

NACK：
“这个 RTP 包丢了，请重传。”

PLI：
“我需要关键帧。”

FIR：
“请发送完整帧内图像。”

H264 FU-A：
“一个太大的 NALU 被拆成多个 RTP 包了。”

GB28181：
“SIP 管信令，RTP 管媒体，PS 是媒体承载格式。”
```
