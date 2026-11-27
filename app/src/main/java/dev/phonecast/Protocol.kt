package dev.phonecast

/**
 * Wire protocol (TCP, big-endian), sender -> receiver:
 *
 *   handshake: int MAGIC, int VERSION
 *   packets:   byte type | int length | long ptsMicros | byte[length] payload
 *
 * Receiver -> sender: single bytes (REQ_KEYFRAME).
 */
object Protocol {
    const val PORT = 48765
    const val SERVICE_TYPE = "_phonecast._tcp"
    const val MIME = "video/avc"

    const val MAGIC = 0x50434153 // "PCAS"
    const val VERSION = 1
    const val MAX_PACKET = 16 * 1024 * 1024

    const val VIDEO_HEADER = 1   // payload: int width, int height
    const val VIDEO_CONFIG = 2   // payload: H.264 SPS + PPS (Annex-B)
    const val VIDEO_FRAME = 3    // payload: H.264 access unit
    const val VIDEO_KEYFRAME = 4 // payload: H.264 IDR access unit
    const val AUDIO_HEADER = 5   // payload: int sampleRate, int channels
    const val AUDIO_DATA = 6     // payload: PCM 16-bit little-endian

    const val REQ_KEYFRAME = 1
}

data class Preset(val label: String, val maxLongSide: Int, val fps: Int, val bitrate: Int)

object Presets {
    val ALL = listOf(
        Preset("720p · 30 fps · light on Wi-Fi", 1280, 30, 4_000_000),
        Preset("1080p · 30 fps · balanced", 1920, 30, 8_000_000),
        Preset("1080p · 60 fps · smoothest (strong Wi-Fi)", 1920, 60, 12_000_000),
    )
    const val DEFAULT = 1
    fun get(index: Int): Preset = ALL.getOrElse(index) { ALL[DEFAULT] }
}
