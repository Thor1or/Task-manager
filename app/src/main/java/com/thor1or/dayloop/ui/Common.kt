@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.thor1or.dayloop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thor1or.dayloop.data.GroupEntity
import com.thor1or.dayloop.logic.NlParser
import com.thor1or.dayloop.logic.Parsed
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DayloopTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        android.os.Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF8FD3AE), secondary = Color(0xFFB4CCBE))
        else -> lightColorScheme(primary = Color(0xFF2F6B4F), secondary = Color(0xFF4D6357))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** The current time, refreshed every [periodMs]. */
@Composable
fun rememberNow(periodMs: Long): State<Long> = produceState(System.currentTimeMillis(), periodMs) {
    while (true) {
        value = System.currentTimeMillis()
        delay(periodMs)
    }
}

private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

/** "Today", "Tomorrow", "Yesterday" or "Thu 12 Nov". */
fun dayLabel(day: LocalDate, today: LocalDate = LocalDate.now()): String = when (day) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(dayFormat) + if (day.year != today.year) " ${day.year}" else ""
}

fun elapsedLabel(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    else "%02d:%02d".format(s / 60, s % 60)
}

fun groupColor(groups: List<GroupEntity>, id: Long?): Color? =
    groups.firstOrNull { it.id == id }?.let { Color(it.color) }

@Composable
fun Dot(color: Color?, size: Dp = 10.dp) {
    Box(
        Modifier
            .size(size)
            .background(color ?: MaterialTheme.colorScheme.outlineVariant, CircleShape)
    )
}

/** A scrolling row of group chips. [noneLabel] is the chip that stands for "no group". */
@Composable
fun GroupChips(
    groups: List<GroupEntity>,
    selected: Long?,
    noneLabel: String,
    modifier: Modifier = Modifier,
    onSelect: (Long?) -> Unit,
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(noneLabel) })
        groups.forEach { g ->
            FilterChip(
                selected = selected == g.id,
                onClick = { onSelect(g.id) },
                label = { Text(g.name) },
                leadingIcon = { Dot(Color(g.color)) },
            )
        }
    }
}

/** Colours the parts of the text that were understood as a date, time, length and so on. */
private class Highlight(val spans: List<IntRange>, val color: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val out = AnnotatedString.Builder(text.text)
        spans.forEach {
            if (it.first >= 0 && it.last < text.length) {
                out.addStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold), it.first, it.last + 1)
            }
        }
        return TransformedText(out.toAnnotatedString(), OffsetMapping.Identity)
    }
}

/** One-line capture field that shows what it understood while you type. */
@Composable
fun QuickAddField(
    value: String,
    onValueChange: (String) -> Unit,
    parsed: Parsed,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSubmit: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(placeholder, maxLines = 1) },
        visualTransformation = Highlight(parsed.spans, MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        trailingIcon = {
            IconButton(onClick = onSubmit, enabled = parsed.title.isNotBlank()) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        },
        supportingText = parsedSummary(parsed).takeIf { it.isNotEmpty() }?.let { { Text(it) } },
    )
}

/** "Thu 10 Dec · 09:00 · 30m · #Home · every day" */
fun parsedSummary(p: Parsed): String = listOfNotNull(
    p.date?.let { dayLabel(it) },
    p.time?.let { NlParser.formatClock(it.hour * 60 + it.minute) },
    p.durationMin?.let { NlParser.formatDuration(it) },
    p.group?.let { "#$it" },
    p.recurrence?.describe(),
).joinToString(" · ")

/**
 * A GitHub-style grid: one column per week, Monday at the top, the current week on the right.
 * [value] gives 0..1 for a day, or null when the day does not count.
 */
@Composable
fun Heatmap(today: LocalDate, modifier: Modifier = Modifier, value: (Long) -> Float?) {
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val full = MaterialTheme.colorScheme.primary
    val cell = 13.dp
    val gap = 3.dp
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val weeks = ((maxWidth + gap) / (cell + gap)).toInt().coerceIn(1, 53)
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong()).toEpochDay()
        val last = today.toEpochDay()
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(cell * 7 + gap * 6)
        ) {
            val c = cell.toPx()
            val step = c + gap.toPx()
            for (w in 0 until weeks) {
                for (d in 0 until 7) {
                    val day = monday - (weeks - 1 - w) * 7L + d
                    if (day > last) continue
                    val v = value(day)
                    val color = when {
                        v == null -> empty.copy(alpha = 0.35f)
                        v <= 0f -> empty
                        // Four steps, like the contribution graph.
                        else -> lerp(empty, full, (kotlin.math.ceil(v.coerceAtMost(1f) * 4) / 4f).coerceAtLeast(0.25f))
                    }
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(w * step, d * step),
                        size = Size(c, c),
                        cornerRadius = CornerRadius(c / 4, c / 4),
                    )
                }
            }
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        trailing()
    }
}
