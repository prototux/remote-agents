package net.prototux.remoteagents.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.prototux.remoteagents.Container
import net.prototux.remoteagents.api.AddressClass
import net.prototux.remoteagents.api.Endpoint
import net.prototux.remoteagents.api.HostPolicy
import net.prototux.remoteagents.api.PairingInfo
import net.prototux.remoteagents.api.PeerCertificate
import net.prototux.remoteagents.api.PolicyInfo
import net.prototux.remoteagents.api.ProviderInfo
import net.prototux.remoteagents.api.Providers
import net.prototux.remoteagents.api.TlsProbe
import net.prototux.remoteagents.data.ServerConfig
import net.prototux.remoteagents.ui.theme.tokens
import java.text.DateFormat

/** Pair (QR or pasted URL) or edit a server: address, token, TLS pin, public-address override, test. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(container: Container, serverId: String?, onDone: () -> Unit) {
    val existing = remember(serverId) { serverId?.let { container.servers.get(it) } }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var https by rememberSaveable { mutableStateOf(existing?.https ?: false) }
    var host by rememberSaveable { mutableStateOf(existing?.host ?: "") }
    var port by rememberSaveable { mutableStateOf(existing?.port?.toString() ?: "3456") }
    var token by rememberSaveable { mutableStateOf("") }
    var showToken by rememberSaveable { mutableStateOf(false) }
    var provider by rememberSaveable { mutableStateOf(existing?.defaultProvider ?: Providers.CLAUDE) }
    var pins by rememberSaveable { mutableStateOf(existing?.pins ?: emptyList()) }
    var allowPublic by rememberSaveable { mutableStateOf(existing?.allowPublic ?: false) }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var chain by remember { mutableStateOf<List<PeerCertificate>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    // The "always allow Bash" policy (agentd extension): null while unknown, false on even-terminal/older agentd.
    var policyAvailable by remember { mutableStateOf<Boolean?>(null) }
    var policyProviders by remember { mutableStateOf<List<ProviderInfo>>(emptyList()) }
    var policy by remember { mutableStateOf<PolicyInfo?>(null) }
    var policyBusy by remember { mutableStateOf(false) }
    var policyMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    fun apply(p: PairingInfo) {
        https = p.https; host = p.host; port = p.port.toString(); token = p.token
        p.provider?.let { provider = if (it == Providers.CODEX) Providers.CODEX else Providers.CLAUDE }
        if (name.isBlank()) name = p.name ?: p.host
        if (!p.https) pins = emptyList()
        message = true to "Pairing details filled in. Test the connection, then save."
    }

    val scan = androidx.activity.compose.rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        PairingInfo.parse(text)?.let(::apply) ?: run { message = false to "That QR code is not an even-terminal pairing URL." }
    }

    val portNum = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val addressClass = remember(host) { HostPolicy.classifyLiteral(host.trim()) }
    val publicHttp = !https && addressClass == AddressClass.Public
    val tokenToUse = token.ifBlank { existing?.let { container.servers.token(it) }.orEmpty() }
    val problems = buildList {
        if (host.isBlank()) add("Host is required")
        if (portNum == null) add("Port must be 1-65535")
        if (tokenToUse.isBlank()) add("Token is required")
        if (https && pins.isEmpty()) add("Fetch and trust the certificate first")
        if (publicHttp && !allowPublic) add("Public address over plain HTTP is refused")
    }

    fun endpoint() = Endpoint(https, host.trim(), portNum ?: 0, tokenToUse, pins.toSet(), allowPublic)

    // Read once on opening an existing server: whether it has the policy API, its providers, and its current
    // policy. Unsaved servers (still pairing) have nothing to show a policy for yet.
    LaunchedEffect(existing?.id) {
        if (existing == null) return@LaunchedEffect
        policyBusy = true
        val client = container.clients.forEndpoint(endpoint())
        val info = runCatching { client.info() }.getOrNull()
        policyAvailable = info?.policy == true
        policyProviders = info?.providers.orEmpty()
        if (policyAvailable == true) runCatching { client.policy() }.onSuccess { policy = it }.onFailure { policyMessage = it.describe() }
        policyBusy = false
    }

    // Sent immediately, like a server-side toggle: [providers] takes a provider's new choice, null meaning
    // "server default" (left out of the request, so it inherits [global] again).
    fun pushPolicy(global: Boolean, providers: Map<String, Boolean?>) {
        policyBusy = true; policyMessage = null
        scope.launch {
            val r = runCatching { container.clients.forEndpoint(endpoint()).setPolicy(global, providers) }
            policyBusy = false
            r.fold({ policy = it }, { policyMessage = it.describe() })
        }
    }

    fun providerChoice(name: String, choice: Boolean?): Map<String, Boolean?> =
        policyProviders.associate { it.name to if (it.name == name) choice else policy?.bashAutoAllow?.providers?.get(it.name) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Pair server" else "Edit server") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { if (existing != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete server") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = tokens.screenMargin),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the even-terminal QR code").setBeepEnabled(false).setOrientationLocked(false))
                }) { Icon(AppIcons.QrCode, null); Spacer(Modifier.size(8.dp)); Text("Scan QR") }
                OutlinedButton(onClick = {
                    val text = clipboard.getText()?.text.orEmpty()
                    PairingInfo.parse(text)?.let(::apply) ?: run { message = false to "The clipboard has no pairing URL (http://host:port?token=…)." }
                }) { Icon(AppIcons.Paste, null); Spacer(Modifier.size(8.dp)); Text("Paste URL") }
            }
            Text("The pairing URL is shown by even-terminal at startup. The token is stored encrypted on this phone and only ever sent to this server, in a header.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(!https, { https = false; pins = emptyList(); chain = null }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("HTTP over VPN") }
                SegmentedButton(https, { https = true; if (port == "3456") port = "443" }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("HTTPS proxy") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it.trim(); pins = if (https) pins else emptyList() }, label = { Text("Host") }, singleLine = true, modifier = Modifier.weight(2f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            AddressNote(addressClass, host, https)
            OutlinedTextField(
                token, { token = it.trim() },
                label = { Text(if (existing != null) "Token (leave empty to keep)" else "Token") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            )
            Text("Default agent", style = MaterialTheme.typography.labelMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                providerOptions().forEachIndexed { i, p ->
                    SegmentedButton(provider == p, { provider = p }, SegmentedButtonDefaults.itemShape(i, 2)) { Text(Providers.label(p)) }
                }
            }

            if (policyAvailable == true) PolicySection(policy, policyProviders, policyBusy, policyMessage, onGlobal = { on ->
                pushPolicy(on, policy?.bashAutoAllow?.providers.orEmpty())
            }, onProvider = { providerName, choice -> pushPolicy(policy?.bashAutoAllow?.global ?: false, providerChoice(providerName, choice)) })

            if (https) {
                SectionTitle("Certificate")
                if (pins.isNotEmpty()) {
                    Text("Trusted key", style = MaterialTheme.typography.labelMedium)
                    pins.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = tokens.mono) }
                }
                FilledTonalButton(enabled = host.isNotBlank() && portNum != null && !busy, onClick = {
                    busy = true; message = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { TlsProbe.fetchChain(host.trim(), portNum!!) } }
                        busy = false
                        r.fold({ chain = it }, { message = false to "Could not read the certificate: ${it.describe()}" })
                    }
                }) { Text(if (pins.isEmpty()) "Fetch certificate" else "Fetch again") }
                Text("Pinning trusts this exact key, whoever signed it, so a self-signed proxy works. With Caddy and a public CA, set reuse_private_keys so renewals keep the key.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (publicHttp) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Public address over plain HTTP", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        Text("Anyone on the path could read the token and take over your agent (that is remote code execution on your workstation). Use the VPN address, or an HTTPS proxy with a pinned key.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Allow anyway", Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                            Switch(allowPublic, { allowPublic = it })
                        }
                    }
                }
            }

            message?.let { (ok, text) -> Text(text, color = if (ok) tokens.success else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = problems.isEmpty() && !busy, onClick = {
                    busy = true; message = null
                    scope.launch {
                        val client = container.clients.forEndpoint(endpoint())
                        val r = runCatching { client.info() }
                        busy = false
                        message = r.fold(
                            { info ->
                                val exposed = info.extra?.expose?.takeIf { it !in setOf("off", "tailscale", null) }
                                true to listOfNotNull(
                                    "Connected: even-terminal ${info.version ?: "?"}",
                                    info.model?.let { "model $it" },
                                    info.account?.email?.let { "account $it" },
                                    exposed?.let { "WARNING: public tunnel '$it' is on" },
                                ).joinToString(" · ")
                            },
                            { false to it.describe() },
                        )
                    }
                }) { Text("Test connection") }
                Button(enabled = problems.isEmpty() && name.isNotBlank() && !busy, onClick = {
                    val base = existing ?: ServerConfig(name = name.trim(), host = host.trim())
                    container.servers.save(
                        base.copy(name = name.trim(), https = https, host = host.trim(), port = portNum!!, defaultProvider = provider, pins = pins, allowPublic = allowPublic && publicHttp),
                        token = token.ifBlank { null },
                    )
                    onDone()
                }) { Text("Save") }
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            problems.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.size(24.dp))
        }
    }

    chain?.let { certs ->
        val leaf = certs.first()
        AlertDialog(
            onDismissRequest = { chain = null },
            title = { Text(if (pins.isNotEmpty() && leaf.pin !in pins) "Certificate changed" else "Trust this certificate?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Compare the fingerprint with the one on your proxy before trusting it.", style = MaterialTheme.typography.bodySmall)
                    KeyValue("Subject", leaf.subject)
                    KeyValue("Issuer", leaf.issuer)
                    KeyValue("Expires", DateFormat.getDateInstance().format(leaf.notAfter))
                    KeyValue("Key fingerprint (SHA-256)", leaf.pin.removePrefix("sha256/"))
                }
            },
            confirmButton = { TextButton(onClick = { pins = listOf(leaf.pin); chain = null; message = true to "Key pinned." }) { Text("Trust") } },
            dismissButton = { TextButton(onClick = { chain = null }) { Text("Cancel") } },
        )
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.name}?") },
            text = { Text("Its token is removed from this phone. The server and its sessions are not touched.") },
            confirmButton = { TextButton(onClick = { container.servers.delete(existing.id); confirmDelete = false; onDone() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AddressNote(c: AddressClass?, host: String, https: Boolean) {
    if (host.isBlank()) return
    val (text, bad) = when (c) {
        AddressClass.Vpn -> "VPN address (Tailscale or WireGuard ULA)" to false
        AddressClass.Private -> "Private LAN address: fine on your own network or a WireGuard subnet" to false
        AddressClass.Loopback -> "Loopback: only works on this phone" to true
        AddressClass.Public -> (if (https) "Public address over HTTPS with a pinned key" else "Public address") to !https
        null -> "Host name: the address it resolves to is checked on every connection" + (if (https) "" else "; it must be private or VPN") to false
    }
    Text(text, style = MaterialTheme.typography.bodySmall, color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * agentd's "always allow Bash" policy for this server (owner decision 2026-11-25): a global switch, then
 * a "server default / always allow / ask" choice per provider the server reports in `/info`. Shown only
 * when the server has the API (`/info`'s `policy`); hidden on even-terminal and older agentd.
 */
@Composable
private fun PolicySection(
    policy: PolicyInfo?, providers: List<ProviderInfo>, busy: Boolean, message: String?,
    onGlobal: (Boolean) -> Unit, onProvider: (name: String, choice: Boolean?) -> Unit,
) {
    SectionTitle("Shell commands")
    val stillAsk = policy?.stillAsk.orEmpty()
    Text(
        "Agents run shell commands without asking." +
            (if (stillAsk.isNotEmpty()) " Commands like ${stillAsk.joinToString(", ")} still ask." else "") +
            " This is a safety net, not a guarantee.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (policy != null) {
        Row(Modifier.fillMaxWidth().clickable { onGlobal(!policy.bashAutoAllow.global) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Always allow shell commands", Modifier.weight(1f))
            Switch(policy.bashAutoAllow.global, onGlobal)
        }
        providers.forEach { prov ->
            val label = if (prov.name in Providers.KNOWN) Providers.label(prov.name) else prov.label?.ifBlank { null } ?: prov.name
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
            val current = policy.bashAutoAllow.providers[prov.name]
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(null to "Server default", true to "Always allow", false to "Ask").forEachIndexed { i, (choice, text) ->
                    SegmentedButton(current == choice, { onProvider(prov.name, choice) }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(text) }
                }
            }
        }
    }
    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}
