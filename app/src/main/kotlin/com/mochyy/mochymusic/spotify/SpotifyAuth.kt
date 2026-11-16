// signing in to spotify: password, a code sent by email, or google, each ending in a login5 session kept in the keystore

package com.mochyy.mochymusic.spotify

import android.content.Context
import com.google.protobuf.InvalidProtocolBufferException
import com.mochyy.mochymusic.spotify.engine.Login5Error
import com.mochyy.mochymusic.spotify.engine.Login5ProtocolRefused
import com.mochyy.mochymusic.spotify.engine.SessionVault
import com.mochyy.mochymusic.spotify.engine.SpotifyAccount
import com.mochyy.mochymusic.spotify.engine.SpotifyFailure
import com.mochyy.mochymusic.spotify.engine.SpotifySession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

/** what the sign-in sheet shows */
data class SpotifySignInState(
    val working: Boolean = false,
    /** digits of the code spotify sent, while it waits for one */
    val codeLength: Int = 0,
    /** where the code went, when it went by email */
    val codeEmail: String? = null,
    val error: String? = null,
    /** the account is in */
    val done: Boolean = false,
)

object SpotifyAuth {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(SpotifySignInState())
    val state: StateFlow<SpotifySignInState> = _state.asStateFlow()

    private var job: Job? = null
    private var pendingCode: CompletableDeferred<String>? = null

    // one login flow id per sign-in, as the official app sends it with the email code
    private var loginFlowId = UUID.randomUUID().toString()

    /** what the module says it's signed in as, shown on its page */
    @Volatile
    var accountLabel: String? = null
        internal set

    private val signedInListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    fun onAccountChanged(listener: () -> Unit): AutoCloseable {
        signedInListeners.add(listener)
        return AutoCloseable { signedInListeners.remove(listener) }
    }

    fun reset() {
        if (_state.value.working) return
        loginFlowId = UUID.randomUUID().toString()
        _state.value = SpotifySignInState()
    }

    fun withPassword(context: Context, username: String, password: String) {
        if (username.isBlank() || password.isEmpty()) return
        connect(context) { session -> session.connect(username.trim(), password, ::requestCode) }
    }

    fun withEmail(context: Context, address: String) {
        val email = address.trim()
        if (email.isBlank()) return
        val flow = loginFlowId
        connect(context) { session -> session.connectEmail(email, flow) { requestCode(it, email) } }
    }

    /** what google handed back for spotify's own client, read off the redirect before it loads */
    fun withGoogle(context: Context, authCode: String?, idToken: String?) {
        if (authCode.isNullOrBlank() && idToken.isNullOrBlank()) return
        connect(context) { session ->
            session.connectGoogle(authCode?.takeIf(String::isNotBlank), idToken?.takeIf(String::isNotBlank), SPOTIFY_GOOGLE_REDIRECT, ::requestCode)
        }
    }

    fun submitCode(code: String) {
        if (code.length == _state.value.codeLength && code.all { it in '0'..'9' }) pendingCode?.complete(code)
    }

    fun cancel() {
        job?.cancel()
        pendingCode?.cancel()
        pendingCode = null
        _state.value = SpotifySignInState()
    }

    fun signOut(context: Context) {
        cancel()
        SpotifyAccount.clear()
        SessionVault(context.applicationContext).clear()
        accountLabel = null
        signedInListeners.forEach { it() }
    }

    private fun connect(context: Context, authenticate: suspend (SpotifySession) -> Unit) {
        if (_state.value.working) return
        val app = context.applicationContext
        job = scope.launch {
            _state.value = SpotifySignInState(working = true)
            try {
                val session = SpotifyAccount.create(app)
                authenticate(session)
                SpotifyAccount.attach(session)
                accountLabel = runCatching { session.username() }.getOrNull()
                signedInListeners.forEach { it() }
                _state.value = SpotifySignInState(done = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _state.value = SpotifySignInState(error = readable(failure))
            }
        }
    }

    private suspend fun requestCode(length: Int, email: String? = null): String {
        val deferred = CompletableDeferred<String>()
        pendingCode = deferred
        _state.value = SpotifySignInState(working = true, codeLength = length, codeEmail = email)
        return try {
            deferred.await().also { _state.value = SpotifySignInState(working = true) }
        } finally {
            if (pendingCode === deferred) pendingCode = null
        }
    }

    private suspend fun requestCode(length: Int): String = requestCode(length, null)

    private fun readable(failure: Exception): String = when (failure) {
        is Login5Error, is Login5ProtocolRefused, is SpotifyFailure -> failure.message ?: "Spotify refused the sign-in."
        is InvalidProtocolBufferException -> "Spotify's answer couldn't be read."
        is IOException -> "The connection to Spotify was interrupted. Try again."
        else -> "Couldn't open a Spotify session on this phone."
    }
}
