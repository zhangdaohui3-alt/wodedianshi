# Multi-engine playback implementation gates

Status: engine fallback policy and Media3 error classifier are implemented and unit-tested on the research branch. No secondary engine is integrated or enabled in the app yet.

## Device-first constraints

The first target is Dangbei DBD6X Plus: Android 9 / API 28, MediaTek m7642, 32-bit ARMv7 (armeabi-v7a, armeabi). Any native engine must contain a loadable ARMv7 build. ARM64-only is an automatic rejection for this target. Keep minSdk 23 unless a measured dependency forces a documented change.

The reported failure (video artifacts, then all remote keys unresponsive, then black screen until power removal) is more severe than an ordinary app playback error. A process watchdog cannot promise recovery if the vendor codec/driver or whole Android system hangs. Do not repeatedly reproduce this on the user's box.

## Engine and decoder model

- Engine owns demux, transport, buffering, rendering and lifecycle.
- Decoder backend may be platform MediaCodec hardware or an engine's supported software path.
- Source means a distinct authorized URL for the same channel/program.
- Recovery chooses URL retry, decoder path, another engine, or terminal failure.

Target order after validation: Media3/ExoPlayer primary; IJK + FFmpeg secondary candidate (test MediaCodec and software modes separately); LibVLC tertiary candidate only if it covers a demonstrated compatibility gap.

A larger APK is acceptable when the additional engine demonstrably improves playback. Do not optimize size at the expense of a working ARMv7 engine; avoid unused ABIs/codecs.

## Public upstream evidence and gates

### IJK

- The upstream Bilibili IJKPlayer's published Android artifacts are version 0.8.8 from 2018, built with NDK r10e / old Gradle tooling and FFmpeg 3.x-era sources. The published `ijkplayer-armv7a` artifact is listed as LGPL-2.1, but its age and historical Bintray/JCenter publication path make it unsuitable as an automatic drop-in for this AGP 8.13.2 / Java 17 app.
- The community `brainShake/ijkplayer-android` repo advertises JDK 17 and ARMv7 modules, but its README's example installation command is `all64Debug`; that is not proof that an ARMv7 APK is correctly packaged. It also bundles prebuilt FFmpeg binaries, so exact source revision, build flags, notices and per-ABI binary provenance must be checked before use.
- The preferred route is to build from a pinned, audited source revision in a separate Android sample module/CI job, with `armeabi-v7a` as a required artifact and `arm64-v8a` optional. Do not copy opaque `.so` files into the production APK.

- Bilibili IJKPlayer documents LGPL-2.1-or-later and an old Android package/build setup (0.8.8, NDK r10e, API 9–23). It is not a safe drop-in for this project's AGP 8.13.2 / Java 17 build. Audit FFmpeg configuration and every optional component license.
- brainShake/ijkplayer-android advertises JDK 17, CMake, NDK 30 and ARMv7/ARM64 packaging. Its community-maintained code and bundled prebuilt FFmpeg still require source, provenance, ABI and license audit before use.
- VideoLAN's official Android repository publishes LibVLC as an SDK (org.videolan.android:libvlc-all). LibVLC engine is LGPLv2 while VLC Android application is GPL. Review the exact release/AAR, notices, transitive components and distribution obligations; verify the artifact still contains ARMv7 before integration.

## Controlled fallback rules

PlaybackEnginePolicy is a pure, unit-tested decision layer. It does not instantiate players and does not claim IJK/LibVLC is installed.

- Transport error -> try a distinct backup URL; do not switch decoder for a network failure.
- Unsupported container/codec, demux, decoder initialization/runtime, video-render or audio-output error -> try the next verified available engine.
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
