package com.hamurcuabi.usagelimit.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hamurcuabi.usagelimit.MainActivity
import com.hamurcuabi.usagelimit.R
import com.hamurcuabi.usagelimit.cloud.ChildSync
import com.hamurcuabi.usagelimit.cloud.Cloud
import com.hamurcuabi.usagelimit.data.RoleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.hamurcuabi.usagelimit.data.AppCategory
import com.hamurcuabi.usagelimit.data.Apps
import com.hamurcuabi.usagelimit.data.BatteryStore
import com.hamurcuabi.usagelimit.data.HistoryStore
import com.hamurcuabi.usagelimit.data.LimitStore
import com.hamurcuabi.usagelimit.data.Permissions
import com.hamurcuabi.usagelimit.data.Time
import com.hamurcuabi.usagelimit.data.UsageReader

/**
 * Ekran açıkken birkaç saniyede bir ön plandaki uygulamaya bakar; günlük limitin
 * %80'inde bildirim gönderir, limit dolunca uygulamanın üzerine tam ekran uyarı açar.
 */
class MonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: LimitStore
    private lateinit var batteryStore: BatteryStore
    private lateinit var historyStore: HistoryStore
    private var activeDay: String? = null
    private lateinit var batteryManager: BatteryManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var childSync: ChildSync? = null

    private var prevBatteryPkg: String? = null
    private var prevCharge = -1L
    private var prevLevel = -1
    private var useChargeCounter = true
    private var chargeAtLevelChange = -1L
    private val pendingDrops = HashMap<String, Float>()
    private var lastBatteryFlush = 0L
    private lateinit var powerManager: PowerManager
    private lateinit var windowManager: WindowManager
    private lateinit var notifications: NotificationManager

    private var defaultExempt: Set<String> = emptySet()
    private var launchable: Set<String> = emptySet()
    private val categoryCache = HashMap<String, AppCategory>()
    private var exemptLoadedAt = 0L

    private var overlayView: View? = null
    private var overlayPkg: String? = null

    private var closingPkg: String? = null
    private var closingUntil = 0L

    private var lastFallbackPkg: String? = null
    private var lastFallbackAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (_: Exception) {
                // Tek bir hatalı okuma izlemeyi durdurmasın.
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = LimitStore(this)
        batteryStore = BatteryStore(this)
        historyStore = HistoryStore(this)
        batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannels()

        val status = statusNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(STATUS_ID, status, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(STATUS_ID, status)
        }
    }

    /** Çocuk modunda eşleşme tamamlandıysa bulut eşitlemesini başlatır (tekrar çağrılabilir). */
    private fun ensureChildSync() {
        if (childSync != null) return
        val roles = RoleStore(this)
        val childId = roles.childId
        if (!roles.isPairedChild || childId == null || !Cloud.isAvailable(this)) return
        try {
            childSync = ChildSync(this, childId, store, batteryStore, scope).also {
                it.start()
                it.tick(System.currentTimeMillis(), force = true)
            }
        } catch (_: Exception) {
            childSync = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChildSync()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        childSync?.stop()
        scope.cancel()
        flushBattery(System.currentTimeMillis())
        hideOverlay()
        super.onDestroy()
    }

    /**
     * Son örnekten bu yana düşen pili, o aralıkta ön planda olan uygulamaya yazar.
     * Şarjdayken ve ekran kapalıyken ölçüm yapılmaz.
     */
    private fun sampleBattery(foreground: String?, now: Long) {
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charge = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).toLong()
        val charging = batteryManager.isCharging

        // Bazı telefonlarda şarj sayacı hiç değişmez. Yüzde düştüğü hâlde sayaç aynı
        // kaldıysa sayaca güvenmeyi bırakıp yüzdeye göre ölç.
        if (prevLevel > 0 && level != prevLevel) {
            if (charge <= 0 || charge == chargeAtLevelChange) useChargeCounter = false
            chargeAtLevelChange = charge
        }

        val owner = prevBatteryPkg
        if (!charging && owner != null && level in 1..100) {
            val drop = when {
                // Şarj sayacı (µAh) çalışıyorsa yüzdeden çok daha hassas sonuç verir.
                useChargeCounter && charge > 0 && prevCharge > 0 ->
                    (prevCharge - charge).toFloat() * level / charge
                prevLevel > 0 -> (prevLevel - level).toFloat()
                else -> 0f
            }
            if (drop > 0f && drop < MAX_DROP_PER_TICK) {
                pendingDrops[owner] = (pendingDrops[owner] ?: 0f) + drop
            }
        }

        prevBatteryPkg = if (charging) null else foreground
        prevCharge = charge
        prevLevel = level
        if (now - lastBatteryFlush > BATTERY_FLUSH_MS) flushBattery(now)
    }

    private fun resetBatterySample() {
        prevBatteryPkg = null
        prevCharge = -1L
        prevLevel = -1
    }

    private fun flushBattery(now: Long) {
        lastBatteryFlush = now
        if (pendingDrops.isEmpty()) return
        batteryStore.addAll(Time.dayKey(now), pendingDrops)
        pendingDrops.clear()
    }

    private fun categoryOf(pkg: String): AppCategory =
        categoryCache.getOrPut(pkg) { AppCategory.of(this, pkg) }

    private fun tick() {
        if (!powerManager.isInteractive || !Permissions.hasUsageAccess(this)) {
            // Ekran kapalıyken düşen pil hiçbir uygulamaya yazılmaz.
            resetBatterySample()
            flushBattery(System.currentTimeMillis())
            hideOverlay()
            return
        }

        val now = System.currentTimeMillis()
        if (now - exemptLoadedAt > EXEMPT_REFRESH_MS) {
            defaultExempt = Apps.defaultExempt(this)
            launchable = Apps.launchable(this)
            exemptLoadedAt = now
            store.pruneOtherDays(Time.dayKey(now))
            batteryStore.prune((0..7).mapTo(HashSet()) { Time.dayKey(now - it * Time.DAY_MS) })
        }

        val snapshot = UsageReader.read(this, Time.startOfDay(now), now)
        val pkg = snapshot.foreground
        sampleBattery(pkg, now)
        childSync?.tick(now)

        // Takvim için: izlemenin bugün çalıştığını bir kez kaydet.
        val todayKey = Time.dayKey(now)
        if (todayKey != activeDay) {
            historyStore.markActive(todayKey)
            historyStore.prune(now)
            activeDay = todayKey
        }
        if (pkg == null || pkg == packageName || store.isUnlimited(pkg, defaultExempt)) {
            hideOverlay()
            return
        }

        // "Uygulamadan çık"a basıldıktan sonra ana ekrana geçiş birkaç saniye sürebilir;
        // bu arada sistem hâlâ eski uygulamayı ön planda gösterdiği için uyarıyı yeniden açma.
        if (pkg == closingPkg && now < closingUntil) {
            hideOverlay()
            return
        }
        closingPkg = null

        val day = Time.dayKey(now)
        val label = Apps.label(this, pkg)
        val category = categoryOf(pkg)
        val usedMs = snapshot.perApp[pkg]?.totalMs ?: 0L

        // Aynı anda hem uygulama hem grup limiti dolmuş olabilir; ikisi tek uyarıda toplanır.
        val exceededKeys = ArrayList<String>(2)
        var maxExtensions = 0
        var title = ""
        var body = ""
        var event: LimitEvent? = null

        // 1) Uygulamanın kendi limiti
        val limitMin = store.limitMinutes(pkg, defaultExempt, category.name)
        var ownAllowedMs = 0L
        if (limitMin != null) {
            val extensions = store.extensionsUsed(pkg, day)
            ownAllowedMs = (limitMin + extensions * store.extensionMin) * Time.MINUTE_MS
            if (usedMs >= ownAllowedMs) {
                exceededKeys += pkg
                maxExtensions = maxOf(maxExtensions, extensions)
                title = "$label için bugünlük süre doldu"
                body = "Bugün ${Time.format(usedMs)} kullandın. Günlük limitin $limitMin dk."
                event = LimitEvent(pkg, label, ChildSync.SCOPE_APP, null, usedMs, limitMin)
            }
        }

        // 2) Grubun toplam limiti
        val groupMin = store.groupLimit(category.name)
        val groupKey = "group:${category.name}"
        var groupUsedMs = 0L
        var groupAllowedMs = 0L
        if (groupMin != null) {
            for ((other, usage) in snapshot.perApp) {
                if (other !in launchable || other == packageName) continue
                if (categoryOf(other) != category || store.isUnlimited(other, defaultExempt)) continue
                groupUsedMs += usage.totalMs
            }
            val extensions = store.extensionsUsed(groupKey, day)
            groupAllowedMs = (groupMin + extensions * store.extensionMin) * Time.MINUTE_MS
            if (groupUsedMs >= groupAllowedMs) {
                val groupBody = "\"${category.title}\" grubunda bugün toplam ${Time.format(groupUsedMs)} geçirdin. Grup limiti $groupMin dk."
                if (exceededKeys.isEmpty()) {
                    title = "${category.title} için bugünlük süre doldu"
                    body = groupBody
                } else {
                    body = "$body\n$groupBody"
                }
                exceededKeys += groupKey
                maxExtensions = maxOf(maxExtensions, extensions)
                if (event == null) {
                    event = LimitEvent(pkg, label, ChildSync.SCOPE_GROUP, category.title, groupUsedMs, groupMin)
                }
            }
        }

        if (exceededKeys.isNotEmpty()) {
            val info = event
            // Ebeveyn için: limitin dolduğunu günde bir kez kaydet.
            if (info != null && !store.wasWarned("reached:" + exceededKeys.first(), day)) {
                store.markWarned("reached:" + exceededKeys.first(), day)
                historyStore.addReached(day)
                childSync?.logEvent(
                    ChildSync.EVENT_LIMIT_REACHED, info.pkg, info.label, info.scope,
                    info.groupTitle, info.usedMs, info.limitMin,
                )
            }
            showLimitReached(pkg, title, body, exceededKeys, maxExtensions, day, info)
            return
        }

        hideOverlay()

        if (limitMin != null && usedMs >= limitMin * Time.MINUTE_MS * 8 / 10 && !store.wasWarned(pkg, day)) {
            store.markWarned(pkg, day)
            notifyAlert(
                id = WARN_ID_BASE + (pkg.hashCode() and 0xFFFF),
                title = "$label: limit dolmak üzere",
                text = "Bugün ${Time.format(usedMs)} kullandın. Kalan: ${Time.format(ownAllowedMs - usedMs)}.",
            )
        }
        if (groupMin != null && groupUsedMs >= groupMin * Time.MINUTE_MS * 8 / 10 && !store.wasWarned(groupKey, day)) {
            store.markWarned(groupKey, day)
            notifyAlert(
                id = WARN_ID_BASE + (groupKey.hashCode() and 0xFFFF),
                title = "${category.title}: grup limiti dolmak üzere",
                text = "Bugün toplam ${Time.format(groupUsedMs)}. Kalan: ${Time.format(groupAllowedMs - groupUsedMs)}.",
            )
        }
    }

    private fun showLimitReached(
        pkg: String,
        title: String,
        body: String,
        extensionKeys: List<String>,
        extensions: Int,
        day: String,
        event: LimitEvent?,
    ) {
        if (overlayPkg == pkg && overlayView != null) return
        hideOverlay()

        if (!Settings.canDrawOverlays(this)) {
            // Üste çizme izni yoksa en azından bildirimle haber ver (dakikada en fazla bir kez).
            val now = System.currentTimeMillis()
            if (lastFallbackPkg != pkg || now - lastFallbackAt > Time.MINUTE_MS) {
                lastFallbackPkg = pkg
                lastFallbackAt = now
                notifyAlert(id = LIMIT_ID_BASE + (pkg.hashCode() and 0xFFFF), title = title, text = body)
            }
            return
        }

        val view = buildOverlay(
            title = title,
            body = body,
            extensionsLeft = store.maxExtensions - extensions,
            onClose = {
                closingPkg = pkg
                closingUntil = System.currentTimeMillis() + CLOSE_GRACE_MS
                hideOverlay()
                goHome()
            },
            onExtend = {
                // Dolmuş bütün limitlere birlikte ek süre ver ki uyarı ikinci kez çıkmasın.
                extensionKeys.forEach { store.addExtension(it, day) }
                historyStore.addExtension(day)
                if (event != null) {
                    childSync?.logEvent(
                        ChildSync.EVENT_EXTENSION, event.pkg, event.label, event.scope,
                        event.groupTitle, event.usedMs, event.limitMin, store.extensionMin,
                    )
                    childSync?.tick(System.currentTimeMillis(), force = true)
                }
                hideOverlay()
            },
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        try {
            windowManager.addView(view, params)
            overlayView = view
            overlayPkg = pkg
        } catch (_: Exception) {
            overlayView = null
            overlayPkg = null
        }
    }

    private fun buildOverlay(
        title: String,
        body: String,
        extensionsLeft: Int,
        onClose: () -> Unit,
        onExtend: () -> Unit,
    ): View {
        val pad = dp(28)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(245, 16, 24, 32))
            setPadding(pad, pad, pad, pad)
            isClickable = true
        }

        root.addView(TextView(this).apply {
            text = "⏳"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        })
        root.addView(TextView(this).apply {
            text = body
            setTextColor(Color.argb(220, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(32))
        })

        val buttonParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }

        root.addView(Button(this).apply {
            text = "Uygulamadan çık"
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(242, 165, 65))
            setTextColor(Color.rgb(26, 18, 0))
            setOnClickListener { onClose() }
        }, buttonParams)

        if (extensionsLeft > 0) {
            root.addView(Button(this).apply {
                text = "${store.extensionMin} dk daha (bugün $extensionsLeft hak kaldı)"
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setOnClickListener { onExtend() }
            }, buttonParams)
        } else {
            root.addView(TextView(this).apply {
                text = "Bugünkü ek süre hakların bitti."
                setTextColor(Color.argb(180, 255, 255, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, 0)
            })
        }
        return root
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        overlayView = null
        overlayPkg = null
    }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(home)
        } catch (_: Exception) {
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun createChannels() {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "İzleme durumu", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            }
        )
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Limit uyarıları", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun statusNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle("Kullanım limitleri izleniyor")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openAppIntent())
            .build()

    private fun notifyAlert(id: Int, title: String, text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        try {
            notifications.notify(id, notification)
        } catch (_: SecurityException) {
            // Bildirim izni verilmemiş.
        }
    }

    private class LimitEvent(
        val pkg: String,
        val label: String,
        val scope: String,
        val groupTitle: String?,
        val usedMs: Long,
        val limitMin: Int,
    )

    companion object {
        private const val TICK_MS = 3_000L
        private const val CLOSE_GRACE_MS = 8_000L
        private const val BATTERY_FLUSH_MS = 30_000L
        private const val MAX_DROP_PER_TICK = 3f
        private const val EXEMPT_REFRESH_MS = 10 * 60 * 1000L
        private const val CHANNEL_STATUS = "status"
        private const val CHANNEL_ALERT = "alerts"
        private const val STATUS_ID = 1
        private const val WARN_ID_BASE = 100_000
        private const val LIMIT_ID_BASE = 200_000

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
