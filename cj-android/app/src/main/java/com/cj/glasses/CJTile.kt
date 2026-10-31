package com.cj.glasses

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/** Quick Settings tile: one tap turns hands-free CJ on or off. */
class CJTile : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = if (CJService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (CJService.running) "Say \"CJ\"" else "Off"
            updateTile()
        }
    }

    override fun onClick() {
        try {
            if (CJService.running) CJService.stop(this) else CJService.start(this)
        } catch (e: Exception) {
            // Some Android versions refuse to start a microphone service from the shade; open the app instead.
            Log.w("CJTile", "could not toggle hands-free", e)
        }
        qsTile?.apply {
            state = if (state == Tile.STATE_ACTIVE) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            updateTile()
        }
    }
}
