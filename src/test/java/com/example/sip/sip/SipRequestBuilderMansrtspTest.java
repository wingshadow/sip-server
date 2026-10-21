package com.example.sip.sip;

import com.example.sip.config.SipServerProperties;
import com.example.sip.model.StreamSession;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MANSRTSP over SIP INFO 报文构造测试（回放控制信令契约）。
 *
 * <p>验证：MANSRTSP 指令体格式、请求行/Call-ID/Content-Type/Content-Length
 * 与消息体一致性、Dialog 标识（From/To tag）、同 Dialog 内 CSeq 逐次递增。
 */
class SipRequestBuilderMansrtspTest {

    private final SipServerProperties props = new SipServerProperties(); // 走默认值

    private StreamSession session() {
        // 6 参构造器：(callId, deviceId, channelId, deviceHost, devicePort, rtpPort)
        StreamSession s = new StreamSession(
                "callid0001",
                "34020000001320000001",
                "34020000001310000001",
                "192.168.1.64",
                5060,
                30000);
        s.setFromTag(SipRequestBuilder.newTag());
        s.setToTag("totag123");
        return s;
    }

    @Test
    void mansrtspBodies() {
        assertThat(SipRequestBuilder.mansrtspPause()).isEqualTo("PAUSE");
        assertThat(SipRequestBuilder.mansrtspPlay(1700000000L, 1700003600L))
                .isEqualTo("PLAY range:npt=\"1700000000-1700003600\"");
        assertThat(SipRequestBuilder.mansrtspScale(4.0)).isEqualTo("SCALE 4.0");
        assertThat(SipRequestBuilder.mansrtspScale(0.5)).isEqualTo("SCALE 0.5");
    }

    @Test
    void infoPacket_structure() {
        StreamSession s = session();
        String body = SipRequestBuilder.mansrtspPlay(1700000000L, 1700003600L);
        String text = new String(
                SipRequestBuilder.mansrtspInfo(props, s, body), StandardCharsets.ISO_8859_1);

        assertThat(text).startsWith("INFO sip:");
        assertThat(text).contains("Call-ID: callid0001");
        assertThat(text).contains("Content-Type: Application/MANSRTSP");
        // Dialog 标识：From/To 均携带 tag
        assertThat(text).contains(";tag=" + s.getFromTag());
        assertThat(text).contains(";tag=totag123");
        // To 头闭合（回归保障）
        assertThat(text).matches("(?s).*To: <[^>\r\n]+>.*");
        // Content-Length 与消息体一致，且报文以消息体结尾
        int idx = text.indexOf("Content-Length: ");
        int declared = Integer.parseInt(
                text.substring(idx + "Content-Length: ".length(), text.indexOf("\r\n", idx)));
        assertThat(declared).isEqualTo(body.length());
        assertThat(text).endsWith(body);
    }

    @Test
    void infoPacket_cseqIncrementsWithinDialog() {
        StreamSession s = session();
        int c1 = extractCseq(new String(
                SipRequestBuilder.mansrtspInfo(props, s, "PAUSE"), StandardCharsets.ISO_8859_1));
        int c2 = extractCseq(new String(
                SipRequestBuilder.mansrtspInfo(props, s, "PAUSE"), StandardCharsets.ISO_8859_1));
        // 同一 Dialog 内重复 CSeq 会被严格 UAS 判为重传/序列错误，必须逐次递增
        assertThat(c2).isEqualTo(c1 + 1);
    }

    private static int extractCseq(String text) {
        int idx = text.indexOf("CSeq: ");
        int methodStart = text.indexOf(" INFO", idx);
        return Integer.parseInt(text.substring(idx + 6, methodStart).trim());
    }
}
