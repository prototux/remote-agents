package net.prototux.remoteagents.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import net.prototux.remoteagents.MainActivity
import net.prototux.remoteagents.RemoteAgentsApp
import net.prototux.remoteagents.data.Alert
import net.prototux.remoteagents.data.SessionKey

/** Sends a decision, an answer or a reply straight from a notification, without opening the app. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as RemoteAgentsApp).container
        if (intent.action == ACTION_UNWATCH_ALL) { container.hub.unwatchAll(); return }
        val key = SessionKey(intent.getStringExtra(MainActivity.EXTRA_SERVER) ?: return, intent.getStringExtra(MainActivity.EXTRA_SESSION) ?: return)
        val provider = intent.getStringExtra(MainActivity.EXTRA_PROVIDER)
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim()
        val value = intent.getStringExtra(EXTRA_VALUE)
        val pending = goAsync()
        scope.launch {
            val hub = container.hub
            val notifier = container.notifier
            try {
                withTimeout(9_000) {
                    when (intent.action) {
                        ACTION_PERMISSION -> { hub.respondPermission(key, value ?: return@withTimeout, provider); notifier.cancel(key, Alert.Kind.Permission) }
                        ACTION_ANSWER -> {
                            val question = intent.getStringExtra(EXTRA_QUESTION) ?: return@withTimeout
                            val answer = typed?.ifBlank { null } ?: value ?: return@withTimeout
                            hub.answer(key, mapOf(question to answer), provider)
                            notifier.cancel(key, Alert.Kind.Question)
                        }
                        ACTION_REPLY -> {
                            val text = typed?.ifBlank { null } ?: return@withTimeout
                            hub.reply(key, text, provider)
                            notifier.cancel(key, Alert.Kind.Finished)
                            container.startWatchingIfNeeded()
                        }
                    }
                }
            } catch (e: Exception) {
                notifier.actionFailed(key, e.message ?: e.javaClass.simpleName)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PERMISSION = "net.prototux.remoteagents.PERMISSION"
        const val ACTION_ANSWER = "net.prototux.remoteagents.ANSWER"
        const val ACTION_REPLY = "net.prototux.remoteagents.REPLY"
        const val ACTION_UNWATCH_ALL = "net.prototux.remoteagents.UNWATCH_ALL"
        const val EXTRA_VALUE = "value"
        const val EXTRA_QUESTION = "question"
        const val KEY_TEXT = "text"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
