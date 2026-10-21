package com.example.sip.service.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G.711 A-law 编解码单元测试（语音对讲标准载荷）。
 *
 * <p>覆盖：静音帧、极值钳位、全范围编码-解码往返精度、长度契约、符号保持。
 * 往返容差依据十三折线量化特性推导：第 seg 段（seg≥2）量化步长为 2^(seg+3)，
 * 而该段输入幅值 |x| &gt; 2^(seg+4) − 1，故 |x|/2 + 8 恒大于步长；前两段步长 16，
 * 固定容差 32 覆盖（含解码侧 +8 决策阈值偏移）。
 */
class G711aTest {

    /** 小端序两字节组装为 16 位有符号样本。 */
    private static int sample(byte[] pcmLe) {
        return (short) ((pcmLe[1] << 8) | (pcmLe[0] & 0xFF));
    }

    @Test
    void encode_zeroPcm_isPcmaSilence() {
        byte[] out = G711a.encode(new byte[320]); // 160 样本全零（20ms @8k）
        assertThat(out).hasSize(160);
        for (byte b : out) {
            assertThat(b & 0xFF).isEqualTo(0xD5); // PCMA 静音码
        }
    }

    @Test
    void decode_silenceCode_isDecisionThreshold() {
        int s = sample(G711a.decode(new byte[]{(byte) 0xD5}));
        // A-law 零点决策阈值 8，非数字 0——断言上界即可
        assertThat(Math.abs(s)).isLessThanOrEqualTo(8);
    }

    @Test
    void encode_knownValues() {
        // +32767：段 7 满幅量化
        assertThat(G711a.encode(new byte[]{(byte) 0xFF, 0x7F})[0] & 0xFF).isEqualTo(0xAA);
        // -32768：取绝对值偏移 8 后仍落段 7
        assertThat(G711a.encode(new byte[]{0x00, (byte) 0x80})[0] & 0xFF).isEqualTo(0x2A);
    }

    @Test
    void decode_knownValue() {
        assertThat(sample(G711a.decode(new byte[]{(byte) 0xAA}))).isEqualTo(32256);
    }

    @Test
    void roundTrip_fullRange_withinQuantizationError() {
        for (int x = -32768; x <= 32767; x += 97) {
            byte[] decoded = G711a.decode(
                    G711a.encode(new byte[]{(byte) x, (byte) (x >> 8)}));
            int back = sample(decoded);
            int tolerance = Math.max(32, Math.abs(x) / 2 + 8);
            assertThat(back)
                    .as("sample=%d back=%d tolerance=%d", x, back, tolerance)
                    .isBetween(x - tolerance, x + tolerance);
        }
    }

    @Test
    void lengthContract() {
        assertThat(G711a.encode(new byte[3])).hasSize(1); // 奇数长度：末尾半样本截断
        assertThat(G711a.encode(new byte[0])).isEmpty();
        assertThat(G711a.decode(new byte[2])).hasSize(4);
    }

    @Test
    void encode_preservesSign() {
        int x = 12345;
        int p = sample(G711a.decode(G711a.encode(new byte[]{(byte) x, (byte) (x >> 8)})));
        int n = sample(G711a.decode(
                G711a.encode(new byte[]{(byte) -x, (byte) ((-x) >> 8)})));
        assertThat(p).isPositive();
        assertThat(n).isNegative();
    }
}
