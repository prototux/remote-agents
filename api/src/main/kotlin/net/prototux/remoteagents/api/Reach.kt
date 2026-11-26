package net.prototux.remoteagents.api

/**
 * How the app can reach a session, from agentd's `liveness`, `drive` and `canApprove` fields. even-terminal
 * sends none of them, so every field is null there and the app behaves as before, except that a prompt to an
 * existing session is confirmed first ([resumeWarning]): even-terminal cannot see terminal sessions and
 * resumes them in a second process, which forks the conversation (seen with session bd72f82f).
 */
data class Reach(
    val liveness: String? = null, val drive: String? = null, val canApprove: Boolean? = null,
    /**
     * agentd's "always allow Bash" policy for this session: the effective value, and, only when this
     * session has its own override, that override (null otherwise). Null on even-terminal and on agentd
     * without the policy API ([ServerInfo.policy] says which).
     */
    val bashAutoAllow: Boolean? = null,
    val bashAutoAllowSession: Boolean? = null,
) {
    /** Only agentd reports liveness, and only agentd accepts `fork`. */
    val isAgentd: Boolean get() = liveness != null

    val inTerminal: Boolean get() = liveness == Liveness.TERMINAL

    /** Live in a terminal that agentd cannot type into: the app watches it (and can fork it), nothing more. */
    val watchOnly: Boolean get() = inTerminal && (drive == null || drive == Drive.NONE)

    val canFork: Boolean get() = isAgentd

    /** Short state for the sessions list: "in terminal · tmux", "hosted", "ended"; null when not known. */
    val label: String?
        get() = when (liveness) {
            Liveness.TERMINAL -> "in terminal · " + driveLabel(drive)
            Liveness.HOSTED -> "hosted"
            Liveness.ENDED -> "ended"
            else -> null
        }

    /** The session screen's banner for a terminal session; null otherwise. */
    val banner: String?
        get() = if (!inTerminal) null else "Running in a terminal: " + when (drive) {
            Drive.TMUX -> "messages are typed into it via tmux"
            Drive.INBOX -> "messages arrive in it as a message from another Claude session"
            Drive.STOPHOOK -> "messages are delivered when the turn ends"
            else -> "watch only"
        }

    /**
     * Why the first prompt from this app needs a go-ahead, or null when it does not: sending resumes the
     * session in a new process on the workstation. Hosted and terminal sessions keep their process.
     */
    val resumeWarning: String?
        get() = when (liveness) {
            null -> "The server can't tell whether this session is still open in a terminal. Sending resumes it " +
                "in a new process on the workstation. If a terminal still runs it, the conversation forks: both " +
                "processes continue separately from here."
            Liveness.UNKNOWN -> "agentd can't tell whether this session is still running. Sending resumes it in " +
                "a new process on the workstation. If a terminal still runs it, the conversation forks."
            Liveness.ENDED -> "No process runs this session. Sending resumes it in a new process on the workstation."
            else -> null
        }

    companion object {
        fun of(s: SessionSummary) = Reach(s.liveness, s.drive, s.canApprove, s.bashAutoAllow, s.bashAutoAllowSession)
        fun of(s: StatusResponse) = Reach(s.liveness, s.drive, s.canApprove, s.bashAutoAllow, s.bashAutoAllowSession)

        fun driveLabel(drive: String?): String = when (drive) {
            Drive.HOSTED -> "hosted"
            Drive.TMUX -> "tmux"
            Drive.INBOX -> "inbox"
            Drive.STOPHOOK -> "after the turn"
            else -> "watch only"
        }
    }
}
