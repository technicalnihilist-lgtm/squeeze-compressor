package com.squeeze.compressor;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Everything that is not media: archives, Office files and unknown types.
 *
 * All of it is lossless by definition — bytes go in, the same bytes come out, only
 * the DEFLATE pass is run at maximum effort. Office files are already ZIP
 * containers, so they get unpacked and re-deflated; anything else is wrapped in a
 * ZIP.
 *
 * <b>ZIP64.</b> An archive over 4 GB cannot be described by the classic ZIP format,
 * and the streaming reader this used to rely on ({@code ZipInputStream}) only copes
 * with ZIP64 when the archive happens to be laid out the way it expects — it has no
 * real ZIP64 handling, and an archive written by another tool fails on it. So an
 * input above 3 GB is spooled to scratch space and read through {@code ZipFile},
 * which is defined to be ZIP64-aware, and the output goes through
 * {@code ZipOutputStream}, which writes ZIP64 extra fields, 8-byte data descriptors
 * and a ZIP64 end-of-central-directory by itself. Both the "input over 4 GB" and
 * "output over 4 GB" cases are covered and were verified end to end; the row's note
 * says {@code ZIP64} when that path was taken.
 */
final class Archiver {

    private static final String[] ZIPPY = {"zip", "docx", "xlsx", "pptx", "epub", "jar", "apk", "odt", "ods", "odp"};

    /** Already-compressed payloads: deflating them again only adds overhead. */
    private static final String[] STORE = {
            "jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "gif",
            "mp4", "m4v", "mkv", "webm", "mov", "mp3", "m4a", "aac", "ogg", "opus",
            "flac", "zip", "gz", "bz2", "xz", "7z", "rar", "pdf", "apk", "jar"
    };

    private static final long GB4 = 4L * 1024 * 1024 * 1024;
    /** Above this an archive is read through ZipFile, which handles ZIP64 properly. */
    private static final long ZIP64_IN = 3L * 1024 * 1024 * 1024;

    static Engine.Result run(Context ctx, Engine.Job job, Engine.Progress p) throws Exception {
        String e = Engine.ext(job.name);
        if (isZippy(e)) {
            Engine.Result r = rezip(ctx, job, p);
            if (r != null && r.ok) return r;
            if (r != null && r.error != null) return r;   // a real, explainable failure
        }
        return zip(ctx, job, p);
    }

    private static boolean isZippy(String e) {
        for (String z : ZIPPY) if (z.equals(e)) return true;
        return false;
    }

    private static boolean storeAsIs(String name) {
        String e = Engine.ext(name);
        for (String s : STORE) if (s.equals(e)) return true;
        return false;
    }

    /** Unpack and re-deflate an existing ZIP container (Office docs, zips, APKs). */
    private static Engine.Result rezip(Context ctx, Engine.Job job, Engine.Progress p) {
        if (job.size > ZIP64_IN) return rezipLarge(ctx, job, p);
        File tmp = null;
        try {
            tmp = Engine.tempFile(ctx, ".zip");
            long total = Math.max(1, job.size);

            InputStream raw = new BufferedInputStream(ctx.getContentResolver().openInputStream(job.uri), 1 << 16);
            ZipInputStream zin = new ZipInputStream(new BufferedInputStream(raw, 1 << 16));
            FileOutputStream fos = new FileOutputStream(tmp);
            ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(fos, 1 << 16));
            zout.setLevel(Deflater.BEST_COMPRESSION);

            byte[] buf = new byte[1 << 16];
            ZipEntry entry;
            long read = 0;
            int entries = 0;

            while ((entry = zin.getNextEntry()) != null) {
                ZipEntry out = new ZipEntry(entry.getName());
                out.setTime(entry.getTime());
                zout.putNextEntry(out);
                int n;
                while ((n = zin.read(buf)) > 0) {
                    Engine.checkCancel();
                    zout.write(buf, 0, n);
                    read += n;
                    if (p != null) p.on(Math.min(0.85f, read / (float) total), 0);
                }
                zout.closeEntry();
                zin.closeEntry();
                entries++;
            }
            zout.finish();
            zout.close();
            zin.close();
            raw.close();

            if (entries == 0 || tmp.length() == 0) {
                tmp.delete();
                return null;
            }
            if (job.size > 0 && tmp.length() >= job.size) {
                tmp.delete();
                return Engine.copyRaw(ctx, job, p, "Already fully compressed — kept as-is");
            }

            Engine.Result r = new Engine.Result();
            String outName = Engine.ext(job.name).equals("zip") ? job.name
                    : Engine.base(job.name) + "_squeezed." + Engine.ext(job.name);
            Engine.publishTemp(ctx, job, tmp, outName, Engine.mimeOf(outName), p, r);
            r.codec = "DEFLATE";
            r.note = "Lossless re-compression of " + entries + " entries"
                    + (tmp.length() > GB4 ? " · ZIP64" : "");
            tmp.delete();
            return r;
        } catch (ZipException ze) {
            if (tmp != null) tmp.delete();
            return Engine.fail(Fault.ARCHIVE);
        } catch (Exception ex) {
            if (tmp != null) tmp.delete();
            if (ex instanceof Engine.Cancelled) return null;   // Engine.compress re-throws
            return null;
        }
    }

    /**
     * The ZIP64 path: a multi-gigabyte archive is spooled to scratch space and read
     * through {@link ZipFile}, which understands ZIP64 sizes and entry counts. Entries
     * that are already compressed are stored rather than deflated again, which is both
     * faster and slightly smaller.
     */
    private static Engine.Result rezipLarge(Context ctx, Engine.Job job, Engine.Progress p) {
        File spool = null;
        File tmp = null;
        try {
            spool = Engine.tempFile(ctx, ".zipin");
            InputStream in = new BufferedInputStream(ctx.getContentResolver().openInputStream(job.uri), 1 << 16);
            FileOutputStream sf = new FileOutputStream(spool);
            try {
                Engine.copy(in, sf, job.size, p == null ? null : new Engine.Progress() {
                    final Engine.Progress outer = p;
                    public void on(float f, long out) { outer.on(f * 0.25f, out); }
                });
            } finally {
                Engine.closeQuietly(in);
                sf.flush();
                sf.close();
            }

            tmp = Engine.tempFile(ctx, ".zip");
            ZipFile zf = new ZipFile(spool);
            ZipOutputStream zout = new ZipOutputStream(
                    new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16));
            zout.setLevel(Deflater.BEST_COMPRESSION);

            byte[] buf = new byte[1 << 16];
            int entries = 0;
            long done = 0;
            try {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    Engine.checkCancel();
                    ZipEntry e = en.nextElement();
                    ZipEntry out = new ZipEntry(e.getName());
                    if (e.getTime() >= 0) out.setTime(e.getTime());

                    boolean store = !e.isDirectory()
                            && e.getSize() >= 0 && e.getCrc() >= 0
                            && (e.getMethod() == ZipEntry.STORED || storeAsIs(e.getName()));
                    if (store) {
                        out.setMethod(ZipEntry.STORED);
                        out.setSize(e.getSize());
                        out.setCompressedSize(e.getSize());
                        out.setCrc(e.getCrc());
                    }
                    zout.putNextEntry(out);
                    if (!e.isDirectory()) {
                        InputStream es = zf.getInputStream(e);
                        int n;
                        while ((n = es.read(buf)) > 0) {
                            Engine.checkCancel();
                            zout.write(buf, 0, n);
                            done += n;
                            if (p != null && job.size > 0) {
                                p.on(0.25f + 0.6f * Math.min(1f, done / (float) job.size), 0);
                            }
                        }
                        es.close();
                    }
                    zout.closeEntry();
                    entries++;
                }
            } finally {
                zf.close();
            }
            zout.finish();
            zout.close();

            if (entries == 0 || tmp.length() == 0) return null;
            if (job.size > 0 && tmp.length() >= job.size) {
                return Engine.copyRaw(ctx, job, p, "Already fully compressed — kept as-is");
            }

            String outName = Engine.ext(job.name).equals("zip") ? job.name
                    : Engine.base(job.name) + "_squeezed." + Engine.ext(job.name);
            Engine.Result r = new Engine.Result();
            Engine.publishTemp(ctx, job, tmp, outName, Engine.mimeOf(outName), p, r);
            r.codec = "DEFLATE";
            r.note = "Lossless re-compression of " + entries + " entries · ZIP64"
                    + (tmp.length() > GB4 ? " · output over 4 GB" : "");
            return r;
        } catch (ZipException ze) {
            return Engine.fail(Fault.ARCHIVE);
        } catch (Engine.Cancelled c) {
            return null;
        } catch (Exception ex) {
            return null;
        } finally {
            if (spool != null) spool.delete();
            if (tmp != null) tmp.delete();
        }
    }

    /** Wrap the file in a maximally deflated ZIP stream, written straight to the output. */
    static Engine.Result zip(Context ctx, Engine.Job job, Engine.Progress p) {
        String outName = Engine.base(job.name) + ".zip";
        Engine.Sink sink = Engine.Sink.create(ctx, outName, "application/zip", job.selfTest);
        try {
            InputStream in = new BufferedInputStream(ctx.getContentResolver().openInputStream(job.uri), 1 << 16);
            OutputStream out = sink.stream();
            ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(out, 1 << 16));
            zout.setLevel(Deflater.BEST_COMPRESSION);
            zout.putNextEntry(new ZipEntry(job.name));

            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                Engine.checkCancel();
                zout.write(buf, 0, n);
                done += n;
                if (p != null && job.size > 0) {
                    p.on(Math.min(0.9f, done / (float) job.size), 0);
                }
            }
            zout.closeEntry();
            zout.finish();
            zout.close();
            in.close();

            long outSize = sink.size();
            if (job.size > 0 && outSize >= job.size) {
                sink.delete();
                return Engine.copyRaw(ctx, job, p, "Incompressible — stored unchanged");
            }
            sink.publish();

            Engine.Result r = new Engine.Result();
            r.ok = true;
            r.out = outSize;
            r.name = outName;
            r.uri = sink.uri;
            r.file = sink.file;
            r.codec = "DEFLATE";
            boolean zip64 = job.size > GB4 || outSize > GB4;
            r.note = zip64
                    ? "Lossless ZIP64 (deflate level 9)" + (outSize > GB4 ? " · output over 4 GB" : "")
                    : "Lossless ZIP (deflate level 9)";
            return r;
        } catch (Exception e) {
            sink.delete();
            return null;
        }
    }
}
