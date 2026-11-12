# Playback engine research notes

Date: 2026-11-12

Scope: Android TV boxes, especially the project's ARMv7 / Android 9 target. These notes guide implementation; they are not claims that a decoder is compatible with every device.

## Findings from primary sources

### 1. MPV is not an AAR drop-in

The upstream mpv-android project explicitly says it is an application, not an importable Android library. A real integration needs the MPVLib JNI wrapper, a Surface-owning view, and the native player/JNI code.

- https://github.com/mpv-android/mpv-android
- https://github.com/mpv-android/mpv-android/blob/fdf74f6830c47dbaa8a22ac79726e8303f1db5af/app/src/main/java/is/xyz/mpv/MPVLib.kt
- https://github.com/mpv-android/mpv-android/blob/fdf74f6830c47dbaa8a22ac79726e8303f1db5af/app/src/main/java/is/xyz/mpv/BaseMPVView.kt

The pinned upstream BaseMPVView currently documents a possible race in surface teardown: changing `vo` and then calling `detachSurface()` may not wait for the video output to finish using the Surface. The JNI implementation also holds a global Surface reference. Do not copy this lifecycle unchanged into the app. Before integration, make teardown wait for native player destruction to complete before releasing the Surface reference, and test repeated create/destroy and engine switching.

### 2. MPV hardware decode is not a single mode

The upstream Android view uses `vo=gpu`, `gpu-context=android`, and hardware-decoder options that include `mediacodec` and `mediacodec-copy`. MPV's own manual describes a separate `mediacodec_embed` output mode, which renders directly to an Android Surface but lacks some GPU-output features such as OSD, subtitles, and video filters.

- https://mpv.io/manual/stable/#video-output-drivers
- https://github.com/mpv-android/mpv-android/blob/fdf74f6830c47dbaa8a22ac79726e8303f1db5af/app/src/main/java/is/xyz/mpv/MPVView.kt

Therefore the integration must treat decoder mode and video-output mode as separate compatibility dimensions. Do not assume that switching from Media3 to MPV automatically avoids a vendor MediaCodec bug.

The MPV manual deliberately disables hardware decoding by default for reliability, and says to try disabling it first when glitches or discoloration occur. The mpv-android phone app overrides that default by enabling hardware decoding, but we should not copy that phone-oriented preference blindly to this TV box. The MPV manual also warns that Android `mediacodec` can force RGB conversion and reduce output bit depth, while `mediacodec-copy` copies frames back to system memory. The direct `mediacodec_embed` path can remove some GPU work but loses subtitles, OSD, and filters. For this app, test four explicit candidates rather than inheriting the upstream phone default: GPU + `mediacodec`; GPU + `mediacodec-copy`; GPU + software decode; and direct `mediacodec_embed` + `mediacodec`. Record the active decoder and output path for each. The runtime policy now represents MediaCodec direct and copy as separate decoder candidates, and GPU versus `mediacodec_embed` as separate output candidates. Do not assume an engine's default hardware mode is best for this TV box.

### 3. TV-box hardware-decoder failures are documented upstream

There are public MPV Android reports of green/corrupted frames on Android TV boxes using `mediacodec-copy`, black screens and UI freezes with hardware decoding, and decoder failures on Android 9 devices. These reports are device-specific and do not prove the same fault on this project's Dangbei box, but they show why a universal hardware-decoding default would be unsafe.

- https://github.com/mpv-android/mpv-android/issues/1088
- https://github.com/mpv-android/mpv-android/issues/1012
- https://github.com/mpv-android/mpv-android/issues/733

The upstream 2026-09-17 build notes include hardware-decoder fixes, so the project pins that matching source revision and its dependency revisions rather than old binaries:
https://github.com/mpv-android/mpv-android/releases

### 4. Media3 diagnostic and queueing guidance

Media3 recommends checking track support and decoder logs when playback fails without a clear error. It also documents asynchronous MediaCodec buffer queueing as an opt-in optimization for API 23+ and specifically notes that Android 6 through Android 11 devices with dropped frames or audio underruns may benefit from testing it.

- https://developer.android.com/media/media3/exoplayer/troubleshooting
- https://developer.android.com/media/media3/exoplayer/customization#enabling_asynchronous_buffer_queueing
- https://developer.android.com/reference/androidx/media3/exoplayer/DecoderCounters

The target Android 9 box is in that OS range, but its previous hard freeze makes it inappropriate to enable asynchronous queueing blindly. Keep the current conservative path as baseline; if needed, compare an explicitly opt-in experimental build while recording decoder name, init/release counts, dropped frames, buffering, and first rendered frame.

## Licensing guardrail

Do not label the whole native stack simply “LGPLv3” based only on FFmpeg flags.

- FFmpeg is being configured with `--disable-gpl --enable-version3`, so its generated configuration must prove GPL components are disabled and version-3 licensing is selected.
- The pinned `mpv-android` wrapper repository itself is MIT-licensed. Preserve its copyright/license notice when adapting `MPVLib`, the Surface view, or JNI glue; this wrapper license does not change the licenses of libmpv or its linked dependencies.\n- mpv uses `-Dgpl=false`; upstream describes the resulting mpv program as LGPLv2.1-or-later, and explicitly warns that the switch alone does not grant a license. Source-file and linked-library licenses still need review.
- Keep the exact pinned revisions and generated configuration files in the audit artifact. Do not ship the native engine until the complete dependency/license review is recorded.

Primary source: https://github.com/mpv-player/mpv/blob/master/Copyright

## Implementation decisions

1. Media3 remains the primary player.
2. Register MPV only after ARMv7 native loading, JNI initialization, Surface lifecycle, and teardown tests pass.
3. Treat `mediacodec`, `mediacodec-copy`, software decoding, and video-output mode as independently testable strategies.
4. Transport/source failures rotate to a distinct backup URL; decoder/render failures may try a verified engine/decoder candidate; configuration errors do not trigger blind switching.
5. Never allow two playback engines to own the display Surface simultaneously.
6. Never make the user repeatedly reproduce a system-level hard freeze. Use automated build checks and diagnostic evidence first.


### 5. LibVLC is a viable independent fallback candidate

VideoLAN documents LibVLC as an embeddable Android engine and publishes an Android AAR; the official Android project supports Android TV. The engine itself is LGPLv2-or-later, while the complete VLC Android application and some optional plugins have different, more restrictive licensing. The exact Maven artifact and plugin set must therefore be audited before inclusion.

- https://github.com/videolan/vlc-android
- https://github.com/videolan/vlc/blob/master/doc/standalone/libvlc.md
- https://github.com/videolan/vlc-android/blob/master/README.md

The project's separate LibVLC 3.7.7 ARMv7 prototype already demonstrated successful packaging of a 47.37 MB ARMv7-only APK, but it has not yet been connected to My TV's playback UI or validated on the Dangbei device. Its standard Android video-output API exposes explicit attach/detach operations, which may be easier to integrate than copying MPV's JNI singleton, but it still needs the same single-owner Surface gate and lifecycle tests.

### Engine comparison for this project

| Engine | Strength | Main risk / next proof |
|---|---|---|
| Media3 | Already integrated; good HLS, track diagnostics, and Android platform integration | Device/vendor decoder behavior remains a risk; preserve as primary baseline |
| MPV | Broad FFmpeg demux/codec coverage; flexible hardware/software decoder and video-output modes | JNI and Surface teardown are subtle; prove native teardown and ARMv7 behavior before app integration |
| LibVLC | Official Android AAR and embeddable API; Android TV support; broad protocols/codecs | Larger native package; exact plugin/license set and ARMv7 runtime behavior need validation |

Do not choose an engine based on feature lists alone. Compare them with the same authorized H.264/AAC HLS test streams, identical network conditions, and the same device. Record time-to-first-frame, sustained playback, decoder name, dropped frames, audio continuity, source switch recovery, and whether teardown/reopen survives repeated cycles.


### 6. SoC capability is not the same as app ABI or decoder reliability

MediaTek's MT9669 product specification lists a 64-bit Cortex-A73 CPU and hardware decoding for H.264, HEVC, VP9, and AV1. The Dangbei D6X Plus public specification also identifies MT9669 and Android 9. However, Android applications must package native libraries for the ABIs actually exposed by the installed system: this project's device inventory reports only `armeabi-v7a` and `armeabi`, so the native test package remains ARMv7 even if the silicon CPU is 64-bit.

- https://www.mediatek.com/products/commercial-displays/mt9669
- https://detail.zol.com.cn/2134/2133021/param.shtml

The previously observed H.264 720p stream is not inherently outside the published decoder capabilities. That does not prove the stream is valid or the vendor codec is bug-free; it means we should separately test source integrity, MediaCodec output, MPV's alternative output/decoder modes, and Surface teardown rather than labelling H.264 unsupported.
