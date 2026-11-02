java                       package com.wodedianshi.tv;

import android.app.Activity;

import android.os.Bundle;

import android.graphics.Color;

import android.view.Gravity;

import android.view.KeyEvent;

import android.widget.LinearLayout;

import android.widget.TextView;

import android.widget.Toast;

public class MainActivity extends Activity {

    private LinearLayout channelList;

    private TextView status;

    private int selected = 0;

    private String[] channels = {

        "中央电视台 CCTV-1 综合",

        "中央电视台 CCTV-2 财经",

        "中央电视台 CCTV-4 中文国际",

        "中央电视台 CCTV-5 体育",

        "中央电视台 CCTV-新闻",

        "湖南卫视",

        "浙江卫视",

        "江苏卫视",

        "东方卫视",

        "北京卫视"

    };

    @Override

    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        showHome();

    }

    private void showHome() {

        LinearLayout root = new LinearLayout(this);

        root.setOrientation(LinearLayout.HORIZONTAL);

        root.setBackgroundColor(Color.rgb(18, 25, 42));

        root.setPadding(35, 25, 35, 25);

        channelList = new LinearLayout(this);

        channelList.setOrientation(LinearLayout.VERTICAL);

        LinearLayout left = new LinearLayout(this);

        left.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);

        title.setText("我的电视");

        title.setTextSize(30);

        title.setTextColor(Color.WHITE);

        left.addView(title);

        TextView subtitle = new TextView(this);

        subtitle.setText("电视直播 · 中文遥控器操作");

        subtitle.setTextSize(16);

        subtitle.setTextColor(Color.LTGRAY);

        left.addView(subtitle);

        for (int i = 0; i < channels.length; i++) {

            TextView item = new TextView(this);

            item.setText(channels[i]);

            item.setTextSize(21);

            item.setTextColor(Color.WHITE);

            item.setPadding(15, 12, 15, 12);

            channelList.addView(item);

        }

        left.addView(channelList);

        status = new TextView(this);

        status.setText("请选择频道，按遥控器确认键");

        status.setTextColor(Color.YELLOW);

        status.setTextSize(18);

        status.setGravity(Gravity.CENTER);

        root.addView(left, new LinearLayout.LayoutParams(0,

            LinearLayout.LayoutParams.MATCH_PARENT, 1));

        root.addView(status, new LinearLayout.LayoutParams(0,

            LinearLayout.LayoutParams.MATCH_PARENT, 1));

        setContentView(root);

        updateSelection();

    }

    private void updateSelection() {

        for (int i = 0; i < channelList.getChildCount(); i++) {

            TextView item = (TextView) channelList.getChildAt(i);

            item.setBackgroundColor(i == selected ?

                Color.rgb(35, 105, 190) : Color.TRANSPARENT);

        }

    }

    @Override

    public boolean onKeyDown(int keyCode, KeyEvent event) {

        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {

            selected = (selected + 1) % channels.length;

            updateSelection();

            return true;

        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {

            selected = (selected - 1 + channels.length) % channels.length;

            updateSelection();

            return true;

        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||

            keyCode == KeyEvent.KEYCODE_ENTER) {

            status.setText("已选择：" + channels[selected]

                + "\n播放源将在后续版本接入");

            return true;

        }

        return super.onKeyDown(keyCode, event);

    }

}