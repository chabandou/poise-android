package com.poise.android.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.poise.android.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile that starts and stops Poise from the notification shade. */
class PoiseTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        // Follow the session while the shade is open
        scope = MainScope().apply { launch { AudioServiceState.isActive.collect { showActive(it) } } }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        if (AudioServiceState.isActive.value) {
            startService(Intent(this, AudioCaptureService::class.java).setAction(AudioCaptureService.ACTION_STOP))
            return
        }
        // Starting needs the permission and screen-share prompts, which only an activity can show
        val intent =
                Intent(this, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_START)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }

    private fun showActive(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
