package net.prototux.remoteagents.notify

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.prototux.remoteagents.RemoteAgentsApp

/**
 * Keeps the process (and so the event streams) alive while a watched session is busy or awaiting, with
 * an ongoing notification listing them. Started and stopped by the [net.prototux.remoteagents.data.SessionHub]
 * through the container; it holds no state of its own.
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val container = (application as RemoteAgentsApp).container
        val notification = container.notifier.ongoing(ongoingLines(container.hub.working.value))
        // The specialUse type exists from Android 14; earlier versions take the service without a type.
        if (Build.VERSION.SDK_INT >= 34) startForeground(Notifier.ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(Notifier.ONGOING_ID, notification)
        scope.launch {
            // Agent updates change the working list up to once a second; re-post only when the text changes.
            container.hub.working.map { ongoingLines(it) }.distinctUntilChanged().collect { lines ->
                if (lines.isEmpty()) stopSelf()
                else getSystemService(android.app.NotificationManager::class.java).notify(Notifier.ONGOING_ID, container.notifier.ongoing(lines))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
