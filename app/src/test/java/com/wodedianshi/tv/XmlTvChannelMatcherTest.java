package com.wodedianshi.tv;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.Assert.*;

public class XmlTvChannelMatcherTest {
    private final XmlTvChannelMatcher.NameNormalizer normalizer = value ->
            value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                    .replace(" ", "").replace("-", "");

    private XmlTvParser.Catalog catalog(String xml) throws Exception {
        return new XmlTvParser(TimeZone.getTimeZone("Asia/Shanghai"), 20, 50)
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void matchesAnyXmlTvDisplayNameAlias() throws Exception {
        XmlTvParser.Catalog catalog = catalog("<tv>"
                + "<channel id='cctv1'><display-name>CCTV-1</display-name>"
                + "<display-name>中央一套</display-name></channel>"
                + "</tv>");
        XmlTvChannelMatcher.Match match =
                XmlTvChannelMatcher.match("中央一套", catalog, normalizer);
        assertEquals(XmlTvChannelMatcher.Status.MATCHED, match.status);
        assertEquals("cctv1", match.channel.id);
    }

    @Test public void matchesChannelIdWhenNoDisplayAliasMatches() throws Exception {
        XmlTvParser.Catalog catalog = catalog("<tv>"
                + "<channel id='CCTV-5'><display-name>体育频道</display-name></channel>"
                + "</tv>");
        XmlTvChannelMatcher.Match match =
                XmlTvChannelMatcher.match("cctv5", catalog, normalizer);
        assertEquals(XmlTvChannelMatcher.Status.MATCHED, match.status);
        assertEquals("CCTV-5", match.channel.id);
    }

    @Test public void reportsAmbiguousAliasesInsteadOfGuessing() throws Exception {
        XmlTvParser.Catalog catalog = catalog("<tv>"
                + "<channel id='one'><display-name>新闻综合</display-name></channel>"
                + "<channel id='two'><display-name>新闻综合</display-name></channel>"
                + "</tv>");
        XmlTvChannelMatcher.Match match =
                XmlTvChannelMatcher.match("新闻综合", catalog, normalizer);
        assertEquals(XmlTvChannelMatcher.Status.AMBIGUOUS, match.status);
        assertNull(match.channel);
        assertEquals(2, match.candidateIds.size());
    }

    @Test public void returnsUnmatchedForUnknownOrBlankNames() throws Exception {
        XmlTvParser.Catalog catalog = catalog(
                "<tv><channel id='one'><display-name>CCTV-1</display-name></channel></tv>");
        assertEquals(XmlTvChannelMatcher.Status.UNMATCHED,
                XmlTvChannelMatcher.match("地方未知台", catalog, normalizer).status);
        assertEquals(XmlTvChannelMatcher.Status.UNMATCHED,
                XmlTvChannelMatcher.match(" ", catalog, normalizer).status);
    }

    @Test public void ignoresDuplicateAliasesWithinSameChannel() throws Exception {
        XmlTvParser.Catalog catalog = catalog("<tv>"
                + "<channel id='one'><display-name>CCTV-1</display-name>"
                + "<display-name>CCTV1</display-name></channel></tv>");
        XmlTvChannelMatcher.Match match =
                XmlTvChannelMatcher.match("CCTV1", catalog, normalizer);
        assertEquals(XmlTvChannelMatcher.Status.MATCHED, match.status);
        assertEquals(1, match.candidateIds.size());
    }
}
