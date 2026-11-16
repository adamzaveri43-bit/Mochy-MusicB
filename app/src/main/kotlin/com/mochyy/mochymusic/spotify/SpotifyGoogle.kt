// google's sign-in for spotify's own google client, as melo does it: the address to open and what the redirect carries back

package com.mochyy.mochymusic.spotify

import android.net.Uri

/** Where Google sends the result back for Spotify's client. Login5 needs this exact address with a code. */
const val SPOTIFY_GOOGLE_REDIRECT = "https://accounts.spotify.com/login/google/redirect"
/**
 * Spotify's Google client, the one both `accounts.spotify.com/login/google` and the 9.2 app
 * (`serverClientId` in `classes4.dex`) use.
 */
private const val SPOTIFY_GOOGLE_CLIENT = "1046568431490-ij1gi5shcp2gtorls09frkc56d4mjbe2.apps.googleusercontent.com"

/**
 * Google's authorization page for Spotify's client, asking for a code and an ID token at once:
 * the 9.2 app signs in with either.
 */
fun spotifyGoogleUrl(state: String, nonce: String): String = Uri.parse("https://accounts.google.com/o/oauth2/v2/auth").buildUpon()
    .appendQueryParameter("response_type", "code id_token")
    .appendQueryParameter("client_id", SPOTIFY_GOOGLE_CLIENT)
    .appendQueryParameter("scope", "openid email profile")
    .appendQueryParameter("redirect_uri", SPOTIFY_GOOGLE_REDIRECT)
    .appendQueryParameter("state", state)
    .appendQueryParameter("nonce", nonce)
    .appendQueryParameter("prompt", "select_account")
    .build().toString()

/**
 * The code Google returns to Spotify's sign-in page, or the error it reports instead. Null for any
 * other address.
 */
sealed interface GoogleRedirect {
    data class Signed(val code: String?, val idToken: String?, val state: String?) : GoogleRedirect
    data class Refused(val error: String) : GoogleRedirect
}

/** A hybrid response comes in the fragment, a code-only one in the query: both are read. */
fun googleRedirect(url: String?): GoogleRedirect? {
    val uri = url?.let(Uri::parse) ?: return null
    if (uri.scheme != "https" || uri.host != "accounts.spotify.com" || uri.path?.trimEnd('/') != "/login/google/redirect") return null
    val fragment = uri.encodedFragment?.let { Uri.parse("https://x/?$it") }
    fun value(name: String) = (fragment?.getQueryParameter(name) ?: uri.getQueryParameter(name))?.takeIf(String::isNotBlank)
    val code = value("code")
    val idToken = value("id_token")
    if (code != null || idToken != null) return GoogleRedirect.Signed(code, idToken, value("state"))
    return GoogleRedirect.Refused(value("error") ?: "no_code")
}
