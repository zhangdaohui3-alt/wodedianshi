# Playback source validation quality gate

## What is checked in CI

The APK workflow runs `gradle testDebugUnitTest` before packaging. The first pure-Java test suite covers the sampled media-payload validator used by live-source discovery:

- MPEG-TS packet synchronization requires two sync bytes at 188-byte spacing.
- Fragmented MP4 validation checks top-level ISO-BMFF box headers rather than matching arbitrary text inside an HTML error page.
- FLV and ADTS signatures remain accepted by the generic media-payload check.
- Random bytes, malformed box sizes, and HTML-like payloads are rejected.

## What these checks do not prove

A successful URL probe is only a transport/container sanity check. It does not prove that the Android device can decode the stream, render video, output audible audio, or sustain playback. Those remain separate runtime/device acceptance checks for the final six-stage release.

## Release rule

Do not treat APK compilation alone as completion. Unit tests, APK package/archive validation, and real-device playback/remote-control validation are separate gates.
