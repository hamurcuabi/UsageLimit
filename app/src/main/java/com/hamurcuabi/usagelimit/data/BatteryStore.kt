package com.hamurcuabi.usagelimit.data

import android.content.Context

/**
 * Uygulama ön plandayken düşen pil yüzdesinin günlük kaydı.
 * Android gerçek pil istatistiğini üçüncü parti uygulamalara açmadığı için
 * izleme servisi pil seviyesini örnekleyip düşüşü o an açık olan uygulamaya yazar.
 */
class BatteryStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("battery", Context.MODE_PRIVATE)

    fun addAll(day: String, drops: Map<String, Float>) {
        if (drops.isEmpty()) return
        val editor = prefs.edit()
        for ((pkg, drop) in drops) {
            val key = "$day|$pkg"
            editor.putFloat(key, prefs.getFloat(key, 0f) + drop)
        }
        editor.apply()
    }

    /** Verilen günde uygulama başına harcanan pil yüzdesi. */
    fun day(day: String): Map<String, Float> {
        val prefix = "$day|"
        val result = HashMap<String, Float>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(prefix) && value is Float) result[key.substring(prefix.length)] = value
        }
        return result
    }

    fun prune(keepDays: Set<String>) {
        val stale = prefs.all.keys.filter { it.substringBefore('|') !in keepDays }
        if (stale.isEmpty()) return
        val editor = prefs.edit()
        stale.forEach { editor.remove(it) }
        editor.apply()
    }
}
