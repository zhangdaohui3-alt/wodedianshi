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

    @Test public void respectsDeclaredLegacyXmlEncoding() throws Exception {
        String xml = "<?xml version='1.0' encoding='ISO-8859-1'?>"
                + "<tv><programme channel='c1' start='20261112190000 +0800' "
                + "stop='20261112200000 +0800'><title>Résumé</title></programme></tv>";
        ByteArrayInputStream input = new ByteArrayInputStream(
                xml.getBytes(java.nio.charset.Charset.forName("ISO-8859-1")));
        XmlTvProgram programme = parser().parse(input).programmesFor("c1").get(0);
        assertEquals("Résumé", programme.title);
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

    @Test public void resolvesCurrentAndNextProgrammeAndHandlesScheduleGaps() throws Exception {
        String xml = "<tv>"
                + "<programme channel='c1' start='20261112180000 +0800' stop='20261112190000 +0800'><title>早间</title></programme>"
                + "<programme channel='c1' start='20261112193000 +0800' stop='20261112200000 +0800'><title>新闻</title></programme>"
                + "<programme channel='c1' start='20261112200000 +0800' stop='20261112210000 +0800'><title>晚间</title></programme>"
                + "</tv>";
        XmlTvParser.Catalog catalog = parser().parse(stream(xml));
        long duringNews = parseUtc("20261112194000") - 8L * 60L * 60L * 1000L;
        XmlTvParser.ProgrammeWindow active = catalog.windowFor("c1", duringNews);
        assertEquals("新闻", active.current.title);
        assertEquals("晚间", active.next.title);
        long duringGap = parseUtc("20261112191000") - 8L * 60L * 60L * 1000L;
        XmlTvParser.ProgrammeWindow gap = catalog.windowFor("c1", duringGap);
        assertNull(gap.current);
        assertEquals("新闻", gap.next.title);
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

    @Test public void rejectsDocumentsOverConfiguredByteLimit() throws Exception {
        XmlTvParser limited = new XmlTvParser(TimeZone.getTimeZone("Asia/Shanghai"),
                10, 20, 96L);
        String xml = "<tv><programme channel='c1' start='20261112190000 +0800' "
                + "stop='20261112200000 +0800'><title>"
                + new String(new char[160]).replace('\0', 'x')
                + "</title></programme></tv>";
        try {
            limited.parse(stream(xml));
            fail("document byte limit must be enforced");
        } catch (java.io.IOException expected) {
            assertNotNull(expected);
        }
    }

    @Test public void indexedWindowKeepsLatestStartingOverlapAndNextStart() throws Exception {
        String xml = "<tv>"
                + "<programme channel='c1' start='20261112180000 +0800' stop='20261112210000 +0800'><title>长节目</title></programme>"
                + "<programme channel='c1' start='20261112190000 +0800' stop='20261112193000 +0800'><title>短节目</title></programme>"
                + "<programme channel='c1' start='20261112220000 +0800' stop='20261112230000 +0800'><title>下一档</title></programme>"
                + "</tv>";
        XmlTvParser.Catalog catalog = parser().parse(stream(xml));
        long duringOverlap = parseUtc("20261112191500") - 8L * 60L * 60L * 1000L;
        XmlTvParser.ProgrammeWindow overlap = catalog.windowFor("c1", duringOverlap);
        assertEquals("短节目", overlap.current.title);
        assertEquals("下一档", overlap.next.title);

        long afterOverlap = parseUtc("20261112200000") - 8L * 60L * 60L * 1000L;
        XmlTvParser.ProgrammeWindow stillCoveredByLongProgramme =
                catalog.windowFor("c1", afterOverlap);
        assertEquals("长节目", stillCoveredByLongProgramme.current.title);
        assertEquals("下一档", stillCoveredByLongProgramme.next.title);

        long afterLongProgramme = parseUtc("20261112213000")
                - 8L * 60L * 60L * 1000L;
        XmlTvParser.ProgrammeWindow gap = catalog.windowFor("c1", afterLongProgramme);
        assertNull(gap.current);
        assertEquals("下一档", gap.next.title);
    }

    @Test public void indexedWindowMatchesLinearReferenceAcrossDenseOverlaps() throws Exception {
        StringBuilder xml = new StringBuilder("<tv>");
        for (int i = 0; i < 80; i++) {
            int startMinute = (i * 37) % 240;
            int duration = 20 + ((i * 19) % 100);
            int stopMinute = startMinute + duration;
            String start = String.format(java.util.Locale.US, "20261112%02d%02d00 +0800",
                    16 + startMinute / 60, startMinute % 60);
            String stop = String.format(java.util.Locale.US, "20261112%02d%02d00 +0800",
                    16 + stopMinute / 60, stopMinute % 60);
            xml.append("<programme channel='c1' start='").append(start)
                    .append("' stop='").append(stop).append("'><title>节目")
                    .append(i).append("</title></programme>");
        }
        xml.append("</tv>");
        XmlTvParser.Catalog catalog = parser().parse(stream(xml.toString()));
        List<XmlTvProgram> programmes = catalog.programmesFor("c1");

        for (int minute = 0; minute < 360; minute++) {
            long instant = parseUtc("20261112160000")
                    - 8L * 60L * 60L * 1000L + minute * 60_000L;
            XmlTvProgram expectedCurrent = null;
            XmlTvProgram expectedNext = null;
            for (XmlTvProgram programme : programmes) {
                if (programme.contains(instant)) {
                    if (expectedCurrent == null
                            || programme.startMillis > expectedCurrent.startMillis) {
                        expectedCurrent = programme;
                    }
                } else if (programme.startMillis > instant
                        && (expectedNext == null
                        || programme.startMillis < expectedNext.startMillis)) {
                    expectedNext = programme;
                }
            }
            XmlTvParser.ProgrammeWindow actual = catalog.windowFor("c1", instant);
            assertEquals("current mismatch at minute " + minute,
                    expectedCurrent == null ? null : expectedCurrent.title,
                    actual.current == null ? null : actual.current.title);
            assertEquals("next mismatch at minute " + minute,
                    expectedNext == null ? null : expectedNext.title,
                    actual.next == null ? null : actual.next.title);
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
