package com.getjobs.application.service;

import com.getjobs.worker.dto.DeliveryNotification;
import com.getjobs.worker.dto.DeliveryRunReport;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 输出标准 OOXML Excel：汇总和投递明细两张表，所有外部文本均使用字符串单元格。 */
@Component
public class DeliveryReportWriter {
    public void write(DeliveryRunReport report, OutputStream output) throws IOException {
        try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                      <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                      <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                      <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                    </Types>
                    """);
            entry(zip, "_rels/.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>
                    """);
            entry(zip, "xl/workbook.xml", """
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets><sheet name="投递汇总" sheetId="1" r:id="rId1"/><sheet name="投递明细" sheetId="2" r:id="rId2"/></sheets>
                    </workbook>
                    """);
            entry(zip, "xl/_rels/workbook.xml.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                      <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
                      <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                    </Relationships>
                    """);
            entry(zip, "xl/styles.xml", """
                    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <fonts count="2"><font><sz val="11"/><name val="Microsoft YaHei"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Microsoft YaHei"/></font></fonts>
                      <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF245A81"/><bgColor indexed="64"/></patternFill></fill></fills>
                      <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
                      <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                      <cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf></cellXfs>
                      <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
                    </styleSheet>
                    """);

            List<List<String>> summary = new ArrayList<>();
            summary.add(List.of("统计项目", "本次任务"));
            summary.add(List.of("任务编号", report.id()));
            summary.add(List.of("投递平台", report.platform()));
            summary.add(List.of("开始时间（北京时间）", time(report.startedAt())));
            summary.add(List.of("结束时间（北京时间）", time(report.endedAt())));
            summary.add(List.of("结束原因", report.reason()));
            summary.add(List.of("记录总数（按尝试计数）", String.valueOf(report.deliveries().size())));
            summary.add(List.of("涉及公司（缺失公司不计）", String.valueOf(report.companyCount())));
            for (var status : DeliveryNotification.Status.values()) {
                summary.add(List.of(status.label(), String.valueOf(report.count(status))));
            }
            summary.add(List.of("统计口径", "仅本次任务中已记录的投递尝试；未包含筛选跳过的岗位；待确认、未确认不视为成功。"));
            entry(zip, "xl/worksheets/sheet1.xml", sheet(summary, new int[]{32, 80}, false));

            List<List<String>> details = new ArrayList<>();
            details.add(List.of("序号", "投递平台", "投递公司", "岗位名称", "投递链接", "投递时间（北京时间）", "投递状态", "薪资", "工作地点", "结果说明"));
            int index = 0;
            for (var delivery : report.deliveries()) {
                details.add(List.of(String.valueOf(++index), text(delivery.platform()), text(delivery.company()),
                        text(delivery.title()), text(delivery.link()), time(delivery.time()), delivery.status().label(),
                        text(delivery.salary()), text(delivery.area()), text(delivery.detail())));
            }
            entry(zip, "xl/worksheets/sheet2.xml", sheet(details, new int[]{8, 16, 30, 30, 60, 24, 24, 18, 22, 65}, true));
        }
    }

    private static String time(java.time.ZonedDateTime time) {
        return time.withZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static String text(String value) { return value == null || value.isBlank() ? "未获取" : value; }

    private static void entry(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String sheet(List<List<String>> rows, int[] widths, boolean filter) {
        StringBuilder xml = new StringBuilder("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><cols>");
        for (int i = 0; i < widths.length; i++) {
            xml.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1)
                    .append("\" width=\"").append(widths[i]).append("\" customWidth=\"1\"/>");
        }
        xml.append("</cols><sheetData>");
        for (int i = 0; i < rows.size(); i++) {
            xml.append("<row r=\"").append(i + 1).append("\">");
            for (int j = 0; j < rows.get(i).size(); j++) {
                xml.append("<c r=\"").append((char) ('A' + j)).append(i + 1).append("\" s=\"").append(i == 0 ? 1 : 0)
                        .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(escape(rows.get(i).get(j))).append("</t></is></c>");
            }
            xml.append("</row>");
        }
        xml.append("</sheetData>");
        if (filter) xml.append("<autoFilter ref=\"A1:J").append(rows.size()).append("\"/>");
        return xml.append("</worksheet>").toString();
    }

    private static String escape(String value) {
        // OOXML 单元格文本最多 32767 个 UTF-16 字符；移除 XML 1.0 不接受的控制字符。
        StringBuilder result = new StringBuilder();
        value.codePoints().filter(cp -> cp == 9 || cp == 10 || cp == 13 || cp >= 32 && cp <= 0xD7FF
                || cp >= 0xE000 && cp <= 0xFFFD || cp >= 0x10000 && cp <= 0x10FFFF)
                .limit(16000).forEach(result::appendCodePoint);
        return result.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
