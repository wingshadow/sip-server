package com.example.sip.handler;

import com.example.sip.model.Channel;
import com.example.sip.model.Device;
import com.example.sip.model.SipMessage;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SIP 报文解析工具（各处理器共用）。
 *
 * <p>覆盖：From/To 中 sip-id 提取、Contact 主机端口解析、
 * MANSCDP XML 解析（禁 DOCTYPE 与外部实体，XXE 防护）、
 * Catalog Item 解析、Authorization 摘要参数提取。
 */
public final class SipUtil {

    /** sip:xxx@host:port / <sip:xxx@host:port> 中的编号段 */
    private static final Pattern SIP_URI = Pattern.compile("sip:([^@;>\\s]+)@([^;>\\s]+)");
    private static final Pattern SIP_URI_NO_HOST = Pattern.compile("sip:([^@;>\\s]+)");

    /** 工具类禁止实例化 */
    private SipUtil() {
    }

    /**
     * 从 From / To 头部提取设备或通道编号。
     *
     * <p>兼容 {@code <sip:34020000001320000001@10.0.0.1>;tag=xxx} 与
     * {@code sip:xxx@host} 两种形态。
     */
    public static String extractSipId(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        Matcher m = SIP_URI.matcher(headerValue);
        if (m.find()) {
            return m.group(1).trim();
        }
        Matcher m2 = SIP_URI_NO_HOST.matcher(headerValue);
        if (m2.find()) {
            return m2.group(1).trim();
        }
        return null;
    }

    /**
     * 从 Contact 头部解析设备地址。
     *
     * @return [host, port]；解析失败返回 null
     */
    public static String[] parseContactHostPort(String contact) {
        if (contact == null) {
            return null;
        }
        Matcher m = Pattern.compile("sip:[^@]*@([^;>\\s]+)").matcher(contact);
        if (!m.find()) {
            return null;
        }
        String hostPort = m.group(1);
        String host;
        int port = 5060;
        int idx = hostPort.lastIndexOf(':');
        if (idx > 0) {
            host = hostPort.substring(0, idx);
            try {
                port = Integer.parseInt(hostPort.substring(idx + 1));
            } catch (NumberFormatException ignore) {
                port = 5060;
            }
        } else {
            host = hostPort;
        }
        return new String[]{host, String.valueOf(port)};
    }

    /**
     * 解析 MANSCDP XML 体。
     *
     * <p>优先按 XML 声明编码（常见 GB18030）解析，其次 UTF-8。
     */
    public static Document parseXml(byte[] body) {
        Charset charset = detectCharset(body);
        return parseXmlDocument(body, charset);
    }

    /** 探测 XML 声明中的编码（默认 UTF-8） */
    private static Charset detectCharset(byte[] body) {
        if (body == null) {
            return StandardCharsets.UTF_8;
        }
        String head = new String(body, 0, Math.min(body.length, 128), StandardCharsets.ISO_8859_1);
        int i = head.indexOf("encoding=");
        if (i >= 0) {
            int start = head.indexOf('"', i);
            int end = start >= 0 ? head.indexOf('"', start + 1) : -1;
            if (start >= 0 && end > start) {
                try {
                    return Charset.forName(head.substring(start + 1, end).trim());
                } catch (Exception ignore) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static Document parseXmlDocument(byte[] body, Charset charset) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
        } catch (Exception e) {
            throw new IllegalArgumentException("解析 XML 失败", e);
        }
    }

    /** 取首个匹配子元素 trim 文本，无则 null */
    public static String getXmlValue(Element parent, String tagName) {
        if (parent == null) {
            return null;
        }
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        String v = nodes.item(0).getTextContent();
        return v == null ? null : v.trim();
    }

    /** 解析 Catalog Item 为 Channel（字段缺失时跳过） */
    public static Channel parseChannel(Element item) {
        Channel c = new Channel();
        c.setChannelId(getXmlValue(item, "DeviceID"));
        c.setName(getXmlValue(item, "Name"));
        c.setManufacturer(getXmlValue(item, "Manufacturer"));
        c.setModel(getXmlValue(item, "Model"));
        c.setStatus(getXmlValue(item, "Status"));
        c.setParentId(getXmlValue(item, "ParentID"));
        if (c.getChannelId() == null || c.getChannelId().isEmpty()) {
            return null;
        }
        if (c.getStatus() == null) {
            c.setStatus("UNKNOWN");
        }
        return c;
    }

    /** Catalog 应答中的设备名（DeviceList/Device 直接子元素 Name） */
    public static String parseDeviceName(Document doc) {
        try {
            Element root = doc.getDocumentElement();
            NodeList items = root.getElementsByTagName("Item");
            if (items.getLength() > 0 && items.item(0).getNodeType() == Node.ELEMENT_NODE) {
                Element first = (Element) items.item(0);
                String name = getXmlValue(first, "Name");
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Exception ignore) {
            // 忽略
        }
        return null;
    }

    /**
     * 提取 Authorization 摘要参数（username/realm/nonce/response/uri/qop/nc/cnonce）。
     *
     * <p>先匹配带引号形式（{@code param="..."}，RFC 2617 绝大多数参数）；
     * 未命中再匹配无引号 token 形式（{@code param=token}），uri 按惯例不带引号
     * （如 {@code uri=sip:34020000002000000001@3402000000}）。
     */
    public static String extractAuthParam(String authHeader, String param) {
        if (authHeader == null) {
            return null;
        }
        Pattern quoted = Pattern.compile(param + "\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
        Matcher m = quoted.matcher(authHeader);
        if (m.find()) {
            return m.group(1);
        }
        Pattern bare = Pattern.compile(param + "\\s*=\\s*([^,\\s\"]+)", Pattern.CASE_INSENSITIVE);
        m = bare.matcher(authHeader);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /** 设备描述：日志/前端展示用 */
    public static String describe(Device d) {
        return d == null ? "null" : d.getDeviceId() + "@" + d.getHost() + ":" + d.getPort();
    }

    /** 从 MANSCDP 消息中提取通道编号（Subject 或 DeviceID），兼容多设备形态 */
    public static String resolveChannelId(SipMessage msg, Element root) {
        String subject = msg.getHeader("Subject");
        if (subject != null) {
            java.util.regex.Matcher m = Pattern.compile("(\\d{20})").matcher(subject);
            if (m.find()) {
                return m.group(1);
            }
        }
        return getXmlValue(root, "DeviceID");
    }
}
