package com.outdoor.mapyclone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.outdoor.mapyclone.model.TrackPoint

@Composable
fun ElevationChart(points: List<TrackPoint>, modifier: Modifier = Modifier) {
    if (points.size < 2) return

    val minEle = points.minOf { it.ele }
    val maxEle = points.maxOf { it.ele }
    val eleRange = (maxEle - minEle).coerceAtLeast(10.0)

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Min: ${minEle.toInt()} m",
                fontSize = 12.sp,
                color = Color.Gray
            )
            Text(
                text = "Profilo Altimetrico",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2E7D32)
            )
            Text(
                text = "Max: ${maxEle.toInt()} m",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
        ) {
            val width = size.width
            val height = size.height
            val path = Path()
            val fillPath = Path()

            points.forEachIndexed { index, pt ->
                val x = (index.toFloat() / (points.size - 1)) * width
                val normalizedY = ((pt.ele - minEle) / eleRange).toFloat()
                val y = height - (normalizedY * (height - 16f)) - 8f

                if (index == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, height)
                    fillPath.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo(width, height)
            fillPath.close()

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0x664CAF50), Color(0x0A4CAF50))
                )
            )
            drawPath(
                path = path,
                color = Color(0xFF2E7D32),
                style = Stroke(width = 3.dp.toPx())
            )
        }
    }
}
