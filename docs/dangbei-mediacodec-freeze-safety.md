# Dangbei DBD6X Plus video-freeze safety gate

## Device and incident evidence

Target device reported by the owner:

- Dangbei DBD6X Plus, MediaTek m7642
- Android 9 / API 28
- 1920x1080, 320 dpi
- 32-bit ARMv7 only (armeabi-v7a, armeabi)
- WebView 66; browser playback is not an acceptable solution

Observed playback evidence from prior diagnostics:

- H.264/AVC avc1.64001F, 1280x720, AAC stereo 44.1 kHz was selected and reported as handled.
- VIDEO_RENDERED / first frame reached display output and player state became READY.
- This proves that a first frame was rendered; it does not prove sustained playback stability.
- The owner reports mosaic/artifacting followed by all remote keys becoming unresponsive and a black screen, requiring power removal. Similar incidents occurred on earlier builds.

## Working diagnosis (not a conclusion)

The symptom is more severe than an ordinary app/player error. A MediaCodec vendor decoder, graphics/display path, malformed or unusual stream bitstream, thermal/power instability, or a device firmware defect could be involved. The available logs do not identify which one.

An app-level watchdog cannot reliably recover a device-wide kernel/driver/firmware hang. Do not claim that retries, a player restart, or a Java exception handler can guarantee recovery from this class of failure.

## Mandatory release gates

1. **No risky-channel reproduction loop.** Do not ask the owner to repeatedly open the channel that previously caused a hard freeze. Use controlled lab streams and short, staged tests first.
2. **Preserve hardware-decoder fallback behavior.** Current Media3 decoder fallback only helps when the platform exposes an alternative decoder and an error is surfaced. It is not proof against a vendor-driver hang.
3. **Do not silently force software decode on this device.** The current app does not bundle a software decoder. A future MPV/IJK/LibVLC software path must be tested for CPU load, dropped frames, temperature, memory, and sustained stability before becoming a default.
4. **Transport and decoder failures stay separate.** Network timeout/disconnect may advance to another authorized source. Decoder, demux, renderer, or audio-output failures may try only a verified alternate engine/decoder candidate. Do not repeatedly retry the same failing source/decoder pair.
5. **One engine change at a time.** Keep Media3 as the stable baseline. Integrate a secondary engine behind an explicit capability/allowlist gate, first on VOD or a controlled test item, then a limited live stream. Do not change the main branch until tests pass.
6. **Device compatibility is mandatory.** Every native library must include armeabi-v7a; ARM64-only artifacts are invalid for this target. Check ELF architecture, minimum API, ABI packaging, native dependency closure, and license notices.
7. **Staged runtime validation.** Before wider use: known-good H.264/AAC 720p for a short session; then 10-minute playback; then channel switching; then source failover; then longer soak and thermal checks. Stop immediately on artifacts, system-wide input loss, abnormal heat, or repeated decoder errors.
8. **No false safety claims.** CI/unit tests and APK packaging validate code/build properties only. They cannot certify MediaTek hardware-decoder stability. Only actual device tests can pass the runtime gate.

## Next engineering work

- Complete the isolated MPV ARMv7 LGPL audit and verify actual native ELF files and FFmpeg/MPV GPL configuration.
- Build a small MPV surface/lifecycle adapter in an isolated branch; do not replace the existing Media3 player yet.
- Add explicit engine/decoder capability reporting and failure telemetry before enabling automatic engine fallback.
- Compare Media3 hardware decode against MPV software decode on the target hardware using controlled content, monitoring frame delivery, CPU, memory, thermal behavior, and system responsiveness.
- Keep the stable branch untouched until the above evidence exists.