package dev.lightsaber.motion

/**
 * The mounting presets shipped with the first release.
 *
 * A preset is a *mechanically defined* orientation: the user is told how the watch sits in the
 * cradle and the blade axis follows from the cradle's geometry. This is the deliberate alternative
 * to promising automatic calibration for an arbitrary strap angle — a stationary pose alone cannot
 * determine where the user thinks the blade points.
 *
 * Watch frame convention (Android): +x out of the right edge, +y out of the top edge, +z out of the
 * screen face.
 */
object MountPresets {

    /** Palm cradle, right hand, screen facing away from the palm. Blade runs up the forearm. */
    val PALM_RIGHT = MountPreset(
        id = "palm_right",
        label = "Palm cradle - right hand",
        instruction = "Screen faces out of your palm. The blade points from your wrist toward your fingertips.",
        transform = MountTransform(
            rotationWatchToBlade = Mat3.fromColumns(
                xAxis = Vec3(0f, 1f, 0f),
                yAxis = Vec3(1f, 0f, 0f),
                zAxis = Vec3(0f, 0f, -1f),
            ),
            handedness = Handedness.RIGHT,
        ),
    )

    /** Palm cradle, left hand. The mirror of [PALM_RIGHT]; handedness flips the transverse axis. */
    val PALM_LEFT = MountPreset(
        id = "palm_left",
        label = "Palm cradle - left hand",
        instruction = "Screen faces out of your palm. The blade points from your wrist toward your fingertips.",
        transform = MountTransform(
            rotationWatchToBlade = Mat3.fromColumns(
                xAxis = Vec3(0f, 1f, 0f),
                yAxis = Vec3(-1f, 0f, 0f),
                zAxis = Vec3(0f, 0f, 1f),
            ),
            handedness = Handedness.LEFT,
        ),
    )

    /** Straight wrist mount, screen facing the back of the wrist. The familiar default. */
    val WRIST_FACE_IN = MountPreset(
        id = "wrist_face_in",
        label = "Wrist - screen inward",
        instruction = "Worn normally, screen against the underside of your wrist. Blade points toward your fingertips.",
        transform = MountTransform(
            rotationWatchToBlade = Mat3.fromColumns(
                xAxis = Vec3(0f, -1f, 0f),
                yAxis = Vec3(1f, 0f, 0f),
                zAxis = Vec3(0f, 0f, 1f),
            ),
            handedness = Handedness.RIGHT,
        ),
    )

    /**
     * Back-of-hand cradle, screen facing out of the back of the hand. Here the blade runs out of
     * the screen face, so it shares **no axis** with the wrist and palm presets — which is what
     * makes it useful for catching a transform that quietly got transposed.
     */
    val BACK_OF_HAND = MountPreset(
        id = "back_of_hand",
        label = "Back-of-hand cradle",
        instruction = "Secured across the back of your hand, screen facing outward. The blade extends out of the back of your hand.",
        transform = MountTransform(
            rotationWatchToBlade = Mat3.fromColumns(
                xAxis = Vec3(0f, 0f, 1f),
                yAxis = Vec3(0f, 1f, 0f),
                zAxis = Vec3(-1f, 0f, 0f),
            ),
            handedness = Handedness.RIGHT,
        ),
    )

    val ALL = listOf(PALM_RIGHT, PALM_LEFT, WRIST_FACE_IN, BACK_OF_HAND)

    fun byId(id: String): MountPreset? = ALL.firstOrNull { it.id == id }

    /**
     * Checks that a preset really is a rotation: orthonormal columns, determinant +1, and the blade
     * axis perpendicular to both transverse axes. A preset that fails this would silently swap
     * "swing" for "twist" in the finished app.
     */
    fun validate(preset: MountPreset): String? {
        val r = preset.transform.rotationWatchToBlade
        val c0 = Vec3(r.m[0], r.m[3], r.m[6])
        val c1 = Vec3(r.m[1], r.m[4], r.m[7])
        val c2 = Vec3(r.m[2], r.m[5], r.m[8])
        if (kotlin.math.abs(c0.length() - 1f) > 1e-3f) return "${preset.id}: blade axis is not unit length"
        if (kotlin.math.abs(c1.length() - 1f) > 1e-3f) return "${preset.id}: first transverse axis is not unit length"
        if (kotlin.math.abs(c2.length() - 1f) > 1e-3f) return "${preset.id}: second transverse axis is not unit length"
        if (kotlin.math.abs(c0.dot(c1)) > 1e-3f) return "${preset.id}: blade axis is not perpendicular to transverse 1"
        if (kotlin.math.abs(c0.dot(c2)) > 1e-3f) return "${preset.id}: blade axis is not perpendicular to transverse 2"
        val det = c0.dot(c1.cross(c2))
        if (kotlin.math.abs(det - 1f) > 1e-3f) return "${preset.id}: determinant is $det, expected +1 (handedness flipped)"
        return null
    }
}