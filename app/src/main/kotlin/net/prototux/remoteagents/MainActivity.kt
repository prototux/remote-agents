package net.prototux.remoteagents

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import net.prototux.remoteagents.ui.AppRoot
import net.prototux.remoteagents.ui.OpenRequest
import net.prototux.remoteagents.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    /** A session to open, from a notification; consumed by the navigation. */
    private val openRequest = MutableStateFlow<OpenRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as RemoteAgentsApp).container
        if (savedInstanceState == null) handle(intent)
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            AppTheme(settings.theme, settings.darkMode, settings.dynamicColor) {
                AppRoot(container, openRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        (application as RemoteAgentsApp).container.startWatchingIfNeeded()
    }

    private fun handle(intent: Intent?) {
        val server = intent?.getStringExtra(EXTRA_SERVER) ?: return
        val session = intent.getStringExtra(EXTRA_SESSION) ?: return
        openRequest.value = OpenRequest(server, session, intent.getStringExtra(EXTRA_PROVIDER), intent.getBooleanExtra(EXTRA_OPEN_SHEET, false))
    }

    companion object {
        const val EXTRA_SERVER = "server"
        const val EXTRA_SESSION = "session"
        const val EXTRA_PROVIDER = "provider"
        const val EXTRA_OPEN_SHEET = "openSheet"
    }
}
