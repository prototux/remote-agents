package net.prototux.remoteagents

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.prototux.remoteagents.data.ClientRegistry
import net.prototux.remoteagents.data.ServerStore
import net.prototux.remoteagents.data.SessionHub
import net.prototux.remoteagents.data.SettingsStore
import net.prototux.remoteagents.data.TokenCipher
import net.prototux.remoteagents.notify.Notifier
import net.prototux.remoteagents.notify.WatchService

class RemoteAgentsApp : Application() {
    lateinit var container: Container
        private set

    override fun onCreate() {
        super.onCreate()
        container = Container(this)
    }
}

/** The app's singletons, wired by hand (few enough not to need a DI framework). */
class Container(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cipher = TokenCipher()
    val servers = ServerStore(app.noBackupFilesDir, cipher)
    val settings = SettingsStore(app, cipher)
    val clients = ClientRegistry(servers)
    val notifier = Notifier(app, settings)
    private var serviceWanted = false

    val hub: SessionHub = SessionHub(
        scope, servers, clients,
        onAlert = { alert, state -> notifier.handle(alert, state, visible = hub.visible == alert.key) },
        onServiceNeeded = { need -> serviceWanted = need; if (need) startService() },
    )

    fun startWatchingIfNeeded() { if (serviceWanted) startService() }

    private fun startService() {
        try {
            app.startForegroundService(Intent(app, WatchService::class.java))
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // Started from the background without an exemption; it starts the next time the app is opened.
            Log.w("RemoteAgents", "Foreground service not allowed now")
        } catch (e: IllegalStateException) {
            Log.w("RemoteAgents", "Foreground service not allowed now")
        }
    }
}
