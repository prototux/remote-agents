package net.prototux.remoteagents.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import net.prototux.remoteagents.MainActivity
import net.prototux.remoteagents.R
import net.prototux.remoteagents.api.Format
import net.prototux.remoteagents.data.Alert
import net.prototux.remoteagents.data.LiveState
import net.prototux.remoteagents.data.SessionKey
import net.prototux.remoteagents.data.SettingsStore

/**
 * Renders [Alert]s as notifications: one channel per kind, approval actions when there are at most three
 * options, quick answers and a free-text reply. Content is hidden on a secure lock screen
 * (VISIBILITY_PRIVATE with a neutral public version).
 */
class Notifier(private val context: Context, private val settings: SettingsStore) {
    private val nm = NotificationManagerCompat.from(context)

    init {
        val channels = listOf(
            NotificationChannel(CH_APPROVAL, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply { description = "A tool needs your permission" },
            NotificationChannel(CH_QUESTION, "Questions", NotificationManager.IMPORTANCE_HIGH).apply { description = "The agent asks you to choose" },
            NotificationChannel(CH_FINISHED, "Finished turns", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "A turn finished" },
            NotificationChannel(CH_ERROR, "Errors", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "A session failed" },
            NotificationChannel(CH_WATCHING, "Watching", NotificationManager.IMPORTANCE_LOW).apply { description = "Sessions being followed in the background"; setShowBadge(false) },
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(channels)
    }

    fun handle(alert: Alert, state: LiveState, visible: Boolean) {
        val s = settings.settings.value
        val title = state.meta.title?.let { Format.oneLine(it, 60) } ?: "Session"
        val provider = state.meta.provider
        when (alert) {
            is Alert.Clear -> nm.cancel(id(alert.key, alert.kind))
            is Alert.Permission -> if (s.notifyApproval && !visible) {
                val r = alert.request
                val b = base(CH_APPROVAL, alert.key, Alert.Kind.Permission, provider, openSheet = true)
                    .setContentTitle("Allow ${r.toolName}?")
                    .setContentText(Format.oneLine(r.description ?: r.detail))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(r.description, r.detail).joinToString("\n\n")))
                    .setSubText(title)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setPublicVersion(publicVersion(CH_APPROVAL, "A session needs your approval"))
                if (r.options.size <= 3) r.options.forEachIndexed { i, o ->
                    val intent = actionIntent(ActionReceiver.ACTION_PERMISSION, alert.key, provider).putExtra(ActionReceiver.EXTRA_VALUE, o.key)
                    b.addAction(
                        NotificationCompat.Action.Builder(0, o.text, broadcast(intent, alert.key, Alert.Kind.Permission, i, mutable = false))
                            .setAuthenticationRequired(s.actionsNeedUnlock).build(),
                    )
                }
                post(alert.key, Alert.Kind.Permission, b.build())
            }
            is Alert.Question -> if (s.notifyQuestion && !visible) {
                val qs = alert.question.questions
                val first = qs.firstOrNull()
                val b = base(CH_QUESTION, alert.key, Alert.Kind.Question, provider, openSheet = true)
                    .setContentTitle(first?.header ?: "Question")
                    .setContentText(Format.oneLine(first?.question))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(qs.joinToString("\n\n") { q ->
                        q.question + "\n" + q.options.joinToString("\n") { "• " + it.label }
                    }))
                    .setSubText(title)
                    .setPublicVersion(publicVersion(CH_QUESTION, "A session asks you a question"))
                // Quick answers only for the simple case: one single-choice question with up to two options,
                // so a free-text "Answer" action still fits.
                if (qs.size == 1 && !first!!.multiSelect && first.options.size <= 2) first.options.forEachIndexed { i, o ->
                    val intent = actionIntent(ActionReceiver.ACTION_ANSWER, alert.key, provider)
                        .putExtra(ActionReceiver.EXTRA_QUESTION, first.key).putExtra(ActionReceiver.EXTRA_VALUE, o.label)
                    b.addAction(NotificationCompat.Action.Builder(0, o.label, broadcast(intent, alert.key, Alert.Kind.Question, i, mutable = false))
                        .setAuthenticationRequired(s.actionsNeedUnlock).build())
                }
                if (qs.size == 1) {
                    val intent = actionIntent(ActionReceiver.ACTION_ANSWER, alert.key, provider).putExtra(ActionReceiver.EXTRA_QUESTION, first!!.key)
                    b.addAction(replyAction("Answer", intent, alert.key, Alert.Kind.Question, s.actionsNeedUnlock))
                }
                post(alert.key, Alert.Kind.Question, b.build())
            }
            is Alert.Finished -> if (s.notifyFinished && !visible) {
                val r = alert.result
                val usage = listOfNotNull(r.durationMs?.let(Format::duration), r.costUsd?.let(Format::cost)).joinToString(" · ")
                val text = if (r.success) alert.lastText ?: r.text else r.text
                val b = base(CH_FINISHED, alert.key, Alert.Kind.Finished, provider, openSheet = false)
                    .setContentTitle(if (r.success) title else "Stopped: $title")
                    .setContentText(Format.oneLine(text, 200))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text?.take(1500)))
                    .setSubText(usage.ifBlank { null })
                    .setPublicVersion(publicVersion(CH_FINISHED, "A session finished"))
                    .addAction(replyAction("Reply", actionIntent(ActionReceiver.ACTION_REPLY, alert.key, provider), alert.key, Alert.Kind.Finished, s.actionsNeedUnlock))
                post(alert.key, Alert.Kind.Finished, b.build())
            }
            is Alert.Failed -> if (s.notifyError && !visible) {
                val b = base(CH_ERROR, alert.key, Alert.Kind.Failed, provider, openSheet = false)
                    .setContentTitle("Error: $title")
                    .setContentText(Format.oneLine(alert.message, 200))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(alert.message))
                    .setPublicVersion(publicVersion(CH_ERROR, "A session failed"))
                post(alert.key, Alert.Kind.Failed, b.build())
            }
        }
    }

    /** A failure of a notification action (the decision could not be sent). */
    fun actionFailed(key: SessionKey, message: String) {
        val b = NotificationCompat.Builder(context, CH_ERROR).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Could not send").setContentText(message)
            .setContentIntent(openIntent(key, null, openSheet = true, requestCode = id(key, Alert.Kind.Failed)))
            .setAutoCancel(true)
        post(key, Alert.Kind.Failed, b.build())
    }

    fun cancel(key: SessionKey, kind: Alert.Kind) = nm.cancel(id(key, kind))

    fun ongoing(working: List<LiveState>): Notification {
        val lines = working.map { st ->
            val t = st.transcript
            val what = when {
                t.pendingPermission != null -> "needs approval"
                t.pendingQuestion != null -> "asks a question"
                else -> t.stats?.let { "working · " + Format.duration(it.durationMs) } ?: "working"
            }
            (st.meta.title?.let { Format.oneLine(it, 40) } ?: "Session") + " — " + what
        }
        val stop = Intent(context, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_UNWATCH_ALL)
        return NotificationCompat.Builder(context, CH_WATCHING)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (working.size == 1) "Following 1 session" else "Following ${working.size} sessions")
            .setContentText(lines.firstOrNull())
            .setStyle(NotificationCompat.InboxStyle().also { st -> lines.forEach { st.addLine(it) } })
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "Stop following", PendingIntent.getBroadcast(context, 1, stop, PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    private fun base(channel: String, key: SessionKey, kind: Alert.Kind, provider: String?, openSheet: Boolean) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openIntent(key, provider, openSheet, id(key, kind)))
            .setGroup(key.sessionId)

    private fun publicVersion(channel: String, text: String) =
        NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_stat).setContentTitle(context.getString(R.string.app_name)).setContentText(text).build()

    private fun openIntent(key: SessionKey, provider: String?, openSheet: Boolean, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_SERVER, key.serverId)
            .putExtra(MainActivity.EXTRA_SESSION, key.sessionId)
            .putExtra(MainActivity.EXTRA_PROVIDER, provider)
            .putExtra(MainActivity.EXTRA_OPEN_SHEET, openSheet)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun actionIntent(action: String, key: SessionKey, provider: String?) =
        Intent(context, ActionReceiver::class.java).setAction(action)
            .putExtra(MainActivity.EXTRA_SERVER, key.serverId)
            .putExtra(MainActivity.EXTRA_SESSION, key.sessionId)
            .putExtra(MainActivity.EXTRA_PROVIDER, provider)

    private fun broadcast(intent: Intent, key: SessionKey, kind: Alert.Kind, index: Int, mutable: Boolean): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(context, id(key, kind) * 8 + index, intent, flags)
    }

    private fun replyAction(label: String, intent: Intent, key: SessionKey, kind: Alert.Kind, needUnlock: Boolean) =
        NotificationCompat.Action.Builder(0, label, broadcast(intent, key, kind, 7, mutable = true))
            .addRemoteInput(RemoteInput.Builder(ActionReceiver.KEY_TEXT).setLabel(label).build())
            .setAllowGeneratedReplies(false)
            .setAuthenticationRequired(needUnlock)
            .build()

    private fun post(key: SessionKey, kind: Alert.Kind, n: Notification) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        nm.notify(id(key, kind), n)
    }

    companion object {
        const val CH_APPROVAL = "approval"
        const val CH_QUESTION = "question"
        const val CH_FINISHED = "finished"
        const val CH_ERROR = "error"
        const val CH_WATCHING = "watching"
        const val ONGOING_ID = 1

        fun id(key: SessionKey, kind: Alert.Kind): Int = ((key.serverId + "/" + key.sessionId).hashCode() and 0x0FFFFFFF) * 4 + kind.ordinal + 16
    }
}
