package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class VodCategoryMetadataPolicyTest {
    @Test public void keepsSparseMetadataInsteadOfOverFiltering() {
        assertTrue(VodCategoryMetadataPolicy.accepts("中国纪录片", "一段中国影像", "", "", ""));
        assertTrue(VodCategoryMetadataPolicy.accepts("中国历史影像", "中国历史资料", "", "", ""));
        assertTrue(VodCategoryMetadataPolicy.accepts("中国经典影像", "经典华语作品", "", "", ""));
    }

    @Test public void rejectsAnimationFromAllThreeShelves() {
        for (String category : new String[]{"中国历史影像", "中国纪录片", "中国经典影像"}) {
            assertFalse(VodCategoryMetadataPolicy.accepts(category,
                    "Chinese animated film", "", "animation", ""));
        }
    }

    @Test public void routesNewsreelsAwayFromGeneralDocumentariesAndClassics() {
        assertFalse(VodCategoryMetadataPolicy.accepts("中国纪录片",
                "China newsreel 1965", "documentary footage", "newsreel", ""));
        assertFalse(VodCategoryMetadataPolicy.accepts("中国经典影像",
                "China newsreel 1965", "documentary footage", "newsreel", ""));
        assertTrue(VodCategoryMetadataPolicy.accepts("中国历史影像",
                "China newsreel 1965", "documentary footage", "newsreel", ""));
    }

    @Test public void keepsGeneralDocumentaryButRejectsClearlyFictionalFeature() {
        assertTrue(VodCategoryMetadataPolicy.accepts("中国纪录片",
                "Chinese river documentary", "nature study", "documentary", ""));
        assertFalse(VodCategoryMetadataPolicy.accepts("中国纪录片",
                "Chinese drama", "", "feature film", "classic films"));
    }

    @Test public void classicShelfRejectsDocumentaryButKeepsFeatureFilm() {
        assertFalse(VodCategoryMetadataPolicy.accepts("中国经典影像",
                "A classic documentary", "", "documentary", ""));
        assertTrue(VodCategoryMetadataPolicy.accepts("中国经典影像",
                "A classic Chinese film", "", "feature film", "classic cinema"));
    }

    @Test public void unrelatedCategoriesAreUnaffected() {
        assertTrue(VodCategoryMetadataPolicy.accepts("中国动画",
                "animated film", "", "animation", ""));
    }
}
