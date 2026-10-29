package com.squeeze.compressor;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Everything that is not media: archives, PDFs, Office files and unknown types.
 *
 * All of it is lossless by definition — bytes go in, the same bytes come out, only
 * the DEFLATE pass is run at maximum effort. Office files are already ZIP containers,
 * so they get unpacked and re-deflated; anything else is wrapped in a ZIP.
 */
final class Archiver {

    private static final String[] ZIPPY = {"zip", "docx", "xlsx", "pptx", "epub", "jar", "apk"};

    static Engine.Result run(Context ctx, Engine.Job job, Engine.Progress p) throws Exception {
        String e = Engine.ext(job.name);
        if (isZippy(e)) {
            Engine.Result r = rezip(ctx, job, p);
            if (r != null && r.ok) return r;
        }
        return zip(ctx, job, p);
    }

    private static boolean isZippy(String e) {
        for (String z : ZIPPY) if (z.equals(e)) return true;
        return false;
    }

    /** Unpack and re-deflate an existing ZIP container (Office docs, zips, APKs). */
    private static Engine.Result rezip(Context ctx, Engine.Job job, Engine.Progress p) {
        File tmp = null;
        Engine.Sink sink = null;
        try {
            tmp = Engine.tempFile(ctx, ".zip");
            long total = Math.max(1, job.size);

            InputStream raw = new BufferedInputStream(ctx.getContentResolver().openInputStream(job.uri), 1 << 16);
            ZipInputStream zin = new ZipInputStream(new BufferedInputStream(raw, 1 << 16));
            FileOutputStream fos = new FileOutputStream(tmp);
            ZipOutputStream zout = new ZipOutputStream(new java.io.BufferedOutputStream(fos, 1 << 16));
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
            r.note = "Lossless re-compression of " + entries + " entries";
            tmp.delete();
            return r;
        } catch (Exception ex) {
            if (sink != null) sink.delete();
            if (tmp != null) tmp.delete();
            return null;
        }
    }

    /** Wrap the file in a maximally deflated ZIP stream, written straight to the output. */
    static Engine.Result zip(Context ctx, Engine.Job job, Engine.Progress p) {
        String outName = Engine.base(job.name) + ".zip";
        Engine.Sink sink = Engine.Sink.create(ctx, outName, "application/zip");
        try {
            InputStream in = new BufferedInputStream(ctx.getContentResolver().openInputStream(job.uri), 1 << 16);
            OutputStream out = sink.stream();
            ZipOutputStream zout = new ZipOutputStream(new java.io.BufferedOutputStream(out, 1 << 16));
            zout.setLevel(Deflater.BEST_COMPRESSION);
            zout.putNextEntry(new ZipEntry(job.name));

            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
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
            r.note = "Lossless ZIP (deflate level 9)";
            return r;
        } catch (Exception e) {
            sink.delete();
            return null;
        }
    }
}
