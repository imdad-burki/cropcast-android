package com.imdadburki.cropcast.feature.region

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.imdadburki.cropcast.core.common.AspectPreset
import com.imdadburki.cropcast.core.common.NormalizedRect
import com.imdadburki.cropcast.core.database.RecordingSettingsDao
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val MIN_SIZE_PX = 120f
private const val HANDLE_RADIUS_PX = 28f

private enum class DragTarget { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, TOP, BOTTOM, LEFT, RIGHT }

/**
 * Region Selection (spec §4, Screen 05) — the heart of CROPCAST.
 * Full-screen selector: dimmed surround, rect boundary, 4 corner + 4 edge
 * handles, center drag, live W×H label, aspect lock, reset, confirm, cancel.
 * Geometry is NormalizedRect (unit-tested in :core:common); the selector only
 * converts touch input to normalized coordinates. The selector is a
 * configuration overlay — it is hidden before recording begins, and the app
 * never promises overlays are excluded from capture.
 */
@Composable
fun RegionSelectionScreen(
    settingsDao: RecordingSettingsDao,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val screenW = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenH = with(density) { configuration.screenHeightDp.dp.toPx() }
    val minW = MIN_SIZE_PX / screenW
    val minH = MIN_SIZE_PX / screenH

    var rect by remember { mutableStateOf(NormalizedRect(0.10f, 0.15f, 0.60f, 0.55f)) }
    var aspectLock by remember { mutableStateOf<Float?>(null) }
    var dragTarget by remember { mutableStateOf(DragTarget.NONE) }

    LaunchedEffect(Unit) {
        settingsDao.get()?.let {
            rect = NormalizedRect(it.regionLeft, it.regionTop, it.regionWidth, it.regionHeight)
                .clamped().withMinSize(minW, minH)
            aspectLock = it.aspectRatio
        }
    }

    fun toPx(r: NormalizedRect): Rect =
        Rect(r.left * screenW, r.top * screenH, r.right * screenW, r.bottom * screenH)

    fun fromPx(r: Rect): NormalizedRect =
        NormalizedRect(r.left / screenW, r.top / screenH, r.width / screenW, r.height / screenH)
            .clamped().withMinSize(minW, minH)

    fun applyAspect(r: NormalizedRect): NormalizedRect {
        val target = aspectLock ?: return r
        return r.snappedToAspectRatio(target)
    }

    fun pickTarget(pos: Offset, px: Rect): DragTarget {
        val handles = mapOf(
            DragTarget.TOP_LEFT to Offset(px.left, px.top),
            DragTarget.TOP_RIGHT to Offset(px.right, px.top),
            DragTarget.BOTTOM_LEFT to Offset(px.left, px.bottom),
            DragTarget.BOTTOM_RIGHT to Offset(px.right, px.bottom),
            DragTarget.TOP to Offset(px.center.x, px.top),
            DragTarget.BOTTOM to Offset(px.center.x, px.bottom),
            DragTarget.LEFT to Offset(px.left, px.center.y),
            DragTarget.RIGHT to Offset(px.right, px.center.y),
        )
        for ((target, point) in handles) {
            if ((pos - point).getDistance() <= HANDLE_RADIUS_PX + 16f) return target
        }
        return if (px.contains(pos)) DragTarget.MOVE else DragTarget.NONE
    }

    fun dragBy(target: DragTarget, px: Rect, dx: Float, dy: Float): Rect {
        val minWPx = MIN_SIZE_PX
        val minHPx = MIN_SIZE_PX
        return when (target) {
            DragTarget.MOVE -> px.translate(dx, dy).let {
                val w = it.width
                val h = it.height
                Rect(
                    it.left.coerceIn(0f, screenW - w),
                    it.top.coerceIn(0f, screenH - h),
                    it.left.coerceIn(0f, screenW - w) + w,
                    it.top.coerceIn(0f, screenH - h) + h,
                )
            }
            DragTarget.TOP_LEFT -> Rect(
                (px.left + dx).coerceIn(0f, px.right - minWPx),
                (px.top + dy).coerceIn(0f, px.bottom - minHPx),
                px.right, px.bottom,
            )
            DragTarget.TOP_RIGHT -> Rect(
                px.left,
                (px.top + dy).coerceIn(0f, px.bottom - minHPx),
                (px.right + dx).coerceIn(px.left + minWPx, screenW),
                px.bottom,
            )
            DragTarget.BOTTOM_LEFT -> Rect(
                (px.left + dx).coerceIn(0f, px.right - minWPx),
                px.top,
                px.right,
                (px.bottom + dy).coerceIn(px.top + minHPx, screenH),
            )
            DragTarget.BOTTOM_RIGHT -> Rect(
                px.left, px.top,
                (px.right + dx).coerceIn(px.left + minWPx, screenW),
                (px.bottom + dy).coerceIn(px.top + minHPx, screenH),
            )
            DragTarget.TOP -> Rect(
                px.left,
                (px.top + dy).coerceIn(0f, px.bottom - minHPx),
                px.right, px.bottom,
            )
            DragTarget.BOTTOM -> Rect(
                px.left, px.top, px.right,
                (px.bottom + dy).coerceIn(px.top + minHPx, screenH),
            )
            DragTarget.LEFT -> Rect(
                (px.left + dx).coerceIn(0f, px.right - minWPx),
                px.top, px.right, px.bottom,
            )
            DragTarget.RIGHT -> Rect(
                px.left, px.top,
                (px.right + dx).coerceIn(px.left + minWPx, screenW),
                px.bottom,
            )
            DragTarget.NONE -> px
        }
    }

    val pxRect = toPx(rect)
    val pxW = pxRect.width.toInt()
    val pxH = pxRect.height.toInt()

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .pointerInput(rect, aspectLock) {
                    detectDragGestures(
                        onDragStart = { pos -> dragTarget = pickTarget(pos, toPx(rect)) },
                        onDragEnd = { dragTarget = DragTarget.NONE },
                        onDragCancel = { dragTarget = DragTarget.NONE },
                    ) { change, dragAmount ->
                        change.consume()
                        if (dragTarget == DragTarget.NONE) return@detectDragGestures
                        val next = applyAspect(fromPx(dragBy(dragTarget, toPx(rect), dragAmount.x, dragAmount.y)))
                        rect = next
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = toPx(rect)
                // Dim everything outside the selection.
                drawRect(Color.Black.copy(alpha = 0.55f), size = Size(size.width, r.top))
                drawRect(
                    Color.Black.copy(alpha = 0.55f),
                    topLeft = Offset(0f, r.bottom),
                    size = Size(size.width, size.height - r.bottom),
                )
                drawRect(
                    Color.Black.copy(alpha = 0.55f),
                    topLeft = Offset(0f, r.top),
                    size = Size(r.left, r.height),
                )
                drawRect(
                    Color.Black.copy(alpha = 0.55f),
                    topLeft = Offset(r.right, r.top),
                    size = Size(size.width - r.right, r.height),
                )
                // Selection boundary.
                drawRect(
                    color = Color.White,
                    topLeft = Offset(r.left, r.top),
                    size = Size(r.width, r.height),
                    style = Stroke(width = 3f),
                )
                drawRect(
                    color = Color(0xFFFF453A),
                    topLeft = Offset(r.left, r.top),
                    size = Size(r.width, r.height),
                    style = Stroke(width = 1.5f),
                )
                // Handles: 4 corners + 4 edges.
                val points = listOf(
                    Offset(r.left, r.top), Offset(r.right, r.top),
                    Offset(r.left, r.bottom), Offset(r.right, r.bottom),
                    Offset(r.center.x, r.top), Offset(r.center.x, r.bottom),
                    Offset(r.left, r.center.y), Offset(r.right, r.center.y),
                )
                for (p in points) {
                    drawCircle(Color.White, radius = HANDLE_RADIUS_PX, center = p)
                    drawCircle(Color(0xFFFF453A), radius = HANDLE_RADIUS_PX - 6f, center = p)
                }
            }
            Text(
                text = "$pxW × $pxH",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row {
                OutlinedButton(
                    onClick = {
                        aspectLock = if (aspectLock == null) 16f / 9f else null
                        if (aspectLock != null) rect = applyAspect(rect)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (aspectLock == null) "Aspect: Free" else "Aspect: 16:9 locked")
                }
                Spacer(modifier = Modifier.weight(0.2f))
                OutlinedButton(
                    onClick = {
                        rect = NormalizedRect(0.10f, 0.15f, 0.60f, 0.55f)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Reset") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (abs(rect.aspectRatio - 16f / 9f) < 0.01f) "16:9" else "Custom region",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Spacer(modifier = Modifier.weight(0.2f))
                Button(
                    onClick = {
                        scope.launch {
                            settingsDao.get()?.let {
                                settingsDao.save(it.copy(regionLeft = rect.left, regionTop = rect.top, regionWidth = rect.width, regionHeight = rect.height, aspectRatio = aspectLock))
                            }
                            onConfirm()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Confirm") }
            }
        }
    }
}
