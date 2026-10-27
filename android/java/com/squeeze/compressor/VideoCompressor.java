package com.squeeze.compressor;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Video compression: decode to a surface, re-encode as H.264 at the bitrate the
 * size target allows, and keep the audio track untouched whenever it is already AAC.
 *
 * If the first pass overshoots the target the whole transcode is retried once with a
 * bitrate scaled by the overshoot, so "fit in 150 MB" is met by measurement, not guesswork.
 */
final class VideoCompressor {

    private static final int TIMEOUT = 12000; // microseconds

    static Engine.Result run(Context ctx, Engine.Job job, Engine.Progress p) throws Exception {
        Src probe = Src.open(ctx, job.uri);
        long durUs;
        int sw, sh, rot, fps, srcRate;
        int aRate = 128000;
        try {
            if (probe.vIdx < 0 || probe.vf == null) return null;
            MediaFormat vf = probe.vf;
            MediaFormat af = probe.af;
            durUs = longOr(vf, MediaFormat.KEY_DURATION, 0);
            sw = intOr(vf, MediaFormat.KEY_WIDTH, 0);
            sh = intOr(vf, MediaFormat.KEY_HEIGHT, 0);
            rot = intOr(vf, MediaFormat.KEY_ROTATION, 0);
            fps = intOr(vf, MediaFormat.KEY_FRAME_RATE, 30);
            srcRate = intOr(vf, MediaFormat.KEY_BIT_RATE, 8_000_000);
            if (af != null) aRate = intOr(af, MediaFormat.KEY_BIT_RATE, 128000);
            if (sw <= 0 || sh <= 0) return null;
        } finally {
            probe.close();
        }

        if (job.size > 0 && job.size <= job.target && !"smallest".equals(job.mode)) {
            return Engine.copyRaw(ctx, job, p, "Already under " + Engine.mb(job.target) + " — copied untouched");
        }

        int cap = Math.max(240, job.maxDim);
        double scale = Math.min(1.0, (double) cap / Math.max(sw, sh));
        int ow = even(Math.round(sw * (float) scale));
        int oh = even(Math.round(sh * (float) scale));
        if (ow < 16 || oh < 16) { ow = even(sw); oh = even(sh); }

        fps = Math.max(12, Math.min(fps, 60));
        double secs = Math.max(1.0, durUs / 1_000_000.0);
        // Audio is either copied or re-encoded at aRate, so reserve exactly that budget.
        long audioBits = (long) (aRate * secs);

        long avail = (long) (job.target * 8 * 0.94) - audioBits;
        int rate = (int) Math.max(150_000, avail / secs);
        rate = Math.min(rate, Math.max(200_000, srcRate));
        rate = Math.min(rate, 20_000_000);

        String outName = Engine.base(job.name) + "_squeezed.mp4";
        int attemptRate = rate;
        long best = -1;

        for (int attempt = 0; attempt < 2; attempt++) {
            Engine.Sink sink = Engine.Sink.create(ctx, outName, "video/mp4");
            try {
                encode(ctx, job, sink, ow, oh, attemptRate, fps, rot, durUs, aRate, p);
            } catch (Exception e) {
                sink.delete();
                if (attempt == 0) return null;
                throw e;
            }
            long out = sink.size();
            best = out;

            boolean fits = out > 0 && out <= job.target;
            if (fits || attempt == 1) {
                if (job.size > 0 && out >= job.size) {
                    sink.delete();
                    return Engine.copyRaw(ctx, job, p, "Source already efficient — kept as-is");
                }
                sink.publish();
                Engine.Result r = new Engine.Result();
                r.ok = true;
                r.out = out;
                r.name = outName;
                r.uri = sink.uri;
                r.note = "H.264 " + ow + "×" + oh + " · " + (attemptRate / 1000) + " kbps";
                return r;
            }

            sink.delete();
            attemptRate = (int) Math.max(120_000, attemptRate * (job.target / (double) out) * 0.92);
        }
        return null;
    }

    private static void encode(Context ctx, Engine.Job job, Engine.Sink sink, int ow, int oh,
                               int vRate, int fps, int rot, long durUs, int aRate,
                               Engine.Progress p) throws Exception {
        Src src = Src.open(ctx, job.uri);
        AudioBag bag = null;
        MediaCodec enc = null, dec = null;
        MediaMuxer mux = null;
        Surface surface = null;
        boolean started = false;

        try {
            if (src.vIdx < 0 || src.vf == null) throw new IllegalStateException("No video track");

            // 1. Collect the audio track first (copied, or re-encoded when not AAC).
            if (src.aIdx >= 0 && src.af != null) {
                try {
                    if (AudioBag.isAac(src.af)) {
                        bag = AudioBag.passthrough(src.ex, src.aIdx, src.af, ctx.getCacheDir(), p, 0.02f, 0.14f);
                    } else {
                        bag = AudioBag.transcode(ctx, src.ex, src.aIdx, src.af,
                                Math.min(aRate, 160000), ctx.getCacheDir(), p, 0.02f, 0.14f);
                    }
                } catch (Exception e) {
                    bag = null; // keep the video even if audio could not be carried over
                }
            }

            // 2. Encoder + decoder over a shared surface.
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

            String vmime = src.vf.getString(MediaFormat.KEY_MIME);
            dec = MediaCodec.createDecoderByType(vmime);
            dec.configure(src.vf, surface, null, 0);
            dec.start();

            mux = new MediaMuxer(sink.fd().getFileDescriptor(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (rot != 0) mux.setOrientationHint(rot);

            int aTrack = -1;
            if (bag != null && bag.count > 0 && bag.fmt != null) aTrack = mux.addTrack(bag.fmt);
            int vTrack = -1;

            src.ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            src.ex.selectTrack(src.vIdx);

            boolean inputDone = false, decDone = false, encDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            ArrayList<Object[]> pending = new ArrayList<>();

            while (!encDone) {
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
                        p.on(0.15f + 0.8f * Math.min(1f, info.presentationTimeUs / (float) durUs), 0);
                    }
                }
            }

            if (!started) throw new IllegalStateException("Encoder produced no output format");
            mux.stop();
            mux.release();
            mux = null;
        } finally {
            if (mux != null) {
                try { mux.stop(); } catch (Exception ignored) { }
                try { mux.release(); } catch (Exception ignored) { }
            }
            if (dec != null) {
                try { dec.stop(); } catch (Exception ignored) { }
                dec.release();
            }
            if (enc != null) {
                try { enc.stop(); } catch (Exception ignored) { }
                enc.release();
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
        int vIdx = -1, aIdx = -1;
        MediaFormat vf, af;

        static Src open(Context ctx, Uri uri) throws Exception {
            Src s = new Src();
            s.ex = new MediaExtractor();
            s.ex.setDataSource(ctx, uri, null);
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
        }
    }
}
