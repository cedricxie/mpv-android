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
- The app finds the matching cue by `start`/`end` (with a 1.5-second grace period after the line).
- Playback seeks to the cue start, switches to 0.5×, and loops between the cue boundaries.
- The study panel shows Japanese, corrected Chinese, vocabulary, grammar, listening notes, and the
  translation note.
- Previous/next changes the loop to the adjacent cue.
- Close clears the A/B loop and restores the speed used before entering study mode.

Network URLs and non-resolvable `content://` URIs do not yet support adjacent study-data discovery.
They require a media-to-study-data mapping layer in a future iteration.

## Build

The native libraries remain unmodified. Build them with the upstream `buildscripts` workflow, or
place matching ABI libraries under `app/src/main/libs/<abi>/`, then run:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew :app:assembleDefaultDebug
```
