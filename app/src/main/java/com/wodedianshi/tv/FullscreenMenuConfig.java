package com.wodedianshi.tv;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * User-editable two-column fullscreen remote menu.
 *
 * The model contains stable action IDs rather than localized labels, so labels
 * may change without losing the user's menu arrangement. UI code owns execution
 * of action IDs; this class owns ordering and safe persistence.
 */
public final class FullscreenMenuConfig {
    public static final int MAX_CATEGORIES = 12;
    public static final int MAX_ITEMS_PER_CATEGORY = 24;
    private static final String HEADER = "MYTV_FULLSCREEN_MENU_V1";

    public static final class Item {
        public final String id;
        public String title;
        public Item(String id, String title) {
            this.id = requireText(id, "item id");
            this.title = requireText(title, "item title");
        }
    }

    public static final class Category {
        public final String id;
        public String title;
        private final ArrayList<Item> items = new ArrayList<>();
        public Category(String id, String title) {
            this.id = requireText(id, "category id");
            this.title = requireText(title, "category title");
        }
        public List<Item> getItems() {
            return Collections.unmodifiableList(items);
        }
    }

    private final ArrayList<Category> categories = new ArrayList<>();

    public static FullscreenMenuConfig defaults() {
        FullscreenMenuConfig config = new FullscreenMenuConfig();
        Category live = new Category("live", "直播操作");
        live.items.add(new Item("favorite", "收藏当前频道"));
        live.items.add(new Item("sources", "选择备用播放源"));
        live.items.add(new Item("refresh_channel", "刷新当前频道"));
        live.items.add(new Item("channel_list", "打开频道列表"));
        Category picture = new Category("picture", "画面与声音");
        picture.items.add(new Item("aspect", "画面比例"));
        picture.items.add(new Item("decoder", "解码与声音设置"));
        Category manage = new Category("manage", "频道管理");
        manage.items.add(new Item("category", "频道分类"));
        manage.items.add(new Item("refresh", "更新频道目录"));
        Category vod = new Category("vod", "点播操作");
        vod.items.add(new Item("vod_controls", "播放控制"));
        vod.items.add(new Item("vod_catalogue", "返回点播目录"));
        config.categories.add(live);
        config.categories.add(picture);
        config.categories.add(manage);
        config.categories.add(vod);
        return config;
    }

    /** One-time upgrade for previously saved custom menus; never reset their
     * order or put a live operation into a VOD-only category. */
    public boolean addSingleChannelRefreshForUpgrade(){
        if(findItem("refresh_channel")!=null)return false;
        if(addItem("live","refresh_channel","刷新当前频道"))return true;
        if(addItem("manage","refresh_channel","刷新当前频道"))return true;
        for(Category category:categories)
            if(!"vod".equals(category.id)&&addItem(category.id,"refresh_channel","刷新当前频道"))return true;
        if(addCategory("live","直播操作"))return addItem("live","refresh_channel","刷新当前频道");
        return false;
    }

    public List<Category> getCategories() {
        return Collections.unmodifiableList(categories);
    }

    public Category findCategory(String id) {
        for (Category category : categories) if (category.id.equals(id)) return category;
        return null;
    }

    public boolean addCategory(String id, String title) {
        if (categories.size() >= MAX_CATEGORIES || findCategory(id) != null) return false;
        try { categories.add(new Category(id, title)); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    public boolean renameCategory(String id, String title) {
        Category category = findCategory(id);
        if (category == null || !isText(title)) return false;
        category.title = title.trim();
        return true;
    }

    public boolean removeCategory(String id) {
        for (int i = 0; i < categories.size(); i++) {
            if (categories.get(i).id.equals(id)) { categories.remove(i); return true; }
        }
        return false;
    }

    public boolean moveCategory(String id, int newIndex) {
        int oldIndex = indexOfCategory(id);
        if (oldIndex < 0 || newIndex < 0 || newIndex >= categories.size()) return false;
        Category category = categories.remove(oldIndex);
        categories.add(newIndex, category);
        return true;
    }

    public boolean addItem(String categoryId, String itemId, String title) {
        Category category = findCategory(categoryId);
        if (category == null || category.items.size() >= MAX_ITEMS_PER_CATEGORY
                || findItem(itemId) != null) return false;
        try { category.items.add(new Item(itemId, title)); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    public boolean renameItem(String categoryId, String itemId, String title) {
        Category category = findCategory(categoryId);
        if (category == null || !isText(title)) return false;
        for (Item item : category.items) if (item.id.equals(itemId)) {
            item.title = title.trim(); return true;
        }
        return false;
    }

    public boolean removeItem(String categoryId, String itemId) {
        Category category = findCategory(categoryId);
        if (category == null) return false;
        for (int i = 0; i < category.items.size(); i++) {
            if (category.items.get(i).id.equals(itemId)) {
                category.items.remove(i); return true;
            }
        }
        return false;
    }

    public boolean moveItem(String categoryId, String itemId, int newIndex) {
        Category category = findCategory(categoryId);
        if (category == null) return false;
        int oldIndex = -1;
        for (int i = 0; i < category.items.size(); i++)
            if (category.items.get(i).id.equals(itemId)) { oldIndex = i; break; }
        if (oldIndex < 0 || newIndex < 0 || newIndex >= category.items.size()) return false;
        Item item = category.items.remove(oldIndex);
        category.items.add(newIndex, item);
        return true;
    }

    /** Stable, line-oriented format; labels are URL-escaped and never parsed as code. */
    public String encode() {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (Category category : categories) {
            out.append("C\t").append(escape(category.id)).append('\t')
                    .append(escape(category.title)).append('\n');
            for (Item item : category.items) {
                out.append("I\t").append(escape(item.id)).append('\t')
                        .append(escape(item.title)).append('\n');
            }
            out.append("E\n");
        }
        return out.toString();
    }

    public static FullscreenMenuConfig decode(String encoded) {
        if (encoded == null || !encoded.startsWith(HEADER + "\n"))
            return defaults();
        FullscreenMenuConfig result = new FullscreenMenuConfig();
        Category current = null;
        try {
            String[] lines = encoded.split("\n");
            for (int i = 1; i < lines.length; i++) {
                String[] parts = lines[i].split("\t", -1);
                if (parts.length == 1 && "E".equals(parts[0])) { current = null; continue; }
                if (parts.length != 3) return defaults();
                if ("C".equals(parts[0])) {
                    if (current != null || result.categories.size() >= MAX_CATEGORIES)
                        return defaults();
                    current = new Category(unescape(parts[1]), unescape(parts[2]));
                    if (result.findCategory(current.id) != null) return defaults();
                    result.categories.add(current);
                } else if ("I".equals(parts[0])) {
                    if (current == null || current.items.size() >= MAX_ITEMS_PER_CATEGORY)
                        return defaults();
                    String id = unescape(parts[1]);
                    if (result.findItem(id) != null) return defaults();
                    current.items.add(new Item(id, unescape(parts[2])));
                } else return defaults();
            }
            if (current != null || result.categories.isEmpty()) return defaults();
            return result;
        } catch (RuntimeException malformed) {
            return defaults();
        }
    }

    private Item findItem(String id) {
        for (Category category : categories)
            for (Item item : category.items) if (item.id.equals(id)) return item;
        return null;
    }
    private int indexOfCategory(String id) {
        for (int i = 0; i < categories.size(); i++)
            if (categories.get(i).id.equals(id)) return i;
        return -1;
    }
    private static String escape(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private static String unescape(String value) {
        try { return URLDecoder.decode(value, "UTF-8"); }
        catch (Exception malformed) { throw new IllegalArgumentException(malformed); }
    }
    private static boolean isText(String value) {
        return value != null && !value.trim().isEmpty() && value.trim().length() <= 80;
    }
    private static String requireText(String value, String name) {
        if (!isText(value)) throw new IllegalArgumentException(name + " is empty or too long");
        return value.trim();
    }
}
