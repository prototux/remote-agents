package net.prototux.remoteagents.api

import kotlinx.serialization.Serializable

/**
 * agentd's "always allow Bash" policy (owner decision 2026-11-25): a shell-execution permission request
 * can be approved at once, without reaching the phone. [global] is the base switch; [providers] overrides
 * it per provider name, a provider missing from the map inheriting [global]. Absent on even-terminal and
 * older agentd; [ServerInfo.policy] says whether `/api/policy` exists at all.
 */
@Serializable
data class BashAutoAllow(val global: Boolean = false, val providers: Map<String, Boolean> = emptyMap())

/** `GET /api/policy`'s body, and `PUT`'s reply after the change. */
@Serializable
data class PolicyInfo(
    val bashAutoAllow: BashAutoAllow = BashAutoAllow(),
    /** Commands matching one of these still reach the phone, whatever the policy above says (agentd's safety net). */
    val stillAsk: List<String> = emptyList(),
)

/** A session's policy state, from `POST /api/sessions/{id}/policy`'s reply. */
@Serializable
data class SessionPolicyState(
    val effective: Boolean = false,
    /** This session's own override, or null when it has none and follows the provider/global policy. */
    val session: Boolean? = null,
)

@Serializable
internal data class SessionPolicyResponse(val bashAutoAllow: SessionPolicyState = SessionPolicyState())
