package dev.lightsaber.motion

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Which sensor a [SensorSample] came from. */
enum class SensorKind(val code: Int) {
    GYRO(1),
    ACCEL(2),
    GRAVITY(3);

    companion object {
        fun fromCode(code: Int): SensorKind =
            entries.firstOrNull { it.code == code } ?: throw IllegalArgumentException("unknown sensor code $code")
    }
}

/**
 * One recorded sensor event.
 *
 * @param sensorTimestampNs monotonic `SensorEvent.timestamp`
 * @param arrivalTimestampNs `SystemClock.elapsedRealtimeNanos()` at callback entry, kept so
 *   delivery jitter can be separated from real motion
 */
data class SensorSample(
    val kind: SensorKind,
    val sensorTimestampNs: Long,
    val arrivalTimestampNs: Long,
    val accuracy: Int,
    val values: Vec3,
)

/**
 * Developer-only recording format. Versioned header first so a future format change fails loudly
 * on read instead of silently mis-parsing.
 *
 * Deliberately binary and deliberately tiny: a 10-minute 100 Hz dual-sensor capture is ~250 kB, so
 * sessions can be kept locally without thinking about quota.
 */
object SensorLog {

    private val MAGIC = "LSLOG".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1

    /** Header written at the start of every file. */
    data class Header(
        val version: Int = VERSION,
        val gyroSampleRateHz: Float = 100f,
        val accelSampleRateHz: Float = 100f,
        val note: String = "",
    )

    class Writer(private val out: OutputStream, private val header: Header = Header()) : AutoCloseable {
        private val data = DataOutputStream(BufferedOutputStream(out))
        private var closed = false

        init {
            data.write(MAGIC)
            data.writeInt(0) // reserved, keeps the version field 4-byte aligned
            data.writeInt(header.version)
            data.writeFloat(header.gyroSampleRateHz)
            data.writeFloat(header.accelSampleRateHz)
            data.writeUTF(header.note)
        }

        fun write(sample: SensorSample) {
            check(!closed) { "writer already closed" }
            require(sample.values.isFinite()) { "refusing to log a non-finite sample" }
            data.writeByte(sample.kind.code)
            data.writeInt(sample.accuracy)
            data.writeLong(sample.sensorTimestampNs)
            data.writeLong(sample.arrivalTimestampNs)
            data.writeFloat(sample.values.x)
            data.writeFloat(sample.values.y)
            data.writeFloat(sample.values.z)
        }

        override fun close() {
            if (closed) return
            closed = true
            data.flush()
            data.close()
        }
    }

    class Reader(private val input: InputStream) : AutoCloseable {
        private val data = DataInputStream(BufferedInputStream(input))

        init {
            val magic = ByteArray(MAGIC.size)
            data.readFully(magic)
            check(magic.contentEquals(MAGIC)) { "not a Lightsaber sensor log (bad magic)" }
            data.readInt() // reserved
            val version = data.readInt()
            check(version == VERSION) {
                "sensor log version $version cannot be read by this build (expected $VERSION)"
            }
        }

        val header = Header(
            version = data.readInt(),
            gyroSampleRateHz = data.readFloat(),
            accelSampleRateHz = data.readFloat(),
            note = data.readUTF(),
        )

        /** Streams samples in file order. Returns an empty sequence at end of file. */
        fun forEachSample(consumer: (SensorSample) -> Unit) {
            while (true) {
                val kind = try {
                    SensorKind.fromCode(data.readByte().toInt())
                } catch (e: EOFException) {
                    return
                }
                val accuracy = data.readInt()
                val sensorTs = data.readLong()
                val arrivalTs = data.readLong()
                val v = Vec3(data.readFloat(), data.readFloat(), data.readFloat())
                consumer(SensorSample(kind, sensorTs, arrivalTs, accuracy, v))
            }
        }

        fun readAll(): List<SensorSample> = buildList {
            forEachSample { add(it) }
        }

        override fun close() {
            data.close()
        }
    }

    fun write(path: File, header: Header = Header(), samples: Sequence<SensorSample>) {
        Writer(path.outputStream(), header).use { w -> samples.forEach(w::write) }
    }

    fun read(path: File): List<SensorSample> = Reader(path.inputStream()).use { it.readAll() }
}