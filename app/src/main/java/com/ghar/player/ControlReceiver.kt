package com.ghar.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Play / pause / next / previous / stop from Ghar Launcher's music card.
 * Only Ghar apps signed with the same key may send these.
 */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val s = PlaybackService.instance ?: run { PlaybackService.ensure(c); return }
        when (i.action) {
            "com.ghar.player.action.TOGGLE" -> s.toggle()
            "com.ghar.player.action.NEXT" -> s.next()
            "com.ghar.player.action.PREVIOUS" -> s.previous()
            "com.ghar.player.action.STOP" -> s.stopAll()
        }
    }
}
