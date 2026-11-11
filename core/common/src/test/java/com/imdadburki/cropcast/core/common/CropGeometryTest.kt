package com.imdadburki.cropcast.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CropGeometryTest {

    @Test
    fun `default region from spec example is valid`() {
        val r = NormalizedRect(0.10f, 0.15f, 0.60f, 0.55f)
        assertTrue(r.isValid())
        assertEquals(0.60f / 0.55f, r.aspectRatio, 1e-4f)
    }

    @Test
    fun `clamped keeps rect inside frame`() {
        val r = NormalizedRect(-0.2f, 0.9f, 0.6f, 0.4f).clamped()
        assertTrue(r.isValid())
        assertEquals(0f, r.left, 1e-6f)
        assertEquals(0.6f, r.top, 1e-6f)
    }

    @Test
    fun `snap to 16-9 shrinks width keeping center`() {
        val r = NormalizedRect(0.1f, 0.1f, 0.8f, 0.4f).snappedToAspectRatio(16f / 9f)
        assertEquals(16f / 9f, r.aspectRatio, 1e-3f)
        assertEquals(0.5f, r.centerX, 1e-4f)
    }

    @Test
    fun `snap to 9-16 shrinks height keeping center`() {
        val r = NormalizedRect(0.2f, 0.1f, 0.4f, 0.8f).snappedToAspectRatio(9f / 16f)
        assertEquals(9f / 16f, r.aspectRatio, 1e-3f)
        assertEquals(0.5f, r.centerY, 1e-4f)
    }

    @Test
    fun `pixel rect matches normalized coords`() {
        val p = NormalizedRect(0.10f, 0.15f, 0.60f, 0.55f).toPixelRect(1080, 1920)
        assertEquals(108, p.left)
        assertEquals(288, p.top)
        assertEquals(648, p.width)
        assertEquals(1056, p.height)
    }

    @Test
    fun `rotation 90 swaps axes`() {
        val r = NormalizedRect(0.1f, 0.2f, 0.6f, 0.3f).rotated(90)
        assertEquals(0.2f, r.left, 1e-6f)
        assertEquals(0.3f, r.top, 1e-6f)
        assertEquals(0.3f, r.width, 1e-6f)
        assertEquals(0.6f, r.height, 1e-6f)
    }

    @Test
    fun `rotation 360 is identity`() {
        val r = NormalizedRect(0.1f, 0.2f, 0.6f, 0.3f)
        assertEquals(r, r.rotated(360))
        assertEquals(r, r.rotated(0))
    }

    @Test
    fun `min size grows rect around center`() {
        // Rect centered exactly at 0.5, 0.5 — growing it must keep the center.
        val r = NormalizedRect(0.475f, 0.475f, 0.05f, 0.05f).withMinSize(0.2f, 0.2f)
        assertEquals(0.2f, r.width, 1e-6f)
        assertEquals(0.2f, r.height, 1e-6f)
        assertEquals(0.5f, r.centerX, 1e-4f)
    }

    @Test
    fun `invalid rect detected`() {
        assertFalse(NormalizedRect(0.5f, 0.5f, 0.6f, 0.6f).isValid())
        assertFalse(NormalizedRect(0.1f, 0.1f, 0f, 0.5f).isValid())
    }

    @Test
    fun `file name format`() {
        assertEquals(
            "CROPCAST_20261110_182305.mp4",
            recordingFileName(2026, 11, 10, 18, 23, 5),
        )
    }
}
