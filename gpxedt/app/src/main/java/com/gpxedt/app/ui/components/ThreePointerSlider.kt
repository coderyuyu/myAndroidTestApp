package com.gpxedt.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

val StartPointerColor = Color(0xFF4CAF50) // Green for Start
val MiddlePointerColor = Color(0xFF2196F3) // Blue for WPT
val EndPointerColor = Color(0xFFF44336) // Red for End

private enum class PointerType {
    START, MIDDLE, END
}

@Composable
fun ThreePointerSlider(
    startIndex: Int,
    middleIndex: Int,
    endIndex: Int,
    maxIndex: Int,
    onStartChanged: (Int) -> Unit,
    onMiddleChanged: (Int) -> Unit,
    onEndChanged: (Int) -> Unit,
    onPointerMoving: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (maxIndex <= 0) return

    val density = LocalDensity.current
    val thumbSize = 28.dp
    val thumbSizePx = with(density) { thumbSize.toPx() }

    // Use rememberUpdatedState to avoid stale closures in gesture callbacks
    val currentStart by rememberUpdatedState(startIndex)
    val currentMiddle by rememberUpdatedState(middleIndex)
    val currentEnd by rememberUpdatedState(endIndex)
    val currentMax by rememberUpdatedState(maxIndex)

    var activeDraggingPointer by remember { mutableStateOf<PointerType?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        // Pointer index badges row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = StartPointerColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "Start: #$startIndex",
                    color = StartPointerColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Surface(
                color = MiddlePointerColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "WPT: #$middleIndex",
                    color = MiddlePointerColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Surface(
                color = EndPointerColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "End: #$endIndex",
                    color = EndPointerColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        // Draggable 3-pointer track container
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            val trackWidthPx = (constraints.maxWidth.toFloat() - thumbSizePx).coerceAtLeast(1f)

            fun idxToX(idx: Int): Float {
                return (idx.toFloat() / currentMax.coerceAtLeast(1)) * trackWidthPx
            }

            fun xToIdx(xPx: Float): Int {
                val fraction = (xPx / trackWidthPx).coerceIn(0f, 1f)
                return (fraction * currentMax).roundToInt().coerceIn(0, currentMax)
            }

            val startX = idxToX(startIndex)
            val middleX = idxToX(middleIndex)
            val endX = idxToX(endIndex)

            // 1. Background inactive track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .padding(horizontal = thumbSize / 2)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(3.dp)
                    )
            )

            // 2. Active highlighted track segment between Start and End
            val activeStartDp = with(density) { (thumbSizePx / 2 + startX).toDp() }
            val activeWidthDp = with(density) { (endX - startX).coerceAtLeast(0f).toDp() }
            Box(
                modifier = Modifier
                    .padding(start = activeStartDp)
                    .size(width = activeWidthDp, height = 6.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                        RoundedCornerShape(3.dp)
                    )
            )

            // 3. Render Thumbs (Green - Start, Blue - WPT, Red - End)
            // Left Thumb (Start - Green)
            ThumbView(
                xOffset = startX,
                thumbSize = thumbSize,
                color = StartPointerColor,
                label = "S",
                isHighlighted = activeDraggingPointer == PointerType.START
            )

            // Right Thumb (End - Red)
            ThumbView(
                xOffset = endX,
                thumbSize = thumbSize,
                color = EndPointerColor,
                label = "E",
                isHighlighted = activeDraggingPointer == PointerType.END
            )

            // Middle Thumb (WPT - Blue)
            ThumbView(
                xOffset = middleX,
                thumbSize = thumbSize,
                color = MiddlePointerColor,
                label = "W",
                isHighlighted = activeDraggingPointer == PointerType.MIDDLE
            )

            // 4. Unified Touch & Drag Overlay: handles both instantaneous tap and smooth continuous sliding!
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .pointerInput(trackWidthPx, currentMax) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val touchX = (down.position.x - thumbSizePx / 2).coerceIn(0f, trackWidthPx)
                            val targetIdx = xToIdx(touchX)

                            val sX = idxToX(currentStart)
                            val mX = idxToX(currentMiddle)
                            val eX = idxToX(currentEnd)

                            val draggingPointer = when {
                                touchX <= sX -> PointerType.START
                                touchX >= eX -> PointerType.END
                                else -> {
                                    val dStart = abs(touchX - sX)
                                    val dMiddle = abs(touchX - mX)
                                    val dEnd = abs(touchX - eX)

                                    if (dStart < dMiddle && dStart < dEnd) {
                                        PointerType.START
                                    } else if (dEnd < dStart && dEnd < dMiddle) {
                                        PointerType.END
                                    } else {
                                        PointerType.MIDDLE
                                    }
                                }
                            }

                            activeDraggingPointer = draggingPointer

                            // Immediate update on touch down (tap / click)
                            when (draggingPointer) {
                                PointerType.START -> {
                                    val clamped = targetIdx.coerceIn(0, currentEnd)
                                    if (clamped != currentStart) {
                                        onStartChanged(clamped)
                                        onPointerMoving(clamped)
                                    }
                                }
                                PointerType.MIDDLE -> {
                                    val clamped = targetIdx.coerceIn(currentStart, currentEnd)
                                    if (clamped != currentMiddle) {
                                        onMiddleChanged(clamped)
                                        onPointerMoving(clamped)
                                    }
                                }
                                PointerType.END -> {
                                    val clamped = targetIdx.coerceIn(currentStart, currentMax)
                                    if (clamped != currentEnd) {
                                        onEndChanged(clamped)
                                        onPointerMoving(clamped)
                                    }
                                }
                            }

                            // Continuous smooth sliding as finger moves
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        break
                                    }
                                    change.consume()

                                    val curTouchX = (change.position.x - thumbSizePx / 2).coerceIn(0f, trackWidthPx)
                                    val curTargetIdx = xToIdx(curTouchX)

                                    when (draggingPointer) {
                                        PointerType.START -> {
                                            val clamped = curTargetIdx.coerceIn(0, currentEnd)
                                            if (clamped != currentStart) {
                                                onStartChanged(clamped)
                                                onPointerMoving(clamped)
                                            }
                                        }
                                        PointerType.MIDDLE -> {
                                            val clamped = curTargetIdx.coerceIn(currentStart, currentEnd)
                                            if (clamped != currentMiddle) {
                                                onMiddleChanged(clamped)
                                                onPointerMoving(clamped)
                                            }
                                        }
                                        PointerType.END -> {
                                            val clamped = curTargetIdx.coerceIn(currentStart, currentMax)
                                            if (clamped != currentEnd) {
                                                onEndChanged(clamped)
                                                onPointerMoving(clamped)
                                            }
                                        }
                                    }
                                }
                            } finally {
                                activeDraggingPointer = null
                            }
                        }
                    }
            )
        }
    }
}

@Composable
private fun ThumbView(
    xOffset: Float,
    thumbSize: androidx.compose.ui.unit.Dp,
    color: Color,
    label: String,
    isHighlighted: Boolean
) {
    val scaleFactor = if (isHighlighted) 1.15f else 1f
    val currentThumbSize = thumbSize * scaleFactor

    Box(
        modifier = Modifier
            .offset { IntOffset((xOffset - (currentThumbSize.toPx() - thumbSize.toPx()) / 2).roundToInt(), 0) }
            .size(currentThumbSize)
            .shadow(if (isHighlighted) 8.dp else 4.dp, CircleShape)
            .background(color, CircleShape)
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = if (isHighlighted) 12.sp else 11.sp,
            fontWeight = FontWeight.Black
        )
    }
}
