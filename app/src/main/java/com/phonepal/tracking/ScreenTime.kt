package com.phonepal.tracking

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** Exact per-app foreground time from usage events (the same data Digital Wellbeing uses). */
object ScreenTime {
    data class AppTime(val pkg: String, val label: String, val ms: Long)

    fun foreground(ctx: Context, start: Long, end: Long = System.currentTimeMillis()): List<AppTime> {
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val events = usm.queryEvents(start, end)
        val ev = UsageEvents.Event()
        val openActivities = HashMap<String, MutableSet<String>>()
        val openedAt = HashMap<String, Long>()
        val total = HashMap<String, Long>()

        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            val pkg = ev.packageName ?: continue
            val cls = ev.className ?: ""
            when (ev.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val set = openActivities.getOrPut(pkg) { mutableSetOf() }
                    if (set.isEmpty()) openedAt[pkg] = ev.timeStamp
                    set += cls
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val set = openActivities[pkg] ?: continue
                    if (set.remove(cls) && set.isEmpty()) {
                        openedAt.remove(pkg)?.let { total[pkg] = (total[pkg] ?: 0) + (ev.timeStamp - it) }
                    }
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    // Screen off: close everything that was open.
                    openedAt.forEach { (p, t) -> total[p] = (total[p] ?: 0) + (ev.timeStamp - t) }
                    openedAt.clear(); openActivities.clear()
                }
            }
        }
        openedAt.forEach { (p, t) -> total[p] = (total[p] ?: 0) + (end - t) }

        val pm = ctx.packageManager
        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        return total.filter { (p, ms) -> ms > 5_000 && p != home && p != "com.android.systemui" && pm.getLaunchIntentForPackage(p) != null }
            .map { (p, ms) -> AppTime(p, Capture.appLabel(ctx, p), ms) }
            .sortedByDescending { it.ms }
    }
}

object Permissions {
    fun notificationAccess(ctx: Context) =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun accessibility(ctx: Context): Boolean {
        val me = ComponentName(ctx, PalAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun usageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = if (android.os.Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    val notificationIntent get() = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    val accessibilityIntent get() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    val usageIntent get() = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
}
