package com.example.extbrowser

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Comando interno único, acessível por menus e notificações sem abrir telas. */
class StopAllMediaReceiver : BroadcastReceiver() {
    companion object {
        private const val ACTION_STOP_ALL = "com.example.extbrowser.STOP_ALL_MEDIA"

        fun pendingIntent(context: Context, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(context, requestCode,
                Intent(context, StopAllMediaReceiver::class.java).setAction(ACTION_STOP_ALL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun stopAll(context: Context) {
            // As Activities/serviços podem estar ausentes: não criamos players
            // novos só para encerrá-los.
            MainActivity.stopExistingWebMedia()
            Media3PlaybackService.stopExistingPlayback()
            context.stopService(Intent(context, NativeMediaService::class.java))
            context.stopService(Intent(context, BackgroundAudioService::class.java))
            VideoPipActivity.closeExistingWindow()
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_STOP_ALL) stopAll(context)
    }
}
