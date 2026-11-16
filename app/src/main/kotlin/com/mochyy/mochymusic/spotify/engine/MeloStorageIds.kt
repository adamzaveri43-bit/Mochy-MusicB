package com.mochyy.mochymusic.spotify.engine

import java.util.Base64

/** Stable persisted identifiers retained for compatibility across installed Melo versions. */
internal object MeloStorageIds {
    val artworkUriScheme = decode("c3BvdGlmZXVyX2xvY2FsX2FydA==")
    val downloadNotificationChannel = decode("c3BvdGlmZXVyX2Rvd25sb2Fkcw==")
    val backupFormat = decode("c3BvdGlmZXVyLWJhY2t1cA==")
    val nativeKeyAliasPrefix = decode("c3BvdGlmZXVyLW5hdGl2ZS1rZXlzLQ==")
    val youtubeAccountKeyAlias = decode("c3BvdGlmZXVyLXlvdXR1YmUtYWNjb3VudA==")

    private fun decode(encoded: String) = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
}
