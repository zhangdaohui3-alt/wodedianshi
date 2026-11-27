package com.wodedianshi.tv;

import org.junit.Test;
import static org.junit.Assert.*;

public class FullscreenMenuConfigTest {
    @Test public void defaultsAreTwoColumnAndHaveStableActions() {
        FullscreenMenuConfig config = FullscreenMenuConfig.defaults();
        assertTrue(config.getCategories().size() >= 2);
        assertEquals("favorite", config.findCategory("live").getItems().get(0).id);
    }

    @Test public void singleRefreshUpgradePreservesCustomMenuAndDoesNotDuplicate(){
        FullscreenMenuConfig config=FullscreenMenuConfig.defaults();
        assertFalse(config.addSingleChannelRefreshForUpgrade());
        assertTrue(config.removeItem("live","refresh_channel"));
        assertTrue(config.renameCategory("live","常用"));
        assertTrue(config.addSingleChannelRefreshForUpgrade());
        assertFalse(config.addSingleChannelRefreshForUpgrade());
        assertEquals("常用",config.findCategory("live").title);
        assertTrue(config.findCategory("live").getItems().stream().anyMatch(i->i.id.equals("refresh_channel")));
    }

    @Test public void addRemoveRenameAndReorderCategories() {
        FullscreenMenuConfig config = FullscreenMenuConfig.defaults();
        assertTrue(config.addCategory("custom", "我的快捷项"));
        assertTrue(config.renameCategory("custom", "常用功能"));
        assertTrue(config.moveCategory("custom", 0));
        assertEquals("custom", config.getCategories().get(0).id);
        assertTrue(config.removeCategory("custom"));
        assertFalse(config.removeCategory("missing"));
    }

    @Test public void addRemoveRenameAndReorderItems() {
        FullscreenMenuConfig config = FullscreenMenuConfig.defaults();
        assertTrue(config.addItem("live", "diagnostics", "播放诊断"));
        assertTrue(config.renameItem("live", "diagnostics", "诊断信息"));
        assertTrue(config.moveItem("live", "diagnostics", 0));
        assertEquals("diagnostics", config.findCategory("live").getItems().get(0).id);
        assertTrue(config.removeItem("live", "diagnostics"));
    }

    @Test public void rejectsDuplicateIdsAndInvalidLimits() {
        FullscreenMenuConfig config = FullscreenMenuConfig.defaults();
        assertFalse(config.addCategory("live", "重复"));
        assertFalse(config.addItem("live", "favorite", "重复"));
        assertFalse(config.addItem("missing", "x", "无效"));
        assertFalse(config.renameCategory("live", "  "));
    }

    @Test public void roundTripPreservesOrderAndChineseLabels() {
        FullscreenMenuConfig config = FullscreenMenuConfig.defaults();
        config.addCategory("custom", "我的菜单");
        config.addItem("custom", "my_action", "切换当前备用源");
        config.moveCategory("custom", 1);
        FullscreenMenuConfig restored = FullscreenMenuConfig.decode(config.encode());
        assertEquals("custom", restored.getCategories().get(1).id);
        assertEquals("我的菜单", restored.getCategories().get(1).title);
        assertEquals("切换当前备用源",
                restored.findCategory("custom").getItems().get(0).title);
    }

    @Test public void malformedOrUnknownVersionFallsBackToDefaults() {
        assertEquals("live", FullscreenMenuConfig.decode("bad").getCategories().get(0).id);
        assertEquals("live", FullscreenMenuConfig.decode(
                "MYTV_FULLSCREEN_MENU_V1\nC\tbroken").getCategories().get(0).id);
    }
}
