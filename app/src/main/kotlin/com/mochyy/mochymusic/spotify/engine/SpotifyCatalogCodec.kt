// the request and metadata helpers of melo's spotify catalog, the part the player needs

package com.mochyy.mochymusic.spotify.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap


internal fun playlistFallbackApplies(failure: SpotifyFailure): Boolean =
    failure !is SpotifyAuthRequired && failure !is SpotifyRateLimited && failure.status != null &&
        failure.status in 400..499 && failure.status !in setOf(401, 403, 404)

internal fun spotifyUrl(path: String): HttpUrl = "https://spclient.wg.spotify.com/".toHttpUrl()
    .newBuilder().addPathSegments(path).build()

internal suspend fun authenticatedSpotifyRequest(
    transport: SpotifyTransport,
    session: SpotifySession,
    url: HttpUrl,
    operation: String,
    body: ByteArray? = null,
    contentType: String = "application/x-protobuf",
): ByteArray {
    suspend fun send(headers: SpotifySession.Headers): ByteArray {
        val builder = Request.Builder().url(url)
            .header("Authorization", "Bearer ${headers.user}")
            .header("client-token", headers.client)
            .header("Accept", contentType)
        if (body != null) builder.post(body.toRequestBody(contentType.toMediaType()))
        else builder.header("x-accept-list-items", "audio-track")
        return transport.send(builder.build(), operation)
    }
    val headers = session.headers(url.host)
    return try {
        send(headers)
    } catch (failure: SpotifyFailure) {
        if (failure.status != 401) throw failure
        send(session.headers(url.host, rejected = headers))
    }
}

internal fun decodeMetadataExtensions(response: ByteArray): Map<String, ByteArray> = buildMap {
    Proto(response).messages(2).forEach { array ->
        val providerStatus = array.message(1)?.number(1) ?: 0
        if (providerStatus != 0 && providerStatus != 200) return@forEach
        array.messages(3).forEach extension@ { data ->
            val status = data.message(1)?.number(1) ?: 0
            if (status != 0 && status != 200) return@extension
            val uri = data.text(2)?.let(::canonicalMusicUri) ?: return@extension
            val value = data.message(3)?.bytes(2) ?: return@extension
            put(uri, value)
        }
    }
}

/** Provider and entity statuses of an Extended Metadata response, for the diagnostic report. */
internal fun metadataStatusSummary(response: ByteArray): String = Proto(response).messages(2).joinToString(" ") { array ->
    val entities = array.messages(3).groupingBy { it.message(1)?.number(1) ?: 0 }.eachCount()
    "kind=${array.number(2) ?: "?"} provider=${array.message(1)?.number(1) ?: 0} entities=$entities"
}.ifEmpty { "arrays=0" }

/** Artist biographies carry HTML links and entities: plain text with paragraphs kept. */
internal fun cleanBiography(text: String): String {
    if (text.isBlank()) return ""
    val noTags = text.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "")
    val decoded = androidx.core.text.HtmlCompat.fromHtml(noTags.replace("\n", "<br>"), androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
    return decoded.replace(Regex("\n{3,}"), "\n\n").trim()
}

internal fun metadataKind(uri: String): Int? = when {
    uri.startsWith("spotify:artist:") -> 8
    uri.startsWith("spotify:album:") -> 9
    uri.startsWith("spotify:track:") -> 10
    else -> null
}

/** Track.alternative is supplied by the account-aware metadata service, not a title search. */
internal fun playableTrackUri(original: String, metadata: Proto): String {
    if (metadata.messages(12).isNotEmpty()) return original
    val alternatives = metadata.messages(13)
    val available = alternatives.firstOrNull { it.messages(12).isNotEmpty() && gidUri("track", it.bytes(1)) != null }
        ?: alternatives.firstOrNull { gidUri("track", it.bytes(1)) != null }
    return available?.let { gidUri("track", it.bytes(1)) } ?: original
}

internal fun canonicalMusicUri(value: String): String? {
    val pieces = value.split(':')
    val pair = when {
        pieces.size == 3 && pieces[0] == "spotify" -> pieces[1] to pieces[2]
        pieces.size == 5 && pieces[0] == "spotify" && pieces[1] == "user" && pieces[3] == "playlist" ->
            "playlist" to pieces[4]
        else -> return null
    }
    if (pair.first !in setOf("track", "album", "artist", "playlist") ||
        pair.second.length !in 21..22 || pair.second.any { it !in BASE62 }
    ) return null
    return "spotify:${pair.first}:${pair.second}"
}

private const val BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

internal fun gidUri(kind: String, gid: ByteArray?): String? {
    if (gid == null || gid.size != 16) return null
    var value = BigInteger(1, gid)
    val base = BigInteger.valueOf(62)
    val text = StringBuilder()
    while (value.signum() != 0) {
        val pair = value.divideAndRemainder(base)
        text.append(BASE62[pair[1].toInt()])
        value = pair[0]
    }
    return "spotify:$kind:${text.reverse().toString().padStart(22, '0')}"
}

internal fun decodeMetadataTrack(track: Proto, parentAlbum: Music? = null): Music? {
    val uri = gidUri("track", track.bytes(1)) ?: return null
    val title = track.text(2)?.takeIf(String::isNotBlank) ?: return null
    val album = track.message(3)?.let(::decodeMetadataAlbum) ?: parentAlbum
    val artists = track.messages(4).mapNotNull { it.text(2)?.takeIf(String::isNotBlank) }
    return Music(
        id = uri, title = title, subtitle = artists.joinToString(", ").ifBlank { album?.subtitle.orEmpty() },
        kind = MusicKind.Track, artwork = Math.floorMod(uri.hashCode(), 6), imageUrl = album?.imageUrl,
        durationMs = track.number(7)?.let(::unZigZag)?.coerceAtLeast(0) ?: 0,
    )
}

internal fun decodeMetadataAlbum(album: Proto): Music? {
    val uri = gidUri("album", album.bytes(1)) ?: return null
    val title = album.text(2)?.takeIf(String::isNotBlank) ?: return null
    val artists = album.messages(3).mapNotNull { it.text(2) }.joinToString(", ")
    val images = album.message(17)?.messages(1).orEmpty() + album.messages(9)
    val tracks = album.messages(11).flatMap { it.messages(3) }.mapNotNull { gidUri("track", it.bytes(1)) }
    val year = album.message(6)?.number(1)?.let(::unZigZag)
    return Music(
        id = uri, title = title, subtitle = artists, kind = MusicKind.Album,
        artwork = Math.floorMod(uri.hashCode(), 6), imageUrl = metadataImage(images), tracks = tracks,
        note = year?.takeIf { it > 0 }?.toString().orEmpty(),
    )
}

internal fun decodeMetadataArtist(artist: Proto): Music? {
    val uri = gidUri("artist", artist.bytes(1)) ?: return null
    val title = artist.text(2)?.takeIf(String::isNotBlank) ?: return null
    val images = artist.messages(17).flatMap { it.messages(1) } + artist.messages(11)
    return Music(
        id = uri, title = title, subtitle = "Artist", kind = MusicKind.Artist,
        artwork = Math.floorMod(uri.hashCode(), 6), imageUrl = metadataImage(images),
        note = artist.messages(12).firstNotNullOfOrNull { it.text(1) }.orEmpty(),
    )
}

private fun metadataImage(images: List<Proto>): String? = images.sortedByDescending {
    it.number(3)?.let(::unZigZag) ?: 0
}.firstNotNullOfOrNull { image ->
    image.bytes(1)?.takeIf { it.size == 20 }?.let { "https://i.scdn.co/image/${it.metadataHex()}" }
}

internal fun unZigZag(number: Int): Int = (number ushr 1) xor -(number and 1)
private fun ByteArray.metadataHex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

/**
 * Spotify image identifiers carry their size after an 8-character family prefix. Search and some
 * lists name the thumbnail; the same picture exists at the largest size under the same hash.
 */
private val LARGEST_IMAGE = mapOf(
    "ab67616d" to ("0000b273" to setOf("00004851", "00001e02")),
    "ab676161" to ("0000e5eb" to setOf("0000f178", "00005174")),
)

/**
 * The cover Spotify shows for a playlist without its own picture: the covers of its first four
 * different albums in a 2×2 grid, served by Spotify's mosaic CDN. Fewer than four: the first cover.
 */
internal fun playlistMosaic(tracks: List<Music>): String? {
    val covers = tracks.mapNotNull { largestSpotifyImage(it.imageUrl) }.distinct()
    val ids = covers.mapNotNull { Regex("^https://i\\.scdn\\.co/image/([0-9a-f]{40})$").matchEntire(it)?.groupValues?.get(1) }
    return if (ids.size >= 4) "https://mosaic.scdn.co/640/${ids.take(4).joinToString("")}" else covers.firstOrNull()
}

internal fun largestSpotifyImage(url: String?): String? {
    if (url == null) return null
    val match = Regex("^https://i\\.scdn\\.co/image/([0-9a-f]{8})([0-9a-f]{8})([0-9a-f]{24})$").matchEntire(url) ?: return url
    val (family, size, hash) = match.destructured
    val (largest, smaller) = LARGEST_IMAGE[family] ?: return url
    return if (size in smaller) "https://i.scdn.co/image/$family$largest$hash" else url
}

internal fun spotifyArtwork(value: String?): String? = largestSpotifyImage(rawSpotifyArtwork(value))

private fun rawSpotifyArtwork(value: String?): String? {
    if (value == null) return null
    if (value.matches(Regex("spotify:image:[a-fA-F0-9]{40}"))) {
        return "https://i.scdn.co/image/${value.substringAfterLast(':')}"
    }
    val url = runCatching { value.toHttpUrl() }.getOrNull() ?: return null
    return url.takeIf {
        it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() &&
            (it.host == "i.scdn.co" || it.host == "mosaic.scdn.co" || it.host.endsWith(".spotifycdn.com"))
    }?.toString()
}

internal data class PlaylistPage(
    val item: Music, val uris: List<String>, val total: Int, val rawCount: Int, val hasMore: Boolean,
    /** Tracks and local file references, in playlist order. */
    val entries: List<String> = uris,
)

internal fun decodePlaylistPage(bytes: ByteArray, fallback: Music): PlaylistPage {
    val list = Proto(bytes)
    val contents = list.message(5)
    val raw = contents?.messages(3).orEmpty()
    val entries = raw.mapNotNull { row ->
        val uri = row.text(1) ?: return@mapNotNull null
        if (uri.startsWith(SPOTIFY_LOCAL_PREFIX)) uri else canonicalMusicUri(uri)?.takeIf { it.startsWith("spotify:track:") }
    }
    val uris = entries.filter { it.startsWith("spotify:track:") }
    val attrs = list.message(3)
    val image = playlistImage(attrs) ?: fallback.imageUrl
    val total = list.number(2) ?: raw.size
    return PlaylistPage(
        item = fallback.copy(
            title = attrs?.text(1)?.takeIf(String::isNotBlank) ?: fallback.title,
            subtitle = if (total == 1) "1 song" else "$total songs",
            imageUrl = image, note = attrs?.text(2).orEmpty(), ownerUsername = list.text(16) ?: fallback.ownerUsername,
        ),
        uris = uris, total = total, rawCount = raw.size, entries = entries,
        hasMore = contents?.number(2) == 1 || (contents?.number(1) ?: 0) + raw.size < total,
    )
}

internal fun playlistImage(attrs: Proto?): String? {
    attrs ?: return null
    val sized = attrs.messages(13).firstNotNullOfOrNull { spotifyArtwork(it.text(2)) }
    if (sized != null) return sized
    val picture = attrs.bytes(3) ?: return null
    return if (picture.size == 20) "https://i.scdn.co/image/${picture.metadataHex()}"
    else spotifyArtwork(picture.decodeToString())
}

/**
 * A local file reference of a Spotify playlist: `spotify:local:{artist}:{album}:{title}:{seconds}`,
 * each part URL-encoded. The audio is looked up among the device's files.
 */
internal fun spotifyLocalTrack(uri: String): Music? {
    if (!uri.startsWith(SPOTIFY_LOCAL_PREFIX)) return null
    val parts = uri.removePrefix(SPOTIFY_LOCAL_PREFIX).split(':')
    if (parts.size < 4) return null
    fun decode(value: String) = runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    val title = decode(parts[2]).ifBlank { return null }
    return Music(
        id = uri, title = title, subtitle = decode(parts[0]), kind = MusicKind.Track,
        album = decode(parts[1]), durationMs = (parts[3].toIntOrNull() ?: 0) * 1000,
    )
}

/** a file from the listener's own phone that sits in a Spotify playlist */
internal const val SPOTIFY_LOCAL_PREFIX = "spotify:local:"
