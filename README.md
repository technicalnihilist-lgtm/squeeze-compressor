# Squeeze

**A file compressor for Android that takes any format and fits it into the size you pick — 20 MB, 150 MB, whatever you need — without throwing quality away.**

Photos, 4K video, audio, PDFs, Office documents and archives all go through one queue. Pick a target, hit compress, and everything lands in `Downloads/Squeeze`. Everything runs on the phone: no uploads, no account, no ads, no file-size limits.

And you do not have to take any of that on trust: there is a **built-in self-test** that encodes a real H.264 + AAC clip on your own phone, pushes it through the same engine your files go through, and shows you the input bytes, output bytes, codec and time taken. No file picking required.

<p align="center">
  <img src="screenshots/icon.png" width="96" alt="Squeeze icon">
</p>

## Screenshots

| Queue &amp; target size | Compressing (cancel + live note) | Done |
|---|---|---|
| <img src="screenshots/home.png" width="240"> | <img src="screenshots/compressing.png" width="240"> | <img src="screenshots/done.png" width="240"> |

| Self-test | Settings |
|---|---|
| <img src="screenshots/selftest.png" width="240" alt="Self-test result"> | <img src="screenshots/settings.png" width="240" alt="Settings"> |

## Cancel, share, and pick up where you left off

A long transcode is easy to start and hard to take back, so cancel is real rather than cosmetic. While a job runs the main button turns into **Cancel**; tapping it sets a flag that the worker checks between files *and* inside the encode loop itself, so a video transcode unwinds between two frames instead of running to the end. Half-written scratch files are deleted, pending entries are removed from `Downloads/Squeeze`, and every unfinished file goes straight back into the queue — ready to retry with a different target, no re-picking.

Each finished row also keeps the engine's own note: `H.264 1280×720 · 30 fps · 812 kbps`, `AAC 96 kbps`, `Lossless re-compression of 61 entries`, or `Smallest reachable is 38 MB` when the target was physically out of reach. It is the engine's measurement, not a decoration.

Squeeze registers itself as a share target and an "open with" handler, so **Share → Squeeze** from Gallery, Files or a chat app drops the file straight into the queue. The queue itself is kept across restarts — close the app mid-job, reopen it, and the same files are waiting. A share grant that expired in the meantime is dropped rather than failing later.

While a job is running a small pill in the footer says the screen stays awake, which reflects the actual wake lock: the display is held on and a partial wake lock keeps the CPU going, so a locked phone does not quietly stall the encoder.

## Motion you can dial down

The UI has two motion effects, and both are adjustable in **Settings → Motion & depth** rather than baked in.

The **4D depth effect** tilts the glass cards towards your finger (up to 6° at full strength) and slides a soft light across them, so the surface reads as layered instead of flat. **Parallax** moves the four backdrop blobs at their own rates — the nearest one travels furthest — both with your finger and as the queue scrolls, which is what gives the screen depth when the list is long.

Each has its own switch and its own strength slider (0–100%), saved on the device and restored on the next launch. Both are one `transform` per frame written into CSS custom properties, so nothing reflows and there is no measurable battery cost; if the system's "reduce motion" setting is on, both start switched off instead of overriding it.

## Install

Download **[Squeeze.apk](Squeeze.apk)** and open it on your phone. Android 10 or newer. You may need to allow "install unknown apps" for your browser or file manager the first time.

Signed with a release key, so future builds install straight over this one.

## What it actually does

The honest version, format by format. There is no single "compress without losing anything" switch that works on every format — anyone who tells you otherwise is either lying or quietly re-encoding your video. Squeeze picks the right strategy per file and tells you which one it used.

| Format | Lossless mode | Balanced (default) | Smallest |
|---|---|---|---|
| **JPG / PNG / HEIC / WebP / BMP / TIFF / AVIF** | Re-encoded losslessly (WebP lossless on Android 11+, PNG otherwise). Pixels are bit-identical, metadata stripped. | Quality 82, downscaled to the resolution cap, stepped down until it fits the target. | Quality 58 and smaller dimensions. |
| **GIF** | Copied untouched — re-encoding one frame of an animation would be worse than useless. | Same. | Same. |
| **MP4 / MKV / MOV / WEBM / AVI / 3GP / TS / M2TS / FLV / F4V / WMV** | Copies the file untouched if it already fits the target. Otherwise it is the same H.264 re-encode as Balanced — there is no lossless way to shrink a video without keeping the original file. | H.264 re-encode sized to your target, resolution stepped down to fit. **The audio track is copied bit-for-bit** when it already fits the budget. | Lowest resolution and bitrate that still looks fine on a phone. |
| **MP3 / M4A / AAC / WAV / FLAC / OGG / OPUS / AMR / AIFF / WMA** | Original samples preserved, untouched. | AAC at whatever bitrate the target allows. Output is always an `.m4a`. | Same, lower bitrate floor. |
| **ZIP / DOCX / XLSX / PPTX / EPUB / APK / JAR / ODT / ODS / ODP** | Unpacked and re-deflated at level 9. Truly lossless — the bytes inside are unchanged. ZIP64, so entries and archives over 4 GB work. | Same. | Same. |
| **PDF** | Parsed and rewritten: every Flate stream re-deflated at level 9, dead objects dropped, metadata stripped, images copied byte for byte. See below. | Same. | Same. |
| **RAR / 7Z / TAR / GZ** | Wrapped in a max-deflate ZIP — these containers cannot be unpacked without a third-party library, so the inner bytes are stored rather than re-deflated. | Same. | Same. |
| **Anything else** | Wrapped in a maximum-deflate ZIP, or stored unchanged if that would make it bigger. | Same. | Same. |

Two rules it never breaks: it will not hand you a file bigger than the one you gave it, and it will not re-encode something that already fits your target — those come back copied untouched. The one deliberate exception is a photo in **Lossless** mode, which is re-encoded at full resolution even when it already fits, because that pass is what strips its metadata and re-packs it losslessly.

**On PDFs:** a PDF is already a container of compressed streams, so wrapping one in a ZIP — which is what Squeeze used to do — saves almost nothing. It is rewritten instead. The file is parsed into objects, the object graph is walked from the catalog, and a new PDF is written containing only what is reachable:

- every `FlateDecode` stream is inflated and re-deflated at maximum effort, and kept only if it actually came out smaller;
- streams that were left uncompressed get a `FlateDecode` filter, which is where print-driver and hand-made PDFs lose most of their weight;
- objects nothing points at — incremental-update leftovers, orphaned images, replaced page trees — are dropped, and so are the old cross-reference structures;
- `/Info` and the XMP metadata packet are removed, so author, producer and dates do not survive;
- **images are never re-encoded.** A `DCTDecode`, `JPXDecode` or `CCITTFaxDecode` stream is copied byte for byte, because re-encoding it would change what the page looks like. Only DEFLATE is touched, and DEFLATE is lossless.

That means the pages render identically: the rewrite was checked by rendering every page of three documents — a 40-page generated PDF, a MuPDF-compressed one with an embedded JPEG, and a hand-built one using object streams — before and after, and the pixels came out identical. The on-device self-test additionally re-reads the result to prove the header, cross-reference table and `%%EOF` all survived. A PDF that is mostly scanned images will barely shrink, because there is nothing left to squeeze without touching the pictures; one with uncompressed or lazily-compressed content streams can drop a lot. If the rewrite is not smaller than the original, you get the original. Encrypted PDFs are refused with a message that says so rather than producing a broken file.

## Prove it works — the self-test

Every compressor claims a lot. Squeeze ships with a button that checks it, on your hardware, in about fifteen seconds.

Tap **Run** under the add button (or *Run the self-test* in Settings). The app then:

1. Lists the encoders this device actually reports — H.264, HEVC and AAC, with the codec name and whether it is hardware or software.
2. Encodes a real H.264 + AAC clip with `MediaCodec` — 640 × 360, CBR, about 24 MB — so the input is genuinely larger than the target.
3. Compresses a synthetic 2400 × 1600 PNG the same way.
4. Builds a 12-page PDF with uncompressed content streams and rewrites it through the PDF path.
5. Pushes all three through `Engine.compress` at a **20 MB target**, exactly as it would a file you picked.
6. Re-opens the video with `MediaExtractor` to prove it is a playable MP4, and the PDF to prove its cross-reference table and `%%EOF` are intact.

You get a pass/fail card with the measured numbers: bytes in, bytes out, the codec that did the work, the encode time and the total time. If any step fails, the card fails and names the step — the self-test never reports a pass for work that did not happen.

The test's own inputs and outputs are written to scratch space and deleted afterwards, so running it does not clutter `Downloads/Squeeze`.

## Hitting the target

The 20 / 50 / 100 / 150 / 250 / 500 MB figure is a hard cap, not a wish. Four things make that true:

1. **The budget is split before encoding.** Audio is capped at a quarter of the target (and re-encoded down if it would eat more), and whatever is left becomes the video bitrate. A two-hour video will never quietly spend 900 MB on its audio track.
2. **Resolution follows the budget.** A bitrate alone cannot put 1080p into 20 MB, so the engine picks the highest resolution whose comfortable bitrate actually fits — 1920 → 854 → 480 — instead of producing mush at full size.
3. **Every attempt is measured.** The transcode runs, the finished file is read, and if it overshoots the next attempt is scaled by the *real* overshoot.
4. **The smallest honest result wins.** If even the bottom rung cannot reach the target, you get the smallest file the footage allows and a note telling you what that floor was — never a fake "done" with nothing to show for it.

Images do the same thing by walking quality and dimension down in steps. Archives and unknown types fall back to a plain copy if compression would grow the file.

### When a target is physically impossible

A two-hour film in 20 MB is about 22 kbps of video plus audio — below anything watchable, and below the container's own overhead. Squeeze does not pretend otherwise: it encodes to the lowest sane rung and reports `Smallest reachable is 38 MB`. Short clips hit 20 MB easily; length is the limit, not the format.

## Big files

Nothing is loaded into memory whole. Inputs are streamed in 64 KB chunks, video frames go through hardware codec surfaces, and audio samples spill to a temp file with an in-memory index. PDFs need random access, so they are spooled to scratch space and read through a memory-mapped file rather than a byte array; streams over 24 MB are copied rather than re-deflated, and an inflation that expands past 96 MB is abandoned instead of exhausting the heap. A 4 GB video and a 40 KB photo use the same code path — the only real limits are free disk space and how long you are willing to watch a progress bar.

Files are read through a file descriptor rather than a content URI, which is what makes multi-gigabyte selections from Downloads, an SD card or Google Drive work; and the finished output is written to scratch space first, then moved into Downloads/Squeeze, so a long transcode cannot leave a half-written file behind. Free space is checked before the job starts rather than discovered half-way through.

Archives get ZIP64. A `.zip` over 4 GB cannot be described by the classic format, and the streaming ZIP reader this used to use only copes with ZIP64 when the archive happens to be laid out the way it expects — an archive written by another tool fails on it. So an archive input above 3 GB is spooled to scratch space and read through a random-access reader that is defined to handle ZIP64, and the writer emits ZIP64 extra fields, 8-byte data descriptors and a ZIP64 end-of-central-directory by itself whenever an entry count or either size crosses the limit. Inputs over 4 GB and outputs over 4 GB were both tested end to end — a 4.3 GB entry and a 4.3 GB archive — and the row's note says `ZIP64` when that path was taken.

If something genuinely cannot be compressed, the queue row turns red and says why — "not enough space", "no video track", "smallest reachable is X". Nothing is ever reported as done when no file was produced.

### When something goes wrong

Four failures account for almost every red row, and each one names itself and its next step instead of saying "compression failed":

- **Unsupported codec** — "No decoder on this device can read this file's codec (AV1, VP9, ProRes and some HEVC profiles are common culprits). Try Smallest mode; if that still fails, convert it on a device or app that supports it."
- **DRM-protected** — a store-encrypted download, or a PDF locked with a password or owner certificate. The message says to export a DRM-free copy (print-to-PDF works for the PDF case) rather than pretending a re-encode is possible.
- **Corrupt header** — usually an interrupted download or a partial export; the message names the format it expected and asks for a re-download. A damaged ZIP is reported as a damaged ZIP instead of being silently re-wrapped.
- **File vanished mid-job** — the file was moved, renamed, or the app that granted access closed. It reads as "vanished" rather than "unreadable", because the fix is different: add it again from Files.

The queue is unlimited, and it runs on two lanes: photos and archives go through a small pool of workers while one video or audio job takes the codec lane on its own thread. A batch of forty photos therefore does not sit idle behind a ten-minute transcode — the two finish in roughly the time of the slower one, not the sum. MediaCodec work stays strictly single-threaded on purpose: two simultaneous transcodes fight over the same hardware encoder and make both of them worse. The screen is kept awake and a wake lock is held while a job runs, so a long transcode does not get killed when the display sleeps.

## Speed

Three things decide whether a queue feels fast, and all three are deliberate:

- **A photo is decoded once.** The source is decoded a single time at the largest size the ladder needs; every later attempt is an encode of those same pixels, at a lower quality or after one in-place downscale of a reused bitmap. The old path re-opened and re-decoded the file for each quality step — on a 12 MP JPEG a decode costs about five encodes, and it was doing eight of them.
- **Work that cannot help is skipped.** A file that already fits the target and is already inside the resolution cap is copied untouched without a single pixel being decoded.
- **Progress knows how long it has left.** Every progress event carries a time-remaining estimate, measured from bytes processed per second where the byte count is real and from the job's own progress rate where it is not. The queue row shows `64% · 42s left` instead of a bar that sits still and looks stuck. When no honest estimate exists yet, no number is shown.

Memory is bounded rather than merely large: one decode plus one working bitmap per photo, one reusable sample buffer for a whole video encode, and one scratch array for a whole audio track — no per-frame allocations in any hot loop.

## Where files go

`Downloads/Squeeze`, via MediaStore, so they show up in Files, Gallery and any other app. No storage permission is requested — Android 10+ lets the app write to its own output folder without one. Files are written as *pending* and only published when complete, so you never see a half-finished file.

## Privacy

No `INTERNET` permission at all. The app physically cannot upload your files. No analytics, no account, no ads, no tracking.

## Build it yourself

No Gradle, no AndroidX, no dependencies — `aapt2`, `javac`, `d8` and `apksigner` directly, and not a single third-party library, including for the PDF work. The whole app is about 5,850 lines — 4,800 of Java across twelve files, plus one 1,050-line HTML file.

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
    ├── MainActivity.java       # WebView shell, SAF picker, two worker lanes
    ├── Engine.java             # job model, MediaStore output, dispatch
    ├── Plan.java               # which jobs run in parallel, image ladder rules
    ├── Speed.java              # bytes/sec and time-remaining math
    ├── ImageCompressor.java    # one decode → reused bitmap → step quality
    ├── VideoCompressor.java    # MediaCodec surface transcode + target fitting
    ├── AudioCompressor.java    # AAC re-encode
    ├── AudioBag.java           # encoded-audio spool + AAC passthrough
    ├── Archiver.java           # ZIP / Office re-deflate (ZIP64), generic fallback
    ├── Pdf.java                # PDF parser + rewriter: re-deflate, prune, strip metadata
    ├── PdfCompressor.java      # spools the PDF, runs the rewrite, keeps the original if bigger
    ├── Fault.java              # exception → what went wrong + what to do next
    └── SelfTest.java           # on-device H.264+AAC clip, test image, test PDF, encoder list
```

The UI is a single HTML file talking to the engine over a small JS bridge (`Native.pickFiles`, `Native.start`, `Native.selfTest`, `Native.encoders`, `Sq.onProgress`, `Sq.onDone`, `Sq.onSelfTest`), which is also why the screenshots above are the real interface rendered at phone size rather than mockups.

## Licence

MIT — see [LICENSE](LICENSE).
