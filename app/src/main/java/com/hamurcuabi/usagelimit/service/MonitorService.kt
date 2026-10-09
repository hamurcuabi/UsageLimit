package com.hamurcuabi.usagelimit.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
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
import com.hamurcuabi.usagelimit.data.Apps
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
    private lateinit var powerManager: PowerManager
    private lateinit var windowManager: WindowManager
    private lateinit var notifications: NotificationManager

    private var defaultExempt: Set<String> = emptySet()
    private var exemptLoadedAt = 0L

    private var overlayView: View? = null
    private var overlayPkg: String? = null

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        hideOverlay()
        super.onDestroy()
    }

    private fun tick() {
        if (!powerManager.isInteractive || !Permissions.hasUsageAccess(this)) {
            hideOverlay()
            return
        }

        val now = System.currentTimeMillis()
        if (now - exemptLoadedAt > EXEMPT_REFRESH_MS) {
            defaultExempt = Apps.defaultExempt(this)
            exemptLoadedAt = now
            store.pruneOtherDays(Time.dayKey(now))
        }

        val snapshot = UsageReader.read(this, Time.startOfDay(now), now)
        val pkg = snapshot.foreground
        if (pkg == null || pkg == packageName) {
            hideOverlay()
            return
        }
        val limitMin = store.limitMinutes(pkg, defaultExempt)
        if (limitMin == null) {
            hideOverlay()
            return
        }

        val day = Time.dayKey(now)
        val usedMs = snapshot.perApp[pkg]?.totalMs ?: 0L
        val extensions = store.extensionsUsed(pkg, day)
        val allowedMs = (limitMin + extensions * LimitStore.EXTENSION_MIN) * Time.MINUTE_MS

        if (usedMs >= allowedMs) {
            showLimitReached(pkg, usedMs, limitMin, extensions, day)
            return
        }

        hideOverlay()
        if (usedMs >= limitMin * Time.MINUTE_MS * 8 / 10 && !store.wasWarned(pkg, day)) {
            store.markWarned(pkg, day)
            val left = Time.format(allowedMs - usedMs)
            notifyAlert(
                id = WARN_ID_BASE + (pkg.hashCode() and 0xFFFF),
                title = "${Apps.label(this, pkg)}: limit dolmak üzere",
                text = "Bugün ${Time.format(usedMs)} kullandın. Kalan: $left.",
            )
        }
    }

    private fun showLimitReached(pkg: String, usedMs: Long, limitMin: Int, extensions: Int, day: String) {
        if (overlayPkg == pkg && overlayView != null) return
        hideOverlay()

        val label = Apps.label(this, pkg)
        if (!Settings.canDrawOverlays(this)) {
            // Üste çizme izni yoksa en azından bildirimle haber ver (dakikada en fazla bir kez).
            val now = System.currentTimeMillis()
            if (lastFallbackPkg != pkg || now - lastFallbackAt > Time.MINUTE_MS) {
                lastFallbackPkg = pkg
                lastFallbackAt = now
                notifyAlert(
                    id = LIMIT_ID_BASE + (pkg.hashCode() and 0xFFFF),
                    title = "$label: günlük limit doldu",
                    text = "Bugün ${Time.format(usedMs)} kullandın (limit $limitMin dk).",
                )
            }
            return
        }

        val extensionsLeft = LimitStore.MAX_EXTENSIONS - extensions
        val view = buildOverlay(
            title = "$label için bugünlük süre doldu",
            body = "Bugün ${Time.format(usedMs)} kullandın. Günlük limitin $limitMin dk.",
            extensionsLeft = extensionsLeft,
            onClose = {
                hideOverlay()
                goHome()
            },
            onExtend = {
                store.addExtension(pkg, day)
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
            setOnClickListener { onClose() }
        }, buttonParams)

        if (extensionsLeft > 0) {
            root.addView(Button(this).apply {
                text = "${LimitStore.EXTENSION_MIN} dk daha (bugün $extensionsLeft hak kaldı)"
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

    companion object {
        private const val TICK_MS = 3_000L
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
