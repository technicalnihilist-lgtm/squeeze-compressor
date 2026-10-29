package com.squeeze.compressor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Photo / image compression.
 *
 * Decodes with sub-sampling so memory stays bounded no matter the source resolution,
 * then steps quality and dimension down until the file fits the target. In lossless
 * mode the pixels are never touched — the image is re-encoded losslessly.
 */
final class ImageCompressor {

    static Engine.Result run(Context ctx, Engine.Job job, Engine.Progress p) throws Exception {
        String e = Engine.ext(job.name);
        if (e.equals("gif")) {
            return Engine.copyRaw(ctx, job, p, "Animated GIF kept as-is to preserve the animation");
        }

        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        InputStream in = ctx.getContentResolver().openInputStream(job.uri);
        try {
            BitmapFactory.decodeStream(in, null, probe);
        } finally {
            Engine.closeQuietly(in);
        }
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return null;

        boolean lossless = "lossless".equals(job.mode);
        int cap = Math.max(320, job.maxDim);
        int longest = Math.max(probe.outWidth, probe.outHeight);
        int dim = lossless ? longest : Math.min(cap, longest);

        int quality = lossless ? 100 : "smallest".equals(job.mode) ? 58 : 82;

        File best = Engine.tempFile(ctx, ".img");
        File trial = Engine.tempFile(ctx, ".img");
        long bestSize = Long.MAX_VALUE;
        Bitmap.CompressFormat fmt = null;

        for (int iter = 0; iter < 8; iter++) {
            Bitmap bmp = decode(ctx, job.uri, dim);
            if (bmp == null) break;
            fmt = formatFor(lossless, bmp.hasAlpha());

            FileOutputStream fo = new FileOutputStream(trial);
            bmp.compress(fmt, quality, fo);
            fo.getFD().sync();
            fo.close();
            bmp.recycle();

            long sz = trial.length();
            if (p != null) p.on(0.2f + 0.65f * (iter / 8f), sz);

            if (sz > 0 && sz < bestSize) {
                bestSize = sz;
                File swap = best;
                best = trial;
                trial = swap;
            } else {
                break;
            }
            if (sz <= job.target || lossless) break;
            quality = Math.max(30, quality - 12);
            dim = Math.max(240, (int) (dim * 0.85));
        }

        if (fmt == null || bestSize == Long.MAX_VALUE || bestSize <= 0) {
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
        r.note = lossless ? "Lossless re-encode" : "Visually lossless";
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

    static Bitmap decode(Context ctx, Uri uri, int dim) throws Exception {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        try {
            BitmapFactory.decodeStream(in, null, probe);
        } finally {
            Engine.closeQuietly(in);
        }
        if (probe.outWidth <= 0) return null;

        int sample = 1;
        while (Math.max(probe.outWidth, probe.outHeight) / (sample * 2) >= dim) sample *= 2;

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        in = ctx.getContentResolver().openInputStream(uri);
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
