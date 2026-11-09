package com.phonepal.tracking

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick-settings tile "PhonePal tracking": one tap to switch screen reading off before a payment
 * and back on afterwards. Toggles directly once WRITE_SECURE_SETTINGS is granted over adb;
 * otherwise it opens Accessibility settings.
 */
class PalTileService : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        val on = Permissions.accessibility(this)
        if (PaymentGuard.canToggle(this) && PaymentGuard.setServiceEnabled(this, !on)) {
            if (!on) stopService(Intent(this, PaymentGuardService::class.java))
            refresh(!on)
            return
        }
        val intent = Permissions.accessibilityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh(on: Boolean = Permissions.accessibility(this)) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) "Reading screens" else "Paused"
        tile.updateTile()
    }
}
