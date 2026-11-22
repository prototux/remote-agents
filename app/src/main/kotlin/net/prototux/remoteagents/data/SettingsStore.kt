package net.prototux.remoteagents.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeId { Material, Nothing }
enum class DarkMode { System, Light, Dark }
enum class VoiceMode { Off, System, Server }

data class AppSettings(
    val theme: ThemeId = ThemeId.Material,
    val darkMode: DarkMode = DarkMode.System,
    val dynamicColor: Boolean = true,
    val notifyApproval: Boolean = true,
    val notifyQuestion: Boolean = true,
    val notifyFinished: Boolean = true,
    val notifyError: Boolean = true,
    /** Notification actions (allow/deny/reply) ask for unlock first: approving a tool is code execution. */
    val actionsNeedUnlock: Boolean = true,
    val voice: VoiceMode = VoiceMode.Off,
    /** Base URL of an OpenAI-compatible STT server (`.../v1/audio/transcriptions` is appended unless present). */
    val sttUrl: String = "",
    val sttModel: String = "whisper-1",
    val sttLanguage: String = "",
    val showUnsupported: Boolean = true,
    val keepScreenOn: Boolean = false,
    val expandTools: Boolean = false,
)

/** Settings in SharedPreferences (not backed up: allowBackup is off). The STT key is encrypted like tokens. */
class SettingsStore(context: Context, private val cipher: TokenCipher) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(change: (AppSettings) -> AppSettings) {
        val s = change(_settings.value)
        prefs.edit().apply {
            putString("theme", s.theme.name); putString("darkMode", s.darkMode.name); putBoolean("dynamicColor", s.dynamicColor)
            putBoolean("notifyApproval", s.notifyApproval); putBoolean("notifyQuestion", s.notifyQuestion)
            putBoolean("notifyFinished", s.notifyFinished); putBoolean("notifyError", s.notifyError)
            putBoolean("actionsNeedUnlock", s.actionsNeedUnlock)
            putString("voice", s.voice.name); putString("sttUrl", s.sttUrl); putString("sttModel", s.sttModel); putString("sttLanguage", s.sttLanguage)
            putBoolean("showUnsupported", s.showUnsupported); putBoolean("keepScreenOn", s.keepScreenOn); putBoolean("expandTools", s.expandTools)
        }.apply()
        _settings.value = s
    }

    var sttKey: String?
        get() = prefs.getString("sttKeyEnc", null)?.let(cipher::decrypt)
        set(value) { prefs.edit().apply { if (value.isNullOrBlank()) remove("sttKeyEnc") else putString("sttKeyEnc", cipher.encrypt(value)) }.apply() }

    /** Recent prompts across sessions, newest first, for the prompt box history. */
    fun recentPrompts(): List<String> = prefs.getString("recentPrompts", null)
        ?.let { runCatching { net.prototux.remoteagents.api.ApiJson.decodeFromString<List<String>>(it) }.getOrNull() }.orEmpty()

    fun rememberPrompt(text: String) {
        if (text.isBlank() || text.length > 2000) return
        val list = (listOf(text) + recentPrompts().filterNot { it == text }).take(30)
        prefs.edit().putString("recentPrompts", net.prototux.remoteagents.api.ApiJson.encodeToString(list)).apply()
    }

    private fun read(): AppSettings {
        val d = AppSettings()
        fun <E : Enum<E>> enum(key: String, def: E, values: Array<E>) = prefs.getString(key, null)?.let { n -> values.firstOrNull { it.name == n } } ?: def
        return AppSettings(
            theme = enum("theme", d.theme, ThemeId.entries.toTypedArray()),
            darkMode = enum("darkMode", d.darkMode, DarkMode.entries.toTypedArray()),
            dynamicColor = prefs.getBoolean("dynamicColor", d.dynamicColor),
            notifyApproval = prefs.getBoolean("notifyApproval", d.notifyApproval),
            notifyQuestion = prefs.getBoolean("notifyQuestion", d.notifyQuestion),
            notifyFinished = prefs.getBoolean("notifyFinished", d.notifyFinished),
            notifyError = prefs.getBoolean("notifyError", d.notifyError),
            actionsNeedUnlock = prefs.getBoolean("actionsNeedUnlock", d.actionsNeedUnlock),
            voice = enum("voice", d.voice, VoiceMode.entries.toTypedArray()),
            sttUrl = prefs.getString("sttUrl", d.sttUrl) ?: d.sttUrl,
            sttModel = prefs.getString("sttModel", d.sttModel) ?: d.sttModel,
            sttLanguage = prefs.getString("sttLanguage", d.sttLanguage) ?: d.sttLanguage,
            showUnsupported = prefs.getBoolean("showUnsupported", d.showUnsupported),
            keepScreenOn = prefs.getBoolean("keepScreenOn", d.keepScreenOn),
            expandTools = prefs.getBoolean("expandTools", d.expandTools),
        )
    }
}
