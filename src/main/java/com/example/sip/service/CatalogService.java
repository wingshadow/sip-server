package com.example.sip.service;

import com.example.sip.model.Device;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 国标目录（Catalog）查询响应 XML 生成。
 */
@Service
public class CatalogService {

    /**
     * 生成目录查询响应 XML（GB2312 编码）。
     *
     * @param serverId  服务器国标编码（作为 ParentID）
     * @param sn        请求中的 SN 序号，原样回填
     * @param devices   当前已注册设备列表
     */
    public String buildCatalogXml(String serverId, String sn, List<Device> devices) {
        StringBuilder xml = new StringBuilder(512);
        xml.append("<?xml version=\"1.0\" encoding=\"GB2312\"?>\r\n");
        xml.append("<Response>\r\n");
        xml.append("<CmdType>Catalog</CmdType>\r\n");
        xml.append("<SN>").append(sn == null ? "0" : sn).append("</SN>\r\n");
        xml.append("<DeviceID>").append(serverId).append("</DeviceID>\r\n");
        xml.append("<SumNum>").append(devices.size()).append("</SumNum>\r\n");
        xml.append("<DeviceList>\r\n");
        for (Device d : devices) {
            xml.append("<Item>\r\n");
            xml.append("<DeviceID>").append(d.getDeviceId()).append("</DeviceID>\r\n");
            xml.append("<Name>").append(escape(d.getName())).append("</Name>\r\n");
            xml.append("<Manufacturer>HIKVISION</Manufacturer>\r\n");
            xml.append("<Model>test</Model>\r\n");
            xml.append("<Owner>owner</Owner>\r\n");
            xml.append("<CivilCode>340200</CivilCode>\r\n");
            xml.append("<Address>test</Address>\r\n");
            xml.append("<Parental>0</Parental>\r\n");
            xml.append("<ParentID>").append(serverId).append("</ParentID>\r\n");
            xml.append("<SafetyWay>0</SafetyWay>\r\n");
            xml.append("<RegisterWay>1</RegisterWay>\r\n");
            xml.append("<Secrecy>0</Secrecy>\r\n");
            xml.append("<Status>").append(d.isOnline() ? "ON" : "OFF").append("</Status>\r\n");
            xml.append("</Item>\r\n");
        }
        xml.append("</DeviceList>\r\n");
        xml.append("</Response>");
        return xml.toString();
    }

    /** XML 转义（&amp; &lt; &gt; &quot;） */
    private String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
