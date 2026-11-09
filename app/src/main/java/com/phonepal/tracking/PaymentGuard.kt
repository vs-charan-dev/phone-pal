package com.phonepal.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.phonepal.PhonePalApp
import com.phonepal.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Payment and banking apps refuse to work while a screen-reading accessibility service is on.
 * PhonePal never reads them, and as soon as one opens it switches its accessibility service off;
 * [PaymentGuardService] switches it back on once you leave.
 *
 * Switching the service back on needs WRITE_SECURE_SETTINGS, which can only be granted once over adb:
 *   adb shell pm grant com.phonepal android.permission.WRITE_SECURE_SETTINGS
 * Without it, PhonePal still switches itself off, and you turn it back on from the quick-settings tile
 * (which then opens Accessibility settings).
 */
object PaymentGuard {
    private const val TAG = "PhonePal.Guard"

    /** Well-known payment, wallet and banking apps. */
    val KNOWN = setOf(
        // UPI / wallets
        "com.google.android.apps.nbu.paisa.user",   // Google Pay (India)
        "com.google.android.apps.walletnfcrel",     // Google Wallet
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp",                       // BHIM
        "com.dreamplug.androidapp",                 // CRED
        "com.mobikwik_new",
        "com.freecharge.android",
        "com.enstage.wibmo.hdfc",                   // PayZapp
        "money.super.payments",                     // super.money
        "com.naviapp",
        "com.samsung.android.spay",
        "com.paypal.android.p2pmobile",
        "com.venmo",
        "com.squareup.cash",
        // Banks
        "com.sbi.lotusintouch",                     // YONO SBI
        "com.sbi.SBIFreedomPlus",
        "com.sbi.upi",
        "com.csam.icici.bank.imobile",
        "com.snapwork.hdfc",
        "com.hdfcbank.android.now",
        "com.axis.mobile",
        "com.msf.kbank.mobile",                     // Kotak
        "com.bankofbaroda.mconnect",
        "com.canarabank.mobility",
        "com.infrasoft.uboi",                       // Union Bank
        "com.idfcfirstbank.optimus",
        "com.fedbank.fedmobile",
        "com.indusind.indie",
        "com.yesbank.yesmobile",
        "com.pnb.pnbone",
    )

    /** Package-name parts that mark a payment app too (com.xyz.bank, in.abc.upi, …). */
    private val CONTAINS = listOf("bank", "wallet")
    private val EXACT = setOf("upi", "paytm", "phonepe", "bhim", "pay")

    /** System screens that may pop up in the middle of a payment; they don't count as "you left". */
    private val TRANSIENT = setOf(
        "com.android.systemui", "com.google.android.gms", "android",
        "com.google.android.permissioncontroller", "com.android.permissioncontroller",
        "com.google.android.inputmethod.latin", "com.samsung.android.honeyboard",
    )

    fun isPayment(ctx: Context, pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        if (pkg in KNOWN) return true
        val parts = pkg.lowercase().split('.', '_')
        if (parts.any { p -> p in EXACT || CONTAINS.any { p.contains(it) } }) return true
        return pkg in (ctx.applicationContext as PhonePalApp).settings.extraPaymentApps
    }

    fun isTransient(pkg: String) = pkg in TRANSIENT

    /** Installed apps that PhonePal will stay away from, for the settings screen. */
    fun installedProtected(ctx: Context): List<String> {
        val pm = ctx.packageManager
        return pm.getInstalledApplications(0)
            .map { it.packageName }
            .filter { isPayment(ctx, it) && pm.getLaunchIntentForPackage(it) != null }
            .map { Capture.appLabel(ctx, it) }
            .sorted()
    }

    /** True once WRITE_SECURE_SETTINGS has been granted over adb. */
    fun canToggle(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Adds or removes PhonePal's accessibility service. Needs [canToggle]. */
    fun setServiceEnabled(ctx: Context, on: Boolean): Boolean = try {
        val me = ComponentName(ctx, PalAccessibilityService::class.java)
        val cr = ctx.contentResolver
        val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != me }
        val next = if (on) current + me.flattenToString() else current
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next.joinToString(":"))
        if (on) Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        true
    } catch (e: SecurityException) {
        Log.w(TAG, "No WRITE_SECURE_SETTINGS", e)
        false
    }
}

/**
 * Runs while PhonePal is paused for a payment app. Watches which app is in front (usage access)
 * and turns the accessibility service back on once you've been out of payment apps for a few seconds.
 */
class PaymentGuardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESUME) {
            resume()
            return START_NOT_STICKY
        }
        val pkg = intent?.getStringExtra(EXTRA_PKG) ?: return START_NOT_STICKY.also { stopSelf() }
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification(pkg),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        if (watcher == null) watcher = scope.launch { watch(pkg) }
        return START_NOT_STICKY
    }

    private suspend fun watch(startPkg: String) {
        val usm = getSystemService(UsageStatsManager::class.java)
        var current = startPkg
        var leftAt = 0L      // when a non-payment app came to the front
        var since = System.currentTimeMillis() - 3_000
        val ev = UsageEvents.Event()
        while (scope.isActive) {
            delay(1_000)
            // Turned back on some other way (tile, Settings): nothing left to do.
            if (Permissions.accessibility(this)) return stop()
            if (!Permissions.usageAccess(this)) continue   // only the notification's Resume button works then
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(since, now)
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                val p = ev.packageName ?: continue
                if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED && !PaymentGuard.isTransient(p) && p != current) {
                    current = p
                    leftAt = if (PaymentGuard.isPayment(this, p)) 0L else ev.timeStamp
                }
            }
            since = now
            if (leftAt > 0 && now - leftAt >= LEAVE_GRACE_MS) return resume()
        }
    }

    private fun resume() {
        PaymentGuard.setServiceEnabled(this, true)
        stop()
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(pkg: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Paused for payments", NotificationManager.IMPORTANCE_MIN))
        val resume = PendingIntent.getService(
            this, 0, Intent(this, PaymentGuardService::class.java).setAction(ACTION_RESUME),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val auto = Permissions.usageAccess(this)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("PhonePal paused for ${Capture.appLabel(this, pkg)}")
            .setContentText(if (auto) "Turns back on when you leave the app." else "Tap Resume when you're done.")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .addAction(0, "Resume now", resume)
            .build()
    }

    companion object {
        private const val CHANNEL = "payment_guard"
        private const val NOTIF_ID = 42
        private const val EXTRA_PKG = "pkg"
        private const val ACTION_RESUME = "com.phonepal.RESUME_TRACKING"
        private const val LEAVE_GRACE_MS = 4_000L

        fun start(ctx: Context, pkg: String) {
            ctx.startForegroundService(Intent(ctx, PaymentGuardService::class.java).putExtra(EXTRA_PKG, pkg))
        }
    }
}
