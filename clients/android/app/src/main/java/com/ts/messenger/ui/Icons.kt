package com.ts.messenger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class IconKind { Back, Send, Plus, Shield, Phone, More, Download, Mic, MicOff, Speaker, Lock, ShieldLock, Video, VideoOff }

/** Small built-in icon set drawn on a 24-unit grid, so no icon library is needed. */
@Composable
fun TsIcon(kind: IconKind, tint: Color, iconSize: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(iconSize)) {
        val unit = this.size.minDimension / 24f
        val line = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        scale(scale = unit, pivot = Offset.Zero) {
            when (kind) {
                IconKind.Back -> drawPath(
                    Path().apply { moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) },
                    tint, style = line,
                )
                IconKind.Send -> drawPath(
                    Path().apply { moveTo(3f, 20f); lineTo(21f, 12f); lineTo(3f, 4f); lineTo(6f, 12f); close() },
                    tint,
                )
                IconKind.Plus -> {
                    drawLine(tint, Offset(12f, 5f), Offset(12f, 19f), 2f, StrokeCap.Round)
                    drawLine(tint, Offset(5f, 12f), Offset(19f, 12f), 2f, StrokeCap.Round)
                }
                IconKind.Shield -> {
                    drawPath(
                        Path().apply {
                            moveTo(12f, 3f); lineTo(19f, 6f); lineTo(19f, 12f)
                            cubicTo(19f, 16.5f, 15.5f, 19.5f, 12f, 21f)
                            cubicTo(8.5f, 19.5f, 5f, 16.5f, 5f, 12f)
                            lineTo(5f, 6f); close()
                        },
                        tint, style = line,
                    )
                    drawPath(
                        Path().apply { moveTo(9f, 12f); lineTo(11.2f, 14.2f); lineTo(15f, 10f) },
                        tint, style = line,
                    )
                }
                IconKind.Phone -> {
                    drawPath(
                        Path().apply { moveTo(6f, 4.5f); cubicTo(4f, 12f, 12f, 20f, 19.5f, 18f) },
                        tint, style = Stroke(width = 3.2f, cap = StrokeCap.Round),
                    )
                    drawCircle(tint, 2.3f, Offset(6f, 4.5f))
                    drawCircle(tint, 2.3f, Offset(19.5f, 18f))
                }
                IconKind.More -> {
                    drawCircle(tint, 1.8f, Offset(12f, 5f))
                    drawCircle(tint, 1.8f, Offset(12f, 12f))
                    drawCircle(tint, 1.8f, Offset(12f, 19f))
                }
                IconKind.Download -> {
                    drawLine(tint, Offset(12f, 4f), Offset(12f, 15f), 2f, StrokeCap.Round)
                    drawPath(Path().apply { moveTo(7f, 11f); lineTo(12f, 16f); lineTo(17f, 11f) }, tint, style = line)
                    drawLine(tint, Offset(5f, 20f), Offset(19f, 20f), 2f, StrokeCap.Round)
                }
                IconKind.Mic, IconKind.MicOff -> {
                    drawRoundRect(tint, Offset(9f, 3f), Size(6f, 11f), CornerRadius(3f, 3f), style = line)
                    drawPath(Path().apply { moveTo(6f, 11f); cubicTo(6f, 18f, 18f, 18f, 18f, 11f) }, tint, style = line)
                    drawLine(tint, Offset(12f, 17f), Offset(12f, 21f), 2f, StrokeCap.Round)
                    if (kind == IconKind.MicOff) {
                        drawLine(tint, Offset(4f, 4f), Offset(20f, 20f), 2.2f, StrokeCap.Round)
                    }
                }
                IconKind.Lock -> {
                    drawRoundRect(tint, Offset(5f, 10.5f), Size(14f, 10f), CornerRadius(2.5f, 2.5f), style = line)
                    drawPath(Path().apply { moveTo(8f, 10.5f); lineTo(8f, 8f); cubicTo(8f, 3.5f, 16f, 3.5f, 16f, 8f); lineTo(16f, 10.5f) }, tint, style = line)
                    drawCircle(tint, 1.4f, Offset(12f, 15.5f))
                }
                IconKind.ShieldLock -> {
                    drawPath(
                        Path().apply {
                            moveTo(12f, 2.5f); lineTo(20f, 5.5f); lineTo(20f, 12f)
                            cubicTo(20f, 17f, 16.2f, 20.5f, 12f, 22f)
                            cubicTo(7.8f, 20.5f, 4f, 17f, 4f, 12f)
                            lineTo(4f, 5.5f); close()
                        },
                        tint, style = line,
                    )
                    drawRoundRect(tint, Offset(8.5f, 11f), Size(7f, 5.5f), CornerRadius(1.2f, 1.2f))
                    drawPath(Path().apply { moveTo(9.8f, 11f); lineTo(9.8f, 9.6f); cubicTo(9.8f, 6.8f, 14.2f, 6.8f, 14.2f, 9.6f); lineTo(14.2f, 11f) }, tint, style = Stroke(width = 1.4f, cap = StrokeCap.Round))
                }
                IconKind.Video, IconKind.VideoOff -> {
                    drawRoundRect(tint, Offset(3f, 7f), Size(12f, 10f), CornerRadius(2.5f, 2.5f), style = line)
                    drawPath(Path().apply { moveTo(15f, 11f); lineTo(21f, 7.5f); lineTo(21f, 16.5f); lineTo(15f, 13f) }, tint, style = line)
                    if (kind == IconKind.VideoOff) drawLine(tint, Offset(4f, 4f), Offset(20f, 20f), 2f, StrokeCap.Round)
                }
                IconKind.Speaker -> {
                    drawPath(
                        Path().apply {
                            moveTo(4f, 9f); lineTo(8f, 9f); lineTo(13f, 5f); lineTo(13f, 19f)
                            lineTo(8f, 15f); lineTo(4f, 15f); close()
                        },
                        tint, style = line,
                    )
                    drawPath(Path().apply { moveTo(16.5f, 9f); cubicTo(18.5f, 10.5f, 18.5f, 13.5f, 16.5f, 15f) }, tint, style = line)
                    drawPath(Path().apply { moveTo(18.5f, 6f); cubicTo(22f, 9f, 22f, 15f, 18.5f, 18f) }, tint, style = line)
                }
            }
        }
    }
}
