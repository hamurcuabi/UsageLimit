package com.hamurcuabi.usagelimit.data

import android.content.Context

enum class Role {
    /** Tek başına kullanım: limitler bu telefonda belirlenir. */
    LOCAL,

    /** Ebeveyn: çocukların telefonlarını uzaktan görür ve limit koyar. */
    PARENT,

    /** Çocuğun telefonu: limitler ebeveynden gelir, burada değiştirilemez. */
    CHILD,
}

class RoleStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("role", Context.MODE_PRIVATE)

    var role: Role?
        get() = prefs.getString("role", null)?.let { name -> Role.entries.firstOrNull { it.name == name } }
        set(value) = prefs.edit().putString("role", value?.name).apply()

    /** Çocuk modunda eşleşilen çocuk kaydının kimliği. */
    var childId: String?
        get() = prefs.getString("childId", null)
        set(value) = prefs.edit().putString("childId", value).apply()

    var childName: String?
        get() = prefs.getString("childName", null)
        set(value) = prefs.edit().putString("childName", value).apply()

    val isPairedChild: Boolean get() = role == Role.CHILD && childId != null
}
