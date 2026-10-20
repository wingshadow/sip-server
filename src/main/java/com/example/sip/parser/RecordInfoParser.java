package com.example.sip.parser;

import com.example.sip.model.RecordInfo;
import com.example.sip.model.RecordInfoQuery;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;

/**
 * GB/T 28181 RecordInfo 录像查询应答（MANSCDP+xml）解析器：
 * 提取 SN / DeviceID / Result / SumNum 与 Item 列表。
 * XML 解析禁 DOCTYPE 与外部实体（XXE 防护），按 XML 声明处理编码（如 GB18030）。
 */
public final class RecordInfoParser {

    private static final Charset GB18030 = Charset.forName("GB18030");

    /** 工具类禁止实例化 */
    private RecordInfoParser() {
    }

    /**
     * 解析 RecordInfo 应答 XML，失败抛 IllegalArgumentException。
     * Result=ERROR 时 success=false；SumNum 解析失败置 -1。
     */
    public static RecordInfoQuery parse(byte[] body) {
        RecordInfoQuery result = new RecordInfoQuery();

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);

            Document document = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(body));

            Element root = document.getDocumentElement();

            result.setSn(text(root, "SN"));
            result.setDeviceId(text(root, "DeviceID"));

            String resultValue = text(root, "Result");
            String reason = text(root, "Reason");

            result.setSuccess(!"ERROR".equalsIgnoreCase(resultValue));
            result.setReason(reason);

            String sumNum = text(root, "SumNum");
            if (sumNum != null && !sumNum.isEmpty()) {
                try {
                    result.setSumNum(Integer.parseInt(sumNum));
                } catch (NumberFormatException ignored) {
                    result.setSumNum(-1);
                }
            }

            NodeList items = root.getElementsByTagName("Item");
            for (int i = 0; i < items.getLength(); i++) {
                Node node = items.item(i);
                if (node instanceof Element) {
                    result.getRecords().add(parseItem((Element) node));
                }
            }

            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("解析 RecordInfo XML 失败", e);
        }
    }

    /**
     * 解析单条录像 Item（DeviceID/Name/FilePath/起止时间/Type/RecorderID/FileSize/Secrecy）。
     * 起止时间为 dateTime 类型字符串：'T' 分隔是 XML Schema dateTime 的合法标准表示，
     * 部分设备实现为空格分隔，均按原样透传不做格式归一。
     */
    private static RecordInfo parseItem(Element item) {
        RecordInfo record = new RecordInfo();

        record.setDeviceId(text(item, "DeviceID"));
        record.setName(text(item, "Name"));
        record.setFilePath(text(item, "FilePath"));
        record.setAddress(text(item, "Address"));
        record.setStartTime(text(item, "StartTime"));
        record.setEndTime(text(item, "EndTime"));
        record.setType(text(item, "Type"));
        record.setRecorderId(text(item, "RecorderID"));
        record.setFileSize(text(item, "FileSize"));

        String secrecy = text(item, "Secrecy");
        if (secrecy != null && !secrecy.isEmpty()) {
            try {
                record.setSecrecy(Integer.parseInt(secrecy));
            } catch (NumberFormatException ignored) {
                record.setSecrecy(0);
            }
        }

        return record;
    }

    /** 取首个匹配子元素 trim 文本，无则 null */
    private static String text(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }

        Node node = nodes.item(0);
        String value = node.getTextContent();
        return value == null ? null : value.trim();
    }
}