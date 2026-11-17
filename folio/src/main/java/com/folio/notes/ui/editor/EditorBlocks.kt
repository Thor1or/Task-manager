package com.folio.notes.ui.editor

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.folio.notes.markdown.ImageBlock
import com.folio.notes.markdown.TableBlock
import com.folio.notes.ui.common.AttachmentImage

@Composable
fun ImageBlockView(block: ImageBlock, readOnly: Boolean, onRemove: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("imageBlock")) {
        AttachmentImage(block.file, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)), ContentScale.FillWidth)
        if (!readOnly) Box(
            Modifier.align(Alignment.TopEnd).padding(8.dp).size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Close, "Remove picture", tint = Color.White, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
fun TableBlockView(block: TableBlock, readOnly: Boolean, base: TextUnit, vm: EditorViewModel) {
    val line = MaterialTheme.colorScheme.outline
    Column(Modifier.padding(vertical = 6.dp).testTag("tableBlock")) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Column(Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, line, RoundedCornerShape(8.dp))) {
                block.rows.forEachIndexed { r, cells ->
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        cells.forEachIndexed { c, cell ->
                            BasicTextField(
                                value = cell,
                                onValueChange = { vm.setCell(block.id, r, c, it.replace("\n", " ")) },
                                readOnly = readOnly,
                                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = base, fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.width(132.dp).fillMaxHeight()
                                    .background(if (r == 0) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                                    .border(0.5.dp, line)
                                    .onFocusChanged { if (it.isFocused) vm.focusedId = block.id }
                                    .padding(horizontal = 10.dp, vertical = 9.dp)
                                    .semantics { contentDescription = "Cell ${r + 1}, ${c + 1}" },
                            )
                        }
                    }
                }
            }
        }
        if (!readOnly) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            TableAction("+ Row") { vm.addRow(block.id) }
            TableAction("+ Column") { vm.addColumn(block.id) }
            if (block.rows.size > 1) TableAction("− Row") { vm.removeRow(block.id) }
            if (block.rows.first().size > 1) TableAction("− Column") { vm.removeColumn(block.id) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.removeBlock(block.id) }) { Icon(Icons.Outlined.Delete, "Delete table", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun TableAction(label: String, onClick: () -> Unit) {
    Text(
        label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

// ---- Sketch pad ---------------------------------------------------------------------------------------------

private class Stroke2(val color: Color, val width: Float, val points: List<Offset>)

/** A blank sheet to draw on with a finger; the result is attached to the note as a picture. */
@Composable
fun SketchDialog(onDone: (Bitmap) -> Unit, onDismiss: () -> Unit) {
    val strokes = remember { mutableStateListOf<Stroke2>() }
    val current = remember { mutableStateListOf<Offset>() }
    val palette = listOf(Color(0xFF1B1B1F), Color(0xFFD03A2F), Color(0xFF3F51B5), Color(0xFF2E8B57), Color(0xFFE08A00))
    val widths = listOf(4f, 9f, 18f)
    var color by remember { mutableStateOf(palette[0]) }
    var width by remember { mutableStateOf(widths[0]) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { strokes.removeLastOrNull() }, enabled = strokes.isNotEmpty()) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo stroke") }
                    IconButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Icon(Icons.Outlined.Delete, "Clear sketch") }
                    TextButton(onClick = { render(strokes, size)?.let(onDone) ?: onDismiss() }, enabled = strokes.isNotEmpty()) { Text("Done") }
                }
                Canvas(
                    Modifier.weight(1f).fillMaxWidth().background(Color.White).onSizeChanged { size = it }.testTag("sketchCanvas")
                        .pointerInput(color, width) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                current.clear()
                                current += down.position
                                drag(down.id) { change -> current += change.position; change.consume() }
                                strokes += Stroke2(color, width, current.toList())
                                current.clear()
                            }
                        },
                ) {
                    fun draw(points: List<Offset>, c: Color, w: Float) {
                        if (points.size == 1) drawCircle(c, w / 2, points[0])
                        else drawPath(Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) } }, c, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    strokes.forEach { draw(it.points, it.color, it.width) }
                    if (current.isNotEmpty()) draw(current, color, width)
                }
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    palette.forEach { c ->
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(c)
                                .border(if (c == color) 3.dp else 0.dp, if (c == color) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, CircleShape)
                                .clickable { color = c }.semantics { contentDescription = "Pen colour" },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    widths.forEach { w ->
                        Box(Modifier.size(34.dp).clip(CircleShape).background(if (w == width) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent).clickable { width = w }.semantics { contentDescription = "Pen width" }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size((w / 1.4f + 3).dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
                        }
                    }
                }
            }
        }
    }
}

/** Paints the strokes onto a white bitmap cropped to what was drawn. */
private fun render(strokes: List<Stroke2>, size: IntSize): Bitmap? {
    val points = strokes.flatMap { it.points }
    if (points.isEmpty() || size.width <= 0 || size.height <= 0) return null
    val pad = 32f
    val left = (points.minOf { it.x } - pad).coerceAtLeast(0f)
    val top = (points.minOf { it.y } - pad).coerceAtLeast(0f)
    val right = (points.maxOf { it.x } + pad).coerceAtMost(size.width.toFloat())
    val bottom = (points.maxOf { it.y } + pad).coerceAtMost(size.height.toFloat())
    val bitmap = Bitmap.createBitmap(maxOf(1, (right - left).toInt()), maxOf(1, (bottom - top).toInt()), Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    canvas.translate(-left, -top)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    for (s in strokes) {
        paint.color = s.color.toArgb()
        paint.strokeWidth = s.width
        if (s.points.size == 1) { paint.style = Paint.Style.FILL; canvas.drawCircle(s.points[0].x, s.points[0].y, s.width / 2, paint) }
        else {
            paint.style = Paint.Style.STROKE
            canvas.drawPath(android.graphics.Path().apply { moveTo(s.points[0].x, s.points[0].y); s.points.drop(1).forEach { lineTo(it.x, it.y) } }, paint)
        }
    }
    return bitmap
}
