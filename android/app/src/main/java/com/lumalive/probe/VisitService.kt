package com.lumalive.probe

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Bounded wearer-started foreground visit; never auto-restarts or starts after boot. */
class VisitService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime get() = application as LumaApplication
    private var wake: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Visita con Luma", NotificationManager.IMPORTANCE_LOW))
        scope.launch {
            runtime.guide.state.collect { state ->
                if (state.active) {
                    val paused = state.phase == VisitPhase.PAUSED
                    if (paused) releaseWake() else keepAwake()
                    val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or if (paused) 0 else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    startForeground(ID, notification(state), type)
                }
            }
        }
    }

    private fun notification(state: VisitState): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, VisitService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val paused = state.phase == VisitPhase.PAUSED
        val action = PendingIntent.getService(this, 3, Intent(this, VisitService::class.java).setAction(if (paused) RESUME else PAUSE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.luma_icon).setContentTitle(if (paused) "Luma está en pausa" else "Luma te acompaña")
            .setContentText(state.status).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_play, if (paused) "Retomar conversación" else "Pausar escucha", action)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Terminar visita", stop).build()
    }

    private fun releaseWake() { wake?.let { if (it.isHeld) it.release() }; wake = null }
    private fun keepAwake() {
        if (wake?.isHeld == true) return
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Luma:Visit").apply { acquire((runtime.guide.remainingMs + 10_000).coerceAtLeast(10_000)) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val source = intent?.getStringExtra("source")?.takeIf { it in setOf("voice", "button") } ?: "notification"
        Log.i("LumaVisit", "service:activation,source=$source,phase=${runtime.guide.state.value.phase}")
        // Satisfy startForegroundService even if a stop arrives before pending startup.
        startForeground(ID, notification(runtime.guide.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (intent?.action == STOP || !runtime.voiceConsent || !canNotify(this)) {
            runtime.guide.stop("Visita terminada"); stopSelf(); return START_NOT_STICKY
        }
        runCatching {
            if (intent?.action == PAUSE) {
                if (runtime.guide.state.value.active) runtime.guide.pause() else stopSelf()
            }
            else { runtime.guide.start { stopSelf() }; if (runtime.guide.state.value.active && runtime.guide.state.value.phase != VisitPhase.PAUSED) keepAwake() }
        }.onFailure { runtime.guide.stop(it.message ?: "No se pudo iniciar la visita"); stopSelf() }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) { runtime.guide.stop("Luma cerrada desde aplicaciones recientes"); stopSelf() }
    override fun onDestroy() {
        if (runtime.guide.state.value.active) runtime.guide.stop("Visita detenida por Android")
        releaseWake()
        scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "luma_visit"
        private const val ID = 41
        private const val STOP = "com.lumalive.probe.STOP_VISIT"
        private const val PAUSE = "com.lumalive.probe.PAUSE_VISIT"
        private const val RESUME = "com.lumalive.probe.RESUME_VISIT"
        fun canNotify(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }
        fun begin(context: Context, source: String = "button") { context.startForegroundService(Intent(context, VisitService::class.java).putExtra("source", source)) }
        fun end(context: Context) { context.startService(Intent(context, VisitService::class.java).setAction(STOP)) }
    }
}
