package net.prototux.remoteagents.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.prototux.remoteagents.Container
import net.prototux.remoteagents.api.Format
import net.prototux.remoteagents.api.Providers
import net.prototux.remoteagents.api.Transcript
import net.prototux.remoteagents.api.TranscriptItem
import net.prototux.remoteagents.data.Alert
import net.prototux.remoteagents.data.Link
import net.prototux.remoteagents.data.SessionKey
import net.prototux.remoteagents.data.SessionMeta
import net.prototux.remoteagents.ui.theme.tokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    container: Container,
    serverId: String,
    sessionId: String,
    provider: String?,
    title: String?,
    openSheet: Boolean,
    onBack: () -> Unit,
    onEditServer: (String) -> Unit,
    onOpenSession: (SessionKey, String?, String?) -> Unit,
) {
    val key = remember(serverId, sessionId) { SessionKey(serverId, sessionId) }
    val hub = container.hub
    val state by remember(key) { hub.state(key, SessionMeta(title = title, provider = provider)) }.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val t = state.transcript
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    var draft by remember(key) { mutableStateOf(hub.drafts[key].orEmpty()) }
    var sending by remember { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf(openSheet) }
    var menu by remember { mutableStateOf(false) }
    var watched by remember(key) { mutableStateOf(hub.isWatched(key)) }
    // A prompt waiting for the owner's go-ahead to resume the session in a new process; the fork dialog's text.
    var confirmSend by remember(key) { mutableStateOf<String?>(null) }
    var forkText by remember(key) { mutableStateOf<String?>(null) }
    val reach = state.meta.reach
    val voice = rememberVoice(container)

    // The stream is open while the screen is started; leaving it only keeps it if the session is followed.
    LifecycleStartEffect(key) {
        hub.attach(key, SessionMeta(title = title, provider = provider))
        hub.visible = key
        watched = hub.isWatched(key)
        Alert.Kind.entries.forEach { container.notifier.cancel(key, it) }
        onStopOrDispose { hub.detach(key); if (hub.visible == key) hub.visible = null }
    }

    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // A new request opens the sheet, with a buzz: the owner is looking at this session.
    val pendingId = t.pendingPermission?.let { "p${it.id}${it.toolUseId}" } ?: t.pendingQuestion?.let { "q${it.id}${it.toolUseId}" }
    LaunchedEffect(pendingId) {
        if (pendingId != null && !state.loading) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); sheet = true }
        if (pendingId == null) sheet = false
    }

    fun act(block: suspend () -> Unit) {
        sending = true
        scope.launch {
            runCatching { block() }.onFailure { snackbar.showSnackbar(it.describe()) }
            sending = false
        }
    }

    val items = remember(t.items, settings.showUnsupported) { if (settings.showUnsupported) t.items else t.items.filterNot { it is TranscriptItem.Unknown } }
    val list = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.canScrollForward }.collect { (scrolling, canForward) -> if (scrolling) follow = !canForward }
    }
    val lastSize = (items.lastOrNull() as? TranscriptItem.Text)?.text?.length ?: 0
    LaunchedEffect(items.size, lastSize, follow) {
        if (follow && items.isNotEmpty()) list.scrollToItem(items.lastIndex, Int.MAX_VALUE / 2)
    }

    fun send(text: String) {
        val previous = draft
        draft = ""; hub.drafts.remove(key); follow = true
        container.settings.rememberPrompt(text)
        act { runCatching { hub.prompt(key, text) }.onFailure { draft = previous; throw it } }
    }

    fun fork(text: String) = act {
        val forked = hub.fork(key, text)
        draft = ""; hub.drafts.remove(key)
        container.settings.rememberPrompt(text)
        onOpenSession(forked, state.meta.provider, null)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.meta.title ?: "Session", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LinkDot(state.link)
                            Text(
                                listOfNotNull(Providers.label(state.meta.provider), state.meta.cwd?.let { Format.path(it, 2) }, linkText(state.link)).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { watched = !watched; hub.setWatched(key, watched) }) {
                        Icon(if (watched) Icons.Filled.Notifications else Icons.Outlined.Notifications, if (watched) "Stop following" else "Follow in background")
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Reload") }, onClick = { menu = false; hub.reload(key) })
                        // Only agentd forks; even-terminal would ignore the flag and resume this session instead.
                        if (reach?.canFork == true) DropdownMenuItem(text = { Text("Fork into a new session") }, onClick = { menu = false; forkText = draft })
                        DropdownMenuItem(text = { Text("Copy session id") }, onClick = { menu = false; clipboard.setText(AnnotatedString(sessionId)) })
                        DropdownMenuItem(text = { Text("Copy last reply") }, onClick = {
                            menu = false
                            (t.items.lastOrNull { it is TranscriptItem.Text } as? TranscriptItem.Text)?.let { clipboard.setText(AnnotatedString(it.text)) }
                        })
                        if (t.totalCostUsd > 0) DropdownMenuItem(text = { Text("Cost this session: ${Format.cost(t.totalCostUsd)} · ${t.turns} turns") }, onClick = { menu = false }, enabled = false)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding()) {
                AnimatedVisibility(t.awaitingInput) {
                    Banner(
                        if (t.pendingPermission != null) "Waiting for your approval: ${t.pendingPermission!!.toolName}" else "The agent asks you a question",
                        tokens.attention, tokens.onAttention, Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    ) { TextButton(onClick = { sheet = true }) { Text("Answer", color = tokens.onAttention) } }
                }
                PromptBox(
                    text = draft,
                    onText = { draft = it; hub.drafts[key] = it },
                    provider = state.meta.provider,
                    busy = t.busy,
                    sending = sending,
                    showQuickReplies = !t.busy && items.lastOrNull() is TranscriptItem.Outcome,
                    recentPrompts = { container.settings.recentPrompts() },
                    voice = voice,
                    onSend = { text -> if (hub.resumeWarning(key) != null) confirmSend = text else send(text) },
                    onInterrupt = { act { hub.interrupt(key) } },
                    disabledHint = reach?.takeIf { it.watchOnly }?.let { "Watch only" + if (it.canFork) ": fork it to write from here" else "" },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                LinkBanner(state.link, onEditServer = { onEditServer(serverId) })
                reach?.let { r ->
                    r.banner?.let { text ->
                        Banner(text, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            if (r.watchOnly && r.canFork) TextButton(onClick = { forkText = draft }) { Text("Fork") }
                        }
                    }
                }
                StatusStrip(t)
                if (state.loading && items.isEmpty()) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else if (items.isEmpty()) Text("No messages in the server's buffer for this session yet.", Modifier.padding(tokens.screenMargin), color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(items, key = { it.key }) { item -> TranscriptRow(item, settings.expandTools, onAnswer = { sheet = true }) }
                }
            }
            if (!follow) SmallFloatingActionButton(
                onClick = { follow = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) { Icon(Icons.Filled.KeyboardArrowDown, "Jump to latest") }
        }
    }

    confirmSend?.let { text ->
        AlertDialog(
            onDismissRequest = { confirmSend = null },
            title = { Text("Resume this session?") },
            text = { Text(hub.resumeWarning(key) ?: "Sending resumes this session in a new process.") },
            confirmButton = { TextButton(onClick = { confirmSend = null; send(text) }) { Text("Resume and send") } },
            dismissButton = {
                Row {
                    if (reach?.canFork == true) TextButton(onClick = { confirmSend = null; forkText = text }) { Text("Fork instead") }
                    TextButton(onClick = { confirmSend = null }) { Text("Cancel") }
                }
            },
        )
    }

    forkText?.let { text ->
        AlertDialog(
            onDismissRequest = { forkText = null },
            title = { Text("Fork into a new session") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A new session continues from this conversation. This one is not touched, even if a terminal runs it.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(text, { forkText = it }, placeholder = { Text("First message") }, maxLines = 6, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { forkText = null; fork(text.trim()) }, enabled = text.isNotBlank() && !sending) { Text("Fork") } },
            dismissButton = { TextButton(onClick = { forkText = null }) { Text("Cancel") } },
        )
    }

    if (sheet && (t.pendingPermission != null || t.pendingQuestion != null)) {
        val permission = t.pendingPermission
        val question = if (permission == null) t.pendingQuestion else null
        ApprovalSheet(
            permission = permission,
            question = question,
            onDismiss = { sheet = false },
            // The id of the request on screen: the server refuses the decision if that one has ended.
            onDecide = { decision -> act { hub.respondPermission(key, decision, toolUseId = permission?.toolUseId); sheet = false } },
            onAnswer = { answers -> act { hub.answer(key, answers, toolUseId = question?.toolUseId); sheet = false } },
            sending = sending,
        )
    }
}

@Composable
private fun LinkDot(link: Link) = Dot(
    when (link) {
        Link.Live -> tokens.success
        Link.Connecting, is Link.Retrying -> MaterialTheme.colorScheme.outline
        Link.Stopped -> MaterialTheme.colorScheme.outlineVariant
        Link.AuthFailed, Link.CertificateChanged -> MaterialTheme.colorScheme.error
    },
)

private fun linkText(link: Link): String? = when (link) {
    Link.Live -> null
    Link.Connecting -> "connecting"
    is Link.Retrying -> "reconnecting"
    Link.Stopped -> "paused"
    Link.AuthFailed -> "token refused"
    Link.CertificateChanged -> "certificate changed"
}

@Composable
private fun LinkBanner(link: Link, onEditServer: () -> Unit) {
    val m = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
    when (link) {
        Link.AuthFailed -> Banner("The server refused the token. Pair again.", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, m) {
            TextButton(onClick = onEditServer) { Text("Re-pair") }
        }
        Link.CertificateChanged -> Banner("The server presented a different certificate. Nothing was sent.", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, m) {
            TextButton(onClick = onEditServer) { Text("Review") }
        }
        is Link.Retrying -> {
            var left by remember(link) { mutableLongStateOf(link.inMs / 1000) }
            LaunchedEffect(link) { while (left > 0) { delay(1000); left-- } }
            Text(
                "Connection lost${link.reason?.let { ": " + Format.oneLine(it, 60) } ?: ""}. Retrying" + (if (left > 0) " in ${left}s" else "…"),
                m.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
            )
        }
        else -> Unit
    }
}

/** While the agent works: what it does now, a live ticker (time, tokens) and the todo progress. */
@Composable
private fun StatusStrip(t: Transcript) {
    if (!t.busy) return
    var todosOpen by rememberSaveable { mutableStateOf(false) }
    // running_stats arrives every 10 s; between them the clock runs locally.
    var tick by remember { mutableLongStateOf(0L) }
    val statsAt = remember(t.stats) { System.currentTimeMillis() }
    LaunchedEffect(t.stats) { while (true) { tick = System.currentTimeMillis(); delay(1000) } }
    val running = t.items.lastOrNull { it is TranscriptItem.Tool && it.running } as? TranscriptItem.Tool
    val doing = when {
        t.pendingPermission != null -> "Waiting for approval"
        t.pendingQuestion != null -> "Waiting for your answer"
        running != null -> "Running ${running.name}"
        t.phase == Transcript.Phase.Thinking -> "Thinking"
        t.phase == Transcript.Phase.Responding -> "Writing"
        else -> "Working"
    }
    val progress = t.progress?.takeIf { it.total > 0 }
    Column(Modifier.fillMaxWidth()) {
        if (progress != null) LinearProgressIndicator({ progress.completed.toFloat() / progress.total }, Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(
            Modifier.fillMaxWidth().clickable(enabled = t.todos.isNotEmpty()) { todosOpen = !todosOpen }.padding(horizontal = tokens.screenMargin, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("$doing…", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            t.stats?.let { s ->
                val elapsed = s.durationMs + (tick - statsAt).coerceAtLeast(0)
                Text("${Format.duration(elapsed)} · ${Format.tokens(s.inputTokens)}↑ ${Format.tokens(s.outputTokens)}↓",
                    style = MaterialTheme.typography.labelMedium, fontFamily = tokens.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            progress?.let { Text("${it.completed}/${it.total}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        progress?.current?.takeIf { !todosOpen }?.let {
            Text(it, Modifier.padding(horizontal = tokens.screenMargin).padding(bottom = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AnimatedVisibility(todosOpen && t.todos.isNotEmpty()) {
            Box(Modifier.padding(horizontal = tokens.screenMargin).padding(bottom = 8.dp)) { TodoList(t.todos) }
        }
    }
}
