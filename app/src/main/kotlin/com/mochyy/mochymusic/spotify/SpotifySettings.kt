// what the spotify source keeps for itself: the quality to stream at and how much recent audio to keep

package com.mochyy.mochymusic.spotify

import android.content.Context
import androidx.core.content.edit
import com.mochyy.mochymusic.spotify.engine.AudioQuality

object SpotifySettings {
    private const val FILE = "spotify_source"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun quality(context: Context): AudioQuality = AudioQuality.of(prefs(context).getString("quality", null) ?: AudioQuality.AUTO.key)

    fun setQuality(context: Context, quality: AudioQuality) = prefs(context).edit { putString("quality", quality.key) }

    fun audioCacheLimitMb(context: Context): Int = prefs(context).getInt("cache_limit_mb", 512)

    fun cachePlayedTracks(context: Context): Boolean = prefs(context).getBoolean("cache_played", true)
}
