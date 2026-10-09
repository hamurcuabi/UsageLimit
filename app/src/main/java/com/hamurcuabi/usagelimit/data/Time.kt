package com.hamurcuabi.usagelimit.data

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Time {
    const val MINUTE_MS = 60_000L
    const val DAY_MS = 86_400_000L

    fun startOfDay(now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun dayKey(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now))

    /** "2 sa 14 dk", "14 dk", "<1 dk" */
    fun format(ms: Long): String {
        val totalMin = ms / MINUTE_MS
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "$h sa $m dk"
            h > 0 -> "$h sa"
            m > 0 -> "$m dk"
            ms > 0 -> "<1 dk"
            else -> "0 dk"
        }
    }
}
