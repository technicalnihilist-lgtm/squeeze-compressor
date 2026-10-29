package com.squeeze.compressor;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Core of Squeeze: job description, output plumbing and format dispatch.
 *
 * Everything streams. No file is ever loaded fully into memory, so multi-gigabyte
 * inputs take the same code path as small ones.
 *
 * Errors are never swallowed: if a format-specific compressor cannot do the job it
 * returns a Result with ok=false and a readable reason, so the UI can say what went
 * wrong instead of pretending the file was compressed.
 */
final class Engine {

    interface Progress {
        void on(float fraction, long outBytes);
    }

    static final class Job {
        Uri uri;
        String name = "file";
        long size;
        String mode = "balanced";
        long target = 150L * 1024 * 1024;
        int maxDim = 1920;
        boolean keepOriginal = true;
        boolean stripMeta = true;
    }

    static final class Result {
        boolean ok;
        long out;
        String name;
        String note;
        String error;
        Uri uri;
    }

    private static final String IMG = "jpg jpeg png webp heic heif bmp gif tiff avif";
    private static final String VID = "mp4 mkv mov webm avi 3gp m4v ts flv wmv";
    private static final String AUD = "mp3 m4a aac wav flac ogg opus amr";

    static Result fail(String why) {
        Result r = new Result();
        r.ok = false;
        r.error = why;
        return r;
    }

    static String ext(String n) {
        if (n == null) return "";
        int i = n.lastIndexOf('.');
        return i < 0 || i == n.length() - 1 ? "" : n.substring(i + 1).toLowerCase();
    }

    static String base(String n) {
        int i = n.lastIndexOf('.');
        return i <= 0 ? n : n.substring(0, i);
    }

    static String mb(long bytes) {
        if (bytes < 1024L * 1024L) return Math.max(1, bytes / 1024L) + " KB";
        long m = Math.round(bytes / (1024.0 * 1024.0));
        if (m >= 1024) return String.format(java.util.Locale.US, "%.1f GB", m / 1024.0);
        return m + " MB";
    }

    static boolean isMedia(String e) {
        return has(IMG, e) || has(VID, e) || has(AUD, e);
    }

    static Result compress(Context ctx, Job job, Progress p) throws Exception {
        String e = ext(job.name);

        // Refuse up front rather than half-way through a 3 GB transcode.
        long need = Math.max(job.size, job.target);
        long free = freeSpace(ctx);
        if (free > 0 && need > free) {
            return fail("Not enough space — needs about " + mb(need) + ", only " + mb(free) + " free");
        }

        Result r;
        if (has(IMG, e)) {
            r = ImageCompressor.run(ctx, job, p);
            if (r == null) r = fail("This image could not be decoded (corrupt or unsupported)");
        } else if (has(VID, e)) {
            r = VideoCompressor.run(ctx, job, p);
            if (r == null) r = fail("This video could not be re-encoded — no usable video track");
        } else if (has(AUD, e)) {
            r = AudioCompressor.run(ctx, job, p);
            if (r == null) r = fail("This audio file could not be re-encoded");
        } else {
            // Archives, Office documents, PDFs and anything unknown: always lossless.
            r = Archiver.run(ctx, job, p);
            if (r == null) r = fail("Could not read this file");
        }
        return r;
    }

    private static boolean has(String list, String e) {
        return (" " + list + " ").contains(" " + e + " ");
    }

    /** Byte-for-byte copy of the original into the output folder. */
    static Result copyRaw(Context ctx, Job job, Progress p, String note) throws Exception {
        Sink sink = Sink.create(ctx, job.name, mimeOf(job.name));
        try {
            InputStream in = new BufferedInputStream(ctx.getContentResolver().openInputStream(job.uri), 1 << 16);
            OutputStream out = sink.stream();
            copy(in, out, job.size, p);
            out.flush();
            out.close();
            in.close();
        } catch (Exception e) {
            sink.delete();
            throw e;
        }
        sink.publish();
        Result r = new Result();
        r.ok = true;
        r.out = sink.size();
        r.name = job.name;
        r.uri = sink.uri;
        r.note = note;
        return r;
    }

    static long copy(InputStream in, OutputStream out, long total, Progress p) throws Exception {
        byte[] buf = new byte[1 << 16];
        long done = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done += n;
            if (p != null && total > 0) p.on(Math.min(1f, done / (float) total), done);
        }
        return done;
    }

    static String mimeOf(String name) {
        String e = ext(name);
        switch (e) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "heic": case "heif": return "image/heic";
            case "bmp": return "image/bmp";
            case "tiff": return "image/tiff";
            case "mp4": case "m4v": return "video/mp4";
            case "mkv": return "video/x-matroska";
            case "mov": return "video/quicktime";
            case "webm": return "video/webm";
            case "avi": return "video/x-msvideo";
            case "3gp": return "video/3gpp";
            case "mp3": return "audio/mpeg";
            case "m4a": return "audio/mp4";
            case "aac": return "audio/aac";
            case "wav": return "audio/wav";
            case "flac": return "audio/flac";
            case "ogg": case "opus": return "audio/ogg";
            case "pdf": return "application/pdf";
            case "zip": return "application/zip";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "txt": return "text/plain";
            default: return "application/octet-stream";
        }
    }

    /** Scratch space for encoded output before it is published. */
    static File workDir(Context ctx) {
        File d = ctx.getExternalFilesDir(null);
        if (d == null) d = ctx.getCacheDir();
        File w = new File(d, "work");
        if (!w.exists()) w.mkdirs();
        return w;
    }

    static File tempFile(Context ctx, String suffix) throws Exception {
        return File.createTempFile("sq", suffix, workDir(ctx));
    }

    static long freeSpace(Context ctx) {
        try {
            return workDir(ctx).getUsableSpace();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Output destination in Downloads/Squeeze, created pending and published on success. */
    static final class Sink {
        final Uri uri;
        final String name;
        private final Context ctx;
        private OutputStream os;
        private ParcelFileDescriptor pfd;

        private Sink(Context ctx, Uri uri, String name) {
            this.ctx = ctx;
            this.uri = uri;
            this.name = name;
        }

        static Sink create(Context ctx, String name, String mime) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Squeeze");
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri u = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (u == null) throw new IllegalStateException("Could not create the output file in Downloads/Squeeze");
            return new Sink(ctx, u, name);
        }

        OutputStream stream() throws Exception {
            if (os == null) os = ctx.getContentResolver().openOutputStream(uri, "w");
            return os;
        }

        ParcelFileDescriptor fd() throws Exception {
            if (pfd == null) pfd = ctx.getContentResolver().openFileDescriptor(uri, "rw");
            return pfd;
        }

        long size() {
            try {
                if (pfd != null) return pfd.getStatSize();
                ParcelFileDescriptor p = ctx.getContentResolver().openFileDescriptor(uri, "r");
                if (p == null) return -1;
                long s = p.getStatSize();
                p.close();
                return s;
            } catch (Exception e) {
                return -1;
            }
        }

        void publish() {
            try {
                if (os != null) { os.flush(); os.close(); os = null; }
            } catch (Exception ignored) { }
            try {
                if (pfd != null) { pfd.close(); pfd = null; }
            } catch (Exception ignored) { }
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
            try { ctx.getContentResolver().update(uri, cv, null, null); } catch (Exception ignored) { }
        }

        void delete() {
            try {
                if (os != null) os.close();
            } catch (Exception ignored) { }
            os = null;
            try {
                if (pfd != null) pfd.close();
            } catch (Exception ignored) { }
            pfd = null;
            try { ctx.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
        }
    }

    /** Stream a finished temp file into Downloads/Squeeze. */
    static void publishTemp(Context ctx, Job job, File tmp, String outName, String mime,
                            Progress p, Result r) throws Exception {
        Sink sink = Sink.create(ctx, outName, mime);
        try {
            OutputStream out = sink.stream();
            if (out == null) throw new IllegalStateException("Could not open the output file");
            InputStream in = new BufferedInputStream(new FileInputStream(tmp), 1 << 16);
            byte[] buf = new byte[1 << 16];
            long total = tmp.length(), done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                if (p != null && total > 0) p.on(0.9f + 0.1f * Math.min(1f, done / (float) total), done);
            }
            in.close();
            out.flush();
            out.close();
        } catch (Exception e) {
            sink.delete();
            throw e;
        }
        sink.publish();
        r.ok = true;
        r.out = sink.size() > 0 ? sink.size() : tmp.length();
        r.name = outName;
        r.uri = sink.uri;
    }

    static FileOutputStream open(File f) throws Exception {
        return new FileOutputStream(f);
    }

    static void closeQuietly(java.io.Closeable c) {
        try { if (c != null) c.close(); } catch (Exception ignored) { }
    }
}
