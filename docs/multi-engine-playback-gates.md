# Multi-engine playback implementation gates

Status: engine/decoder candidate policy and Media3 error classifier are implemented and unit-tested on the research branch. LibVLC 3.7.7 ARMv7 packaging is build-verified in an isolated prototype. No secondary engine is integrated or enabled in the app yet.

## Device-first constraints

The first target is Dangbei DBD6X Plus: Android 9 / API 28, MediaTek m7642, 32-bit ARMv7 (armeabi-v7a, armeabi). Any native engine must contain a loadable ARMv7 build. ARM64-only is an automatic rejection for this target. Keep minSdk 23 unless a measured dependency forces a documented change.

The reported failure (video artifacts, then all remote keys unresponsive, then black screen until power removal) is more severe than an ordinary app playback error. A process watchdog cannot promise recovery if the vendor codec/driver or whole Android system hangs. Do not repeatedly reproduce this on the user's box.

## Engine and decoder model

- Engine owns demux, transport, buffering, rendering and lifecycle.
- Decoder backend may be platform MediaCodec hardware or an engine's supported software path.
- Source means a distinct authorized URL for the same channel/program.
- Recovery chooses URL retry, decoder path, another engine, or terminal failure.

Do not hard-code one universal fallback order. Media3 remains primary. A verified runtime registry supplies the remaining candidates in a device/format-specific order from MPV, IJK and LibVLC, including separate HARDWARE and SOFTWARE decoder modes. For a suspected MediaCodec runtime failure, the registry should prioritize a software-decoder candidate rather than sending the same stream straight back through the same vendor hardware decoder.

A larger APK is acceptable when the additional engine demonstrably improves playback. Do not optimize size at the expense of a working ARMv7 engine; avoid unused ABIs/codecs.

## Public upstream evidence and gates

### IJK

- The upstream Bilibili IJKPlayer's published Android artifacts are version 0.8.8 from 2018, built with NDK r10e / old Gradle tooling and FFmpeg 3.x-era sources. The published `ijkplayer-armv7a` artifact is listed as LGPL-2.1, but its age and historical Bintray/JCenter publication path make it unsuitable as an automatic drop-in for this AGP 8.13.2 / Java 17 app.
- The community `brainShake/ijkplayer-android` repo has a real `armeabi-v7a` module and source-built `libijkplayer.so` / `libijksdl.so`, but the inspected repository tree has no top-level IJK/FFmpeg license bundle; it carries prebuilt `libijkffmpeg.so` and no clear per-binary source/config provenance. Its README's example install command is `all64Debug`, not proof of a validated ARMv7 package. Do not redistribute its opaque native binaries as-is.
- Preferred route: build from a pinned, audited source revision, restore every upstream license/notice, verify FFmpeg configure flags and corresponding source, and require an ARMv7 artifact in CI before any app integration.

### LibVLC

- VideoLAN's official Android repository publishes LibVLC as an SDK (`org.videolan.android:libvlc-all`). The official README identifies the LibVLC engine as LGPLv2; the VLC Android application is GPL. The current 3.x SDK line includes 3.7.7. Keep the SDK distinct from GPL VLC application sources.
- **Build proof (2026-11-12):** an isolated branch added only `libvlc-all:3.7.7`. GitHub Actions unit tests, APK build and archive validation passed. The resulting universal APK was 217,070,829 bytes because it contained four ABIs. ELF inspection confirmed the `armeabi-v7a/libvlc.so` and JNI library are 32-bit ARM EABI5 binaries built for Android API 17 with NDK r21e; the device's Android 9 / API 28 is above that native minimum.
- **Packaging proof:** a second isolated build restricted to `armeabi-v7a` passed the same CI checks. Its APK was 47,370,075 bytes and contained only `libvlc.so` (39,822,028 bytes), `libvlcjni.so`, and `libc++_shared.so` for ARMv7. This is a practical size for the user's device and proves ABI packaging, not runtime playback.
- Before integration, audit the exact 3.7.7 AAR notices/transitive components and test playback, hardware/software decode, Surface lifecycle and repeated release on Android 9 ARMv7. The successful build does not establish real-device stability.

### MPV / libmpv

- `mpv-android` is an actively maintained Android TV player based on `libmpv`. Its README explicitly says it is not an AAR/library; integration requires its `MPVLib` API, `BaseMPVView` surface lifecycle and native JNI layer. Its Android build sets minSdk 23, NDK r30, Android API 23, and the native build script defaults to ARMv7 (`armv7l`). Current releases provide an `armeabi-v7a` APK and support both hardware and software video decoding.
- This is a strong candidate for the reported MediaTek hardware-decoder risk: a software decode mode can avoid MediaCodec for video, while still using an independent FFmpeg demux/decode stack. It must be performance-tested on 720p/1080p content; software decoding can overload an older SoC and still relies on a working render surface/GPU.
- **Critical license gate:** mpv is GPLv2+ by default, with an LGPLv2.1+ build mode when configured with `-Dgpl=false`. The inspected mpv-android FFmpeg script currently enables `gpl` and `version3`, and its mpv script does not disable GPL. Do not import its unmodified binaries. A compliant candidate build must change FFmpeg to `--disable-gpl --disable-version3`, set mpv `-Dgpl=false`, audit every enabled FFmpeg component and linked library, and ship all required notices/source access.
- The upstream build scripts are Linux/macOS oriented; our GitHub Actions Linux runner can host a dedicated reproducible ARMv7 build. Pin the mpv-android commit, mpv/FFmpeg revisions, dependency versions and NDK. Do not depend on the moving `master` branch for a release build.

## Controlled fallback rules

PlaybackFailureClassifier maps Media3 1.11.x error codes into transport, configuration, demux, decoder-init/runtime, audio-output and video-render domains. Live and VOD diagnostics record that classification. PlaybackEnginePolicy accepts ordered engine+decoder candidates (MEDIA3, MPV, IJK, LIBVLC; PLATFORM_AUTO, HARDWARE or SOFTWARE) and is unit-tested for switching engines versus changing decoder mode. Neither class instantiates players or claims a native engine is installed; only a runtime registry may advertise a candidate after ABI/library verification.

- Transport error -> try a distinct backup URL; do not switch decoder for a network failure.
- Unsupported container/codec, demux, decoder initialization/runtime, video-render or audio-output error -> try the next verified engine+decoder candidate; this may be a software mode within the same engine.
- Local network-policy/configuration error (for example, cleartext not permitted) -> fix configuration; do not rotate URLs or engines.
- Unknown error -> do not blindly switch.
- Never select an unavailable engine.
- Before runtime switching: invalidate previous attempt generation; detach its surface/listeners; cancel timers; stop and release it; only then create and attach the next engine. Exactly one engine owns a surface.
- Bound each attempt and record engine, decoder mode, source index (never full signed URLs), media format, first-frame timing and failure category.

## Next implementation sequence

1. Complete IJK source/FFmpeg configure/license review; verify ARMv7 shared libraries and native symbol compatibility.
2. Build an isolated IJK sample artifact on the research branch; verify ARMv7 packaging, load, HLS/TS/MP4, MediaCodec mode, software mode and repeated clean release. Do not integrate until this passes.
3. Define a narrow player adapter around MainActivity's direct ExoPlayer use. Convert live playback first and keep VOD stable; add explicit surface ownership and attempt-generation checks.
4. Integrate IJK behind a non-default developer switch, then test authorized known-good and failure samples.
5. Evaluate official LibVLC only after IJK comparison demonstrates a real gap.
6. Add runtime tests: cold start, channel zap, 30-minute playback, source failover, A/V sync, audio-only/black-screen, stop/release/recreate, background/foreground and remote input.
7. Promote only after CI and real Dangbei tests; keep main unchanged until all release gates pass.

## Current limitation

The current APK still uses Media3/ExoPlayer for live and VOD. setEnableDecoderFallback(true) only lets Media3 try alternate platform decoders; it is not an independent engine and does not protect against a system-level MediaTek driver hang. This research branch is not a user-ready multi-engine build.
