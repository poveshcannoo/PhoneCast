# PhoneCast

Share your Android phone's screen (and sound) to an Android / Android-TV-style projector over Wi-Fi.
One APK goes on both devices: the phone is the **sender**, the projector is the **receiver**.

```
Phone (sender)                                   Projector (receiver)
MediaProjection → VirtualDisplay → H.264 HW enc ─┐
AudioPlaybackCapture → 48 kHz PCM ───────────────┼─ TCP :48765 ─→ H.264 HW decoder → SurfaceView
NSD lookup "_phonecast._tcp" ←───────────────────┘                 AudioTrack
                                                  ←─ "send keyframe" requests
```

## 1. Build the APK

**Android Studio (easiest)**
1. Install Android Studio (Ladybug 2024.2 or newer).
2. *File → Open* → choose the `PhoneCast` folder. Let Gradle sync (it downloads Gradle 8.11.1 + AGP 8.7.3 the first time).
3. *Build → Build App Bundle(s)/APK(s) → Build APK(s)*. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.
   Or plug in your phone with USB debugging on and press ▶ Run.

**GitHub, no Android Studio needed:** push this folder to a GitHub repo. The included workflow
(`.github/workflows/build.yml`) builds the APK; download it from the run's *Artifacts*.

**Command line:** with the Android SDK installed, run `gradle assembleDebug` (or `gradle wrapper` once, then `./gradlew assembleDebug`).

## 2. Install

- **Phone:** open the APK and allow "install unknown apps" when asked.
- **Projector**, using any one of these:
  - Copy the APK to a USB stick, then open it with the projector's file manager. You may need to enable *Unknown sources* in its security settings.
  - Use a "Send Files to TV" app on the phone and the projector.
  - Use ADB over the network: turn on *Developer options → Network/ADB debugging* on the projector, then run
    `adb connect <projector-ip>:5555` and `adb install app-debug.apk`.

On a TV-style launcher the app opens straight into receiver mode. If your projector's launcher shows the phone
screen instead, tap **Use THIS device as the receiver**.

## 3. Use

1. Put the phone and the projector on the **same Wi-Fi** (or join the projector to the phone's hotspot).
2. Open PhoneCast on the projector. It shows its IP address.
3. Open PhoneCast on the phone. Tap the projector in the list, or type the IP and tap **Connect**.
4. Accept Android's "Start recording or casting?" prompt. To stop, tap **Stop sharing**, use the notification, or use the status-bar cast chip.

Rotating the phone is handled: the stream re-shapes and the projector letterboxes it.

## Quality presets

| Preset | Resolution | FPS | Bitrate |
|---|---|---|---|
| Light | up to 1280 px long side | 30 | 4 Mbps |
| Balanced (default) | up to 1920 px | 30 | 8 Mbps |
| Smoothest | up to 1920 px | 60 | 12 Mbps |

If the encoder can't handle a size, the app automatically picks a smaller one. If the projector stutters,
use *Light*. Cheap projector chips often can't decode 1080p60.

## Known limits

- **DRM / secure apps** (Netflix, Prime Video, Disney+, banking apps) show black. Android blocks capturing them. This applies to every screen-mirroring app.
- **Sound** needs Android 10+ on the phone. Apps can opt out of being captured (some games, WhatsApp calls), and the phone also keeps playing the sound itself.
- **Latency** is usually around 100–250 ms on decent Wi-Fi. That's fine for slides, photos and videos, but not ideal for fast games.
  When the network can't keep up, the sender drops frames until the next keyframe instead of building up a delay.
- Single viewer at a time. A new phone that connects replaces the current one.
- Traffic is not encrypted, so use it on your own network.

## Troubleshooting

- **Projector not in the list:** some routers block mDNS ("AP/client isolation", guest networks). Type the IP shown on the projector instead.
- **"Could not connect":** both devices must be on the same subnet, the receiver screen must be open, and nothing else can be using port 48765.
- **Black screen with audio:** the app being shown is DRM-protected.
- **Logs:** run `adb logcat -s CastService ScreenEncoder ReceiverServer VideoDecoder`.

## Code map (`app/src/main/java/dev/phonecast`)

| File | Role |
|---|---|
| `MainActivity.kt` | Sender UI, permissions, screen-capture consent |
| `CastService.kt` | Foreground service: projection, encoder, rotation, connection lifecycle |
| `ScreenEncoder.kt` | Hardware H.264 encoder (Surface input), size/encoder fallback |
| `AudioCapturer.kt` | Captures playback audio (Android 10+) |
| `PacketWriter.kt` | Network send queue with congestion-aware frame dropping |
| `ReceiverDiscovery.kt` | Finds receivers with NSD/mDNS |
| `ReceiverActivity.kt` | Full-screen receiver UI, NSD advertising, IP display |
| `ReceiverServer.kt` | TCP server, stream parsing, decoder recovery |
| `VideoDecoder.kt` / `AudioPlayer.kt` | Hardware decode to Surface / PCM playback |
| `Protocol.kt` | Wire format, port, quality presets |
