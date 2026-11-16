// spotify as one of mochy's song sources: melo's native engine finds the song on spotify and plays its own encrypted stream

package com.mochyy.mochymusic.spotify

import android.content.Context
import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.CatalogTrack
import com.mochyy.mochymusic.module.DiagnosticStep
import com.mochyy.mochymusic.module.MatchedTrack
import com.mochyy.mochymusic.module.ModuleArt
import com.mochyy.mochymusic.module.ModuleChoice
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.ModuleInfo
import com.mochyy.mochymusic.module.ResolveResult
import com.mochyy.mochymusic.module.SignInSpec
import com.mochyy.mochymusic.module.SourceModule
import com.mochyy.mochymusic.module.SourceStream
import com.mochyy.mochymusic.module.StreamFormat
import com.mochyy.mochymusic.module.StreamOption
import com.mochyy.mochymusic.module.TrackMatcher
import com.mochyy.mochymusic.module.TrackQuery
import com.mochyy.mochymusic.spotify.engine.AudioQuality
import com.mochyy.mochymusic.spotify.engine.MusicKind
import com.mochyy.mochymusic.spotify.engine.NetworkMonitor
import com.mochyy.mochymusic.spotify.engine.SessionVault
import com.mochyy.mochymusic.spotify.engine.SpotifyAccount
import com.mochyy.mochymusic.spotify.engine.SpotifyFailure
import com.mochyy.mochymusic.spotify.engine.SpotifySearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class SpotifyModule(context: Context) : SourceModule, ModuleArt {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var host: ModuleHost

    // a song mochy asked for once is not searched for again while the app runs
    private val matches = ConcurrentHashMap<String, MatchedTrack>()

    @Volatile
    private var hasCredential = false

    override val info = ModuleInfo(
        id = Id,
        name = "Spotify",
        version = "1.0.1",
        description = "Plays songs from your Spotify account through Spotify's own audio engine, ported from Melo. " +
            "Needs a Spotify account, and an engine supplied when Mochy is built (ARM64 only).",
        sourceUrl = "https://github.com/aarush-io/melo",
        author = "Melo",
    )

    override val icon: String = "https://upload.wikimedia.org/wikipedia/commons/thumb/1/19/Spotify_logo_without_text.svg/240px-Spotify_logo_without_text.svg.png"

    // mochy's page needs a sign-in to show the account row; the sheet swaps in spotify's own sign-in (SpotifySignInSheet)
    override val signIn = SignInSpec(
        startUrl = "https://accounts.spotify.com/login",
        origins = listOf("https://accounts.spotify.com", "https://accounts.google.com"),
        sessionOrigins = setOf("https://accounts.spotify.com"),
        desktopSite = false,
        title = "Sign in to Spotify",
        subtitle = "Your account stays on this phone, kept in the Android keystore.",
    )

    override val choices = listOf(
        ModuleChoice(
            id = QualityChoice,
            label = "Quality",
            options = listOf(
                StreamOption(AudioQuality.AUTO.key, "Auto", "256 kbps on Wi-Fi, 128 kbps on mobile data"),
                StreamOption(AudioQuality.VERY_HIGH.key, "Very high", "256 kbps AAC, Premium accounts"),
                StreamOption(AudioQuality.NORMAL.key, "Normal", "128 kbps AAC"),
            ),
        ),
    )

    override fun attach(host: ModuleHost) {
        this.host = host
        NetworkMonitor.start(app)
        hasCredential = runCatching { SessionVault(app).load() != null }.getOrDefault(false)
        SpotifyAuth.onAccountChanged { hasCredential = SpotifyAccount.current() != null }
        if (hasCredential) scope.launch {
            runCatching { SpotifyAccount.restore(app) }.getOrNull()?.let { session ->
                SpotifyAuth.accountLabel = runCatching { session.username() }.getOrNull()
            }
        }
    }

    override fun accountState(): AccountState = when {
        !SpotifyEngine.available -> AccountState.Expired("this build of Mochy has no Spotify engine")
        hasCredential || SpotifyAccount.signedIn -> AccountState.SignedIn(SpotifyAuth.accountLabel ?: "Spotify account")
        else -> AccountState.SignedOut
    }

    override suspend fun verifyAccount(): AccountState {
        if (accountState() !is AccountState.SignedIn) return accountState()
        return try {
            val session = SpotifyAccount.require(app)
            session.headers("spclient.wg.spotify.com")
            SpotifyAuth.accountLabel = runCatching { session.username() }.getOrNull()
            AccountState.SignedIn(SpotifyAuth.accountLabel ?: "Spotify account")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SpotifyFailure) {
            if (failure.status == 401) AccountState.Expired(failure.message ?: "signed out") else accountState()
        } catch (_: Exception) {
            accountState()
        }
    }

    override fun signOut() = SpotifyAuth.signOut(app)

    override suspend fun resolve(track: TrackQuery, picks: Map<String, String>): ResolveResult = diagnose(track, picks) {}

    override suspend fun diagnose(track: TrackQuery, picks: Map<String, String>, report: (DiagnosticStep) -> Unit): ResolveResult {
        if (!SpotifyEngine.available) return ResolveResult.Unavailable("this build has no Spotify engine")
        if (!NetworkMonitor.online.value) return ResolveResult.Unavailable("offline")
        SpotifySettings.setQuality(app, AudioQuality.of(picks[QualityChoice] ?: AudioQuality.AUTO.key))

        report(DiagnosticStep("Account", null, "opening the session"))
        val session = try {
            SpotifyAccount.restore(app) ?: return ResolveResult.Unavailable("signed out").also {
                report(DiagnosticStep("Account", false, "not signed in"))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report(DiagnosticStep("Account", false, failure.message ?: "couldn't open the session"))
            return ResolveResult.Unavailable(failure.message ?: "couldn't open the session")
        }
        report(DiagnosticStep("Account", true, SpotifyAuth.accountLabel ?: "signed in"))

        val key = "${track.title}|${track.artists.joinToString(",")}|${track.durationMs}"
        val matched = matches[key] ?: run {
            report(DiagnosticStep("Search", null, track.title))
            val term = "${TrackMatcher.core(track.title)} ${track.artists.firstOrNull().orEmpty()}".trim()
            val found = try {
                SpotifySearch(SpotifyAccount.transport, session).search(term, Locale.getDefault(), null).items
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report(DiagnosticStep("Search", false, failure.message ?: "search failed"))
                return ResolveResult.Unavailable(failure.message ?: "search failed")
            }
            val songs = found.filter { it.kind == MusicKind.Track }.map {
                CatalogTrack(id = it.id, title = it.title, artist = it.subtitle, durationMs = it.durationMs.takeIf { d -> d > 0 }?.toLong())
            }
            val best = TrackMatcher.best(track, songs) ?: run {
                report(DiagnosticStep("Search", false, "no match among ${songs.size} songs"))
                return ResolveResult.NotFound("Spotify doesn't have it")
            }
            MatchedTrack(best.track.id, best.track.title, best.track.artist, best.track.album, best.track.durationMs, best.score)
                .also { matches[key] = it }
        }
        report(DiagnosticStep("Search", true, "${matched.title} · ${matched.artist}"))

        val quality = SpotifySettings.quality(app)
        return ResolveResult.Found(
            SourceStream(
                // the player hands spotify: addresses to the engine's own hls path (SpotifyMediaSource)
                url = matched.id,
                format = StreamFormat.Hls,
                sourceLabel = "Spotify",
                qualityLabel = when (quality) {
                    AudioQuality.NORMAL -> "128 kbps"
                    AudioQuality.VERY_HIGH -> "256 kbps"
                    AudioQuality.AUTO -> "Auto"
                },
                matched = matched,
                codec = "AAC",
            ),
        )
    }

    companion object {
        const val Id = "spotify"
        private const val QualityChoice = "quality"
    }
}
