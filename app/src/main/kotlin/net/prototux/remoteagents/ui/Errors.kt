package net.prototux.remoteagents.ui

import net.prototux.remoteagents.api.ApiException
import net.prototux.remoteagents.api.AuthFailedException
import net.prototux.remoteagents.api.PublicAddressRefused
import net.prototux.remoteagents.api.findPinMismatch
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** A failure in words the owner can act on. */
fun Throwable.describe(): String {
    findPinMismatch()?.let { return "The server's certificate changed. Re-pin it in the server settings if you expected this." }
    var t: Throwable? = this
    while (t != null) {
        when (t) {
            is AuthFailedException -> return "The server refused the token. Pair again with the current QR code."
            is PublicAddressRefused -> return t.message ?: "Public address refused"
            is UnknownHostException -> return "Host not found. Is the VPN connected?"
            is ConnectException -> return "Can't reach the server. Is the VPN up and even-terminal running?"
            is SocketTimeoutException -> return "The server did not answer in time."
            is ApiException -> return t.message ?: "Server error ${t.httpStatus}"
        }
        t = t.cause
    }
    return message ?: javaClass.simpleName
}
