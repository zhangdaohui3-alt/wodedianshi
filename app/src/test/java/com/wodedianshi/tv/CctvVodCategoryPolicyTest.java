package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class CctvVodCategoryPolicyTest {
    @Test public void documentaryCategoriesUseDocumentaryCatalogue() {
        assertEquals("CHAL1460955924871139", CctvVodCategoryPolicy.channelIdFor("中国纪录片"));
        assertEquals("CHAL1460955924871139", CctvVodCategoryPolicy.channelIdFor("中文纪录片"));
    }

    @Test public void animationCategoriesUseAnimationCatalogue() {
        assertEquals("CHAL1460955899450127", CctvVodCategoryPolicy.channelIdFor("中国动画"));
        assertEquals("CHAL1460955899450127", CctvVodCategoryPolicy.channelIdFor("中文动画"));
    }

    @Test public void unrelatedCategoriesDoNotReuseDocumentaryCatalogue() {
        assertNull(CctvVodCategoryPolicy.channelIdFor("多源精选"));
        assertNull(CctvVodCategoryPolicy.channelIdFor("精选电影"));
        assertNull(CctvVodCategoryPolicy.channelIdFor("中国电影"));
        assertNull(CctvVodCategoryPolicy.channelIdFor(null));
    }
}
