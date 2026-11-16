# Native runtime (Spotify source)

Mochy's Spotify source module, ported from [Melo](https://github.com/aarush-io/melo), plays audio with Spotify's own native engine (Orbit), taken unchanged from the official Android package you supply. This folder holds what is needed to load it. The Spotify binaries themselves are never committed.

## Contents

| Path | Purpose |
| --- | --- |
| `bootstrap/` | Java adapters (`probe.bootstrap.*`) that build the engine's object graph and implement its callbacks with concrete classes |
| `compile-stubs/` | Interface declarations so `javac` can compile the adapters. They are not packaged; at runtime the real classes come from the supplied DEX files. |
| `build-bootstrap.sh` | Compiles the adapters into `bootstrap.dex` (run by Gradle) |
| `artifacts/` | Local, ignored by Git. Place the APKM or the extracted files here. |

## Supplying the engine

You need the official Spotify Android APKM, version **9.2.0.26** (build 148373686). The Gradle task `prepareSpotifyRuntime` extracts:

- every `classes*.dex` from `base.apk`, packaged as `engine.jar`;
- `lib/arm64-v8a/liborbit-jni-spotify.so` from `split_config.arm64_v8a.apk`.

It checks both SHA-256 hashes and stops if they differ.

```sh
# Either point Gradle at the APKM
./gradlew :app:assembleArm64FossDebug -PspotifyArtifact=/path/to/com.spotify.music_9.2.0.26.apkm

# or place it at native-runtime/artifacts/spotify-9.2.0.26.apkm
# or place the extracted files at
#   native-runtime/artifacts/spotify-9.2.0.26.base.apk
#   native-runtime/artifacts/spotify-9.2.0.26.orbit.so
```

Nothing is downloaded automatically. The engine is ARM64 only.

With no APKM supplied (or with `-PspotifyRuntime=false`) the build is the usual Mochy: the Spotify module is listed but says the build has no engine, and every other source plays as before. Supplying the artifact turns the engine on (`BuildConfig.SPOTIFY_NATIVE_RUNTIME`).

With the engine on, the debug build is not debuggable and native libraries use legacy packaging (see below). Use an `arm64` or `universal` flavour.

## Why the debug build is not debuggable

The engine's JNI code calls `FindClass` with dotted class names, which CheckJNI rejects. CheckJNI is enabled for debuggable apps, so the build with the engine is non-debuggable, like the official app. The engine is not patched and no device setting is changed.

## Where it lives in Mochy

| Path | Purpose |
| --- | --- |
| `app/src/main/kotlin/com/mochyy/mochymusic/spotify/engine` | Melo's Login5 session, transport, search, HLS data source (package renamed) |
| `app/src/main/kotlin/com/mochyy/mochymusic/spotify/playback` | Native runtime, sample processor, HLS extractor, recent-audio cache |
| `spotify/SpotifyModule.kt` | Built-in `SourceModule`: matches Mochy's song on Spotify and returns a `spotify:track:` stream |
| `spotify/SpotifyEngine.kt` | What `MusicService` borrows: the codec hook and the HLS media source for `spotify:` addresses |
| `ui/screens/settings/modules/SpotifySignInSheet.kt` | Sign-in by email code, password or Google |

The codec hook only touches codecs configured without a platform CDM, so Apple Music's Widevine and ClearKey streams decode as before. Spotify is left out of the download order: its audio only becomes clear inside the decoder.

## How it is wired

`SpotifyNativeRuntime` opens the engine with an in-memory copy of the current Login5 session. The persistent credential stays encrypted in Android Keystore.

1. Load `engine.jar`, `bootstrap.dex` and Orbit in one child class loader.
2. Start the native timer thread and create every scope on it.
3. Register the HTTP factory and the mobile device info before Connectivity.
4. Create Connectivity/Core, Login5/OAuth, PubSub, NativeSession, Authenticated and FullAuthenticated, in that order.
5. Follow Android connectivity. Recreate the session and handles when the account changes.
6. On shutdown, close subscriptions and processors first, call `prepareForShutdown`, then destroy scopes in reverse order and the timer last.

`SdkHttpFactory` runs the engine's network calls on IO threads, attaches fresh Login5 and ClientToken credentials for Spotify API hosts only, retries once on 401, and never forwards credentials to CDN hosts.

Audio goes through HLS mode 4. `GetMediaHandleId(AUDIO)` takes the **full 20-byte file ID (40 hex characters)**; the 16-byte MP4 key ID is only used to find the right processing context. `NativeSampleProcessor` processes each sample in place before it is queued to the decoder. `MediaCrypto` is not involved on this path.

The protocol side of all this is documented in [`protocol/08-native-engine.md`](08-native-engine.md).

## Tests

Melo's instrumented signal tests (`NativePlaybackSignalTest`, `SpotifyPlaybackStressTest`) were not ported. A playing state or a moving position does not prove that audio is audible; check on a device that the decoded signal continues past the clear lead-in, after seeks and across track transitions.
