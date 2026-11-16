// spotify's own sign-in in a module sheet: a code by email, a password, or google for spotify's client, all through melo's login5

package com.mochyy.mochymusic.ui.screens.settings.modules

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.spotify.SpotifyAuth
import com.mochyy.mochymusic.spotify.SpotifyModule
import com.mochyy.mochymusic.spotify.googleRedirect
import com.mochyy.mochymusic.spotify.GoogleRedirect
import com.mochyy.mochymusic.spotify.spotifyGoogleUrl
import com.mochyy.mochymusic.ui.component.PopupSheetHandle
import com.mochyy.mochymusic.ui.component.PopupSheetState
import com.mochyy.mochymusic.ui.component.SheetPillButton
import com.mochyy.mochymusic.ui.component.SheetTextField
import com.mochyy.mochymusic.ui.screens.search.SearchColors

private enum class SpotifyWay { Email, Password, Google }

/** opens spotify's sign-in; [onDone] hears how it went, the sheet closes itself once the account is in */
fun PopupSheetState.showSpotifySignInSheet(module: SpotifyModule, onDone: (AccountState) -> Unit) {
    var sheet: PopupSheetHandle? = null
    sheet = showWithHandle {
        SpotifySignInContent(
            onDone = {
                sheet?.dismiss()
                onDone(module.accountState())
            },
            onClose = {
                SpotifyAuth.cancel()
                sheet?.dismiss()
            },
        )
    }
}

@Composable
private fun SpotifySignInContent(onDone: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val state by SpotifyAuth.state.collectAsState()
    var way by rememberSaveable { mutableStateOf(SpotifyWay.Email) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { SpotifyAuth.reset() }
    LaunchedEffect(state.done) { if (state.done) onDone() }

    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text("Sign in to Spotify", color = SearchColors.Primary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Songs Spotify has play from your account through its own engine. Your sign-in stays on this phone, in the Android keystore.",
            color = SearchColors.Secondary, fontSize = 14.sp, lineHeight = 20.sp,
        )
        Spacer(Modifier.height(22.dp))

        when {
            // spotify sent a code and waits for it
            state.codeLength > 0 -> {
                Text(
                    state.codeEmail?.let { "Enter the ${state.codeLength}-digit code Spotify sent to $it." }
                        ?: "Enter the ${state.codeLength}-digit code Spotify sent you.",
                    color = SearchColors.Secondary, fontSize = 14.sp, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(12.dp))
                SheetTextField(
                    value = code,
                    onValueChange = { value -> code = value.filter { it in '0'..'9' }.take(state.codeLength) },
                    placeholder = "Code",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { SpotifyAuth.submitCode(code) }),
                )
                Spacer(Modifier.height(14.dp))
                SheetPillButton(
                    text = "Continue",
                    onClick = {
                        keyboard?.hide()
                        SpotifyAuth.submitCode(code)
                        code = ""
                    },
                    enabled = code.length == state.codeLength,
                )
            }

            way == SpotifyWay.Google && !state.working -> GoogleForSpotify(
                onSigned = { authCode, idToken -> SpotifyAuth.withGoogle(context, authCode, idToken) },
                onClose = { way = SpotifyWay.Email },
            )

            else -> {
                SheetTextField(
                    value = username,
                    onValueChange = { username = it },
                    placeholder = if (way == SpotifyWay.Password) "Email or username" else "Email",
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Email,
                        imeAction = if (way == SpotifyWay.Password) ImeAction.Next else ImeAction.Done,
                    ),
                )
                if (way == SpotifyWay.Password) {
                    Spacer(Modifier.height(10.dp))
                    SheetTextField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = "Password",
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    )
                }
                Spacer(Modifier.height(14.dp))
                SheetPillButton(
                    text = when {
                        state.working -> "Connecting…"
                        way == SpotifyWay.Password -> "Sign in"
                        else -> "Send me a code"
                    },
                    onClick = {
                        keyboard?.hide()
                        if (way == SpotifyWay.Password) SpotifyAuth.withPassword(context, username, password)
                        else SpotifyAuth.withEmail(context, username)
                    },
                    enabled = !state.working && username.isNotBlank() && (way != SpotifyWay.Password || password.isNotEmpty()),
                )
                if (!state.working) {
                    Spacer(Modifier.height(16.dp))
                    SwitchLink(if (way == SpotifyWay.Password) "Use a code instead" else "Use a password instead") {
                        way = if (way == SpotifyWay.Password) SpotifyWay.Email else SpotifyWay.Password
                    }
                    SwitchLink("Continue with Google") { way = SpotifyWay.Google }
                } else {
                    Spacer(Modifier.height(16.dp))
                    SwitchLink("Cancel") { onClose() }
                }
            }
        }

        state.error?.let {
            Text(it, color = ProblemColor, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 12.dp, start = 4.dp))
        }
    }
}

@Composable
private fun SwitchLink(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = SearchColors.Primary,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
    )
}

/**
 * Google's sign-in for Spotify's own Google client. The redirect back to Spotify is stopped
 * before it loads: its code and ID token go to Login5, as the official app sends them.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GoogleForSpotify(onSigned: (code: String?, idToken: String?) -> Unit, onClose: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var done by remember { mutableStateOf(false) }
    val requestState = remember { java.util.UUID.randomUUID().toString() }
    val nonce = remember { java.util.UUID.randomUUID().toString() }
    val pageHeight = (LocalConfiguration.current.screenHeightDp * 0.66f).dp

    fun finish(result: GoogleRedirect) {
        if (done) return
        done = true
        // nothing of this sign-in stays in the web view
        CookieManager.getInstance().apply { removeAllCookies(null); flush() }
        when (result) {
            is GoogleRedirect.Signed -> if (result.state == requestState) onSigned(result.code, result.idToken) else onClose()
            is GoogleRedirect.Refused -> onClose()
        }
    }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onClose()
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(pageHeight),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // google refuses its sign-in to pages that say they're an embedded web view
                settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex("""Version/\S+\s"""), "")
                CookieManager.getInstance().setAcceptCookie(true)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val redirect = googleRedirect(request.url.toString()) ?: return false
                        finish(redirect)
                        return true
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        googleRedirect(url)?.let { view.stopLoading(); finish(it) }
                    }
                }
                CookieManager.getInstance().removeAllCookies { loadUrl(spotifyGoogleUrl(requestState, nonce)) }
                webView = this
            }
        },
        onRelease = { it.stopLoading(); it.destroy() },
    )
    Spacer(Modifier.height(12.dp))
    SwitchLink("Back") { onClose() }
}
