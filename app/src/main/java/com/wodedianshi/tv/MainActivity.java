package com.wodedianshi.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Color;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;

public class MainActivity extends Activity {
    private ExoPlayer player;
    private PlayerView playerView;
    private LinearLayout channelList;
    private TextView status;
    private final ArrayList<Channel> channels = new ArrayList<>();
    private int selected = 0;

    private static class Channel {
        String name, url;
        Channel(String name, String url) { this.name = name; this.url = url; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        channels.add(new Channel("添加自己的直播源", ""));
        buildScreen();
    }

    private int dp(int n) {
        return (int)(n * getResources().getDisplayMetrics().density);
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(14));
        root.setBackgroundColor(Color.rgb(12, 18, 32));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("我的电视");
        title.setTextColor(Color.WHITE);
        title.setTextSize(27);
        left.addView(title);

        Button add = new Button(this);
        add.setText("＋ 添加直播源");
        add.setOnClickListener(v -> showAddDialog());
        left.addView(add);

        channelList = new LinearLayout(this);
        channelList.setOrientation(LinearLayout.VERTICAL);
        left.addView(channelList);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        status.setText("请选择频道，或添加 M3U8 / HLS 地址");
        status.setTextColor(Color.WHITE);
        status.setTextSize(16);
        right.addView(status);

        playerView = new PlayerView(this);
        playerView.setUseController(true);
        right.addView(playerView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        root.addView(left, new LinearLayout.LayoutParams(dp(270),
            LinearLayout.LayoutParams.MATCH_PARENT));
        root.addView(right, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.MATCH_PARENT, 1));
        setContentView(root);
        renderChannels();
    }

    private void renderChannels() {
        channelList.removeAllViews();
        for (int i = 0; i < channels.size(); i++) {
            final int index = i;
            Button b = new Button(this);
            b.setText(channels.get(i).name);
            b.setTextSize(15);
            b.setOnClickListener(v -> {
                selected = index;
                if (channels.get(index).url.isEmpty()) showAddDialog();
                else play(index);
                renderChannels();
            });
            if (i == selected) b.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Color.rgb(40, 105, 185)));
            channelList.addView(b);
        }
    }

    private void showAddDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setPadding(dp(16), dp(8), dp(16), 0);
        form.setOrientation(LinearLayout.VERTICAL);
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("频道名称，例如 CCTV-1");
        EditText url = new EditText(this);
        url.setSingleLine(true);
        url.setHint("直播地址（http(s)://...m3u8）");
        url.setInputType( android.text.InputType.TYPE_CLASS_TEXT |
            android.text.InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(name);
        form.addView(url);
        new AlertDialog.Builder(this).setTitle("添加直播频道")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存并播放", (d, w) -> {
                String n = name.getText().toString().trim();
                String u = url.getText().toString().trim();
                if (n.isEmpty()) n = "新频道";
                if (!(u.startsWith("https://") || u.startsWith("http://"))) {
                    Toast.makeText(this, "请输入有效的 http/https 地址", Toast.LENGTH_LONG).show();
                    return;
                }
                channels.add(new Channel(n, u));
                selected = channels.size() - 1;
                renderChannels();
                play(selected);
            }).show();
    }

    private void play(int index) {
        Channel c = channels.get(index);
        status.setText("正在加载：" + c.name);
        if (player == null) {
            player = new ExoPlayer.Builder(this).build();
            playerView.setPlayer(player);
            player.addListener(new Player.Listener() {
                @Override public void onPlayerError(PlaybackException error) {
                    status.setText("播放失败：" + error.getErrorCodeName() +
                        "\n请检查地址、网络、地区限制或播放权限。");
                }
                @Override public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY)
                        status.setText("正在播放：" + channels.get(selected).name);
                }
            });
        }
        player.setMediaItem(MediaItem.fromUri(c.url));
        player.prepare();
        player.play();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !channels.isEmpty()) {
            selected = Math.min(selected + 1, channels.size() - 1);
            renderChannels(); return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && !channels.isEmpty()) {
            selected = Math.max(0, selected - 1);
            renderChannels(); return true;
        }
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            && selected < channels.size()) {
            if (channels.get(selected).url.isEmpty()) showAddDialog();
            else play(selected);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onStop() {
        super.onStop();
        if (player != null) { player.release(); player = null; }
    }
}