package com.imdadburki.cropcast.core.common

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Normalized crop rectangle: all values are fractions of the captured frame
 * (0.0..1.0). This is the single source of truth for the selected region —
 * it survives rotation, density and resolution changes because it never
 * stores pixels. Pure Kotlin: unit-tested on the JVM, no Android dependency.
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val aspectRatio: Float get() = if (height > 0f) width / height else 0f
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f

    fun isValid(): Boolean =
        width > 0f && height > 0f &&
            left >= 0f && top >= 0f && right <= 1f + 1e-6f && bottom <= 1f + 1e-6f

    /** Clamp the rect fully inside the 0..1 frame, preserving size where possible. */
    fun clamped(): NormalizedRect {
        val w = width.coerceIn(0f, 1f)
        val h = height.coerceIn(0f, 1f)
        return copy(
            left = left.coerceIn(0f, 1f - w),
            top = top.coerceIn(0f, 1f - h),
            width = w,
            height = h,
        )
    }

    /** Enforce a minimum size (given in normalized units), growing around the center. */
    fun withMinSize(minWidth: Float, minHeight: Float): NormalizedRect {
        val w = max(width, minWidth)
        val h = max(height, minHeight)
        return copy(
            left = (centerX - w / 2f).coerceIn(0f, 1f - w),
            top = (centerY - h / 2f).coerceIn(0f, 1f - h),
            width = w,
            height = h,
        )
    }

    /**
     * Snap to a target aspect ratio (width/height), keeping the center.
     * Shrinks the longer side; never grows outside the frame.
     */
    fun snappedToAspectRatio(targetAspect: Float): NormalizedRect {
        require(targetAspect > 0f) { "aspect ratio must be positive" }
        val current = aspectRatio
        if (abs(current - targetAspect) < 1e-4f) return this
        return if (current > targetAspect) {
            // Too wide: shrink width.
            val newWidth = height * targetAspect
            copy(left = (centerX - newWidth / 2f).coerceIn(0f, 1f - newWidth), width = newWidth)
        } else {
            // Too tall: shrink height.
            val newHeight = width / targetAspect
            copy(top = (centerY - newHeight / 2f).coerceIn(0f, 1f - newHeight), height = newHeight)
        }.clamped()
    }

    /** Pixel rect inside a frame of [frameWidth] x [frameHeight]. */
    fun toPixelRect(frameWidth: Int, frameHeight: Int): PixelRect {
        val l = (left * frameWidth).toInt().coerceIn(0, frameWidth - 1)
        val t = (top * frameHeight).toInt().coerceIn(0, frameHeight - 1)
        val r = (right * frameWidth).toInt().coerceIn(l + 1, frameWidth)
        val b = (bottom * frameHeight).toInt().coerceIn(t + 1, frameHeight)
        return PixelRect(l, t, r - l, b - t)
    }

    /**
     * Rotate the rect for a display rotation change. [rotationDegrees] is the
     * clockwise rotation of the frame (0, 90, 180, 270). A 90/270 rotation
     * swaps the axes.
     */
    fun rotated(rotationDegrees: Int): NormalizedRect = when ((rotationDegrees % 360 + 360) % 360) {
        0 -> this
        90 -> NormalizedRect(top, 1f - right, height, width)
        180 -> NormalizedRect(1f - right, 1f - bottom, width, height)
        270 -> NormalizedRect(1f - bottom, left, height, width)
        else -> throw IllegalArgumentException("rotation must be 0/90/180/270, was $rotationDegrees")
    }
}

data class PixelRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

/** Aspect-ratio presets from the spec (Recording Setup, mode B). */
enum class AspectPreset(val label: String, val ratio: Float?) {
    FREE("Free", null),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_9_16("9:16", 9f / 16f),
    RATIO_1_1("1:1", 1f),
    RATIO_4_3("4:3", 4f / 3f),
}

/** Recording quality presets (spec §7). */
enum class QualityPreset(val label: String, val width: Int, val height: Int, val fps: Int, val bitrate: Int) {
    STANDARD("Standard · 720p30", 1280, 720, 30, 6_000_000),
    HIGH("High · 1080p30", 1920, 1080, 30, 12_000_000),
    SMOOTH("Smooth · 1080p60", 1920, 1080, 60, 20_000_000),
}

/** Audio capture modes (spec §5). */
enum class AudioMode(val label: String) {
    NONE("No audio"),
    MICROPHONE("Microphone"),
    INTERNAL("Internal audio (Android 10+)"),
}

/** Recording modes (spec §4, Screen 04). */
enum class RecordingMode(val label: String) {
    CUSTOM_REGION("Custom Region"),
    FIXED_ASPECT("Fixed Aspect Ratio"),
    FULL_SCREEN("Full Screen"),
    APP_WINDOW("App Window"),
}

/** Recording file name: CROPCAST_yyyyMMdd_HHmmss.mp4 (local time, no PII). */
fun recordingFileName(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): String {
    fun p(n: Int) = n.toString().padStart(2, '0')
    return "CROPCAST_${year}${p(month)}${p(day)}_${p(hour)}${p(minute)}${p(second)}.mp4"
}
