package com.squeeze.compressor;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * PDF compression, for real.
 *
 * Until now a PDF was wrapped in a ZIP and called compressed, which did almost
 * nothing: the file is already a container of Flate streams, so wrapping it just
 * added a second layer of deflate over bytes that were compressed once already.
 *
 * This path rewrites the document instead. {@link Pdf} parses the object graph,
 * re-deflates every FlateDecode stream at level 9, deflates the streams that were
 * left raw, drops objects nothing points at, strips metadata and writes a fresh
 * file with a new cross-reference table. Images are never re-encoded — a
 * DCTDecode/JPXDecode/CCITTFax stream is copied byte for byte — so the pages render
 * exactly as they did.
 *
 * If the rewrite is not smaller than the original, the original is what you get.
 * The note says which of those two things happened.
 */
final class PdfCompressor {

    static Engine.Result run(Context ctx, Engine.Job job, Engine.Progress p) throws Exception {
        File src = null;
        File dst = null;
        try {
            // Random access is needed for the object walk, so the content URI is spooled
            // to scratch space first. It streams, so a 300 MB scan does not sit in RAM.
            src = Engine.tempFile(ctx, ".pdf");
            InputStream in = new BufferedInputStream(
                    ctx.getContentResolver().openInputStream(job.uri), 1 << 16);
            FileOutputStream fo = new FileOutputStream(src);
            try {
                final Engine.Progress copyProgress = p;
                Engine.copy(in, fo, job.size, copyProgress == null ? null : new Engine.Progress() {
                    public void on(float f, long out) { copyProgress.on(f * 0.35f, out); }
                });
            } finally {
                Engine.closeQuietly(in);
                fo.flush();
                fo.close();
            }

            dst = Engine.tempFile(ctx, ".pdfout");
            Pdf.Report rep;
            FileOutputStream oo = new FileOutputStream(dst);
            try {
                rep = Pdf.rewrite(src, oo);
            } finally {
                oo.flush();
                oo.close();
            }
            if (p != null) p.on(0.9f, dst.length());

            if (rep.encrypted) {
                dst.delete();
                return Engine.fail(Fault.DRM_PDF);
            }
            if (dst.length() <= 0 || rep.out <= 0) return null;

            if (job.size > 0 && dst.length() >= job.size) {
                dst.delete();
                return Engine.copyRaw(ctx, job, p, "PDF is already at its smallest — kept as-is");
            }

            String outName = Engine.base(job.name) + "_squeezed.pdf";
            Engine.Result r = new Engine.Result();
            Engine.publishTemp(ctx, job, dst, outName, "application/pdf", p, r);
            r.codec = "PDF Flate";
            r.note = rep.note;
            return r;
        } finally {
            if (src != null) src.delete();
            if (dst != null) dst.delete();
        }
    }
}
