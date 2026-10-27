# Squeeze

**A file compressor for Android that takes any format and fits it into the size you pick — 150 MB by default — without throwing quality away.**

Photos, 4K video, audio, PDFs, Office documents and archives all go through one queue. Pick a target, hit compress, and everything lands in `Downloads/Squeeze`. Everything runs on the phone: no uploads, no account, no ads, no file-size limits.

<p align="center">
  <img src="screenshots/icon.png" width="96" alt="Squeeze icon">
</p>

## Screenshots

| Queue &amp; target size | Compressing | Done |
|---|---|---|
| <img src="screenshots/home.png" width="240"> | <img src="screenshots/compressing.png" width="240"> | <img src="screenshots/done.png" width="240"> |

<img src="screenshots/settings.png" width="240" alt="Settings">

## Install

Download **[Squeeze.apk](Squeeze.apk)** and open it on your phone. Android 10 or newer. You may need to allow "install unknown apps" for your browser or file manager the first time.

Signed with a release key, so future builds install straight over this one.

## What it actually does

The honest version, format by format. There is no single "compress without losing anything" switch that works on every format — anyone who tells you otherwise is either lying or quietly re-encoding your video. Squeeze picks the right strategy per file and tells you which one it used.

| Format | Lossless mode | Balanced (default) | Smallest |
|---|---|---|---|
| **JPG / PNG / HEIC / WebP** | Re-encoded losslessly (WebP lossless / PNG). Pixels are bit-identical, metadata stripped. | Quality 82, downscaled to the resolution cap, stepped down until it fits the target. | Quality 58 and smaller dimensions. |
| **MP4 / MKV / MOV / WEBM** | Copies the file untouched if it already fits; otherwise high-bitrate H.264. | H.264 re-encode sized to your target. **The audio track is copied bit-for-bit** when it is already AAC. | Lowest bitrate that still looks fine on a phone. |
| **MP3 / M4A / FLAC / WAV / OGG** | Original samples preserved, untouched. | AAC at whatever bitrate the target allows. | Same, lower bitrate floor. |
| **ZIP / DOCX / XLSX / PPTX** | Unpacked and re-deflated at level 9. Truly lossless — the bytes inside are unchanged. | Same. | Same. |
| **PDF / anything else** | Wrapped in a maximum-deflate ZIP, or stored unchanged if that would make it bigger. | Same. | Same. |

Two rules it never breaks: it will not hand you a file bigger than the one you gave it, and it will not re-encode something that already fits your target — those come back copied untouched.

**On PDFs:** embedded images inside a PDF are not recompressed, because doing that properly needs a full PDF library and would change the document's rendering. Squeeze re-deflates the container. A PDF that is mostly scanned images will barely shrink; one with uncompressed content streams can drop a lot.

## Hitting the target

The 150 MB (or 50/100/250/500) figure is a hard cap, not a wish:

1. The bitrate is computed from the target, the duration and the audio budget.
2. The video is transcoded and the result **measured**.
3. If it overshoots, the whole transcode runs again with the bitrate scaled by the overshoot.

Images do the same thing by walking quality down in steps and keeping the smallest result. Archives and unknown types fall back to a plain copy if compression would grow the file.

## Big files

Nothing is loaded into memory whole. Inputs are streamed in 64 KB chunks, video frames go through hardware codec surfaces, and audio samples spill to a temp file with an in-memory index. A 4 GB video and a 40 KB photo use the same code path — the only real limits are free disk space and how long you are willing to watch a progress bar.

The queue is unlimited; add a hundred files and they run one after another. The screen is kept awake and a wake lock is held while a job runs, so a long transcode does not get killed when the display sleeps.

## Where files go

`Downloads/Squeeze`, via MediaStore, so they show up in Files, Gallery and any other app. No storage permission is requested — Android 10+ lets the app write to its own output folder without one. Files are written as *pending* and only published when complete, so you never see a half-finished file.

## Privacy

No `INTERNET` permission at all. The app physically cannot upload your files. No analytics, no account, no ads, no tracking.

## Build it yourself

No Gradle, no AndroidX, no dependencies — `aapt2`, `javac`, `d8` and `apksigner` directly. The whole app is about 2,000 lines, Java plus one HTML file.

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk    # needs platforms;android-34, build-tools;34.0.0
export JAVA_HOME=/path/to/jdk-17
bash android/build.sh                           # -> android/build/Squeeze.apk
```

GitHub Actions does the same thing on every push (`.github/workflows/build.yml`) and attaches the APK to the release when you push a `v*` tag.

```
android/
├── AndroidManifest.xml
├── build.sh                    # aapt2 + javac + d8 + apksigner
├── release.keystore            # demo signing key (password: squeeze123)
├── assets/index.html           # the entire UI — one file
└── java/com/squeeze/compressor/
    ├── MainActivity.java       # WebView shell, SAF picker, worker thread
    ├── Engine.java             # job model, MediaStore output, dispatch
    ├── ImageCompressor.java    # decode → scale → encode → step quality
    ├── VideoCompressor.java    # MediaCodec surface transcode + target fitting
    ├── AudioCompressor.java    # AAC re-encode
    ├── AudioBag.java           # encoded-audio spool + AAC passthrough
    └── Archiver.java           # ZIP / Office re-deflate, generic fallback
```

The UI is a single HTML file talking to the engine over a small JS bridge (`Native.pickFiles`, `Native.start`, `Sq.onProgress`, `Sq.onDone`), which is also why the screenshots above are the real interface rendered at phone size rather than mockups.

## Licence

MIT — see [LICENSE](LICENSE).
