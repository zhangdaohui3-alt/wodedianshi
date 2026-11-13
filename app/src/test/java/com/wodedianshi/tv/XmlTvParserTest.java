package com.wodedianshi.tv;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TimeZone;

import static org.junit.Assert.*;

public class XmlTvParserTest {
    private XmlTvParser parser() {
        return new XmlTvParser(TimeZone.getTimeZone("Asia/Shanghai"), 10, 20);
    }

    @Test public void parsesChannelsAliasesAndOrderedProgrammeTimes() throws Exception {
        String xml = "<tv>"
                + "<channel id='cctv1'><display-name>CCTV-1</display-name>"
                + "<display-name>中央一套</display-name></channel>"
                + "<programme channel='cctv1' start='20261112190000 +0800' "
                + "stop='20261112200000 +0800'><title>新闻联播</title>"
                + "<desc>晚间新闻</desc><category>News</category></programme>"
                + "<programme channel='cctv1' start='20261112180000 +0800' "
                + "stop='20261112190000 +0800'><title>前一节目</title></programme>"
                + "</tv>";
        XmlTvParser.Catalog catalog = parser().parse(stream(xml));

        assertEquals(1, catalog.channels.size());
        assertEquals("CCTV-1", catalog.channels.get("cctv1").preferredName());
        List<XmlTvProgram> programmes = catalog.programmesFor("cctv1");
        assertEquals(2, programmes.size());
        assertEquals("前一节目", programmes.get(0).title);
        assertEquals("新闻联播", programmes.get(1).title);
        assertEquals("晚间新闻", programmes.get(1).description);
        assertTrue(programmes.get(1).contains(programmes.get(1).startMillis));
        assertFalse(programmes.get(1).contains(programmes.get(1).stopMillis));
    }

    @Test public void timestampsWithoutOffsetUseConfiguredChinaTimeZone() throws Exception {
        String xml = "<tv><programme channel='c1' start='20261112190000' "
                + "stop='20261112200000'><title>节目</title></programme></tv>";
        XmlTvProgram programme = parser().parse(stream(xml))
                .programmesFor("c1").get(0);
        assertEquals(-8L * 60L * 60L * 1000L,
                programme.startMillis - parseUtc("20261112190000"));
    }

    @Test public void skipsMalformedOrEmptyProgrammeRecords() throws Exception {
        String xml = "<tv>"
                + "<programme channel='c1' start='bad' stop='20261112200000 +0800'>"
                + "<title>坏时间</title></programme>"
                + "<programme channel='c1' start='20261112190000 +0800' "
                + "stop='20261112180000 +0800'><title>倒序时间</title></programme>"
                + "<programme channel='c1' start='20261112190000 +0800' "
                + "stop='20261112200000 +0800'></programme>"
                + "</tv>";
        XmlTvParser.Catalog catalog = parser().parse(stream(xml));
        assertTrue(catalog.programmesFor("c1").isEmpty());
        assertEquals(3, catalog.skippedProgrammes);
    }

    @Test public void rejectsDoctypeAndExternalEntityPayloads() throws Exception {
        String xml = "<!DOCTYPE tv [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>"
                + "<tv><channel id='x'><display-name>&xxe;</display-name></channel></tv>";
        try {
            parser().parse(stream(xml));
            fail("DOCTYPE must be rejected");
        } catch (java.io.IOException expected) {
            assertNotNull(expected.getCause());
        }
    }

    @Test public void enforcesConfiguredRecordLimits() throws Exception {
        XmlTvParser limited = new XmlTvParser(TimeZone.getTimeZone("Asia/Shanghai"), 1, 1);
        String xml = "<tv><channel id='one'/><channel id='two'/></tv>";
        try {
            limited.parse(stream(xml));
            fail("channel limit must be enforced");
        } catch (java.io.IOException expected) {
            assertNotNull(expected.getCause());
        }
    }

    private static ByteArrayInputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static long parseUtc(String value) throws Exception {
        java.text.SimpleDateFormat format =
                new java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.parse(value).getTime();
    }
}
