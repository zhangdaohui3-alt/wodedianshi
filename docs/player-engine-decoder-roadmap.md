# Player engine and decoder compatibility roadmap

Status: research branch; no runtime engine switch is enabled by this document.
Baseline: `fix/vod-player-init-diagnostics` at the time this plan was created.

## Current implementation snapshot (2026-11-12)

The research branch now contains a first diagnostic implementation, but it is not yet a multi-engine player:

- Added `MediaCodecInventory`: reads the device's Android decoder list and audio/video MIME types; on API 29+ reports Android's hardware/software/vendor classification where available.
- Extended live and VOD Media3 analytics to record selected audio/video decoder names, initialization timing, input media format (codec MIME, resolution, frame rate, audio channels/sample rate), codec errors and player errors.
- Expanded the device/player diagnostic dialog so these records and the device decoder inventory can be copied without exposing full stream URLs.
- The research branch now has a tested engine+decoder candidate policy and a Media3 error classifier. Run #43186736333 passed unit tests, APK assembly, package/version/SDK checks and archive validation. The APK still contains no secondary native engine. This is a diagnostics/architecture build, not a multi-engine release and not proof of playback on a Dangbei device.
- No runtime engine selector or IJK/LibVLC dependency has been added. The next gate is to collect actual Dangbei codec inventory and failure records, then test one secondary engine in an isolated prototype.

## Why this work is necessary

The current application uses Media3/ExoPlayer only. Its `PlaybackPlayerFactory` enables MediaCodec decoder fallback, but that is fallback among Android platform decoders; it does not add IJK/FFmpeg or LibVLC. The current APK therefore does not yet have the multiple independent playback engines observed in the reference apps.

Keep these concepts separate:
- **Playback engine**: owns demuxing, protocol/network loading, buffering, rendering and lifecycle (Media3/ExoPlayer, IJK, LibVLC).
- **Decoder backend**: turns compressed audio/video into frames/samples (Android MediaCodec hardware decoders, software FFmpeg decoders, VLC's codec stack).
- **Recovery policy**: decides whether to retry the same source, change decoder/backend, change engine, or move to another authorized source.

Do not launch two engines against the same surface/source simultaneously. Every attempt must have one owner, a generation ID, bounded timeout, explicit teardown and diagnostic record.

## Reference APK findings (static only; not proof of playback quality)

- Overseas Starfire 1.0.49: IJKPlayer + FFmpeg native libraries; ARMv7 only; separate encoded channel configuration assets; visible reconnect/cache options.
- 非凡TV 1.0.2: IJK/FFmpeg native components, Cronet, QuickJS, RTMP and P2P/Thunder-related libraries; 534-entry EPG alias/logo catalogue; local-proxy URL clues. Do not claim an ExoPlayer+IJK runtime switch without further call-path evidence.
- 超级ITV 1.0.5: IJK/FFmpeg plus `libtvcore.so` and `libforcetv.so`; ARM64 and ARMv7; separate player/channel/live service classes; visible CDN/P2P-related symbols. Symbols alone do not prove that those paths are active for every stream.

Use these as architecture and behavior references, not as code to copy. Do not bypass encryption, extract protected streams, or reuse proprietary assets.

## Candidate engine strategy (no fixed global order)

1. **Media3/ExoPlayer remains the primary path** for common formats supported by the actual box. Its decoder fallback is still within Android's platform decoder family; it is not a second player engine.
2. **MPV/libmpv is a high-priority software-decoder candidate.** Its modern FFmpeg stack and explicit hardware/software modes make it especially relevant to the MediaTek decoder-hang risk. It is not an AAR; integration requires native libraries, JNI and a SurfaceView-based view lifecycle.
3. **IJK + FFmpeg remains a reference-driven candidate** because the supplied Starfire/非凡TV/超级ITV APKs contain IJK/FFmpeg components. However, the old upstream build and opaque community FFmpeg binaries require source/provenance work before use.
4. **LibVLC is a build-verified ARMv7 candidate** with an official SDK and a distinct demux/decoder/render pipeline. It has a large native footprint but the ARMv7-only packaging is manageable.

The runtime registry must supply a device/format-specific ordered list of engine+decoder candidates (for example MPV/SOFTWARE after a suspected MediaCodec runtime failure). Never assume a universal fixed order. Media3 software extensions may be evaluated separately for exact failing codecs; the FFmpeg extension is principally an audio decoder extension, not a blanket software video decoder.

No engine should be added to the production APK solely on the basis of library names in a reference binary. A candidate is promoted only after reproducible ARMv7 builds, source/license checks and repeatable playback tests.

## Candidate engine research (2026-11-12)

### IJKPlayer

- The upstream Bilibili README still documents the 0.8.8 Android artifacts and an old build environment (NDK r10e / Android Studio 2.1.3 / FFmpeg n3.4-era). Do not drop those prebuilt binaries into this modern Media3/AGP project without rebuilding and ABI/runtime validation.
- A community self-contained fork, `brainShake/ijkplayer-android`, claims a JDK 17 / CMake build, ARM64 and ARMv7 variants, and bundled FFmpeg. Its README also warns that mismatched `libijkplayer.so` and `libijkffmpeg.so` can crash on teardown. The public repository currently shows only four commits, no releases, and no root-level LICENSE in its displayed file list. Treat it as a code-audit candidate only, not an approved dependency. Audit every source license, FFmpeg configure flags, binary provenance, ABI coverage and native teardown before any prototype.
- Expected APK-size impact is material: its README reports an all64 example APK around 30 MB. Measure an app-specific minimal build rather than importing the example package wholesale.

### LibVLC

- VideoLAN publishes LibVLC as an Android SDK/library and documents it as a separate engine with broad format/protocol and hardware-decode support. The official project distinguishes the LGPL-licensed LibVLC engine from the GPL-licensed VLC Android application. This project must review the exact AAR, notices, linking/distribution obligations and chosen version before shipping.
- **Build proof (2026-11-12):** isolated GitHub Actions build with official `libvlc-all:3.7.7` passed tests, APK assembly and validation. ELF inspection found the ARMv7 library built for Android API 17 with NDK r21e. A four-ABI universal APK was 217 MB; an ARMv7-only APK was 47,370,075 bytes and contained only ARMv7 native libraries. This proves packaging, not runtime playback. Test SurfaceView/TextureView behavior, seek/stop/release lifecycle, startup latency and memory on the actual Dangbei box before promotion.

### MPV / libmpv

- The upstream `mpv-android` project is actively maintained, supports Android TV, offers hardware and software video decoding, and publishes ARMv7 builds. Its September 17, 2026 build uses NDK r30, FFmpeg 9.0.2 and minSdk 23, so its platform requirements align with the target's Android 9 / API 28.
- It is explicitly not an AAR/library. We must build and integrate the native `libmpv` stack, JNI bridge and SurfaceView lifecycle rather than embedding the standalone app or launching an external player.
- **License gate:** mpv defaults to GPLv2+; it can be built LGPLv2.1+ with `-Dgpl=false`. The inspected upstream FFmpeg script currently enables GPL/version3. The isolated audit workflow patches FFmpeg to `--disable-gpl --disable-version3` and mpv to `-Dgpl=false`, pins the September 2026 source revisions, builds ARMv7 in Linux CI, and verifies the configure flags before accepting an artifact.
- This build is currently running on the dedicated prototype branch. Even a successful native build will prove compilation/packaging only; we still need Android 9 device playback and software-decoder performance measurements.

### Media3

- The application already uses Media3 1.11.1. Keep it as the default engine; don't upgrade or add decoder extensions just to increase dependency count. First collect actual failing stream formats and codec capabilities. Evaluate each software extension against a known failing sample; an audio FFmpeg extension is not a universal software video decoder.

## Compatibility matrix to build

For every test stream record:
- protocol/container: HLS/M3U8, MPEG-TS, MP4, DASH, RTSP and any other authorized sample actually encountered;
- video codec/profile/level/bit depth/resolution/frame rate;
- audio codec/channel layout/sample rate;
- device model, Android version, SoC, ABI and available MediaCodec decoder names;
- engine and decoder backend selected;
- connect time, time-to-first-rendered-frame, A/V sync, dropped frames, stalls, memory/CPU, audio-only and black-screen outcome;
- exact error category: transport, demux, unsupported format, decoder init, decoder runtime, surface/render, audio output or stream discontinuity.

## Controlled fallback state machine

1. Validate transport and media payload; reject HTML/error pages masquerading as media.
2. Start with the best verified engine+decoder candidate for the source profile and device.
3. If transport fails, retry a distinct authorized backup URL; do not waste time changing decoder.
4. If the container/demux is unsupported, move to the next engine with a different demuxer. If decoder initialization/runtime fails, prefer a verified SOFTWARE candidate that avoids the suspected MediaCodec path before trying another hardware path.
5. If audio advances but no video frame renders, distinguish decoder failure from surface/render failure. A software decoder may avoid a MediaCodec fault, but a separate render path may be required for a GPU/surface fault.
6. If a rendered stream later stalls, first distinguish network starvation from decoder/render stall using load and frame telemetry.
7. Invalidate the prior attempt generation, detach its surface/listeners, cancel timers, stop and release it, and only then create the next attempt. Exactly one engine may own the video surface.
8. A full Android/driver freeze cannot be recovered reliably by an in-app watchdog. Prevent recurrence by blacklisting the risky hardware-decoder path after confirmed failures; do not repeatedly reproduce hard freezes on the user's box.
9. After all source/backend attempts fail, show a useful reason and continue to the next channel only according to the user's channel-zap policy.

## Immediate implementation sequence

- [x] Inventory current Media3 dependencies, player construction sites and error/watchdog callbacks.
- [x] Add Media3 failure-domain classification to live/VOD diagnostics and test it.
- [x] Add engine+decoder-mode candidate policy with unit tests for engine switching versus same-engine hardware-to-software fallback.
- [x] Verify official LibVLC 3.7.7 ARMv7 packaging in an isolated build; measure ABI-specific APK size.
- [ ] Complete the isolated LGPL-mode libmpv ARMv7 native build; verify FFmpeg/mpv GPL flags and all packaged ABIs.
- [ ] Build a minimal LibVLC 3.7.7 ARMv7 playback sample and test HLS/TS/MP4, hardware/software decode and repeated release.
- [ ] Audit and build IJK from pinned source with complete FFmpeg provenance and notices; do not use opaque community binaries.
- [ ] Define a narrow player adapter around MainActivity's direct ExoPlayer use. Convert live playback first, keep VOD stable, and replace PlayerView coupling with a single surface host owned by one engine at a time.
- [ ] Integrate one secondary engine behind a non-default developer switch, then compare on authorized known-good/failure samples.
- [ ] Preserve stable main; do not merge until CI and real Dangbei tests pass.

## Release gates

CI success proves compilation and automated tests only. A release candidate must additionally pass real-device install/upgrade, channel switch, audio/video, first-frame, long-play, source failover, surface teardown, remote-control and memory tests on the target Dangbei box.

## Licensing and provenance

Check the exact license and notices of every selected native dependency and every FFmpeg configuration before shipping. FFmpeg licensing depends on build configuration; IJK forks and bundled codecs must be reviewed individually. Keep proprietary reference APK code/assets out of this project.
