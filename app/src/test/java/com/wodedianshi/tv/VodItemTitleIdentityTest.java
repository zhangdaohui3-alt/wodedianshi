package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class VodItemTitleIdentityTest {
    @Test public void keepsDifferentReleaseYearsSeparate() {
        assertNotEquals(VodItemTitleIdentity.key("The Thing (1982)"),
                VodItemTitleIdentity.key("The Thing (2011)"));
    }

    @Test public void mergesPunctuationAndQualityVariantsOfSameYear() {
        assertEquals(VodItemTitleIdentity.key("Spider-Man: Homecoming (2017) 1080P"),
                VodItemTitleIdentity.key("Spider Man Homecoming [高清] 2017"));
    }

    @Test public void yearlessTitlesUseAnExplicitUnknownYearBucket() {
        assertNotEquals(VodItemTitleIdentity.key("The Thing"),
                VodItemTitleIdentity.key("The Thing (1982)"));
    }

    @Test public void keepsChineseRemakesWithDifferentYearsSeparate() {
        assertNotEquals(VodItemTitleIdentity.key("倩女幽魂（1987）"),
                VodItemTitleIdentity.key("倩女幽魂（2011）"));
    }

    @Test public void emptyOrNullTitlesDoNotCreateSharedDuplicateKey() {
        assertEquals("", VodItemTitleIdentity.key(null));
        assertEquals("", VodItemTitleIdentity.key("  "));
    }
}
