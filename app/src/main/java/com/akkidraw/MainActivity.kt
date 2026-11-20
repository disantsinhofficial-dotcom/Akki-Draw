package com.akkidraw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max

private enum class DrawTool { SELECT, FREEHAND, RECTANGLE, ELLIPSE }
private data class DrawStroke(val points: List<Offset>)
private data class DrawShape(val type: DrawTool, val start: Offset, val end: Offset)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AkkiDrawScreen() }
    }
}

@Composable
private fun AkkiDrawScreen() {
    var tool by remember { mutableStateOf(DrawTool.FREEHAND) }
    var strokes by remember { mutableStateOf(listOf<DrawStroke>()) }
    var shapes by remember { mutableStateOf(listOf<DrawShape>()) }
    var activePoints by remember { mutableStateOf(emptyList<Offset>()) }
    var shapeStart by remember { mutableStateOf<Offset?>(null) }
    var shapeEnd by remember { mutableStateOf<Offset?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var status by remember { mutableStateOf("Ready to draw") }

    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF3157D5), background = Color(0xFFF3F5F9))) {
        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Column {
                        Text("Akki Draw", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("Vector studio · Phase 1", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                })
            },
            bottomBar = {
                Surface(shadowElevation = 8.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        ToolChip("Draw", tool == DrawTool.FREEHAND) { tool = DrawTool.FREEHAND }
                        ToolChip("Rect", tool == DrawTool.RECTANGLE) { tool = DrawTool.RECTANGLE }
                        ToolChip("Oval", tool == DrawTool.ELLIPSE) { tool = DrawTool.ELLIPSE }
                        ToolChip("Select", tool == DrawTool.SELECT) { tool = DrawTool.SELECT }
                        TextButton(onClick = {
                            if (strokes.isNotEmpty()) strokes = strokes.dropLast(1)
                            else if (shapes.isNotEmpty()) shapes = shapes.dropLast(1)
                            status = "Undo"
                        }) { Text("Undo") }
                    }
                }
            }
        ) { insets ->
            Column(Modifier.fillMaxSize().background(Color(0xFFF3F5F9)).padding(insets)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(status, fontSize = 12.sp, color = Color(0xFF626B7A))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { zoom = (zoom / 1.2f).coerceAtLeast(.5f) }) { Text("−") }
                        Text("${(zoom * 100).toInt()}%", fontSize = 12.sp)
                        TextButton(onClick = { zoom = (zoom * 1.2f).coerceAtMost(4f) }) { Text("+") }
                    }
                }
                Box(Modifier.fillMaxSize().padding(12.dp).background(Color(0xFFE6EAF1), RoundedCornerShape(18.dp)).padding(10.dp)) {
                    Canvas(Modifier.fillMaxSize().background(Color.White, RoundedCornerShape(8.dp))
                        .pointerInput(tool) {
                            detectDragGestures(
                                onDragStart = { p ->
                                    when (tool) {
                                        DrawTool.FREEHAND -> activePoints = listOf(p)
                                        DrawTool.RECTANGLE, DrawTool.ELLIPSE -> { shapeStart = p; shapeEnd = p }
                                        else -> Unit
                                    }
                                },
                                onDrag = { change, _ ->
                                    when (tool) {
                                        DrawTool.FREEHAND -> activePoints = activePoints + change.position
                                        DrawTool.RECTANGLE, DrawTool.ELLIPSE -> shapeEnd = change.position
                                        else -> Unit
                                    }
                                    change.consume()
                                },
                                onDragEnd = {
                                    if (tool == DrawTool.FREEHAND && activePoints.size > 1) strokes = strokes + DrawStroke(activePoints)
                                    if ((tool == DrawTool.RECTANGLE || tool == DrawTool.ELLIPSE) && shapeStart != null && shapeEnd != null)
                                        shapes = shapes + DrawShape(tool, shapeStart!!, shapeEnd!!)
                                    activePoints = emptyList()
                                    shapeStart = null
                                    shapeEnd = null
                                    status = "Drawing added"
                                }
                            )
                        }
                    ) {
                        strokes.forEach { s ->
                            if (s.points.size > 1) {
                                val path = Path().apply {
                                    moveTo(s.points.first().x, s.points.first().y)
                                    s.points.drop(1).forEach { lineTo(it.x, it.y) }
                                }
                                drawPath(path, Color(0xFF2563EB), style = Stroke(5f, cap = StrokeCap.Round))
                            }
                        }
                        shapes.forEach { drawItem ->
                            val l = minOf(drawItem.start.x, drawItem.end.x)
                            val t = minOf(drawItem.start.y, drawItem.end.y)
                            val sz = Size(max(1f, abs(drawItem.end.x - drawItem.start.x)), max(1f, abs(drawItem.end.y - drawItem.start.y)))
                            if (drawItem.type == DrawTool.RECTANGLE) drawRect(Color(0xFF2563EB), Offset(l, t), sz, style = Stroke(4f))
                            else drawOval(Color(0xFF2563EB), Offset(l, t), sz, style = Stroke(4f))
                        }
                        if (activePoints.size > 1) {
                            val path = Path().apply {
                                moveTo(activePoints.first().x, activePoints.first().y)
                                activePoints.drop(1).forEach { lineTo(it.x, it.y) }
                            }
                            drawPath(path, Color(0xFF2563EB), style = Stroke(5f, cap = StrokeCap.Round))
                        }
                        val a = shapeStart
                        val b = shapeEnd
                        if (a != null && b != null) {
                            val l = minOf(a.x, b.x); val t = minOf(a.y, b.y)
                            val sz = Size(max(1f, abs(b.x - a.x)), max(1f, abs(b.y - a.y)))
                            if (tool == DrawTool.RECTANGLE) drawRect(Color(0xFF2563EB), Offset(l, t), sz, style = Stroke(4f))
                            else drawOval(Color(0xFF2563EB), Offset(l, t), sz, style = Stroke(4f))
                        }
                    }
                    if (strokes.isEmpty() && shapes.isEmpty() && activePoints.isEmpty())
                        Text("Choose a tool below and start drawing", Modifier.align(Alignment.Center).padding(24.dp),
                            color = Color(0xFF9AA3B2), fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun ToolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (selected) Color(0xFFDCE5FF) else Color(0xFFF1F3F7),
            contentColor = if (selected) Color(0xFF2449C5) else Color(0xFF333B49)
        ), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
        Text(label, fontSize = 12.sp)
    }
}
