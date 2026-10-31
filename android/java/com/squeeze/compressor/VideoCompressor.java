package com.squeeze.compressor;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Video compression.
 *
 * Four things this does that a naive "pick a bitrate and hope" transcoder does not:
 *
 * 1. It walks a resolution ladder. When the target is very small (20 MB), no bitrate
 *    will get a 1080p file there — the frames themselves cost too much — so it steps
 *    the resolution down (1920 → 854 → 480) and re-tries until it actually fits.
 * 2. It measures. Each attempt produces a real file, gets its size read, and the next
 *    attempt is scaled by the actual overshoot rather than by a guess.
 * 3. It verifies. Every attempt is reopened with MediaExtractor before it is trusted:
 *    a video track, a plausible duration and real samples, or the attempt is thrown
 *    away and retried once at a richer bitrate.
 * 4. It respects the footage. Frames are encoded at display orientation (a phone
 *    portrait clip is 1080x1920, not a squashed 1920x1080), the source frame rate is
 *    kept as-is, and the keyframe interval is sized from the clip's own duration.
 *
 * If even the smallest rung cannot reach the target, the smallest result is kept and
 * the UI is told the true floor instead of failing silently.
 *
 * Audio is copied bit-for-bit when it is already AAC, so only the picture is touched.
 */
final class VideoCompressor {

    private static final int TIMEOUT = 12000;   // microseconds
    private static final int MAX_ATTEMPTS = 3;

    /**
     * The encoder surface is built at display orientation, so the frames coming off
     * the decoder already fill it upright; the muxer hint then tells players which
     * way up that is. If a device is ever seen rotating twice (a portrait clip that
     * plays sideways), flip this to false — that is the one knob for it.
     */
    private static final boolean ORIENTATION_HINT = true;

    static Engine.Result run(Context ctx, Engine.Job job, Engine.Progress p) throws Exception {
        boolean smallest = "smallest".equals(job.mode);

        Src probe = Src.open(ctx, job.uri);
        long durUs;
        int sw, sh, rot, fps, srcRate;
        int aRate = 128000;
        try {
            if (probe.vIdx < 0 || probe.vf == null) return null;
            MediaFormat vf = probe.vf;
            sw = intOr(vf, MediaFormat.KEY_WIDTH, 0);
            sh = intOr(vf, MediaFormat.KEY_HEIGHT, 0);
            rot = intOr(vf, MediaFormat.KEY_ROTATION, 0);
            fps = frameRate(vf);
            srcRate = intOr(vf, MediaFormat.KEY_BIT_RATE, 8_000_000);
            if (probe.af != null) aRate = intOr(probe.af, MediaFormat.KEY_BIT_RATE, 128000);
            if (sw <= 0 || sh <= 0) return null;
            // Duration is often missing on MKV/AVI — derive it instead of giving up.
            durUs = duration(vf, job.size, srcRate);
        } finally {
            probe.close();
        }

        if (job.size > 0 && job.size <= job.target && !smallest) {
            return Engine.copyRaw(ctx, job, p, "Already under " + Engine.mb(job.target) + " — copied untouched");
        }

        // Storage dimensions are not what the viewer sees: a 1920x1080 file flagged
        // rotation=90 is a 1080x1920 portrait video. Encoding at the storage size is
        // exactly what squashed them, so work in display space from here on.
        int[] disp = displayDims(sw, sh, rot);
        int dw = disp[0], dh = disp[1];
        int cap = Math.max(240, job.maxDim);
        int longest = Math.max(dw, dh);
        double secs = Math.max(0.5, durUs / 1_000_000.0);
        int keySecs = keyframeIntervalSecs(durUs);

        // The target is the boss: work out what it allows, then spend it.
        long targetBits = (long) (job.target * 8 * 0.94);          // 6% for container overhead
        int audioRate = aRate;
        long audioShare = (long) (targetBits * 0.25);              // audio may take at most 25%
        audioRate = (int) Math.min(aRate, Math.max(24000, audioShare / secs));
        long audioBits = (long) (audioRate * secs);
        long videoBits = Math.max((long) (20_000 * secs), targetBits - audioBits);
        int rateNeeded = (int) Math.max(20_000, Math.min(videoBits / secs, srcRate));

        int[] rungs = ladder(longest, cap, smallest);
        // Highest resolution whose comfortable bitrate fits the budget; else the lowest rung.
        int startIdx = rungs.length - 1;
        for (int i = 0; i < rungs.length; i++) {
            if (floorFor(rungs[i], smallest) <= rateNeeded) { startIdx = i; break; }
        }

        File best = null;
        long bestSize = Long.MAX_VALUE;
        int bestW = 0, bestH = 0, bestRate = 0;
        boolean bestRicher = false;
        long rate = rateNeeded;
        long ceiling = Math.max(srcRate, (long) (rateNeeded * 2.5));
        String lastProblem = null;

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Engine.checkCancel();   // between rungs, not just between frames
            int dim = rungs[Math.min(startIdx + attempt, rungs.length - 1)];
            double k = dim / (double) longest;
            int ow = even(Math.round(dw * (float) k));
            int oh = even(Math.round(dh * (float) k));
            if (ow < 16 || oh < 16) continue;

            if (bestSize != Long.MAX_VALUE && bestSize > 0) {
                // Scale by the real overshoot from the previous attempt.
                rate = (long) (rate * (job.target / (double) bestSize) * 0.92);
            }
            rate = Math.max(20_000, Math.min(rate, srcRate));

            File tmp = null;
            boolean accepted = false;
            long tryRate = rate;

            for (int pass = 0; pass < 2 && !accepted; pass++) {
                tmp = Engine.tempFile(ctx, ".mp4");
                boolean ok;
                try {
                    ok = encode(ctx, job, tmp, ow, oh, (int) tryRate, fps, rot, durUs,
                            audioRate, keySecs, p, attempt, MAX_ATTEMPTS);
                } catch (Exception e) {
                    tmp.delete();
                    tmp = null;
                    if (attempt == MAX_ATTEMPTS - 1 && pass == 1 && best == null) throw e;
                    ok = false;
                }

                if (ok && tmp != null && tmp.length() > 0) {
                    String why = check(tmp, durUs);
                    if (why == null) {
                        accepted = true;
                        break;
                    }
                    lastProblem = why;
                    tmp.delete();
                    tmp = null;
                } else if (tmp != null) {
                    tmp.delete();
                    tmp = null;
                }

                // One retry, richer, before dropping a rung: an output that will not
                // reopen is usually a starved encoder, not a resolution that is too big.
                tryRate = Math.min(ceiling, (long) (tryRate * 1.6) + 120_000);
            }

            if (!accepted || tmp == null) continue;

            long sz = tmp.length();
            if (sz < bestSize) {
                if (best != null) best.delete();
                best = tmp;
                bestSize = sz;
                bestW = ow;
                bestH = oh;
                bestRate = (int) tryRate;
                bestRicher = tryRate > rate;
            } else {
                tmp.delete();
            }
            if (bestSize <= job.target) break;
        }

        if (best == null) {
            if (lastProblem != null) throw new IllegalStateException("Encode did not verify: " + lastProblem);
            return null;
        }

        // Never hand back something bigger than what came in.
        if (job.size > 0 && bestSize >= job.size) {
            best.delete();
            return Engine.copyRaw(ctx, job, p, "Source is already more efficient — kept as-is");
        }

        String outName = Engine.base(job.name) + "_squeezed.mp4";
        Engine.Result r = new Engine.Result();
        Engine.publishTemp(ctx, job, best, outName, "video/mp4", p, r);
        r.codec = "H.264";
        r.note = "H.264 " + bestW + "×" + bestH + (bestH > bestW ? " portrait" : "")
                + " · " + fps + " fps · " + (bestRate / 1000) + " kbps"
                + (bestRicher ? " · re-encoded richer to verify" : "");
        if (bestSize > job.target) {
            r.note = "Smallest reachable is " + Engine.mb(bestSize)
                    + " — " + Engine.mb(job.target) + " is below what this footage allows";
        }
        best.delete();
        return r;
    }

    /* ------------------------------------------------------------------ */
    /*  frame geometry, rate plan and keyframes — all pure math            */
    /* ------------------------------------------------------------------ */

    /** {width, height} as the viewer sees them: storage dims swapped when rotated 90/270. */
    static int[] displayDims(int w, int h, int rot) {
        int r = ((rot % 360) + 360) % 360;
        if (r == 90 || r == 270) return new int[]{h, w};
        return new int[]{w, h};
    }

    /**
     * Source frame rate, kept exactly. A missing or nonsensical value falls back to
     * 30 rather than to a halved rate — silently halving is how footage ends up
     * looking like it was shot on a webcam.
     */
    static int frameRate(MediaFormat f) {
        return frameRate(intOr(f, MediaFormat.KEY_FRAME_RATE, 0),
                intOr(f, MediaFormat.KEY_CAPTURE_RATE, 0));
    }

    /** Pure half of {@link #frameRate(MediaFormat)} so the rule can be tested off-device. */
    static int frameRate(int declared, int capture) {
        int r = declared > 0 ? declared : capture;
        if (r <= 0) r = 30;
        return Math.max(1, Math.min(r, 120));
    }

    /**
     * Keyframe spacing in seconds, sized from the clip instead of a flat 1 s.
     * Roughly one every 2 s; longer clips can afford 3 s, very short ones need 1 s,
     * and every clip keeps at least two keyframes so seeking still works.
     */
    static int keyframeIntervalSecs(long durUs) {
        double secs = Math.max(0.5, durUs / 1_000_000.0);
        int iv = secs > 300 ? 3 : 2;
        if (secs < 4) iv = 1;
        int room = Math.max(1, (int) Math.floor(secs / 2.0));
        return Math.max(1, Math.min(iv, room));
    }

    /**
     * Resolution rungs to try, biggest first. Capped at three attempts so a long
     * video does not turn into a half-hour wait. "Smallest" stops at 1280: below a
     * tight target it is better to spend the budget on bitrate at 720p than to keep
     * 1080p and let faces smear.
     */
    static int[] ladder(int longest, int cap, boolean smallest) {
        int top = smallest ? Math.min(cap, 1280) : cap;
        int start = Math.min(longest, top);
        start = start - (start % 2);
        int mid = start > 900 ? 854 : start - (start % 2);
        int low = 480;
        if (start <= 480) return new int[]{start};
        if (mid >= start || mid <= low) return new int[]{start, low};
        return new int[]{start, mid, low};
    }

    /**
     * Roughly the lowest bitrate that still looks acceptable at a given width.
     * Balanced sits higher on purpose — the old floors let 720p drop to 500 kbps,
     * which is where faces start to smear. Smallest trades resolution instead.
     */
    static long floorFor(int dim, boolean smallest) {
        if (smallest) {
            if (dim >= 1920) return 1_100_000;
            if (dim >= 1280) return 700_000;
            if (dim >= 854) return 420_000;
            if (dim >= 640) return 260_000;
            return 170_000;
        }
        if (dim >= 1920) return 1_800_000;
        if (dim >= 1280) return 1_100_000;
        if (dim >= 854) return 650_000;
        if (dim >= 640) return 420_000;
        return 260_000;
    }

    /* ------------------------------------------------------------------ */
    /*  post-encode verification                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Reopens a finished attempt and answers "is this actually a video?".
     *
     * @return null when the file is usable, otherwise the reason it is not.
     */
    static String check(File out, long expectDurUs) {
        if (out == null || out.length() <= 0) return "empty file";
        MediaExtractor ex = new MediaExtractor();
        ByteBuffer buf = ByteBuffer.allocate(1 << 20);
        try {
            ex.setDataSource(out.getAbsolutePath());
            int vi = -1;
            long dur = 0;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat mf = ex.getTrackFormat(i);
                String m = mf.getString(MediaFormat.KEY_MIME);
                if (m == null || !m.startsWith("video/")) continue;
                if (vi < 0) vi = i;
                dur = Math.max(dur, longOr(mf, MediaFormat.KEY_DURATION, 0));
            }
            if (vi < 0) return "no video track";
            if (dur <= 0) return "no duration";
            if (expectDurUs > 0) {
                long lo = Math.max(200_000L, (long) (expectDurUs * 0.6));
                long hi = (long) (expectDurUs * 1.4) + 2_000_000L;
                if (dur < lo || dur > hi) {
                    return String.format(java.util.Locale.US,
                            "duration %.1f s, expected about %.1f s", dur / 1e6, expectDurUs / 1e6);
                }
            }
            ex.selectTrack(vi);
            int samples = 0;
            for (int i = 0; i < 64; i++) {
                int n;
                try {
                    n = ex.readSampleData(buf, 0);
                } catch (Exception e) {
                    break;
                }
                if (n < 0) break;
                if (n > 0) samples++;
                ex.advance();
            }
            if (samples == 0) return "no decodable samples";
            return null;
        } catch (Exception e) {
            return "unreadable (" + e.getClass().getSimpleName() + ")";
        } finally {
            try { ex.release(); } catch (Exception ignored) { }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  the encode itself                                                 */
    /* ------------------------------------------------------------------ */

    private static boolean encode(Context ctx, Engine.Job job, File out, int ow, int oh,
                                  int vRate, int fps, int rot, long durUs, int aRate,
                                  int keySecs, Engine.Progress p, int attempt, int total) throws Exception {
        Src src = Src.open(ctx, job.uri);
        AudioBag bag = null;
        MediaCodec enc = null, dec = null;
        MediaMuxer mux = null;
        Surface surface = null;
        boolean started = false;

        try {
            if (src.vIdx < 0 || src.vf == null) throw new IllegalStateException("No video track");

            // Phase 1 — collect audio (copied when AAC, re-encoded otherwise).
            float base = total <= 1 ? 0.05f : 0.05f + (attempt * 0.03f);
            if (src.aIdx >= 0 && src.af != null) {
                try {
                    int srcAudio = intOr(src.af, MediaFormat.KEY_BIT_RATE, 128000);
                    if (AudioBag.isAac(src.af) && aRate >= srcAudio) {
                        // Already small enough: copy the samples bit-for-bit.
                        bag = AudioBag.passthrough(src.ex, src.aIdx, src.af, Engine.workDir(ctx), p, base, base + 0.08f);
                    } else {
                        bag = AudioBag.transcode(ctx, src.ex, src.aIdx, src.af,
                                Math.min(aRate, 160000), Engine.workDir(ctx), p, base, base + 0.08f);
                    }
                } catch (Exception e) {
                    bag = null; // carry the picture even if audio could not be handled
                }
            }

            // Phase 2 — encoder + decoder over a shared surface, in display space.
            MediaFormat ef = MediaFormat.createVideoFormat("video/avc", ow, oh);
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, vRate);
            ef.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keySecs);
            try {
                ef.setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            } catch (Exception ignored) { }

            enc = MediaCodec.createEncoderByType("video/avc");
            enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = enc.createInputSurface();
            enc.start();

            dec = MediaCodec.createDecoderByType(src.vf.getString(MediaFormat.KEY_MIME));
            dec.configure(src.vf, surface, null, 0);
            dec.start();

            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (rot != 0 && ORIENTATION_HINT) mux.setOrientationHint(((rot % 360) + 360) % 360);

            int aTrack = -1;
            if (bag != null && bag.count > 0 && bag.fmt != null) aTrack = mux.addTrack(bag.fmt);
            int vTrack = -1;

            src.ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            src.ex.selectTrack(src.vIdx);

            boolean inputDone = false, decDone = false, encDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            SampleBuf sbuf = new SampleBuf();
            ArrayList<Object[]> pending = new ArrayList<>();
            int stall = 0;

            while (!encDone) {
                // Cooperative abort: the user's cancel lands here, between two frames.
                Engine.checkCancel();
                boolean progressed = false;

                if (!inputDone) {
                    int i = dec.dequeueInputBuffer(TIMEOUT);
                    if (i >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(i);
                        int sz = src.ex.readSampleData(ib, 0);
                        if (sz < 0) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            dec.queueInputBuffer(i, 0, sz, src.ex.getSampleTime(), 0);
                            src.ex.advance();
                        }
                        progressed = true;
                    }
                }

                if (!decDone) {
                    int o = dec.dequeueOutputBuffer(info, TIMEOUT);
                    if (o >= 0) {
                        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        dec.releaseOutputBuffer(o, true);
                        if (eos) {
                            decDone = true;
                            try { enc.signalEndOfInputStream(); } catch (Exception ignored) { }
                        }
                        progressed = true;
                    }
                }

                int eo = enc.dequeueOutputBuffer(info, TIMEOUT);
                if (eo == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = enc.getOutputFormat();
                    // Say the frame rate out loud: some encoders leave it at 0, and a
                    // track without it is what makes players guess (and guess wrong).
                    try {
                        of.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                    } catch (Exception ignored) { }
                    vTrack = mux.addTrack(of);
                    mux.start();
                    started = true;
                    if (aTrack >= 0) bag.play(mux, aTrack);
                    for (Object[] b : pending) {
                        write(mux, vTrack, (byte[]) b[0], (Long) b[1], (Integer) b[2]);
                    }
                    pending.clear();
                    progressed = true;
                } else if (eo >= 0) {
                    ByteBuffer eb = enc.getOutputBuffer(eo);
                    if (info.size > 0 && eb != null
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        // One sample buffer per encode, grown as needed and reused for
                        // every frame. Allocating a fresh byte[] per frame was pure
                        // garbage: a 10-minute clip meant ~15 000 throwaway arrays.
                        sbuf.read(eb, info.offset, info.size);
                        if (started) {
                            sbuf.write(mux, vTrack, info.size, info.presentationTimeUs, info.flags);
                        } else {
                            pending.add(new Object[]{sbuf.copy(info.size),
                                    info.presentationTimeUs, info.flags});
                        }
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    enc.releaseOutputBuffer(eo, false);
                    if (eos) encDone = true;
                    if (p != null && durUs > 0) {
                        float f = Math.min(1f, info.presentationTimeUs / (float) durUs);
                        p.on(base + 0.08f + (0.82f - attempt * 0.03f) * f, 0);
                    }
                    progressed = true;
                }

                // Guard against a codec that never returns anything.
                if (progressed) stall = 0;
                else if (++stall > 400) throw new IllegalStateException("Video codec stalled");
            }

            if (!started) throw new IllegalStateException("Encoder produced no output format");
            mux.stop();
            mux.release();
            mux = null;
            return true;
        } finally {
            if (mux != null) {
                try { mux.stop(); } catch (Exception ignored) { }
                try { mux.release(); } catch (Exception ignored) { }
            }
            if (dec != null) {
                try { dec.stop(); } catch (Exception ignored) { }
                try { dec.release(); } catch (Exception ignored) { }
            }
            if (enc != null) {
                try { enc.stop(); } catch (Exception ignored) { }
                try { enc.release(); } catch (Exception ignored) { }
            }
            if (surface != null) surface.release();
            if (bag != null) bag.close();
            src.close();
        }
    }

    /**
     * A single reusable sample buffer for the encode loop.
     *
     * The muxer copies what it is handed, so one array can carry every frame: it is
     * grown only when a frame is bigger than anything seen so far, and the ByteBuffer
     * wrapper is rebuilt only when the array itself is replaced.
     */
    private static final class SampleBuf {
        private byte[] data = new byte[0];
        private ByteBuffer bb;
        private final MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();

        void read(ByteBuffer src, int offset, int size) {
            if (size > data.length) {
                int cap = Math.max(size, Math.max(1 << 16, data.length * 2));
                data = new byte[cap];
                bb = ByteBuffer.wrap(data);
            }
            src.position(offset);
            src.limit(offset + size);
            src.get(data, 0, size);
        }

        void write(MediaMuxer mux, int track, int size, long pts, int flags) {
            bb.position(0);
            bb.limit(size);
            bi.set(0, size, pts, flags & ~MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
            mux.writeSampleData(track, bb, bi);
        }

        byte[] copy(int size) {
            return java.util.Arrays.copyOf(data, size);
        }
    }

    private static void write(MediaMuxer mux, int track, byte[] data, long pts, int flags) {
        ByteBuffer bb = ByteBuffer.wrap(data);
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        bi.set(0, data.length, pts, flags & ~MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
        mux.writeSampleData(track, bb, bi);
    }

    private static int even(long v) {
        int i = (int) Math.max(2, v);
        return i - (i % 2);
    }

    static long duration(MediaFormat vf, long fileSize, int srcRate) {
        long d = longOr(vf, MediaFormat.KEY_DURATION, 0);
        if (d <= 0 && fileSize > 0 && srcRate > 0) {
            d = (long) (fileSize * 8.0 / srcRate * 1_000_000L);
        }
        if (d <= 0) d = 60_000_000L;
        return d;
    }

    static int intOr(MediaFormat f, String key, int def) {
        try {
            if (!f.containsKey(key)) return def;
            return f.getInteger(key);
        } catch (Exception e) {
            try { return Math.round(f.getFloat(key)); } catch (Exception e2) { return def; }
        }
    }

    static long longOr(MediaFormat f, String key, long def) {
        try {
            if (!f.containsKey(key)) return def;
            return f.getLong(key);
        } catch (Exception e) {
            try { return f.getInteger(key); } catch (Exception e2) { return def; }
        }
    }

    static final class Src {
        MediaExtractor ex;
        ParcelFileDescriptor pfd;
        int vIdx = -1, aIdx = -1;
        MediaFormat vf, af;

        static Src open(Context ctx, Uri uri) throws Exception {
            Src s = new Src();
            s.ex = new MediaExtractor();
            // A file descriptor is far more reliable than a content URI on SAF
            // providers (Downloads, Drive, SD cards) — this is what broke big files.
            try {
                s.pfd = ctx.getContentResolver().openFileDescriptor(uri, "r");
            } catch (Exception e) {
                s.pfd = null;
            }
            if (s.pfd != null) {
                s.ex.setDataSource(s.pfd.getFileDescriptor());
            } else {
                s.ex.setDataSource(ctx, uri, null);
            }
            for (int i = 0; i < s.ex.getTrackCount(); i++) {
                MediaFormat f = s.ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m == null) continue;
                if (m.startsWith("video/") && s.vIdx < 0) {
                    s.vIdx = i;
                    s.vf = f;
                } else if (m.startsWith("audio/") && s.aIdx < 0) {
                    s.aIdx = i;
                    s.af = f;
                }
            }
            return s;
        }

        void close() {
            try { ex.release(); } catch (Exception ignored) { }
            try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
        }
    }
}
