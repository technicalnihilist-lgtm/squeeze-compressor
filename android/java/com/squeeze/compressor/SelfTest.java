package com.squeeze.compressor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.util.Range;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The self-test.
 *
 * Answers one question — "does this actually compress anything, or is it a pretty
 * shell?" — without asking the user to pick a file first. It builds its own input:
 *
 *   1. a synthetic H.264 + AAC clip, encoded on this device with MediaCodec,
 *   2. a synthetic 2400x1600 test image,
 *   3. a synthetic multi-page PDF with uncompressed content streams,
 *
 * then pushes all three through the exact same Engine.compress path a real file takes,
 * with the same 20 MB target, and reports the real byte counts, the codec that did
 * the work and the wall-clock time. Nothing is faked: if a step cannot run, the row
 * says so and the whole card fails.
 *
 * It also lists every encoder the device reports (H.264 / HEVC / AAC), so a phone
 * with no HEVC encoder is visible instead of mysterious.
 */
final class SelfTest {

    /** The target the self-test compresses to — the same 20 MB a user can pick. */
    static final long TARGET = 20L * 1024 * 1024;

    private static final int SR = 44100;
    private static final int A_BITRATE = 96000;
    private static final int FPS = 24;
    private static final int CW = 640;
    private static final int CH = 360;

    interface Step {
        void on(String label, int pct);
    }

    /* ------------------------------------------------------------------ */
    /*  encoder inventory                                                 */
    /* ------------------------------------------------------------------ */

    static JSONObject inventory() {
        JSONObject o = new JSONObject();
        try {
            o.put("h264", encodersFor("video/avc"));
            o.put("hevc", encodersFor("video/hevc"));
            o.put("aac", encodersFor("audio/mp4a-latm"));
        } catch (Exception ignored) { }
        return o;
    }

    private static JSONArray encodersFor(String mime) {
        JSONArray arr = new JSONArray();
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
            for (MediaCodecInfo ci : list.getCodecInfos()) {
                if (!ci.isEncoder()) continue;
                boolean has = false;
                for (String t : ci.getSupportedTypes()) {
                    if (t != null && t.equalsIgnoreCase(mime)) { has = true; break; }
                }
                if (!has) continue;
                JSONObject e = new JSONObject();
                e.put("name", ci.getName());
                e.put("hw", ci.isHardwareAccelerated());
                arr.put(e);
            }
        } catch (Exception ignored) { }
        return arr;
    }

    private static String firstEncoder(String mime) {
        try {
            JSONArray a = encodersFor(mime);
            if (a.length() == 0) return null;
            return a.getJSONObject(0).optString("name", null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String label(JSONArray a) {
        if (a == null || a.length() == 0) return "not available";
        StringBuilder sb = new StringBuilder();
        int n = Math.min(a.length(), 2);
        for (int i = 0; i < n; i++) {
            try {
                JSONObject e = a.getJSONObject(i);
                if (sb.length() > 0) sb.append(" · ");
                sb.append(e.optString("name", "?"));
                sb.append(e.optBoolean("hw", false) ? " (hw)" : " (sw)");
            } catch (Exception ignored) { }
        }
        if (a.length() > n) sb.append(" +").append(a.length() - n);
        return sb.toString();
    }

    /* ------------------------------------------------------------------ */
    /*  the run                                                           */
    /* ------------------------------------------------------------------ */

    static JSONObject run(Context ctx, Step step) {
        long t0 = System.currentTimeMillis();
        JSONObject res = new JSONObject();
        JSONArray rows = new JSONArray();

        JSONObject codecs = inventory();
        try {
            res.put("codecs", codecs);
            res.put("device", Build.MANUFACTURER + " " + Build.MODEL);
            res.put("sdk", Build.VERSION.SDK_INT);
            res.put("target", TARGET);
        } catch (Exception ignored) { }

        JSONArray h264 = codecs.optJSONArray("h264");
        JSONArray hevc = codecs.optJSONArray("hevc");
        JSONArray aac = codecs.optJSONArray("aac");

        step.on("Checking encoders", 4);
        row(rows, "H.264 encoder", (h264 != null && h264.length() > 0) ? "pass" : "fail",
                label(h264));
        row(rows, "AAC encoder", (aac != null && aac.length() > 0) ? "pass" : "fail",
                label(aac));
        row(rows, "HEVC encoder", (hevc != null && hevc.length() > 0) ? "pass" : "warn",
                hevc != null && hevc.length() > 0 ? label(hevc) : "not on this device — H.264 is used instead");

        /* ---- 1. build the synthetic clip ---- */
        File clip = null;
        File vidOut = null;
        File imgIn = null;
        File imgOut = null;
        try {
            step.on("Generating test video", 8);
            int rate = chooseBitrate();
            int secs = Math.max(4, Math.min(12, Math.round(24f * 1024 * 1024 * 8 / rate)));
            clip = File.createTempFile("sqselftest", ".mp4", Engine.workDir(ctx));
            long g0 = System.currentTimeMillis();
            makeClip(ctx, clip, secs, rate);
            long genMs = System.currentTimeMillis() - g0;

            String[] probe = probe(clip);
            boolean clipOk = probe != null && probe[0] != null && clip.length() > 0;
            row(rows, "Synthetic H.264+AAC clip", clipOk ? "pass" : "fail",
                    clipOk ? Engine.mb(clip.length()) + " · " + probe[2] + " · built in "
                            + genMs + " ms" : "encoder produced nothing readable");

            /* ---- 2. push it through the real engine ---- */
            if (clipOk) {
                step.on("Compressing video to 20 MB", 40);
                Engine.Job job = new Engine.Job();
                job.name = "squeeze_selftest.mp4";
                job.uri = Uri.fromFile(clip);
                job.size = clip.length();
                job.target = TARGET;
                job.mode = "smallest";   // force a real re-encode instead of the "already fits" copy
                job.maxDim = 1920;
                job.selfTest = true;

                final int base = 40;
                Engine.Progress p = new Engine.Progress() {
                    public void on(float f, long out) {
                        step.on("Compressing video to 20 MB", base + Math.round(f * 35f));
                    }
                };

                long c0 = System.currentTimeMillis();
                Engine.Result r = null;
                String err = null;
                try {
                    r = Engine.compress(ctx, job, p);
                } catch (Throwable t) {
                    err = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                }
                long ms = System.currentTimeMillis() - c0;

                if (r == null || !r.ok) {
                    row(rows, "Video compress", "fail",
                            err != null ? err : (r != null && r.error != null ? r.error : "engine returned nothing"));
                } else {
                    vidOut = r.file;
                    long out = r.out > 0 ? r.out : (vidOut != null ? vidOut.length() : 0);
                    boolean shrank = out > 0 && out < job.size;
                    String[] op = vidOut != null ? probe(vidOut) : null;
                    boolean readable = op != null && op[0] != null;

                    row(rows, "Video compress", (shrank && readable) ? "pass" : "fail",
                            Engine.mb(job.size) + " → " + Engine.mb(out)
                                    + " · " + (r.codec != null ? r.codec : "?")
                                    + (op != null && op[0] != null ? " · " + op[2] : "")
                                    + " · " + ms + " ms");
                    row(rows, "Output is a playable MP4", readable ? "pass" : "fail",
                            readable ? op[1] + " · " + op[2] : "output could not be reopened");

                    if (out > 0) {
                        res.put("inBytes", job.size);
                        res.put("outBytes", out);
                        res.put("codec", r.codec != null ? r.codec : "?");
                        res.put("ms", ms);
                        res.put("savedPct", Math.round((1 - out / (double) job.size) * 100));
                        if (op != null && op[0] != null) res.put("dims", op[2]);
                    }
                }
            }
        } catch (Throwable t) {
            row(rows, "Synthetic H.264+AAC clip", "fail",
                    t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        }

        /* ---- 3. the image ---- */
        try {
            step.on("Compressing test image", 78);
            imgIn = File.createTempFile("sqselftest", ".png", Engine.workDir(ctx));
            makeImage(imgIn);

            Engine.Job job = new Engine.Job();
            job.name = "squeeze_selftest.png";
            job.uri = Uri.fromFile(imgIn);
            job.size = imgIn.length();
            job.target = TARGET;
            job.mode = "balanced";
            job.maxDim = 1920;
            job.selfTest = true;

            long c0 = System.currentTimeMillis();
            Engine.Result r = null;
            String err = null;
            try {
                r = Engine.compress(ctx, job, null);
            } catch (Throwable t) {
                err = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            }
            long ms = System.currentTimeMillis() - c0;

            if (r == null || !r.ok) {
                row(rows, "Image compress", "fail",
                        err != null ? err : (r != null && r.error != null ? r.error : "engine returned nothing"));
            } else {
                imgOut = r.file;
                long out = r.out > 0 ? r.out : (imgOut != null ? imgOut.length() : 0);
                String dims = "";
                try {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(imgOut.getAbsolutePath(), o);
                    if (o.outWidth > 0) dims = o.outWidth + "×" + o.outHeight;
                } catch (Exception ignored) { }
                boolean shrank = out > 0 && out < job.size;
                row(rows, "Image compress", (shrank && dims.length() > 0) ? "pass" : "fail",
                        Engine.mb(job.size) + " → " + Engine.mb(out)
                                + " · " + (r.codec != null ? r.codec : "?")
                                + (dims.length() > 0 ? " · " + dims : "")
                                + " · " + ms + " ms");
            }
        } catch (Throwable t) {
            row(rows, "Image compress", "fail",
                    t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        }

        /* ---- 4. the PDF ---- */
        File pdfIn = null;
        File pdfOut = null;
        try {
            step.on("Compressing test PDF", 88);
            pdfIn = File.createTempFile("sqselftest", ".pdf", Engine.workDir(ctx));
            byte[] doc = Pdf.sample(12);
            FileOutputStream pfo = new FileOutputStream(pdfIn);
            try { pfo.write(doc); } finally { pfo.close(); }

            Engine.Job job = new Engine.Job();
            job.name = "squeeze_selftest.pdf";
            job.uri = Uri.fromFile(pdfIn);
            job.size = pdfIn.length();
            job.target = TARGET;
            job.selfTest = true;

            long c0 = System.currentTimeMillis();
            Engine.Result r = null;
            String err = null;
            try {
                r = Engine.compress(ctx, job, null);
            } catch (Throwable t) {
                err = Fault.explain(t, job);
            }
            long ms = System.currentTimeMillis() - c0;

            if (r == null || !r.ok) {
                row(rows, "PDF compress", "fail",
                        err != null ? err : (r != null && r.error != null ? r.error : "engine returned nothing"));
            } else {
                pdfOut = r.file;
                long out = r.out > 0 ? r.out : (pdfOut != null ? pdfOut.length() : 0);
                boolean shrank = out > 0 && out < job.size;
                boolean valid = pdfOut != null && looksLikePdf(pdfOut);
                row(rows, "PDF compress", (shrank && valid) ? "pass" : "fail",
                        Engine.mb(job.size) + " → " + Engine.mb(out)
                                + " · " + (r.note != null ? r.note : (r.codec != null ? r.codec : "?"))
                                + " · " + ms + " ms");
                row(rows, "Output is a valid PDF", valid ? "pass" : "fail",
                        valid ? "header, cross-reference table and %%EOF all present"
                                : "the rewritten file could not be re-read");
            }
        } catch (Throwable t) {
            row(rows, "PDF compress", "fail", Fault.explain(t, null));
        }

        /* ---- 5. verdict ---- */
        int fails = 0;
        try {
            for (int i = 0; i < rows.length(); i++) {
                if ("fail".equals(rows.getJSONObject(i).optString("status"))) fails++;
            }
            res.put("rows", rows);
            res.put("ok", fails == 0);
            res.put("fails", fails);
            res.put("totalMs", System.currentTimeMillis() - t0);
        } catch (Exception ignored) { }

        step.on("Done", 100);

        delete(clip);
        delete(vidOut);
        delete(imgIn);
        delete(imgOut);
        delete(pdfIn);
        delete(pdfOut);
        return res;
    }

    /** A rewritten PDF is only useful if a reader can still find its cross-reference. */
    private static boolean looksLikePdf(File f) {
        if (f == null || f.length() < 64) return false;
        byte[] head = new byte[8];
        byte[] tail = new byte[(int) Math.min(2048, f.length())];
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(f, "r");
            raf.readFully(head);
            raf.seek(f.length() - tail.length);
            raf.readFully(tail);
        } catch (Exception e) {
            return false;
        } finally {
            if (raf != null) try { raf.close(); } catch (Exception ignored) { }
        }
        String h = new String(head, java.nio.charset.StandardCharsets.ISO_8859_1);
        String t = new String(tail, java.nio.charset.StandardCharsets.ISO_8859_1);
        return h.startsWith("%PDF") && t.contains("%%EOF") && t.contains("startxref");
    }

    private static void row(JSONArray rows, String label, String status, String detail) {
        try {
            JSONObject o = new JSONObject();
            o.put("label", label);
            o.put("status", status);
            o.put("detail", detail);
            rows.put(o);
        } catch (Exception ignored) { }
    }

    private static void delete(File f) {
        if (f != null) {
            try { f.delete(); } catch (Exception ignored) { }
        }
    }

    /** {mime, "video 640x360 + audio", "640×360 · 8.0 s"} or null when unreadable. */
    private static String[] probe(File f) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            String vMime = null;
            int vc = 0, ac = 0, w = 0, h = 0;
            long dur = 0;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat mf = ex.getTrackFormat(i);
                String m = mf.getString(MediaFormat.KEY_MIME);
                if (m == null) continue;
                if (m.startsWith("video/")) {
                    vc++;
                    vMime = m;
                    w = VideoCompressor.intOr(mf, MediaFormat.KEY_WIDTH, w);
                    h = VideoCompressor.intOr(mf, MediaFormat.KEY_HEIGHT, h);
                } else if (m.startsWith("audio/")) {
                    ac++;
                }
                dur = Math.max(dur, VideoCompressor.longOr(mf, MediaFormat.KEY_DURATION, 0));
            }
            if (vc == 0) return null;
            String tracks = "video" + (ac > 0 ? " + audio" : " only");
            String detail = (w > 0 ? w + "×" + h : vMime)
                    + (dur > 0 ? " · " + String.format(java.util.Locale.US, "%.1f s", dur / 1_000_000.0) : "");
            return new String[]{vMime, tracks, detail};
        } catch (Exception e) {
            return null;
        } finally {
            try { ex.release(); } catch (Exception ignored) { }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  synthetic clip                                                    */
    /* ------------------------------------------------------------------ */

    /** Bitrate that actually fills the target: a big number, clamped to what the codec allows. */
    private static int chooseBitrate() {
        int want = 24_000_000;
        try {
            MediaCodecInfo ci = encoderInfo("video/avc");
            if (ci != null) {
                Range<Integer> r = ci.getCapabilitiesForType("video/avc")
                        .getVideoCapabilities().getBitrateRange();
                want = Math.max(r.getLower(), Math.min(want, r.getUpper()));
            }
        } catch (Exception ignored) { }
        return want;
    }

    private static MediaCodecInfo encoderInfo(String mime) {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
            for (MediaCodecInfo ci : list.getCodecInfos()) {
                if (!ci.isEncoder()) continue;
                for (String t : ci.getSupportedTypes()) {
                    if (t != null && t.equalsIgnoreCase(mime)) return ci;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * Encode a real H.264 + AAC MP4 on this device. Video goes in as raw YUV through
     * MediaCodec's flexible-YUV image path (no EGL, no GL surface), audio as a 440 Hz
     * tone turned into AAC. Both tracks are muxed into one playable file.
     */
    static void makeClip(Context ctx, File out, int secs, int rate) throws Exception {
        List<byte[]> aData = new ArrayList<>();
        List<Long> aPts = new ArrayList<>();
        MediaFormat aFmt = encodeAudio(tone(secs), aData, aPts);
        if (aFmt == null || aData.isEmpty()) throw new IllegalStateException("AAC encoder produced nothing");

        MediaMuxer mux = new MediaMuxer(out.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        MediaCodec enc = null;
        try {
            enc = openVideoEncoder(rate, true);
            if (enc == null) enc = openVideoEncoder(rate, false);
            if (enc == null) throw new IllegalStateException("No usable H.264 encoder");
            enc.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int frames = Math.max(1, secs * FPS);
            int vTrack = -1, aTrack = -1;
            boolean started = false, eos = false;
            List<Object[]> pending = new ArrayList<>();
            int submitted = 0;
            int guard = 0;

            while (true) {
                if (!eos && submitted <= frames) {
                    int i = enc.dequeueInputBuffer(20000);
                    if (i >= 0) {
                        if (submitted < frames) {
                            Image img = enc.getInputImage(i);
                            if (img == null) {
                                throw new IllegalStateException(
                                        "encoder exposes no flexible-YUV input on this device");
                            }
                            fillYuv(img, CW, CH, submitted);
                            enc.queueInputBuffer(i, 0, CW * CH * 3 / 2,
                                    submitted * 1_000_000L / FPS, 0);
                        } else {
                            enc.queueInputBuffer(i, 0, 0, frames * 1_000_000L / FPS,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            eos = true;
                        }
                        submitted++;
                    }
                }

                int o = enc.dequeueOutputBuffer(info, 20000);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    vTrack = mux.addTrack(enc.getOutputFormat());
                    aTrack = mux.addTrack(aFmt);
                    mux.start();
                    started = true;
                    for (int k = 0; k < aData.size(); k++) {
                        writeSample(mux, aTrack, aData.get(k), aPts.get(k), 0);
                    }
                    for (Object[] b : pending) {
                        writeSample(mux, vTrack, (byte[]) b[0], (Long) b[1], (Integer) b[2]);
                    }
                    pending.clear();
                } else if (o >= 0) {
                    boolean end = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        ByteBuffer ob = enc.getOutputBuffer(o);
                        if (ob != null) {
                            byte[] b = new byte[info.size];
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            ob.get(b);
                            if (started) writeSample(mux, vTrack, b, info.presentationTimeUs, info.flags);
                            else pending.add(new Object[]{b, info.presentationTimeUs, info.flags});
                        }
                    }
                    enc.releaseOutputBuffer(o, false);
                    if (end) break;
                }
                if (++guard > 400000) throw new IllegalStateException("Video encoder stalled");
            }
            if (!started) throw new IllegalStateException("Video encoder produced no output");
        } finally {
            if (enc != null) {
                try { enc.stop(); } catch (Exception ignored) { }
                try { enc.release(); } catch (Exception ignored) { }
            }
            try { mux.stop(); } catch (Exception ignored) { }
            try { mux.release(); } catch (Exception ignored) { }
        }
    }

    private static MediaCodec openVideoEncoder(int rate, boolean cbr) {
        MediaCodec c = null;
        try {
            MediaFormat ef = MediaFormat.createVideoFormat("video/avc", CW, CH);
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, rate);
            ef.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            if (cbr) {
                ef.setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            }
            c = MediaCodec.createEncoderByType("video/avc");
            c.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            return c;
        } catch (Exception e) {
            if (c != null) {
                try { c.release(); } catch (Exception ignored) { }
            }
            return null;
        }
    }

    /** Paint a moving pattern into the encoder's YUV input image, honouring its strides. */
    private static void fillYuv(Image img, int w, int h, int f) {
        Image.Plane[] ps = img.getPlanes();
        if (ps.length < 1) return;

        Image.Plane y = ps[0];
        ByteBuffer yb = y.getBuffer();
        int yr = y.getRowStride(), yp = Math.max(1, y.getPixelStride());
        byte[] row = new byte[Math.min(w * yp, Math.max(1, yb.capacity()))];
        for (int r = 0; r < h; r++) {
            int base = r * yr;
            if (base >= yb.capacity()) break;
            int n = Math.min(row.length, yb.capacity() - base);
            for (int c = 0, k = 0; c < w && k < n; c++, k += yp) {
                row[k] = (byte) (((c * 2 + r + f * 6) & 0x7F) + 40);
            }
            yb.position(base);
            yb.put(row, 0, n);
        }

        int cw = (w + 1) / 2, ch = (h + 1) / 2;
        for (int p = 1; p < 3 && p < ps.length; p++) {
            Image.Plane cp = ps[p];
            ByteBuffer cb = cp.getBuffer();
            int cr = cp.getRowStride(), cpp = Math.max(1, cp.getPixelStride());
            byte[] crow = new byte[Math.min(cw * cpp, Math.max(1, cb.capacity()))];
            for (int r = 0; r < ch; r++) {
                int base = r * cr;
                if (base >= cb.capacity()) break;
                int n = Math.min(crow.length, cb.capacity() - base);
                for (int c = 0, k = 0; c < cw && k < n; c++, k += cpp) {
                    crow[k] = (byte) (128 + ((c * 3 + r * 5 + f) % 40) - 20);
                }
                cb.position(base);
                cb.put(crow, 0, n);
            }
        }
    }

    private static void writeSample(MediaMuxer mux, int track, byte[] data, long pts, int flags) {
        ByteBuffer bb = ByteBuffer.wrap(data);
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        bi.set(0, data.length, pts, flags & ~MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
        mux.writeSampleData(track, bb, bi);
    }

    private static byte[] tone(int secs) {
        int n = SR * Math.max(1, secs);
        byte[] b = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            double t = i / (double) SR;
            double v = Math.sin(2 * Math.PI * 440 * t) * 0.35
                    + Math.sin(2 * Math.PI * 660 * t) * 0.15;
            short s = (short) (v * Short.MAX_VALUE);
            b[i * 2] = (byte) (s & 0xFF);
            b[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return b;
    }

    private static MediaFormat encodeAudio(byte[] pcm, List<byte[]> data, List<Long> pts) throws Exception {
        MediaFormat f = MediaFormat.createAudioFormat("audio/mp4a-latm", SR, 1);
        f.setInteger(MediaFormat.KEY_BIT_RATE, A_BITRATE);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        MediaCodec enc = MediaCodec.createEncoderByType("audio/mp4a-latm");
        MediaFormat outFmt = null;
        try {
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int offset = 0;
            boolean inputDone = false, done = false;
            int guard = 0;

            while (!done) {
                if (!inputDone) {
                    int i = enc.dequeueInputBuffer(10000);
                    if (i >= 0) {
                        ByteBuffer ib = enc.getInputBuffer(i);
                        ib.clear();
                        int chunk = Math.min(ib.remaining(), pcm.length - offset);
                        if (chunk <= 0) {
                            enc.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            ib.put(pcm, offset, chunk);
                            long ptsUs = (offset / 2L) * 1_000_000L / SR;
                            enc.queueInputBuffer(i, 0, chunk, ptsUs, 0);
                            offset += chunk;
                        }
                    }
                }

                int o = enc.dequeueOutputBuffer(info, 10000);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFmt = enc.getOutputFormat();
                } else if (o >= 0) {
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        ByteBuffer ob = enc.getOutputBuffer(o);
                        if (ob != null) {
                            byte[] b = new byte[info.size];
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            ob.get(b);
                            data.add(b);
                            pts.add(info.presentationTimeUs);
                        }
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true;
                    enc.releaseOutputBuffer(o, false);
                }
                if (++guard > 200000) throw new IllegalStateException("Audio encoder stalled");
            }
        } finally {
            try { enc.stop(); } catch (Exception ignored) { }
            try { enc.release(); } catch (Exception ignored) { }
        }
        return outFmt;
    }

    /* ------------------------------------------------------------------ */
    /*  synthetic image                                                   */
    /* ------------------------------------------------------------------ */

    /** 2400x1600 PNG: gradients, shapes and text, so it is a real photo-sized decode job. */
    static void makeImage(File out) throws Exception {
        int w = 2400, h = 1600;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        p.setShader(new LinearGradient(0, 0, w, h,
                new int[]{0xFF07101F, 0xFF123A5A, 0xFF2E7D8F, 0xFF5EEAD4},
                null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        Random rnd = new Random(7);
        for (int i = 0; i < 90; i++) {
            p.setColor(Color.argb(40 + rnd.nextInt(90), rnd.nextInt(255),
                    rnd.nextInt(255), rnd.nextInt(255)));
            float x = rnd.nextFloat() * w, y = rnd.nextFloat() * h;
            float r = 40 + rnd.nextFloat() * 260;
            if (i % 2 == 0) c.drawCircle(x, y, r, p);
            else c.drawRoundRect(new RectF(x - r, y - r * 0.6f, x + r, y + r * 0.6f), 40, 40, p);
        }

        for (int i = 0; i < 40; i++) {
            p.setColor(Color.argb(70, 255, 255, 255));
            p.setStrokeWidth(2 + rnd.nextInt(6));
            c.drawLine(rnd.nextFloat() * w, rnd.nextFloat() * h,
                    rnd.nextFloat() * w, rnd.nextFloat() * h, p);
        }

        p.setColor(Color.WHITE);
        p.setTextSize(150);
        p.setFakeBoldText(true);
        c.drawText("SQUEEZE SELF-TEST", 120, h * 0.5f, p);
        p.setTextSize(74);
        p.setColor(0xFFBFE9FF);
        c.drawText("synthetic 2400 × 1600 source", 124, h * 0.5f + 110, p);

        FileOutputStream fo = new FileOutputStream(out);
        try {
            b.compress(Bitmap.CompressFormat.PNG, 100, fo);
            fo.flush();
        } finally {
            try { fo.close(); } catch (Exception ignored) { }
        }
        b.recycle();
    }
}
