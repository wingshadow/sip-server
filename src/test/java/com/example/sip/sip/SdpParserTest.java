package com.example.sip.sip;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 极简 SDP 解析器单元测试。
 *
 * <p>覆盖点播 200 OK answer、语音对讲 answer（PCMA 协商）、
 * RFC 4566 媒体级 c= 优先规则、国标 y= 行、TCP 承载识别与脏输入容错。
 */
class SdpParserTest {

    private static final String LIVE_ANSWER = """
            v=0
            o=34020000001320000001 0 0 IN IP4 192.168.1.64
            s=Play
            c=IN IP4 192.168.1.64
            t=0 0
            m=video 30000 RTP/AVP 96 98
            a=recvonly
            a=rtpmap:96 PS/90000
            a=rtpmap:98 H264/90000
            y=0100000001
            """;

    private static final String VOICE_ANSWER = """
            v=0
            o=34020000001320000001 0 0 IN IP4 192.168.1.64
            s=Talk
            c=IN IP4 192.168.1.64
            t=0 0
            m=audio 30002 RTP/AVP 8
            a=recvonly
            a=rtpmap:8 PCMA/8000
            """;

    @Test
    void parse_liveVideoAnswer() {
        SdpParser.SdpInfo info = SdpParser.parse(LIVE_ANSWER);
        assertThat(info.hasVideo).isTrue();
        assertThat(info.mediaPort).isEqualTo(30000);
        assertThat(info.mediaFormat).isEqualTo("RTP/AVP");
        assertThat(info.mediaPayloads).isEqualTo("96 98");
        assertThat(info.sessionIp).isEqualTo("192.168.1.64");
        assertThat(info.ssrc).isEqualTo("0100000001");
        assertThat(info.hasAudio).isFalse();
    }

    @Test
    void parse_voiceAnswer_fallbackToSessionIp() {
        SdpParser.SdpInfo info = SdpParser.parse(VOICE_ANSWER);
        assertThat(info.hasAudio).isTrue();
        assertThat(info.audioPort).isEqualTo(30002);
        assertThat(info.audioFormat).isEqualTo("RTP/AVP");
        assertThat(info.pcmaPayloadType).isEqualTo(8);
        // 无媒体级 c=：RTP 目标地址回退会话级
        assertThat(info.audioIp).isNull();
        assertThat(info.sessionIp).isEqualTo("192.168.1.64");
        assertThat(info.audioDir).isEqualTo("recvonly");
    }

    @Test
    void parse_mediaLevelC_takesPrecedence() {
        String sdp = "v=0\r\n"
                + "c=IN IP4 192.168.1.64\r\n"
                + "m=audio 30002 TCP/RTP/AVP 8\r\n"
                + "c=IN IP4 10.0.0.5\r\n"
                + "a=setup:active\r\n";
        SdpParser.SdpInfo info = SdpParser.parse(sdp);
        // RFC 4566：m= 之后的 c= 仅对该媒体段生效，语音取对端地址时优先它
        assertThat(info.audioIp).isEqualTo("10.0.0.5");
        assertThat(info.sessionIp).isEqualTo("192.168.1.64");
        assertThat(info.isTcp()).isTrue();
        assertThat(info.setup).isEqualTo("active");
    }

    @Test
    void parse_yLine_overridesAsrc() {
        String sdp = "v=0\r\nc=IN IP4 1.2.3.4\r\n"
                + "m=video 30000 RTP/AVP 96\r\n"
                + "a=ssrc:1111111111\r\n"
                + "y=2222222222\r\n";
        // y= 与 a=ssrc 同时存在时后写覆盖（以最终解析值为准）
        assertThat(SdpParser.parse(sdp).ssrc).isEqualTo("2222222222");
    }

    @Test
    void parse_pcmaCaseInsensitive() {
        String sdp = "v=0\r\nc=IN IP4 1.2.3.4\r\n"
                + "m=audio 30002 RTP/AVP 8\r\n"
                + "a=rtpmap:8 pcma/8000\r\n";
        assertThat(SdpParser.parse(sdp).pcmaPayloadType).isEqualTo(8);
    }

    @Test
    void parse_blankOrJunk_yieldsEmptyInfo() {
        SdpParser.SdpInfo empty = SdpParser.parse(null);
        assertThat(empty.hasVideo).isFalse();
        assertThat(empty.hasAudio).isFalse();
        assertThat(empty.ssrc).isNull();

        assertThat(SdpParser.parse("   \r\n").mediaPort).isEqualTo(0);
        // 非法端口数字安全回退 0，不抛异常
        assertThat(SdpParser.parse("m=video abc RTP/AVP 96").mediaPort).isEqualTo(0);
    }
}
