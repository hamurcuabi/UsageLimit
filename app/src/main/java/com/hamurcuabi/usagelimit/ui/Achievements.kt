package com.hamurcuabi.usagelimit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hamurcuabi.usagelimit.data.Achievements
import com.hamurcuabi.usagelimit.data.Badge
import com.hamurcuabi.usagelimit.data.DayRecord
import com.hamurcuabi.usagelimit.data.DayState
import com.hamurcuabi.usagelimit.data.Time
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val KeptGreen = Color(0xFF3FA772)

/** Seri, aylık takvim ve rozetler. */
@Composable
internal fun AchievementsCard(calendar: Map<String, DayRecord>, emptyHint: String) {
    val scheme = MaterialTheme.colorScheme
    val now = remember { System.currentTimeMillis() }
    val stats = remember(calendar) { Achievements.from(calendar, now) }
    var monthOffset by rememberSaveable { mutableIntStateOf(0) }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔥", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (stats.streak > 0) "${stats.streak} gün seri" else "Seri henüz başlamadı",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (calendar.isEmpty()) emptyHint
                        else "En iyi seri ${stats.bestStreak} gün · toplam ${stats.successDays} başarılı gün",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            MonthCalendar(
                calendar = calendar,
                now = now,
                monthOffset = monthOffset,
                onPrevious = { monthOffset -= 1 },
                onNext = { if (monthOffset < 0) monthOffset += 1 },
            )

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Legend(scheme.primary, "Kusursuz")
                Legend(KeptGreen, "Sınır aşılmadı")
                Legend(scheme.error, "Ek süre alındı")
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "Rozetler · ${stats.badges.count { it.earned }}/${stats.badges.size}",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Kazanılanlar önce gelsin.
                stats.badges.sortedByDescending { it.earned }.forEach { BadgeTile(it) }
            }
        }
    }
}

@Composable
private fun MonthCalendar(
    calendar: Map<String, DayRecord>,
    now: Long,
    monthOffset: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val todayKey = Time.dayKey(now)

    val month = remember(monthOffset) {
        Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 12)
            add(Calendar.MONTH, monthOffset)
        }
    }
    val title = remember(monthOffset) {
        SimpleDateFormat("LLLL yyyy", Locale.forLanguageTag("tr")).format(month.time)
            .replaceFirstChar { it.uppercase() }
    }
    val daysInMonth = month.getActualMaximum(Calendar.DAY_OF_MONTH)
    // Hafta pazartesi başlar: Calendar.MONDAY (2) -> 0.
    val leadingBlanks = (month.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val keys = remember(monthOffset) {
        val format = SimpleDateFormat("yyyyMMdd", Locale.US)
        val cursor = month.clone() as Calendar
        List(daysInMonth) { index ->
            cursor.set(Calendar.DAY_OF_MONTH, index + 1)
            format.format(cursor.time)
        }
    }
    val monthSuccess = keys.count { calendar[it]?.state?.success == true }
    val monthTracked = keys.count { calendar[it] != null }

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onPrevious) { Text("‹", style = MaterialTheme.typography.titleLarge) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (monthTracked > 0) "$monthSuccess / $monthTracked gün başarılı" else "Bu ay kayıt yok",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onNext, enabled = monthOffset < 0) {
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }

    Row(Modifier.fillMaxWidth()) {
        listOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz").forEach { name ->
            Text(
                name,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
    Spacer(Modifier.height(4.dp))

    val cells: List<Int?> = List(leadingBlanks) { null } + List(daysInMonth) { it + 1 }
    cells.chunked(7).forEach { week ->
        Row(Modifier.fillMaxWidth()) {
            for (slot in 0 until 7) {
                val day = week.getOrNull(slot)
                Box(
                    Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .padding(3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (day != null) {
                        val key = keys[day - 1]
                        val record = calendar[key]
                        val isToday = key == todayKey
                        val isFuture = key > todayKey
                        val fill = when (record?.state) {
                            DayState.PERFECT -> scheme.primary
                            DayState.KEPT -> KeptGreen
                            DayState.EXCEEDED -> scheme.error
                            null -> Color.Transparent
                        }
                        val textColor = when {
                            record?.state == DayState.PERFECT -> scheme.onPrimary
                            record != null -> Color.White
                            isFuture -> scheme.onSurfaceVariant.copy(alpha = 0.4f)
                            else -> scheme.onSurfaceVariant
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .background(fill)
                                .then(
                                    if (isToday) Modifier.border(2.dp, scheme.onSurface, CircleShape)
                                    else if (record == null && !isFuture) Modifier.border(1.dp, scheme.outlineVariant, CircleShape)
                                    else Modifier
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "$day",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                color = textColor,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BadgeTile(badge: Badge) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(104.dp)
            .alpha(if (badge.earned) 1f else 0.4f)
            .clip(MaterialTheme.shapes.medium)
            .background(if (badge.earned) scheme.primaryContainer else scheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (badge.earned) badge.emoji else "🔒", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            badge.title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (badge.earned) scheme.onPrimaryContainer else scheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            badge.hint,
            style = MaterialTheme.typography.labelSmall,
            color = if (badge.earned) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            minLines = 2,
            maxLines = 2,
        )
    }
}
