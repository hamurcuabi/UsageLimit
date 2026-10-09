package com.hamurcuabi.usagelimit.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hamurcuabi.usagelimit.data.LimitStore

/** Telefon yeniden başladığında veya uygulama güncellendiğinde izlemeyi geri açar. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (LimitStore(context).monitoringEnabled) {
                    try {
                        MonitorService.start(context)
                    } catch (_: Exception) {
                        // Sistem arka plandan başlatmaya izin vermezse uygulama açılınca yeniden başlar.
                    }
                }
            }
        }
    }
}
