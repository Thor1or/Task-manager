package com.folio.notes.ui.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.folio.notes.markdown.MarkdownStyler
import com.folio.notes.markdown.StyledMarkdown
import com.folio.notes.markdown.SyntaxMode
import com.folio.notes.ui.theme.markdownColors

/**
 * A text field that shows Markdown the way it will read: headings large, emphasis styled, list markers as
 * bullets and tick boxes. Tick boxes and links respond to taps; everything else edits as plain text.
 */
@Composable
fun MarkdownField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    titleFirstLine: Boolean,
    syntax: SyntaxMode,
    base: TextUnit,
    readOnly: Boolean,
    onToggleCheckbox: (lineStart: Int) -> Unit,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = markdownColors()
    // What the transformation last produced, for drawing tick boxes and resolving taps.
    val styled = remember { arrayOfNulls<StyledMarkdown>(1) }
    val reveal = if (syntax == SyntaxMode.CURRENT_LINE) value.selection else null
    val transformation = remember(colors, base, titleFirstLine, syntax, reveal) {
        VisualTransformation { text ->
            val result = MarkdownStyler.style(text.text, colors, base, titleFirstLine, syntax, reveal?.let { it.min..it.max })
            styled[0] = result
            TransformedText(result.text, result.mapping)
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val toggle by rememberUpdatedState(onToggleCheckbox)
    val open by rememberUpdatedState(onOpenLink)
    val canToggle by rememberUpdatedState(!readOnly)
    val accent = colors.accent
    val outline = MaterialTheme.colorScheme.outline
    val onAccent = MaterialTheme.colorScheme.onPrimary

    /** The action for a tap at [position], if it lands on a tick box or a link. */
    fun hit(position: Offset, slop: Float): (() -> Unit)? {
        val l = layout ?: return null
        val s = styled[0] ?: return null
        val length = l.layoutInput.text.length
        if (canToggle) for (box in s.checkboxes) {
            if (box.offset >= length) continue
            val r = l.getBoundingBox(box.offset)
            if (position.x >= r.left - slop && position.x <= r.right + slop && position.y >= r.top && position.y <= r.bottom) {
                return { toggle(box.lineStart) }
            }
        }
        if (s.links.isEmpty() || length == 0) return null
        val line = l.getLineForVerticalPosition(position.y)
        if (position.y > l.getLineBottom(line) || position.x > l.getLineRight(line) || position.x < l.getLineLeft(line)) return null
        val offset = l.getOffsetForPosition(position)
        val link = s.links.firstOrNull { offset >= it.start && offset < it.end } ?: return null
        return { open(link.url) }
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = readOnly,
        textStyle = TextStyle(color = colors.text, fontSize = base),
        cursorBrush = SolidColor(accent),
        visualTransformation = transformation,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        onTextLayout = { layout = it },
        modifier = modifier
            .drawBehind {
                val l = layout ?: return@drawBehind
                val radius = base.toPx() * 0.5f
                styled[0]?.checkboxes?.forEach { box ->
                    if (box.offset >= l.layoutInput.text.length) return@forEach
                    val c = l.getBoundingBox(box.offset).center
                    if (box.checked) {
                        drawCircle(accent, radius, c)
                        val tick = Path().apply {
                            moveTo(c.x - radius * 0.45f, c.y + radius * 0.02f)
                            lineTo(c.x - radius * 0.1f, c.y + radius * 0.36f)
                            lineTo(c.x + radius * 0.48f, c.y - radius * 0.32f)
                        }
                        drawPath(tick, onAccent, style = Stroke(radius * 0.22f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    } else drawCircle(outline, radius - 0.75.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
                }
            }
            .pointerInput(Unit) {
                // Seen before the text field, so a tap on a tick box toggles it instead of moving the caret.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val action = hit(down.position, 8.dp.toPx()) ?: return@awaitEachGesture
                    down.consume()
                    while (true) {
                        val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break // a scroll
                        if (change.changedToUp()) { change.consume(); action(); break }
                    }
                }
            },
    )
}
