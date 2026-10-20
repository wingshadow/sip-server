package com.example.sip.sip;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简 SDP 解析器：提取点播协商所需的关键字段
 * （会话地址 / 媒体端口 / 载荷 / SSRC），支持视频与音频（语音广播）。
 */
public final class SdpParser {

    private static final Pattern RTPMAP_PATTERN =
            Pattern.compile("^a=rtpmap:(\\d+)\\s+([^/\\s]+)/?(\\d*)/?(\\d*)\\s*$",
                    Pattern.CASE_INSENSITIVE);

    private SdpParser() {
    }

    /**
     * 解析 SDP 文本，提取协商所需字段：c= 会话/媒体地址、m=video / m=audio 端口与载荷、
     * a=rtpmap（识别 PCMA/8000）、a=ssrc 与国标 y= 行（SSRC）、a=setup（TCP 角色）。
     *
     * <p>c= 行解析规则（RFC 4566）：出现在 m= 行之后的 c= 属于媒体级（仅对该媒体段生效），
     * 出现在第一个 m= 之前的是会话级。语音对讲取 RTP 目标地址时，
     * <b>优先 m=audio 媒体级 c=（audioIp），缺失时回退会话级 c=（sessionIp）</b>。
     */
    public static SdpInfo parse(String sdp) {
        SdpInfo info = new SdpInfo();
        if (sdp == null || sdp.trim().isEmpty()) {
            return info;
        }

        // 当前媒体段：null=尚未进入任何 m= 行（此段的 c= 为会话级）
        String currentMedia = null;

        for (String line : sdp.split("\\r?\\n")) {
            String t = line.trim();

            if (t.startsWith("m=video ")) {
                currentMedia = "video";
                String[] parts = t.split("\\s+");
                if (parts.length > 2) {
                    info.hasVideo = true;
                    info.mediaPort = parseInt(parts[1]);
                    info.mediaFormat = parts[2];
                    if (parts.length > 3) {
                        info.mediaPayloads = String.join(" ",
                                Arrays.copyOfRange(parts, 3, parts.length));
                    }
                }
            } else if (t.startsWith("m=audio ")) {
                currentMedia = "audio";
                String[] parts = t.split("\\s+");
                if (parts.length > 2) {
                    info.hasAudio = true;
                    info.audioPort = parseInt(parts[1]);
                    info.audioFormat = parts[2];
                    if (parts.length > 3) {
                        info.audioPayloads = String.join(" ",
                                Arrays.copyOfRange(parts, 3, parts.length));
                    }
                }
            } else if (t.startsWith("c=IN IP4 ")) {
                String ip = t.substring("c=IN IP4 ".length()).trim();
                if ("audio".equals(currentMedia)) {
                    info.audioIp = ip;      // m=audio 媒体级 c=（优先）
                } else {
                    info.sessionIp = ip;    // 会话级 c=（回退）
                }
            } else if (t.startsWith("a=rtpmap:")) {
                parseRtpMap(t, info);
            } else if (t.startsWith("a=ssrc:")) {
                info.ssrc = t.substring("a=ssrc:".length()).trim();
            } else if (t.startsWith("a=setup:")) {
                info.setup = t.substring("a=setup:".length()).trim();
            } else if (t.startsWith("a=sendrecv") || t.startsWith("a=recvonly")
                    || t.startsWith("a=sendonly") || t.startsWith("a=inactive")) {
                if ("audio".equals(currentMedia)) {
                    info.audioDir = t.substring(2);
                }
            } else if (t.startsWith("y=")) {
                info.ssrc = t.substring(2).trim();
            }
        }

        return info;
    }

    /** 解析 a=rtpmap 行，识别 PCMA/8000 并记录其 RTP Payload Type（语音广播 PCMA 协商用） */
    private static void parseRtpMap(String line, SdpInfo info) {
        Matcher matcher = RTPMAP_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return;
        }

        int payloadType = parseInt(matcher.group(1));
        String codec = matcher.group(2);

        if ("PCMA".equalsIgnoreCase(codec)) {
            int clockRate = parseInt(matcher.group(3));
            if (clockRate == 8000) {
                info.pcmaPayloadType = payloadType;
            }
        }
    }

    /** 安全整数解析，失败返回 0 */
    private static int parseInt(String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** SDP 解析结果载体：视频/音频媒体信息、SSRC 与 TCP setup 角色 */
    public static class SdpInfo {
        /** c= 会话地址（第一个 m= 之前的 c= 行） */
        public String sessionIp;

        /** c= m=audio 媒体级地址（媒体段内的 c= 行，优先于 sessionIp） */
        public String audioIp;

        /** m=video 媒体端口 / 传输格式 / 载荷列表 */
        public int mediaPort;
        public String mediaFormat;
        public String mediaPayloads;

        /** m=audio 媒体端口 / 传输格式 / 载荷列表 */
        public int audioPort;
        public String audioFormat;
        public String audioPayloads;

        /** SDP 中 PCMA/8000 对应的 RTP Payload Type */
        public int pcmaPayloadType = -1;

        /** SSRC（a=ssrc: 或国标 y= 行） */
        public String ssrc;
        /** 是否含 m=video / m=audio */
        public boolean hasVideo;
        public boolean hasAudio;

        /** a=setup 角色：active / passive / actpass（TCP 传输时有效） */
        public String setup;

        /** m=audio 媒体段方向属性：sendrecv / recvonly / sendonly / inactive */
        public String audioDir;

        /** 拼接关键字段可读摘要（日志用） */
        public String toSummary() {
            return "sessionIp=" + (sessionIp == null ? "-" : sessionIp)
                    + " audioIp=" + (audioIp == null ? "-" : audioIp)
                    + " video=" + (hasVideo ? mediaPort + " fmt=" + mediaFormat + " payloads=" + mediaPayloads : "-")
                    + " audio=" + (hasAudio ? audioPort + " fmt=" + audioFormat + " payloads=" + audioPayloads : "-")
                    + " pcma=" + (pcmaPayloadType >= 0 ? pcmaPayloadType : "-")
                    + " ssrc=" + (ssrc == null ? "-" : ssrc)
                    + " dir=" + (audioDir == null ? "-" : audioDir)
                    + " tcp=" + isTcp();
        }

        /** m=audio 传输是否为 TCP（TCP/RTP/AVP、TCP/RTP/SAVP 等） */
        public boolean isTcp() {
            return audioFormat != null && audioFormat.toUpperCase().startsWith("TCP");
        }
    }
}