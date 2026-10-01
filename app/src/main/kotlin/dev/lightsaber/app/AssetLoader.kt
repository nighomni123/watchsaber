package dev.lightsaber.app

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import kotlin.math.abs

/**
 * Loads the sound assets bundled in the APK and hands them to the native mixer.
 *
 * Decoding happens here, in Kotlin, rather than in the engine: it keeps the WAV parser out of the
 * real-time code, and a single `FloatArray` crossing JNI once at Ignite is cheaper and safer than
 * any per-sample callback from native code.
 *
 * The host offline renderer loads the same files from disk, so an asset that sounds right in replay
 * is the same asset the watch plays.
 */
object AssetLoader {

    private const val TAG = "Lightsaber"

    /** Mirrors AssetId in mixer.h. Order matters: it is the native contract. */
    enum class Slot(val id: Int, val fileName: String) {
        HUM(0, "hum.wav"),
        SWING_LOW(1, "swing_low.wav"),
        SWING_HIGH(2, "swing_high.wav"),
        ACCENT(3, "accent.wav"),
        IGNITE(4, "ignite.wav"),
        RETRACT(5, "retract.wav"),
    }

    /**
     * Decodes every asset into native memory. Idempotent and cheap after the first call, so Ignite
     * can call it unconditionally rather than tracking whether it already ran.
     *
     * Returns the number of assets actually loaded — zero of them means the engine will render
     * silence, which is exactly the failure this function exists to make loud.
     */
    fun loadAll(context: Context, engine: AudioEngine): Int {
        val assets = context.assets
        var loaded = 0
        for (slot in Slot.entries) {
            val samples = decode(assets.open("audio/${slot.fileName}"))
            if (samples == null || samples.isEmpty()) {
                Log.e(TAG, "asset missing or empty: audio/${slot.fileName} — the engine will be silent")
                continue
            }
            engine.loadNativeAsset(slot.id, samples)
            loaded++
            Log.i(TAG, "asset ${slot.fileName}: ${samples.size} frames, peak ${peak(samples)}")
        }
        Log.i(TAG, "loaded $loaded/${Slot.entries.size} assets")
        return loaded
    }

    private fun peak(samples: FloatArray): String =
        "%.3f".format(samples.maxOfOrNull { abs(it) } ?: 0f)

    /**
     * Minimal 16-bit PCM WAV reader. Only what these generated assets contain: PCM format, 16-bit,
     * any channel count (downmixed to mono by taking the first channel, which is how they are
     * authored).
     */
    fun decode(input: InputStream): FloatArray? = input.use { stream ->
        val data = DataInputStream(stream)
        val riff = ByteArray(4)
        data.readFully(riff)
        if (String(riff) != "RIFF") return null
        data.skipBytes(4) // riff size
        val wave = ByteArray(4)
        data.readFully(wave)
        if (String(wave) != "WAVE") return null

        var channels = 1
        var bits = 16
        var pcm: ByteArray? = null

        while (true) {
            val id = ByteArray(4)
            // readFully throws at a clean end of stream, which is how the last chunk is detected.
            try {
                data.readFully(id)
            } catch (e: EOFException) {
                break
            }
            val size = readLeInt(data)
            when (String(id)) {
                "fmt " -> {
                    val body = ByteArray(size)
                    data.readFully(body)
                    channels = readLeShort(body, 10)
                    bits = readLeShort(body, 14)
                }
                "data" -> {
                    val body = ByteArray(size)
                    data.readFully(body)
                    pcm = body
                }
            }
            if (size and 1 == 1) data.skipBytes(1) // chunks are word aligned
        }

        if (bits != 16 || pcm == null) {
            Log.e(TAG, "unsupported asset format: ${bits}-bit")
            return null
        }
        val bytes = pcm
        val frameCount = bytes.size / (2 * channels)
        val out = FloatArray(frameCount)
        var o = 0
        var i = 0
        while (i + 1 < bytes.size && o < frameCount) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt()
            out[o++] = ((hi shl 8) or lo).toShort().toInt() / 32768f
            i += 2 * channels
        }
        out
    }

    private fun readLeInt(data: DataInputStream): Int {
        val b0 = data.read()
        val b1 = data.read()
        val b2 = data.read()
        val b3 = data.read()
        if (b0 < 0 || b1 < 0 || b2 < 0 || b3 < 0) return 0
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun readLeShort(body: ByteArray, offset: Int): Int =
        ((body[offset].toInt() and 0xFF) or ((body[offset + 1].toInt() and 0xFF) shl 8))
}