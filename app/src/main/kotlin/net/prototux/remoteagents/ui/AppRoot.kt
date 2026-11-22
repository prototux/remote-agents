package net.prototux.remoteagents.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.MutableStateFlow
import net.prototux.remoteagents.Container

/** A session to open (from a notification). */
data class OpenRequest(val serverId: String, val sessionId: String, val provider: String?, val openSheet: Boolean)

object Routes {
    const val SERVERS = "servers"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    fun server(id: String) = "server/${Uri.encode(id)}"
    fun sessions(serverId: String) = "sessions/${Uri.encode(serverId)}"
    fun session(serverId: String, sessionId: String, provider: String? = null, title: String? = null, sheet: Boolean = false) =
        "session/${Uri.encode(serverId)}/${Uri.encode(sessionId)}?provider=${Uri.encode(provider.orEmpty())}&title=${Uri.encode(title.orEmpty())}&sheet=$sheet"
}

/** Opens the only server directly on the first start of the process. */
private var autoOpened = false

@Composable
fun AppRoot(container: Container, openRequest: MutableStateFlow<OpenRequest?>) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val request by openRequest.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        request?.let { r ->
            autoOpened = true
            nav.navigate(Routes.session(r.serverId, r.sessionId, r.provider, sheet = r.openSheet)) { launchSingleTop = true }
            openRequest.value = null
        }
    }

    NavHost(nav, startDestination = Routes.SERVERS) {
        composable(Routes.SERVERS) {
            ServersScreen(
                container,
                onOpen = { nav.navigate(Routes.sessions(it)) },
                onEdit = { nav.navigate(Routes.server(it)) },
                onAdd = { nav.navigate(Routes.server("new")) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                autoOpenSingle = { if (!autoOpened) { autoOpened = true; true } else false },
            )
        }
        composable("server/{id}", listOf(navArgument("id") { type = NavType.StringType })) { e ->
            ServerEditScreen(container, e.arguments?.getString("id").takeUnless { it == "new" }, onDone = { nav.popBackStack() })
        }
        composable("sessions/{serverId}", listOf(navArgument("serverId") { type = NavType.StringType })) { e ->
            val serverId = e.arguments?.getString("serverId")!!
            SessionsScreen(
                container, serverId,
                onBack = { nav.popBackStack() },
                onOpen = { s -> nav.navigate(Routes.session(serverId, s.id, s.provider, s.title)) },
                onOpenKey = { key, provider, title -> nav.navigate(Routes.session(key.serverId, key.sessionId, provider, title)) },
                onEditServer = { nav.navigate(Routes.server(serverId)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            "session/{serverId}/{sessionId}?provider={provider}&title={title}&sheet={sheet}",
            listOf(
                navArgument("serverId") { type = NavType.StringType },
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("provider") { type = NavType.StringType; defaultValue = "" },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
                navArgument("sheet") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { e ->
            val a = e.arguments!!
            SessionScreen(
                container,
                serverId = a.getString("serverId")!!,
                sessionId = a.getString("sessionId")!!,
                provider = a.getString("provider")?.ifBlank { null },
                title = a.getString("title")?.ifBlank { null },
                openSheet = a.getBoolean("sheet"),
                onBack = { if (!nav.popBackStack()) nav.navigate(Routes.SERVERS) },
                onEditServer = { nav.navigate(Routes.server(it)) },
                onOpenSession = { key, provider, title -> nav.navigate(Routes.session(key.serverId, key.sessionId, provider, title)) },
            )
        }
        composable(Routes.SETTINGS) { SettingsScreen(container, onBack = { nav.popBackStack() }, onAbout = { nav.navigate(Routes.ABOUT) }) }
        composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
    }
}
