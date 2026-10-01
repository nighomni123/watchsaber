package dev.lightsaber.motion

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Minimal 3-vector. Immutable so a control frame taken from the pipeline can never be mutated by a
 * later sample.
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)

    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)

    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)

    operator fun div(s: Float) = Vec3(x / s, y / s, z / s)

    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z

    fun cross(o: Vec3) = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x,
    )

    fun length(): Float = sqrt(dot(this))

    fun normalized(): Vec3 {
        val n = length()
        // Guard the zero vector rather than producing NaN: a NaN reaching the audio callback would
        // survive every downstream clamp check.
        return if (n < 1e-9f) ZERO else this / n
    }

    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    override fun toString() = "(%.5f, %.5f, %.5f)".format(x, y, z)

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
    }
}

/**
 * 3x3 row-major matrix, used only for orthonormal rotations (watch frame -> blade frame).
 */
data class Mat3(
    val m: FloatArray,
) {
    init {
        require(m.size == 9) { "Mat3 needs exactly 9 elements, got ${m.size}" }
    }

    operator fun times(v: Vec3) = Vec3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[3] * v.x + m[4] * v.y + m[5] * v.z,
        m[6] * v.x + m[7] * v.y + m[8] * v.z,
    )

    /**
     * Transpose-multiply: maps a vector expressed in the rotated frame back into watch
     * coordinates. For an orthonormal R this is the inverse.
     */
    fun transposedTimes(v: Vec3) = Vec3(
        m[0] * v.x + m[3] * v.y + m[6] * v.z,
        m[1] * v.x + m[4] * v.y + m[7] * v.z,
        m[2] * v.x + m[5] * v.y + m[8] * v.z,
    )

    /** Nearest orthonormal matrix, preserving handedness by re-deriving the third column. */
    fun orthonormalized(): Mat3 {
        val c0 = Vec3(m[0], m[3], m[6]).normalized()
        val c1 = Vec3(m[1], m[4], m[7])
        // Gram-Schmidt: remove any component of column 1 along column 0.
        val c1o = (c1 - c0 * c0.dot(c1)).normalized()
        val c2 = c0.cross(c1o)
        return Mat3(
            floatArrayOf(
                c0.x, c1o.x, c2.x,
                c0.y, c1o.y, c2.y,
                c0.z, c1o.z, c2.z,
            ),
        )
    }

    /** True when every element is finite — checked before a matrix is accepted from storage. */
    fun isFinite(): Boolean = m.all { it.isFinite() }

    override fun equals(other: Any?): Boolean =
        other is Mat3 && m.contentEquals(other.m)

    override fun hashCode(): Int = m.contentHashCode()

    companion object {
        val IDENTITY = Mat3(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))

        /**
         * Rotation matrix from a unit quaternion (w, x, y, z). Column-major axis convention
         * matches the Android rotation-vector sensor: q rotates *from* the device frame *to* world.
         */
        fun fromQuaternion(w: Float, x: Float, y: Float, z: Float): Mat3 {
            val n = sqrt(w * w + x * x + y * y + z * z)
            if (n < 1e-9f) return IDENTITY
            val qw = w / n; val qx = x / n; val qy = y / n; val qz = z / n
            return Mat3(
                floatArrayOf(
                    1f - 2f * (qy * qy + qz * qz), 2f * (qx * qy - qz * qw), 2f * (qx * qz + qy * qw),
                    2f * (qx * qy + qz * qw), 1f - 2f * (qx * qx + qz * qz), 2f * (qy * qz - qx * qw),
                    2f * (qx * qz - qy * qw), 2f * (qy * qz + qx * qw), 1f - 2f * (qx * qx + qy * qy),
                ),
            )
        }

        /** Columns given explicitly, handy for hand-authored mounting presets. */
        fun fromColumns(xAxis: Vec3, yAxis: Vec3, zAxis: Vec3): Mat3 = Mat3(
            floatArrayOf(
                xAxis.x, yAxis.x, zAxis.x,
                xAxis.y, yAxis.y, zAxis.y,
                xAxis.z, yAxis.z, zAxis.z,
            ),
        )
    }
}

/** True when two floats agree to within [eps] — used by tests to compare filter outputs. */
fun approx(a: Float, b: Float, eps: Float = 1e-4f): Boolean = abs(a - b) <= eps