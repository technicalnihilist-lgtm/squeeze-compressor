package com.squeeze.compressor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Photo / image compression.
 *
 * The source is decoded <b>once</b>, at the largest size the ladder will use, and
 * every later attempt is an encode of the same pixels — either at a lower quality or
 * after one in-place downscale of a single reusable working bitmap. The previous
 * version re-opened and re-decoded the file for every quality step, which was the
 * single largest cost in a photo batch: on a 12 MP JPEG a decode is worth roughly
 * five encodes, and eight iterations meant eight of them.
 *
 * Alongside that:
 *
 *  - A file that already fits the target and is already inside the resolution cap is
 *    copied untouched without decoding a single pixel ({@link Plan#imageAlreadyFits}).
 *  - One working bitmap is reused across the quality passes at a given size; a new
 *    one is allocated only when the ladder changes size, so peak memory is the base
 *    decode plus one frame, never one bitmap per attempt.
 *  - Trial files are measured by length after a plain flush — no fsync per attempt,
 *    which used to cost more than the encode on slow storage.
 *
 * In lossless mode the pixels are never touched: the image is re-encoded losslessly
 * at full resolution in a single pass.
 */
final class ImageCompressor {

    /** Reusable scratch for the downscale pass — one allocation per job, not per step. */
    private static final class Scaler {
        private Bitmap bmp;
        private Canvas canvas;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

        /** Returns the source pixels at (at most) {@code dim} on the long side. */
        Bitmap at(Bitmap base, int dim) {
            int bw = base.getWidth(), bh = base.getHeight();
            int longest = Math.max(bw, bh);
            if (longest <= dim) return base;

            float k = dim / (float) longest;
            int w = Math.max(1, Math.round(bw * k));
            int h = Math.max(1, Math.round(bh * k));

            if (bmp == null || bmp.isRecycled() || bmp.getWidth() != w || bmp.getHeight() != h) {
                if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                canvas = new Canvas(bmp);
            }
            // Draw the full source into the reused buffer; no per-step allocation.
            canvas.drawBitmap(base, new Rect(0, 0, bw, bh), new Rect(0, 0, w, h), paint);
            return bmp;
        }

        void close() {
            if (bmp != null && !bmp.isRecycled()) bmp.recycle();
            bmp = null;
            canvas = null;
        }
    }

    static Engine.Result run(Context ctx, Engine.Job job, Engine.Progress p) throws Exception {
        String e = Engine.ext(job.name);
        if (e.equals("gif")) {
            return Engine.copyRaw(ctx, job, p, "Animated GIF kept as-is to preserve the animation");
        }

        // Bounds only: this reads the header, it does not decode pixels.
        int[] bounds = bounds(ctx, job.uri);
        if (bounds == null) return null;
        int sw = bounds[0], sh = bounds[1];
        int longest = Math.max(sw, sh);
        int cap = Math.max(320, job.maxDim);
        boolean lossless = "lossless".equals(job.mode);

        // Fast exit: nothing to gain, so no decoder is ever opened for the heavy path.
        if (Plan.imageAlreadyFits(job.size, job.target, longest, cap, lossless)) {
            return Engine.copyRaw(ctx, job, p,
                    "Already fits " + Engine.mb(job.target) + " at this resolution — not re-encoded");
        }

        int dim0 = lossless ? longest : Math.min(cap, longest);
        Bitmap base = decodeAt(ctx, job.uri, dim0, sw, sh);
        if (base == null) return null;

        Scaler scaler = new Scaler();
        File best = Engine.tempFile(ctx, ".img");
        File trial = Engine.tempFile(ctx, ".img");
        long bestSize = Long.MAX_VALUE;
        int bestDim = 0, bestQ = 0;
        Bitmap.CompressFormat fmt = formatFor(lossless, base.hasAlpha());

        int startQ = lossless ? 100 : ("smallest".equals(job.mode) ? 58 : 82);
        int[] dims = Plan.dims(longest, cap, lossless);
        int[] quals = Plan.qualities(startQ, lossless);
        int totalSteps = Plan.steps(dims.length, quals.length);
        int step = 0;
        boolean done = false;

        try {
            for (int di = 0; di < dims.length && !done; di++) {
                Bitmap work = scaler.at(base, dims[di]);
                for (int qi = 0; qi < quals.length; qi++) {
                    int q = quals[qi];
                    long sz = encode(work, fmt, q, trial);
                    step++;
                    if (p != null) {
                        float f = 0.15f + 0.7f * (step / (float) totalSteps);
                        p.on(f, sz > 0 ? sz : 0);
                    }
                    if (sz <= 0) { done = true; break; }

                    if (sz < bestSize) {
                        bestSize = sz;
                        File swap = best;
                        best = trial;
                        trial = swap;
                        bestDim = Math.max(work.getWidth(), work.getHeight());
                        bestQ = q;
                    }
                    // Fits the target, or lossless where one pass is the whole job.
                    if (lossless || sz <= job.target) { done = true; break; }
                }
            }
        } finally {
            scaler.close();
            if (!base.isRecycled()) base.recycle();
        }

        if (bestSize == Long.MAX_VALUE || bestSize <= 0) {
            best.delete();
            trial.delete();
            return null;
        }

        // Never hand back something bigger than what we started with.
        if (job.size > 0 && bestSize >= job.size) {
            best.delete();
            trial.delete();
            return Engine.copyRaw(ctx, job, p, "Already optimal at this size");
        }

        String suffix = fmt == Bitmap.CompressFormat.PNG ? ".png"
                : (fmt == Bitmap.CompressFormat.JPEG ? ".jpg" : ".webp");
        String outName = Engine.base(job.name) + "_squeezed" + suffix;

        Engine.Result r = new Engine.Result();
        Engine.publishTemp(ctx, job, best, outName, Engine.mimeOf(outName), p, r);
        r.codec = suffix.equals(".png") ? "PNG" : suffix.equals(".webp") ? "WebP" : "JPEG";
        r.note = lossless
                ? "Lossless re-encode"
                : "Visually lossless · " + bestDim + "px · q" + bestQ;
        if (bestSize > job.target) {
            r.note = "Smallest reachable is " + Engine.mb(bestSize);
        }
        best.delete();
        trial.delete();
        return r;
    }

    private static Bitmap.CompressFormat formatFor(boolean lossless, boolean alpha) {
        if (lossless) {
            if (Build.VERSION.SDK_INT >= 30) return Bitmap.CompressFormat.WEBP_LOSSLESS;
            return Bitmap.CompressFormat.PNG;
        }
        if (alpha) {
            if (Build.VERSION.SDK_INT >= 30) return Bitmap.CompressFormat.WEBP_LOSSY;
            return Bitmap.CompressFormat.WEBP;
        }
        return Bitmap.CompressFormat.JPEG;
    }

    /** Encodes into {@code out} and returns its length, or 0 when the encoder refused. */
    private static long encode(Bitmap bmp, Bitmap.CompressFormat fmt, int q, File out) throws Exception {
        FileOutputStream fo = new FileOutputStream(out);
        boolean ok;
        try {
            ok = bmp.compress(fmt, q, fo);
            fo.flush();
        } finally {
            fo.close();
        }
        return ok ? out.length() : 0;
    }

    /** {width, height} from the file header alone — no pixel buffer is allocated. */
    static int[] bounds(Context ctx, Uri uri) throws Exception {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        try {
            BitmapFactory.decodeStream(in, null, probe);
        } finally {
            Engine.closeQuietly(in);
        }
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return null;
        return new int[]{probe.outWidth, probe.outHeight};
    }

    /**
     * The one decode. Sub-samples by a power of two first so a 50 MP source never
     * lands in memory at full size, then scales exactly once to {@code dim}.
     */
    static Bitmap decodeAt(Context ctx, Uri uri, int dim, int sw, int sh) throws Exception {
        int sample = 1;
        while (Math.max(sw, sh) / (sample * 2) >= dim) sample *= 2;

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;

        InputStream in = ctx.getContentResolver().openInputStream(uri);
        Bitmap bmp;
        try {
            bmp = BitmapFactory.decodeStream(in, null, o);
        } finally {
            Engine.closeQuietly(in);
        }
        if (bmp == null) return null;

        int longest = Math.max(bmp.getWidth(), bmp.getHeight());
        if (longest > dim) {
            float s = (float) dim / longest;
            int w = Math.max(1, Math.round(bmp.getWidth() * s));
            int h = Math.max(1, Math.round(bmp.getHeight() * s));
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, w, h, true);
            if (scaled != bmp) bmp.recycle();
            bmp = scaled;
        }
        return bmp;
    }
}
