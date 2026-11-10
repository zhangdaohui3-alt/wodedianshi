package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class VodTitleRecordTest {
    @Test public void metadataRecordDoesNotPretendArticleIsPlayable() {
        VodTitleRecord record = new VodTitleRecord("zh:1", "霸王别姬", "",
                "剧情简介", "中国", "Wikipedia (zh)",
                "https://zh.wikipedia.org/?curid=1", 0);
        assertTrue(record.hasMetadataPage());
        assertEquals("霸王别姬", record.title);
        assertEquals(0, record.year);
    }

    @Test public void rejectsNonHttpsMetadataPage() {
        VodTitleRecord record = new VodTitleRecord("x", "Film", "", "",
                "", "catalogue", "http://example.com", -2);
        assertFalse(record.hasMetadataPage());
        assertEquals(0, record.year);
    }

    @Test public void nullFieldsAreSafe() {
        VodTitleRecord record = new VodTitleRecord(null, null, null, null,
                null, null, null, 0);
        assertEquals("", record.id);
        assertEquals("", record.title);
        assertFalse(record.hasMetadataPage());
    }
}
