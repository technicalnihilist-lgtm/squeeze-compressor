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
 * Two things this does that a naive "pick a bitrate and hope" transcoder does not:
 *
 * 1. It walks a resolution ladder. When the target is very small (20 MB), no bitrate
 *    will get a 1080p file there — the frames themselves cost too much — so it steps
 *    the resolution down (1920 → 854 → 480) and re-tries until it actually fits.
 * 2. It measures. Each attempt produces a real file, gets its size read, and the next
 *    attempt is scaled by the actual overshoot rather than by a guess.
 *
 * If even the smallest rung cannot reach the target, the smallest result is kept and
 * the UI is told the true floor instead of failing silently.
 *
 * Audio is copied bit-for-bit when it is already AAC, so only the picture is touched.
 */
final class VideoCompressor {

    private static final int TIMEOUT = 12000;   // microseconds
    private static final int MAX_ATTEMPTS = 3;

    static Engine.Result run(Context ctx, Engine.Job job, Engine.Progress p) throws Exception {
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
            fps = intOr(vf, MediaFormat.KEY_FRAME_RATE, 30);
            srcRate = intOr(vf, MediaFormat.KEY_BIT_RATE, 8_000_000);
            if (probe.af != null) aRate = intOr(probe.af, MediaFormat.KEY_BIT_RATE, 128000);
            if (sw <= 0 || sh <= 0) return null;
            // Duration is often missing on MKV/AVI — derive it instead of giving up.
            durUs = duration(vf, job.size, srcRate);
        } finally {
            probe.close();
        }

        if (job.size > 0 && job.size <= job.target && !"smallest".equals(job.mode)) {
            return Engine.copyRaw(ctx, job, p, "Already under " + Engine.mb(job.target) + " — copied untouched");
        }

        int cap = Math.max(240, job.maxDim);
        int longest = Math.max(sw, sh);
        double secs = Math.max(0.5, durUs / 1_000_000.0);

        // The target is the boss: work out what it allows, then spend it.
        long targetBits = (long) (job.target * 8 * 0.94);          // 6% for container overhead
        int audioRate = aRate;
        long audioShare = (long) (targetBits * 0.25);              // audio may take at most 25%
        audioRate = (int) Math.min(aRate, Math.max(24000, audioShare / secs));
        long audioBits = (long) (audioRate * secs);
        long videoBits = Math.max((long) (20_000 * secs), targetBits - audioBits);
        int rateNeeded = (int) Math.max(20_000, Math.min(videoBits / secs, srcRate));

        int[] rungs = ladder(longest, cap);
        // Highest resolution whose comfortable bitrate fits the budget; else the lowest rung.
        int startIdx = rungs.length - 1;
        for (int i = 0; i < rungs.length; i++) {
            if (floorFor(rungs[i]) <= rateNeeded) { startIdx = i; break; }
        }

        File best = null;
        long bestSize = Long.MAX_VALUE;
        int bestW = 0, bestH = 0, bestRate = 0;
        long rate = rateNeeded;

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            int dim = rungs[Math.min(startIdx + attempt, rungs.length - 1)];
            double k = dim / (double) longest;
            int ow = even(Math.round(sw * (float) k));
            int oh = even(Math.round(sh * (float) k));
            if (ow < 16 || oh < 16) continue;

            if (bestSize != Long.MAX_VALUE && bestSize > 0) {
                // Scale by the real overshoot from the previous attempt.
                rate = (long) (rate * (job.target / (double) bestSize) * 0.92);
            }
            rate = Math.max(20_000, Math.min(rate, srcRate));

            File tmp = Engine.tempFile(ctx, ".mp4");
            boolean ok;
            try {
                ok = encode(ctx, job, tmp, ow, oh, (int) rate, fps, rot, durUs, audioRate, p, attempt, MAX_ATTEMPTS);
            } catch (Exception e) {
                tmp.delete();
                if (attempt == MAX_ATTEMPTS - 1 && best == null) throw e;
                continue;
            }
            if (!ok || tmp.length() <= 0) {
                tmp.delete();
                continue;
            }

            long sz = tmp.length();
            if (sz < bestSize) {
                if (best != null) best.delete();
                best = tmp;
                bestSize = sz;
                bestW = ow;
                bestH = oh;
                bestRate = (int) rate;
            } else {
                tmp.delete();
            }
            if (bestSize <= job.target) break;
        }

        if (best == null) return null;

        // Never hand back something bigger than what came in.
        if (job.size > 0 && bestSize >= job.size) {
            best.delete();
            return Engine.copyRaw(ctx, job, p, "Source is already more efficient — kept as-is");
        }

        String outName = Engine.base(job.name) + "_squeezed.mp4";
        Engine.Result r = new Engine.Result();
        Engine.publishTemp(ctx, job, best, outName, "video/mp4", p, r);
        r.note = "H.264 " + bestW + "×" + bestH + " · " + (bestRate / 1000) + " kbps";
        if (bestSize > job.target) {
            r.note = "Smallest reachable is " + Engine.mb(bestSize)
                    + " — " + Engine.mb(job.target) + " is below what this footage allows";
        }
        best.delete();
        return r;
    }

    /**
     * Resolution rungs to try, biggest first. Capped at three attempts so a long
     * video does not turn into a half-hour wait.
     */
    private static int[] ladder(int longest, int cap) {
        int start = Math.min(longest, cap);
        start = start - (start % 2);
        int mid = start > 900 ? 854 : start - (start % 2);
        int low = 480;
        if (start <= 480) return new int[]{start};
        if (mid >= start || mid <= low) return new int[]{start, low};
        return new int[]{start, mid, low};
    }

    /** Roughly the lowest bitrate that still looks acceptable at a given width. */
    private static long floorFor(int dim) {
        if (dim >= 1920) return 1_400_000;
        if (dim >= 1280) return 900_000;
        if (dim >= 854) return 500_000;
        if (dim >= 640) return 320_000;
        return 200_000;
    }

    private static boolean encode(Context ctx, Engine.Job job, File out, int ow, int oh,
                                  int vRate, int fps, int rot, long durUs, int aRate,
                                  Engine.Progress p, int attempt, int total) throws Exception {
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

            // Phase 2 — encoder + decoder over a shared surface.
            MediaFormat ef = MediaFormat.createVideoFormat("video/avc", ow, oh);
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, vRate);
            ef.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
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
            if (rot != 0) mux.setOrientationHint(rot);

            int aTrack = -1;
            if (bag != null && bag.count > 0 && bag.fmt != null) aTrack = mux.addTrack(bag.fmt);
            int vTrack = -1;

            src.ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            src.ex.selectTrack(src.vIdx);

            boolean inputDone = false, decDone = false, encDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            ArrayList<Object[]> pending = new ArrayList<>();
            int stall = 0;

            while (!encDone) {
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
                    vTrack = mux.addTrack(enc.getOutputFormat());
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
                        byte[] data = new byte[info.size];
                        eb.position(info.offset);
                        eb.limit(info.offset + info.size);
                        eb.get(data);
                        if (started) write(mux, vTrack, data, info.presentationTimeUs, info.flags);
                        else pending.add(new Object[]{data, info.presentationTimeUs, info.flags});
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
