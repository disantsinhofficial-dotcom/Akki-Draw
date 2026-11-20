package com.akkidraw

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import android.graphics.Path as NativePath
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import com.caverock.androidsvg.SVG
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class Tool { SELECT, NODE, PEN, DRAW, RECT, ELLIPSE, LINE }
private data class Item(
    val type: String,
    val points: List<Offset>,
    val color: Long = 0xFF2563EB,
    val stroke: Float = 4f,
    val bitmap: Bitmap? = null,
    val fillColor: Long? = null,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val name: String = type
) {
    fun moved(delta: Offset) = copy(points = points.map { it + delta })
}
private data class Snapshot(val items: List<Item>, val selected: Int)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AkkiDrawScreen(contentResolver) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AkkiDrawScreen(resolver: ContentResolver) {
    var items by remember { mutableStateOf(listOf<Item>()) }
    var tool by remember { mutableStateOf(Tool.DRAW) }
    var selected by remember { mutableIntStateOf(-1) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var active by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var history by remember { mutableStateOf(listOf<Snapshot>()) }
    var future by remember { mutableStateOf(listOf<Snapshot>()) }
    var status by remember { mutableStateOf("Ready · SVG editable objects · PDF page import") }
    var showLayers by remember { mutableStateOf(false) }
    var activeNode by remember { mutableIntStateOf(-1) }
    var showStyle by remember { mutableStateOf(false) }
    var pngLauncherState by remember { mutableStateOf(false) }
    var draggingCanvas by remember { mutableStateOf(false) }
    val appContext = LocalContext.current
    val setupPrefs = remember { appContext.getSharedPreferences("akki_draw_setup", android.content.Context.MODE_PRIVATE) }
    var showSetup by remember { mutableStateOf(!setupPrefs.getBoolean("completed", false)) }

    fun checkpoint() {
        history = history + Snapshot(items, selected)
        if (history.size > 60) history = history.drop(1)
        future = emptyList()
    }
    fun undo() {
        if (history.isNotEmpty()) {
            future = future + Snapshot(items, selected)
            val s = history.last(); history = history.dropLast(1)
            items = s.items; selected = s.selected
        }
    }
    fun redo() {
        if (future.isNotEmpty()) {
            history = history + Snapshot(items, selected)
            val s = future.last(); future = future.dropLast(1)
            items = s.items; selected = s.selected
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(encodeProject(items)) }; status = "Project saved" }
            .onFailure { status = "Save failed: ${it.message}" }
    }
    val svgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/svg+xml")) { uri ->
        if (uri != null) runCatching { resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(exportSvg(items)) }; status = "SVG exported" }
            .onFailure { status = "SVG export failed" }
    }
    val pngLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) runCatching { exportPng(items, uri, resolver); status = "PNG exported" }
            .onFailure { status = "PNG export failed: ${it.message}" }
    }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) runCatching { exportPdf(items, uri, resolver); status = "PDF exported" }
            .onFailure { status = "PDF export failed: ${it.message}" }
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val mime = resolver.getType(uri).orEmpty().lowercase()
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not read selected file")
            val prefix = bytes.take(512).toByteArray().toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            val loaded = when {
                mime.contains("pdf") || prefix.startsWith("%PDF") -> listOf(importPdfPage(uri, resolver))
                mime.contains("svg") || prefix.contains("<svg", ignoreCase = true) -> importSvg(bytes.toString(Charsets.UTF_8))
                mime.contains("json") || prefix.startsWith("{") -> decodeProject(bytes.toString(Charsets.UTF_8))
                else -> error("Unsupported file. Please select SVG, PDF or Akki Draw JSON.")
            }
            require(loaded.isNotEmpty()) { "No drawable content found in this file" }
            checkpoint()
            items = loaded
            selected = -1
            status = "Imported ${loaded.size} object(s)"
        }.onFailure { status = "Open failed: ${it.message ?: "Invalid file"}" }
    }

    if (showSetup) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Welcome to Akki Draw") },
            text = { Text("Akki Draw needs access only to the files you choose. Android's file picker grants access to the selected SVG, PDF or project file; no broad storage permission is required.") },
            confirmButton = {
                TextButton(onClick = {
                    setupPrefs.edit().putBoolean("completed", true).apply()
                    showSetup = false
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = {
                    openLauncher.launch(arrayOf("image/svg+xml", "application/pdf", "application/json"))
                    setupPrefs.edit().putBoolean("completed", true).apply()
                    showSetup = false
                }) { Text("Import a file") }
            }
        )
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF8B9CFF), secondary = Color(0xFF63D6C5), background = Color(0xFF111318), surface = Color(0xFF1B1E25))) {
        Scaffold(topBar = {
            TopAppBar(title = {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Text("AKKI DRAW", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, letterSpacing = 1.5.sp)
                    Text("VECTOR DESIGN STUDIO", fontSize = 9.sp, letterSpacing = 1.8.sp, color = MaterialTheme.colorScheme.secondary)
                }
            }, actions = {
                TextButton(onClick = { undo() }, enabled = history.isNotEmpty()) { Text("Undo") }
                TextButton(onClick = { redo() }, enabled = future.isNotEmpty()) { Text("Redo") }
                TextButton(onClick = { saveLauncher.launch("AkkiDraw-project.json") }) { Text("Save") }
                TextButton(onClick = { showLayers = !showLayers }) { Text("Layers") }
            })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).background(Color(0xFF111318))) {
                Row(Modifier.fillMaxWidth().background(Color(0xFF20242D)).padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("FILE   EDIT   VIEW   OBJECT   ARRANGE", Modifier.weight(1f), fontSize = 10.sp, color = Color(0xFFD7DDF0), fontWeight = FontWeight.SemiBold)
                    Text(status, fontSize = 10.sp, color = Color(0xFF9EA8BE), maxLines = 1)
                    TextButton(onClick = { zoom = (zoom / 1.25f).coerceIn(.25f, 8f) }) { Text("−") }
                    Text("${(zoom * 100).toInt()}%", fontSize = 12.sp)
                    TextButton(onClick = { zoom = (zoom * 1.25f).coerceIn(.25f, 8f) }) { Text("+") }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Start) {
                    TextButton(onClick = { openLauncher.launch(arrayOf("image/svg+xml", "application/pdf", "application/json", "text/*")) }) { Text("Open SVG/PDF/Project") }
                    TextButton(onClick = { svgLauncher.launch("AkkiDraw.svg") }) { Text("Export SVG") }
                    TextButton(onClick = { pdfLauncher.launch("AkkiDraw.pdf") }) { Text("Export PDF") }
                    TextButton(onClick = { pngLauncher.launch("AkkiDraw.png") }) { Text("PNG") }
                }
                if (showStyle && selected in items.indices) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf(0xFF2563EB, 0xFFDC2626, 0xFF16A34A, 0xFF111827, 0xFFF59E0B).forEach { swatch ->
                            TextButton(onClick = { checkpoint(); items = items.toMutableList().also { it[selected] = it[selected].copy(color = swatch, fillColor = swatch) } }) { Text("●", color = Color(swatch)) }
                        }
                        TextButton(onClick = { checkpoint(); items = items.toMutableList().also { it[selected] = it[selected].copy(fillColor = null) } }) { Text("No fill") }
                    }
                }
                if (selected in items.indices) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        TextButton(onClick = { checkpoint(); items = items.toMutableList().also { it[selected] = it[selected].copy(visible = !it[selected].visible) } }) { Text(if (items[selected].visible) "Hide" else "Show") }
                        TextButton(onClick = { checkpoint(); items = items.toMutableList().also { it[selected] = it[selected].copy(locked = !it[selected].locked) } }) { Text(if (items[selected].locked) "Unlock" else "Lock") }
                        TextButton(onClick = { if (selected > 0) { checkpoint(); items = items.toMutableList().also { val v = it.removeAt(selected); it.add(selected - 1, v) }; selected-- } }, enabled = selected > 0) { Text("Up") }
                        TextButton(onClick = { if (selected < items.lastIndex) { checkpoint(); items = items.toMutableList().also { val v = it.removeAt(selected); it.add(selected + 1, v) }; selected++ } }, enabled = selected < items.lastIndex) { Text("Down") }
                    }
                }
                Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                    Column(Modifier.width(48.dp).fillMaxHeight().background(Color(0xFF20242D)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Tool.entries.forEach { t ->
                            val activeTool = tool == t
                            TextButton(onClick = { tool = t }, modifier = Modifier.fillMaxWidth().height(48.dp), contentPadding = PaddingValues(2.dp)) {
                                Text((if (activeTool) "▣\\n" else "") + when(t) { Tool.SELECT -> "Pick"; Tool.NODE -> "Node"; Tool.PEN -> "Pen"; Tool.DRAW -> "Free"; Tool.RECT -> "Rect"; Tool.ELLIPSE -> "Oval"; Tool.LINE -> "Line" }, fontSize = 10.sp, lineHeight = 12.sp, color = if(activeTool) Color(0xFF8B9CFF) else Color(0xFFD7DDF0))
                            }
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color.White)) {
                        Canvas(Modifier.fillMaxSize()
                            .pointerInput(zoom, pan, tool, items, selected) {
                                detectTransformGestures { centroid, panChange, zoomChange, _ ->
                                    // Single-finger movement is owned by the selection/drawing drag handler.
                                    // This handler only owns pinch gestures, avoiding competing one-finger pans.
                                    if (kotlin.math.abs(zoomChange - 1f) > 0.001f) {
                                        val oldZoom = zoom
                                        val nextZoom = (oldZoom * zoomChange).coerceIn(.25f, 8f)
                                        val scale = nextZoom / oldZoom
                                        pan = centroid - (centroid - pan) * scale + panChange
                                        zoom = nextZoom
                                    }
                                }
                            }
                            .pointerInput(tool, zoom, pan, items, selected) {
                                detectDragGestures(
                                    onDragStart = { p ->
                                        val doc = (p - pan) / zoom
                                        when (tool) {
                                            Tool.DRAW, Tool.PEN, Tool.LINE, Tool.RECT, Tool.ELLIPSE -> active = listOf(doc)
                                            Tool.NODE -> {
                                                if (selected in items.indices && !items[selected].locked) {
                                                    val pts = items[selected].points
                                                    activeNode = pts.indices.minByOrNull { (pts[it] - doc).getDistance() } ?: -1
                                                    if (activeNode >= 0 && (pts[activeNode] - doc).getDistance() < 32f) checkpoint() else activeNode = -1
                                                }
                                            }
                                            Tool.SELECT -> {
                                                val hit = items.indexOfLast { it.visible && hitTest(it, doc) }
                                                selected = hit
                                                draggingCanvas = hit < 0
                                                if (hit >= 0 && !items[hit].locked) checkpoint()
                                            }
                                        }
                                    },
                                    onDrag = { change, delta ->
                                        val docDelta = delta / zoom
                                        when (tool) {
                                            Tool.DRAW, Tool.PEN, Tool.LINE, Tool.RECT, Tool.ELLIPSE -> active = active + ((change.position - pan) / zoom)
                                            Tool.NODE -> if (selected in items.indices && activeNode in items[selected].points.indices) {
                                                val old = items[selected]
                                                items = items.toMutableList().also { list -> list[selected] = old.copy(points = old.points.toMutableList().also { it[activeNode] = it[activeNode] + docDelta }) }
                                            }
                                            Tool.SELECT -> {
                                                if (draggingCanvas) pan += delta
                                                else if (selected in items.indices && !items[selected].locked) {
                                                    items = items.toMutableList().also { it[selected] = it[selected].moved(docDelta) }
                                                }
                                            }
                                        }
                                        change.consume()
                                    },
                                    onDragEnd = {
                                        if (active.size > 1) {
                                            val type = when (tool) { Tool.DRAW -> "freehand"; Tool.PEN -> "pen"; Tool.RECT -> "rect"; Tool.ELLIPSE -> "ellipse"; Tool.LINE -> "line"; else -> "freehand" }
                                            checkpoint()
                                            items = items + Item(type, active)
                                            selected = items.lastIndex
                                        }
                                        active = emptyList()
                                        activeNode = -1
                                        draggingCanvas = false
                                    }
                                )
                            }
                        ) {
                            drawContext.transform.translate(pan.x, pan.y)
                            drawContext.transform.scale(zoom, zoom, Offset.Zero)
                            items.forEachIndexed { index, item ->
                                if (item.visible) drawItem(item, if (index == selected) Color(0xFFEF4444) else Color(item.color))
                            }
                            if (active.size > 1) {
                                val preview = Item(when (tool) { Tool.RECT -> "rect"; Tool.ELLIPSE -> "ellipse"; Tool.LINE -> "line"; else -> "freehand" }, active)
                                drawItem(preview, Color(0xFF2563EB))
                            }
                            drawContext.transform.scale(1f / zoom, 1f / zoom, Offset.Zero)
                            drawContext.transform.translate(-pan.x, -pan.y)
                        }
                        if (items.isEmpty()) Text("Choose a tool and draw", Modifier.align(Alignment.Center), color = Color.LightGray)
                    }

                }
                if (showLayers) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 156.dp),
                        color = Color(0xFF20242D),
                        tonalElevation = 4.dp
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("OBJECTS / LAYERS", Modifier.weight(1f), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9EA8BE))
                                TextButton(onClick = { showLayers = false }) { Text("Close") }
                            }
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 112.dp)) {
                                itemsIndexed(items.reversed()) { reverseIndex, item ->
                                    val index = items.lastIndex - reverseIndex
                                    TextButton(onClick = { selected = index }, modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp)) {
                                        Text("${if (selected == index) "● " else ""}${item.name.ifBlank { item.type }} · ${item.type}", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawItem(item: Item, color: Color) {
    if (item.type == "bitmap" && item.bitmap != null) {
        drawImage(item.bitmap.asImageBitmap(), topLeft = item.points.firstOrNull() ?: Offset.Zero)
        return
    }
    if (item.points.isEmpty()) return
    val p = item.points
    val left = p.minOf { it.x }; val top = p.minOf { it.y }
    val width = max(1f, p.maxOf { it.x } - left); val height = max(1f, p.maxOf { it.y } - top)
    when (item.type) {
        "rect" -> drawRect(color, Offset(left, top), Size(width, height), style = Stroke(item.stroke))
        "ellipse" -> drawOval(color, Offset(left, top), Size(width, height), style = Stroke(item.stroke))
        "line" -> drawLine(color, p.first(), p.last(), item.stroke)
        "bitmap" -> Unit
        else -> {
            val path = Path().apply { moveTo(p.first().x, p.first().y); p.drop(1).forEach { lineTo(it.x, it.y) } }
            drawPath(path, color, style = Stroke(item.stroke, cap = StrokeCap.Round))
        }
    }
}
private fun hitTest(item: Item, p: Offset): Boolean {
    if (item.points.isEmpty() || !item.visible || item.locked) return false
    val tolerance = 18f
    val l = item.points.minOf { it.x } - tolerance
    val r = item.points.maxOf { it.x } + tolerance
    val t = item.points.minOf { it.y } - tolerance
    val b = item.points.maxOf { it.y } + tolerance
    return p.x in l..r && p.y in t..b
}
private fun encodeProject(items: List<Item>): String = JSONObject().put("version", 2).put("items", JSONArray().apply {
    items.forEach { item ->
        put(JSONObject().put("type", item.type).put("name", item.name).put("color", item.color).put("fillColor", item.fillColor ?: JSONObject.NULL).put("visible", item.visible).put("locked", item.locked).put("stroke", item.stroke).put("points", JSONArray().apply {
            item.points.forEach { put(JSONArray().put(it.x).put(it.y)) }
        }))
    }
}).toString()
private fun decodeProject(json: String): List<Item> {
    val arr = JSONObject(json).getJSONArray("items")
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i); val pts = o.getJSONArray("points")
        Item(o.getString("type"), (0 until pts.length()).map { j -> val xy = pts.getJSONArray(j); Offset(xy.getDouble(0).toFloat(), xy.getDouble(1).toFloat()) },
            o.optLong("color", 0xFF2563EB), o.optDouble("stroke", 4.0).toFloat(), fillColor = if (o.isNull("fillColor")) null else o.optLong("fillColor"), visible = o.optBoolean("visible", true), locked = o.optBoolean("locked", false), name = o.optString("name", o.optString("type", "Layer")))
    }
}
private fun exportSvg(items: List<Item>): String {
    val body = items.joinToString("\n") { item ->
        if (item.points.isEmpty()) "" else {
            val p = item.points; val l = p.minOf { it.x }; val t = p.minOf { it.y }
            val w = max(1f, p.maxOf { it.x } - l); val h = max(1f, p.maxOf { it.y } - t)
            val color = "#%06X".format(item.color and 0xFFFFFF)
            when (item.type) {
                "rect" -> "<rect x=\"$l\" y=\"$t\" width=\"$w\" height=\"$h\" fill=\"none\" stroke=\"$color\" stroke-width=\" ${item.stroke}\"/>"
                "ellipse" -> "<ellipse cx=\"${l+w/2}\" cy=\"${t+h/2}\" rx=\"${w/2}\" ry=\"${h/2}\" fill=\"none\" stroke=\"$color\" stroke-width=\"${item.stroke}\"/>"
                "line" -> "<line x1=\"${p.first().x}\" y1=\"${p.first().y}\" x2=\"${p.last().x}\" y2=\"${p.last().y}\" stroke=\"$color\" stroke-width=\"${item.stroke}\"/>"
                else -> "<polyline points=\"${p.joinToString(" ") { "${it.x},${it.y}" }}\" fill=\"none\" stroke=\"$color\" stroke-width=\"${item.stroke}\" stroke-linecap=\"round\"/>"
            }
        }
    }
    return """<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="1200" viewBox="0 0 1200 1200">$body</svg>"""
}
private fun importSvg(svg: String): List<Item> {
    val out = mutableListOf<Item>()
    val rect = Regex("""<rect\b[^>]*\bx="([0-9.]+)"[^>]*\by="([0-9.]+)"[^>]*\bwidth="([0-9.]+)"[^>]*\bheight="([0-9.]+)"[^>]*/?>""", RegexOption.IGNORE_CASE)
    rect.findAll(svg).forEach { m ->
        val x = m.groupValues[1].toFloat()
        val y = m.groupValues[2].toFloat()
        val w = m.groupValues[3].toFloat()
        val h = m.groupValues[4].toFloat()
        out += Item("rect", listOf(Offset(x, y), Offset(x + w, y + h)))
    }
    val ellipse = Regex("""<ellipse\b[^>]*\bcx="([0-9.]+)"[^>]*\bcy="([0-9.]+)"[^>]*\brx="([0-9.]+)"[^>]*\bry="([0-9.]+)"[^>]*/?>""", RegexOption.IGNORE_CASE)
    ellipse.findAll(svg).forEach { m ->
        val x = m.groupValues[1].toFloat()
        val y = m.groupValues[2].toFloat()
        val rx = m.groupValues[3].toFloat()
        val ry = m.groupValues[4].toFloat()
        out += Item("ellipse", listOf(Offset(x - rx, y - ry), Offset(x + rx, y + ry)))
    }
    val poly = Regex("""<polyline\b[^>]*\bpoints="([^"]+)"[^>]*/?>""", RegexOption.IGNORE_CASE)
    poly.findAll(svg).forEach { m ->
        val pts = Regex("""(-?[0-9.]+),(-?[0-9.]+)""").findAll(m.groupValues[1])
            .map { Offset(it.groupValues[1].toFloat(), it.groupValues[2].toFloat()) }.toList()
        if (pts.size > 1) out += Item("freehand", pts)
    }
    if (out.isEmpty()) {
        val document = SVG.getFromString(svg)
        val picture = document.renderToPicture(1200, 1200)
        val bitmap = Bitmap.createBitmap(1200, 1200, Bitmap.Config.ARGB_8888)
        val canvas = NativeCanvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        canvas.drawPicture(picture)
        out += Item("bitmap", listOf(Offset.Zero), bitmap = bitmap)
    }
    return out
}
private fun exportPdf(items: List<Item>, uri: Uri, resolver: ContentResolver) {
    val doc = PdfDocument(); val info = PdfDocument.PageInfo.Builder(1200, 1200, 1).create()
    val page = doc.startPage(info); val canvas = page.canvas
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    items.filter { it.visible }.forEach { item ->
        val p=item.points
        if (p.size >= 2) {
            val l=p.minOf { it.x }; val t=p.minOf { it.y }; val w=max(1f,p.maxOf { it.x }-l); val h=max(1f,p.maxOf { it.y }-t)
            paint.color = android.graphics.Color.argb(((item.color ushr 24) and 0xFF).toInt(), ((item.color ushr 16) and 0xFF).toInt(), ((item.color ushr 8) and 0xFF).toInt(), (item.color and 0xFF).toInt())
            paint.strokeWidth = item.stroke
            when(item.type) {
                "rect" -> canvas.drawRect(l,t,l+w,t+h,paint)
                "ellipse" -> canvas.drawOval(l,t,l+w,t+h,paint)
                "line" -> canvas.drawLine(p.first().x,p.first().y,p.last().x,p.last().y,paint)
                else -> { val path=NativePath(); path.moveTo(p.first().x,p.first().y); p.drop(1).forEach { path.lineTo(it.x,it.y) }; canvas.drawPath(path,paint) }
            }
        }
    }
    doc.finishPage(page); resolver.openOutputStream(uri)?.use { doc.writeTo(it) }; doc.close()
}
private fun exportPng(items: List<Item>, uri: Uri, resolver: ContentResolver) {
    val bitmap = Bitmap.createBitmap(1200, 1200, Bitmap.Config.ARGB_8888)
    val canvas = NativeCanvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    items.filter { it.visible }.forEach { item ->
        val points = item.points
        if (item.type == "bitmap" && item.bitmap != null) {
            canvas.drawBitmap(item.bitmap, points.firstOrNull()?.x ?: 0f, points.firstOrNull()?.y ?: 0f, null)
        } else if (points.size >= 2) {
            val color = item.color
            paint.color = android.graphics.Color.argb(((color ushr 24) and 0xFF).toInt(), ((color ushr 16) and 0xFF).toInt(), ((color ushr 8) and 0xFF).toInt(), (color and 0xFF).toInt())
            paint.strokeWidth = item.stroke
            val l = points.minOf { it.x }; val t = points.minOf { it.y }
            val w = max(1f, points.maxOf { it.x } - l); val h = max(1f, points.maxOf { it.y } - t)
            if (item.fillColor != null) {
                paint.style = Paint.Style.FILL
                val fc = item.fillColor
                paint.color = android.graphics.Color.argb(((fc ushr 24) and 0xFF).toInt(), ((fc ushr 16) and 0xFF).toInt(), ((fc ushr 8) and 0xFF).toInt(), (fc and 0xFF).toInt())
                if (item.type == "rect") canvas.drawRect(l, t, l + w, t + h, paint)
                if (item.type == "ellipse") canvas.drawOval(l, t, l + w, t + h, paint)
                paint.style = Paint.Style.STROKE
                paint.color = android.graphics.Color.argb(((color ushr 24) and 0xFF).toInt(), ((color ushr 16) and 0xFF).toInt(), ((color ushr 8) and 0xFF).toInt(), (color and 0xFF).toInt())
            }
            when (item.type) {
                "rect" -> canvas.drawRect(l, t, l + w, t + h, paint)
                "ellipse" -> canvas.drawOval(l, t, l + w, t + h, paint)
                "line" -> canvas.drawLine(points.first().x, points.first().y, points.last().x, points.last().y, paint)
                else -> { val path = NativePath(); path.moveTo(points.first().x, points.first().y); points.drop(1).forEach { path.lineTo(it.x, it.y) }; canvas.drawPath(path, paint) }
            }
        }
    }
    resolver.openOutputStream(uri)?.use { stream -> if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) error("PNG encoding failed") }
    bitmap.recycle()
}

private fun importPdfPage(uri: Uri, resolver: ContentResolver): Item {
    val pfd = resolver.openFileDescriptor(uri, "r") ?: error("Cannot open PDF")
    val renderer = PdfRenderer(pfd)
    require(renderer.pageCount > 0) { "PDF has no pages" }
    val page = renderer.openPage(0)
    val bitmap = Bitmap.createBitmap(page.width.coerceAtMost(1800), page.height.coerceAtMost(1800), Bitmap.Config.ARGB_8888)
    val canvas = NativeCanvas(bitmap); canvas.drawColor(android.graphics.Color.WHITE)
    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
    page.close(); renderer.close(); pfd.close()
    return Item("bitmap", listOf(Offset.Zero), bitmap = bitmap)
}
