# Mpv Study mode

This fork adds a local Japanese-learning workflow to mpv-android while leaving libmpv unchanged.

## Media layout

Place the video, bilingual subtitle, and learning data next to each other with the same base name:

```text
Operation Love - S01E02.avi
Operation Love - S01E02.zh.ass
Operation Love - S01E02.study.json
```

mpv loads the external ASS subtitle for normal bilingual playback. The app discovers the adjacent
`*.study.json` file when the video resolves to a local filesystem path.

## MVP behavior

- Tap **Study** / **学习这句** while a subtitle is active.
- The app finds the matching cue by `start`/`end`; between lines it returns to the nearest previous cue.
- Playback seeks to the cue start, switches to 0.5×, and loops between the cue boundaries.
- The study panel shows Japanese, corrected Chinese, vocabulary, grammar, listening notes, and the
  translation note.
- Previous/next changes the loop to the adjacent cue.
- Close clears the A/B loop and restores the speed used before entering study mode.

## Built-in WebDAV NAS browser

The **Open NAS folder** button opens the built-in WebDAV browser. On first use, configure the
Synology WebDAV server, media root, username, and password. The password is encrypted with an
Android Keystore key.

The first HTTPS connection asks the user to trust the NAS certificate fingerprint; later
connections are pinned to that fingerprint. Video is streamed to mpv through a random, loopback-only
HTTP bridge. The bridge handles HTTP Range requests and connects to the NAS with pinned HTTPS, so NAS
credentials never enter mpv or get reused for unrelated URLs. Matching subtitle and `*.study.json`
companions must be on the configured NAS origin and under the configured media root.

Multiple NAS profiles can be saved. The button showing the active NAS switches between profiles;
**Manage** adds, edits, or deletes profiles. Existing single-NAS installations migrate automatically,
and every saved password remains encrypted with Android Keystore.

## NAS folders through Android document providers (fallback)

Long-press **Open NAS folder** and choose an SMB folder from a document-provider app such as CX File
Explorer. The selected tree permission is persisted by Android. When a video is chosen through the
in-app browser, the app queries that video's parent document and loads the same-name subtitle and
study-data documents through `content://` URIs.

Opening a video directly from another app does not include the parent-tree context, so adjacent
study-data discovery is only guaranteed when the NAS folder is opened from Mpv Study's home screen.
Plain network URLs still require a media-to-study-data mapping layer.

If the SMB app does not appear in Android's folder picker, select the video, bilingual subtitle,
and matching `*.study.json` together in the SMB app and share all three to **Mpv Study**. The app
recognizes the media and its companions by their shared base name instead of creating a playlist.

## Build

The native libraries remain unmodified. Build them with the upstream `buildscripts` workflow, or
place matching ABI libraries under `app/src/main/libs/<abi>/`, then run:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew :app:assembleDefaultDebug
```
