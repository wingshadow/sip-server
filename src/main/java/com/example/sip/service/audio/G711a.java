package com.example.sip.service.audio;

/**
 * G.711 A-law（PCMA）编码：GB/T 28181 语音广播标准音频载荷。
 *
 * <p>A-law 是 ITU-T G.711 标准定义的压扩算法，主要应用于欧洲、中国及世界其他地区。
 * 采用 13 段折线法逼近 A=87.6 的对数压缩曲线，将 13 位线性 PCM 样本压缩为 8 位码字，
 * 压缩比 2:1，输出码率 64kbps（8kHz 采样率下）。</p>
 *
 * <p>编码输出格式为 seeewxyz：
 * <ul>
 *   <li>s —— 符号位（取反后存储）</li>
 *   <li>eee —— 3 位段落码（指数），表示信号所在折线段</li>
 *   <li>wxyz —— 4 位段内码（尾数），表示段内量化级</li>
 * </ul>
 * 最终结果与符号掩码异或完成压扩。</p>
 */
public final class G711a {

    /**
     * A-law 十三折线法的 8 个段落边界值。
     * 每个值对应一段的最大输入幅值，用于快速查表确定段号。
     * 段号 0~7 分别对应输入范围 [0,0xFF]、[0x100,0x1FF]、...、[0x4000,0x7FFF]。
     */
    private static final short[] SEG_END = {
            0xFF, 0x1FF, 0x3FF, 0x7FF,
            0xFFF, 0x1FFF, 0x3FFF, 0x7FFF
    };

    private G711a() {
    }

    /**
     * 将 16bit 小端序线性 PCM 数据编码为 G.711 A-law 字节数组。
     *
     * @param pcmLe 小端序 PCM 字节数组，长度必须为偶数（每 2 字节表示一个采样点）
     * @return A-law 编码后的字节数组，长度为输入的一半
     */
    public static byte[] encode(byte[] pcmLe) {
        int samples = pcmLe.length / 2;
        byte[] out = new byte[samples];
        for (int i = 0; i < samples; i++) {
            // 小端序：低字节在前，高字节在后
            int lo = pcmLe[i * 2] & 0xFF;
            int hi = pcmLe[i * 2 + 1];
            // 组装为 16 位有符号整数
            int pcm = (hi << 8) | lo;
            // 逐样本进行 A-law 压缩
            out[i] = (byte) linearToAlaw(pcm);
        }
        return out;
    }

    /**
     * 线性 PCM → A-law：基于十三折线法的快速整数编码。
     *
     * <p>算法步骤：
     * <ol>
     *   <li>确定符号位：正数掩码 0xD5（0b1101_0101），负数掩码 0x55（0b0101_0101）</li>
     *   <li>负值取绝对值并偏移 8（-pcmVal - 8），使正负半轴对称</li>
     *   <li>查段表确定段落号 seg（0~7），超出范围则钳位到 0x7F</li>
     *   <li>组装 8 位码字：高 3 位为段号，低 4 位为段内偏移</li>
     *   <li>与符号掩码异或，完成压扩编码</li>
     * </ol>
     *
     * <p>段内偏移的提取规则：
     * <ul>
     *   <li>seg &lt; 2（第 0、1 段）：斜率相同，统一右移 4 位取高 4 位</li>
     *   <li>seg ≥ 2：右移 (seg+3) 位，使每段均匀取 16 个量化级</li>
     * </ul>
     *
     * @param pcmVal 16 位有符号线性 PCM 采样值
     * @return 8 位 A-law 编码值（0x00~0xFF）
     */
    private static int linearToAlaw(int pcmVal) {
        int mask;

        if (pcmVal >= 0) {
            // 正数：符号掩码 0xD5，异或后符号位为 1
            mask = 0xD5;
        } else {
            // 负数：符号掩码 0x55，取绝对值并偏移 8 使正负对称
            mask = 0x55;
            pcmVal = -pcmVal - 8;
        }

        // 查段表确定段落号
        int seg = search(pcmVal);
        // 超出最大段（>0x7FFF），钳位到最大值 0x7F
        if (seg >= 8) {
            return 0x7F ^ mask;
        }

        // 组装 8 位码字：高 3 位为段号，低 4 位为段内偏移
        int aval = seg << 4;
        if (seg < 2) {
            // 第 0、1 段斜率相同（均为 16），统一右移 4 位
            aval |= (pcmVal >> 4) & 0x0F;
        } else {
            // 第 2~7 段：右移 (seg+3) 位，每段均匀取 16 个量化级
            aval |= (pcmVal >> (seg + 3)) & 0x0F;
        }

        // 与符号掩码异或，完成压扩（正负半轴对称映射）
        return aval ^ mask;
    }

    /**
     * 查 SEG_END 段表确定 A-law 折线段号。
     *
     * @param val 非负整数值（已取绝对值）
     * @return 段号 0~7；若 val 超过 0x7FFF 则返回 8（表示溢出）
     */
    private static int search(int val) {
        for (int i = 0; i < 8; i++) {
            if (val <= SEG_END[i]) {
                return i;
            }
        }
        return 8;
    }

    /**
     * 将 G.711 A-law 数据解码为 16bit 小端序线性 PCM。
     *
     * @param alaw A-law 编码数据
     * @return 16bit 小端序 PCM 数据
     */
    public static byte[] decode(byte[] alaw) {
        byte[] out = new byte[alaw.length * 2];
        for (int i = 0; i < alaw.length; i++) {
            short pcm = alawToLinear(alaw[i] & 0xFF);
            out[i * 2] = (byte) (pcm & 0xFF);
            out[i * 2 + 1] = (byte) ((pcm >>> 8) & 0xFF);
        }
        return out;
    }

    /**
     * A-law → 16bit 线性 PCM。
     */
    private static short alawToLinear(int alaw) {
        alaw ^= 0x55;

        int t = (alaw & 0x0F) << 4;
        int seg = (alaw & 0x70) >> 4;

        if (seg == 0) {
            t += 8;
        } else if (seg == 1) {
            t += 0x108;
        } else {
            t += 0x108;
            t <<= seg - 1;
        }

        return (short) (((alaw & 0x80) != 0) ? t : -t);
    }
}