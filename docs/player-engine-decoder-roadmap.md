# Player engine and decoder compatibility roadmap

Status: research branch; no runtime engine switch is enabled by this document.
Baseline: `fix/vod-player-init-diagnostics` at the time this plan was created.

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

## Proposed engine order

1. **Media3/ExoPlayer (default path)** — keep hardware decoding first for common H.264/H.265/AV1/VP9 formats supported by the actual box; enable decoder fallback; log selected tracks, decoder names, renderer errors, first decoded/rendered frame and audio session state.
2. **Media3 software extensions** — evaluate only the needed decoders after examining real failing samples. Media3's FFmpeg extension is principally an audio decoder extension; it is not a blanket software video decoder. Evaluate AV1/VP9 extensions independently. Build and package only supported codecs and target ABIs.
3. **IJKPlayer + FFmpeg (secondary engine candidate)** — evaluate HLS/TS/MP4 and legacy stream compatibility; compare hardware MediaCodec mode and software decode separately. Require ABI-complete native libraries and consistent FFmpeg/IJK build versions. Do not use an old prebuilt binary just because it exists in a reference APK.
4. **LibVLC (tertiary candidate)** — evaluate only if IJK and Media3 leave important protocol/container/codec gaps. Review LGPL obligations, binary size, startup time, memory and TV surface behavior before inclusion.

No engine should be added to the production APK solely on the basis of library names in a reference binary. A candidate is promoted only after repeatable tests on emulator and target Android TV hardware.

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
2. Start with the best known engine/decoder combination for the source profile and device.
3. If transport fails, retry a distinct authorized backup URL; do not waste time changing decoder.
4. If the container/codec is unsupported or decoder initialization fails, try a compatible decoder/backend, then another engine.
5. If audio advances but no video frame renders, classify as video decode/render failure and try the next compatible backend within a short bounded deadline.
6. If a rendered stream later stalls, first distinguish network starvation from decoder/render stall using load and frame telemetry.
7. Stop and release the previous player, detach its surface/listeners, cancel timers and invalidate callbacks before creating the next attempt.
8. After all source/backend attempts fail, show a useful reason and continue to the next channel only according to the user's channel-zap policy.

## Immediate implementation sequence

- [ ] Inventory all current Media3 dependencies, player construction sites and error/watchdog callbacks.
- [ ] Add a decoder/engine diagnostic record (engine, decoder, track format, ABI, first frame, failure category).
- [ ] Add device capability inventory using MediaCodecList and Media3 track support; never assume every box has the same codec set.
- [ ] Add repeatable sample-based tests and a test-only engine selector.
- [ ] Build an isolated IJK prototype on a research branch for ARM64 and ARMv7; measure APK size and verify native library loading before wiring it into UI.
- [ ] Compare LibVLC only if the first two engines fail meaningful test cases.
- [ ] Preserve stable main and do not merge until unit/CI tests and real Dangbei tests pass.

## Release gates

CI success proves compilation and automated tests only. A release candidate must additionally pass real-device install/upgrade, channel switch, audio/video, first-frame, long-play, source failover, surface teardown, remote-control and memory tests on the target Dangbei box.

## Licensing and provenance

Check the exact license and notices of every selected native dependency and every FFmpeg configuration before shipping. FFmpeg licensing depends on build configuration; IJK forks and bundled codecs must be reviewed individually. Keep proprietary reference APK code/assets out of this project.
