package com.hamurcuabi.usagelimit.cloud

import android.content.Context
import android.os.Build
import com.google.firebase.firestore.ListenerRegistration
import com.hamurcuabi.usagelimit.data.BatteryStore
import com.hamurcuabi.usagelimit.data.HistoryStore
import com.hamurcuabi.usagelimit.data.LimitStore
import com.hamurcuabi.usagelimit.data.Permissions
import com.hamurcuabi.usagelimit.data.Role
import com.hamurcuabi.usagelimit.data.RoleStore
import com.hamurcuabi.usagelimit.data.RuleSet
import com.hamurcuabi.usagelimit.data.Time
import com.hamurcuabi.usagelimit.data.UsageCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Çocuğun telefonunu ebeveynin açtığı kayıtla eşleştirir. */
object ChildPairing {
    /**
     * Ebeveynin ekranındaki kodla eşleşir. Başarılıysa çocuğun adını döndürür.
     * Cihaz anonim bir hesapla oturum açar; kayıt bu hesabın kimliğine bağlanır.
     */
    suspend fun pair(context: Context, rawCode: String): String {
        val code = rawCode.filter { it.isDigit() }
        require(code.length == 6) { "Kod 6 haneli olmalı." }

        val auth = Cloud.auth
        // Bu telefonda daha önce ebeveyn hesabı açıldıysa çocuk kaydı ona bağlanmasın.
        if (auth.currentUser?.isAnonymous == false) auth.signOut()
        val user = auth.currentUser ?: auth.signInAnonymously().await().user
            ?: error("Oturum açılamadı.")

        val codeDoc = Cloud.db.collection(Cloud.PAIR_CODES).document(code).get().await()
        val childId = codeDoc.getString("childId") ?: error("Bu kod geçerli değil.")

        try {
            Cloud.child(childId).update(
                mapOf(
                    "deviceUid" to user.uid,
                    "claimCode" to code,
                    "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "pairedAt" to System.currentTimeMillis(),
                )
            ).await()
        } catch (e: Exception) {
            error("Bu kod daha önce kullanılmış. Ebeveyn telefonundan yeni kod al.")
        }

        val name = Cloud.child(childId).get().await().getString("name") ?: "Çocuk"
        // Ebeveynin koyduğu kurallar gelene kadar eski yerel ayarlar geçerli kalmasın.
        try {
            val rules = Cloud.rules(childId).get().await().data
            if (rules != null) LimitStore(context).replace(RuleSet.fromMap(rules))
        } catch (_: Exception) {
        }

        val roles = RoleStore(context)
        roles.childId = childId
        roles.childName = name
        roles.role = Role.CHILD
        LimitStore(context).monitoringEnabled = true
        return name
    }
}

/**
 * Çocuğun telefonunda izleme servisiyle birlikte çalışır:
 * ebeveynin kurallarını dinleyip yerel ayarlara yazar, kullanım verisini ve olayları buluta gönderir.
 */
class ChildSync(
    private val context: Context,
    private val childId: String,
    private val store: LimitStore,
    private val batteryStore: BatteryStore,
    private val scope: CoroutineScope,
) {
    private var rulesListener: ListenerRegistration? = null
    private var lastUpload = 0L

    fun start() {
        if (rulesListener != null) return
        rulesListener = Cloud.rules(childId).addSnapshotListener { snapshot, _ ->
            val data = snapshot?.data ?: return@addSnapshotListener
            store.replace(RuleSet.fromMap(data))
        }
    }

    fun stop() {
        rulesListener?.remove()
        rulesListener = null
    }

    /** Servisin her turunda çağrılır; veriyi en fazla [UPLOAD_INTERVAL_MS] aralıkla yükler. */
    fun tick(now: Long, force: Boolean = false) {
        if (!force && now - lastUpload < UPLOAD_INTERVAL_MS) return
        lastUpload = now
        scope.launch(Dispatchers.IO) {
            try {
                upload(now)
            } catch (_: Exception) {
                // Bağlantı yoksa bir sonraki turda yeniden denenir.
            }
        }
    }

    private fun upload(now: Long) {
        val apps = UsageCollector.collect(context, batteryStore, now)
        Cloud.snapshot(childId).set(
            mapOf(
                "day" to Time.dayKey(now),
                "updatedAt" to now,
                "weekLabels" to UsageCollector.dayLabels(now),
                "apps" to apps.map { it.toMap() },
                // Takvim: gün -> [dolan limit sayısı, alınan ek süre sayısı]
                "calendar" to HistoryStore(context).all().mapValues { listOf(it.value.reached, it.value.extensions) },
                "details" to HistoryStore(context).rawDetails(now),
            )
        )
        Cloud.child(childId).update(
            mapOf(
                "lastSeen" to now,
                "totalTodayMs" to apps.sumOf { it.todayMs },
                "status" to mapOf(
                    "usage" to Permissions.hasUsageAccess(context),
                    "overlay" to Permissions.canOverlay(context),
                    "notify" to Permissions.canNotify(context),
                    "battery" to Permissions.ignoresBatteryOptimizations(context),
                ),
            )
        )
    }

    /**
     * Ebeveynin göreceği olay kaydı. Çevrimdışıyken Firestore kuyruğa alır,
     * bağlantı gelince gönderir.
     */
    fun logEvent(
        type: String,
        pkg: String,
        label: String,
        scope: String,
        groupTitle: String?,
        usedMs: Long,
        limitMin: Int,
        extensionMin: Int = 0,
    ) {
        val now = System.currentTimeMillis()
        try {
            Cloud.child(childId).collection("events").add(
                mapOf(
                    "type" to type,
                    "pkg" to pkg,
                    "label" to label,
                    "scope" to scope,
                    "groupTitle" to (groupTitle ?: ""),
                    "usedMs" to usedMs,
                    "limitMin" to limitMin,
                    "extensionMin" to extensionMin,
                    "at" to now,
                    "day" to Time.dayKey(now),
                )
            )
        } catch (_: Exception) {
        }
    }

    companion object {
        const val EVENT_EXTENSION = "extension"
        const val EVENT_LIMIT_REACHED = "limit_reached"
        const val SCOPE_APP = "app"
        const val SCOPE_GROUP = "group"
        private const val UPLOAD_INTERVAL_MS = 5 * 60 * 1000L
    }
}
