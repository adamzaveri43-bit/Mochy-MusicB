// the pieces of melo's player that mochy's player borrows for a spotify song: the engine's codec hook, its hls source and its extractor

package com.mochyy.mochymusic.spotify

import android.content.Context
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.source.MediaSource
import com.mochyy.mochymusic.BuildConfig
import com.mochyy.mochymusic.spotify.engine.SpotifyHlsDataSource
import com.mochyy.mochymusic.spotify.playback.AudioCache
import com.mochyy.mochymusic.spotify.playback.NativeAudioProvider
import com.mochyy.mochymusic.spotify.playback.NativeHlsExtractor
import com.mochyy.mochymusic.spotify.playback.RecentAudioDataSourceFactory

@OptIn(UnstableApi::class)
object SpotifyEngine {
    /** whether this build carries the engine: it's extracted from a Spotify APKM at build time and is ARM64 only */
    val available: Boolean get() = BuildConfig.SPOTIFY_NATIVE_RUNTIME && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    @Volatile private var audio: NativeAudioProvider? = null
    @Volatile private var http: SpotifyHlsDataSource.Factory? = null

    /** what the player service calls once, when it builds its player */
    @Synchronized
    fun start(context: Context) {
        if (!available || audio != null) return
        val app = context.applicationContext
        audio = NativeAudioProvider(app)
        http = SpotifyHlsDataSource.Factory(app, nativeAudio = true)
    }

    @Synchronized
    fun stop() {
        runCatching { audio?.close() }
        runCatching { http?.close() }
        audio = null
        http = null
    }

    /** mochy's codec factory, with the engine processing spotify's samples before they're decoded */
    fun codecFactory(fallback: MediaCodecAdapter.Factory): MediaCodecAdapter.Factory =
        audio?.codecFactory(fallback) ?: fallback

    /** a source for a spotify: address, through the engine's hls path; null when this build has no engine */
    fun mediaSourceFactory(context: Context): MediaSource.Factory? {
        val audio = audio ?: return null
        val http = http ?: return null
        val app = context.applicationContext
        // playlists have no length and stay out of the cache: the data source keeps those itself
        val flags = CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or CacheDataSource.FLAG_IGNORE_CACHE_FOR_UNSET_LENGTH_REQUESTS
        val writer = CacheDataSource.Factory().setCache(AudioCache.cache(app)).setCacheKeyFactory(AudioCache.keys)
            .setUpstreamDataSourceFactory(http).setFlags(flags)
        val reader = CacheDataSource.Factory().setCache(AudioCache.cache(app)).setCacheKeyFactory(AudioCache.keys)
            .setUpstreamDataSourceFactory(http).setCacheWriteDataSinkFactory(null).setFlags(flags)
        return HlsMediaSource.Factory(RecentAudioDataSourceFactory(app, writer, reader))
            .setExtractorFactory(NativeHlsExtractor(audio))
    }

    fun isSpotifyUri(url: String): Boolean = url.startsWith("spotify:")
}
