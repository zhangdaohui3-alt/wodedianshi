package com.wodedianshi.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Independent official-video-platform directory.
 * It only launches official apps or official HTTPS home pages; it never resolves or
 * extracts media streams and does not touch the live/VOD playback pipeline.
 */
public final class OfficialMovieNavigation {
    private static final String TAG = "OfficialMovieNav";

    private static final String[] NAMES = {
        "1905电影网", "爱奇艺", "腾讯视频", "优酷",
        "哔哩哔哩放映厅", "咪咕视频", "央视频"
    };
    private static final String[] DESCRIPTIONS = {
        "CCTV-6官方电影平台，大量国产老电影与经典译制片",
        "院线新片、国产电影、悬疑题材丰富",
        "好莱坞引进片与海外影片资源较丰富",
        "港片、怀旧老片与经典影视内容",
        "文艺片、纪录片、经典老片，支持弹幕",
        "免费老电影、港片及部分4K内容，权益以平台为准",
        "央视正版影片与节目，适合家庭观看；内容分级以平台为准"
    };
    private static final String[] URLS = {
        "https://www.1905.com/",
        "https://www.iqiyi.com/",
        "https://v.qq.com/",
        "https://youku.com/",
        "https://www.bilibili.com/",
        "https://www.miguvideo.com/",
        "https://www.yangshipin.cn/"
    };
    // Known Android package IDs only. If a package is absent, use the official HTTPS site.
    // Several providers publish different phone/TV package IDs, so try their known variants.
    private static final String[][] PACKAGES = {
        {},
        {"com.qiyi.video.pad", "com.qiyi.video"},
        {"com.tencent.qqlivepad", "com.tencent.qqlive"},
        {"com.youku.tv", "com.youku.phone"},
        {"com.bilibili.tv", "tv.danmaku.bili"},
        {"com.cmcc.cmvideo"},
        {"com.cctv.yangshipin.app.androidp"}
    };

    private OfficialMovieNavigation() {}

    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        final int density = activity.getResources().getDisplayMetrics().densityDpi;
        final float scale = density / 160f;
        final int screenWidthDp = (int)(activity.getResources().getDisplayMetrics().widthPixels / scale);
        final int columns = screenWidthDp >= 1000 ? 3 : 2;

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 18), dp(activity, 12), dp(activity, 18), dp(activity, 14));
        root.setBackgroundColor(Color.rgb(8, 15, 27));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText("正版影视导航");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(null, Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(activity);
        subtitle.setText("选择正规平台，使用平台官方客户端或官方网站观看");
        subtitle.setTextColor(Color.rgb(160, 181, 207));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitle.setPadding(0, dp(activity, 4), 0, 0);
        heading.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1f));

        Button close = new Button(activity);
        close.setText("关闭");
        close.setAllCaps(false);
        close.setTextColor(Color.WHITE);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        close.setFocusable(true);
        close.setOnClickListener(v -> {
            View decor = v.getRootView();
            Object tag = decor.getTag();
            if (tag instanceof android.app.Dialog) ((android.app.Dialog) tag).dismiss();
        });
        close.setBackground(cardBackground(Color.rgb(35, 49, 68), false, activity));
        header.addView(close, new LinearLayout.LayoutParams(dp(activity, 76), dp(activity, 42)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(activity, 14), 0, dp(activity, 4));

        GridLayout grid = new GridLayout(activity);
        grid.setColumnCount(columns);
        grid.setUseDefaultMargins(false);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);

        for (int i = 0; i < NAMES.length; i++) {
            final int index = i;
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12));
            card.setFocusable(true);
            card.setFocusableInTouchMode(true);
            card.setClickable(true);
            card.setBackground(cardBackground(Color.rgb(23, 36, 54), false, activity));

            TextView name = new TextView(activity);
            name.setText(NAMES[i]);
            name.setTextColor(Color.WHITE);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            name.setTypeface(null, Typeface.BOLD);
            name.setGravity(Gravity.CENTER_VERTICAL);
            card.addView(name, new LinearLayout.LayoutParams(-1, -2));

            TextView description = new TextView(activity);
            description.setText(DESCRIPTIONS[i]);
            description.setTextColor(Color.rgb(178, 196, 219));
            description.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            description.setLineSpacing(dp(activity, 2), 1f);
            description.setPadding(0, dp(activity, 8), 0, 0);
            card.addView(description, new LinearLayout.LayoutParams(-1, -2));

            card.setOnFocusChangeListener((v, focused) -> {
                v.setBackground(cardBackground(
                    focused ? Color.rgb(27, 76, 119) : Color.rgb(23, 36, 54),
                    focused, activity));
                v.setElevation(focused ? dp(activity, 8) : dp(activity, 1));
                v.setScaleX(focused ? 1.025f : 1f);
                v.setScaleY(focused ? 1.025f : 1f);
                name.setTextColor(focused ? Color.rgb(255, 235, 165) : Color.WHITE);
                description.setTextColor(focused ? Color.WHITE : Color.rgb(178, 196, 219));
            });
            card.setOnHoverListener((v, event) -> {
                int action = event.getActionMasked();
                if (action == android.view.MotionEvent.ACTION_HOVER_ENTER
                    || action == android.view.MotionEvent.ACTION_HOVER_MOVE) {
                    if (!v.hasFocus()) {
                        v.setBackground(cardBackground(Color.rgb(32, 69, 101), true, activity));
                        v.setElevation(dp(activity, 7));
                    }
                } else if (action == android.view.MotionEvent.ACTION_HOVER_EXIT && !v.hasFocus()) {
                    v.setBackground(cardBackground(Color.rgb(23, 36, 54), false, activity));
                    v.setElevation(dp(activity, 1));
                }
                return false;
            });
            card.setOnClickListener(v -> openPlatform(activity, index));

            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = dp(activity, 132);
            params.columnSpec = GridLayout.spec(i % columns, 1, 1f);
            params.rowSpec = GridLayout.spec(i / columns);
            int margin = dp(activity, 6);
            params.setMargins(margin, margin, margin, margin);
            grid.addView(card, params);
        }

        scroll.addView(grid, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView footnote = new TextView(activity);
        footnote.setText("说明：平台片库、会员权益、广告及地区可用性以各平台实际提供为准。本页面仅提供官方入口，不解析或提取视频流。");
        footnote.setTextColor(Color.rgb(126, 148, 174));
        footnote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        footnote.setPadding(dp(activity, 2), dp(activity, 6), dp(activity, 2), 0);
        root.addView(footnote, new LinearLayout.LayoutParams(-1, -2));

        final android.app.Dialog dialog = new android.app.Dialog(activity,
            android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(root);
        dialog.setOnShowListener(d -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setLayout(-1, -1);
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.black);
                dialog.getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
            if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
        });
        dialog.show();
        dialog.getWindow().setLayout(-1, -1);
        root.getRootView().setTag(dialog);
        Log.i(TAG, "navigation_open columns=" + columns + " platform_count=" + NAMES.length);
    }

    private static void openPlatform(Activity activity, int index) {
        if (index < 0 || index >= NAMES.length) return;
        for (String packageName : PACKAGES[index]) {
            try {
                Intent launch = activity.getPackageManager().getLaunchIntentForPackage(packageName);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(launch);
                    Log.i(TAG, "launch_app platform=" + index + " package=" + packageName);
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "app_launch_failed platform=" + index + " package=" + packageName, e);
            }
        }
        try {
            Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse(URLS[index]));
            activity.startActivity(web);
            Log.i(TAG, "open_official_https platform=" + index);
        } catch (Exception e) {
            Log.e(TAG, "official_url_open_failed platform=" + index, e);
            android.widget.Toast.makeText(activity, "无法打开官方入口，请检查网络或浏览器", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private static GradientDrawable cardBackground(int color, boolean focused, Activity activity) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(activity, 10));
        bg.setStroke(dp(activity, focused ? 3 : 1),
            focused ? Color.rgb(255, 205, 55) : Color.rgb(62, 82, 107));
        return bg;
    }

    private static int dp(Activity activity, int value) {
        return (int)(value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
