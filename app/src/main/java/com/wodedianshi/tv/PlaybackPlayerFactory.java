package com.wodedianshi.tv;

import android.content.Context;

import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

/**
 * Centralizes player construction while keeping live and VOD network/buffer
 * policies intentionally separate. Playback listeners and lifecycle remain
 * owned by MainActivity.
 */
@UnstableApi
public final class PlaybackPlayerFactory {
    private PlaybackPlayerFactory() {}

    /**
     * Builds the same correctly typed MediaItem for both live and VOD.
     * Unknown/progressive containers deliberately retain Media3 extractor
     * sniffing; only unambiguous streaming formats receive an explicit MIME.
     */
    public static MediaItem buildMediaItem(String uri, String mediaId) {
        MediaItem.Builder builder = new MediaItem.Builder().setUri(uri);
        String mimeType = MediaFormatPolicy.inferMimeType(uri);
        if (mimeType != null) builder.setMimeType(mimeType);
        if (mediaId != null && !mediaId.trim().isEmpty()) builder.setMediaId(mediaId);
        return builder.build();
    }

    private static DefaultRenderersFactory renderers(Context context, boolean decoderFallback) {
        return new DefaultRenderersFactory(context)
                .setEnableDecoderFallback(decoderFallback);
    }

    public static ExoPlayer createLivePlayer(Context context, boolean decoderFallback) {
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36 MyTV/1.3")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(12000, 30000, 1500, 3000)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build();

        return new ExoPlayer.Builder(context, renderers(context, decoderFallback))
                .setLoadControl(loadControl)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(context)
                        .setDataSourceFactory(httpFactory))
                .build();
    }

    public static ExoPlayer createVodPlayer(Context context, boolean decoderFallback) {
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 MyTV/1.17")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000);

        return new ExoPlayer.Builder(context, renderers(context, decoderFallback))
                .setMediaSourceFactory(new DefaultMediaSourceFactory(context)
                        .setDataSourceFactory(httpFactory))
                .build();
    }
}
