package com.squeeze.compressor;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;
import java.util.zip.ZipException;

/**
 * Turns an exception into something the person holding the phone can act on.
 *
 * "Compression failed" is not an error message, it is an apology. Every branch here
 * names what actually went wrong and what to do next, because the four things that
 * really do go wrong — a codec the device cannot decode, a DRM-protected file, a
 * corrupt header from a half-finished download, and a file that vanished mid-job —
 * each have exactly one sensible next step.
 */
final class Fault {

    static final String DRM = "DRM-protected file — the store that sold it encrypts the contents, "
            + "so no compressor can re-encode it. Export or download a DRM-free copy, then add that.";

    static final String DRM_PDF = "Encrypted PDF — it is locked with a password or an owner "
            + "certificate, so its streams cannot be rewritten. Open it in a PDF app and "
            + "save a decrypted copy (print to PDF works), then add that.";

    static final String VANISHED = "The file disappeared while it was being compressed — it was "
            + "moved, renamed, or the app that granted access closed. Add it again from Files.";

    static final String UNREADABLE = "This file is no longer readable — the permission granted when "
            + "you added it has expired. Remove the row and add the file again.";

    static final String CODEC = "No decoder on this device can read this file's codec (AV1, VP9, "
            + "ProRes and some HEVC profiles are common culprits). Try Smallest mode; if that "
            + "still fails, convert it on a device or app that supports it.";

    static final String NO_TRACK = "No usable track inside this file — the container opens but "
            + "holds no video or audio stream Squeeze can read. Try another copy of the file, "
            + "or export it again from the app that made it.";

    static final String HEADER = "The file's header is corrupt or it is not really a %s file — "
            + "usually an interrupted download or a partial export. Re-download or re-export it, "
            + "then add it again.";

    static final String ARCHIVE = "This archive is damaged: one of its entries cannot be read. "
            + "Repair it first (most file managers can), or add the individual files instead.";

    static final String ENCODER = "The device's hardware encoder refused this job — usually a "
            + "resolution or frame rate it does not support. Try Smallest mode, or a shorter clip.";

    static final String DISK = "Ran out of space while writing the result. Free some space in "
            + "internal storage and retry — the input is never modified, so nothing is lost.";

    static String explain(Throwable t, Engine.Job job) {
        String ext = job == null ? "" : Engine.ext(job.name);
        String raw = t == null ? "" : (t.getMessage() == null ? "" : t.getMessage());
        String m = raw.toLowerCase(Locale.US);
        String type = t == null ? "" : t.getClass().getName().toLowerCase(Locale.US);

        if (t instanceof Engine.Cancelled) return null;

        if (t instanceof FileNotFoundException || t instanceof EOFException
                || m.contains("no such file") || m.contains("enoent")
                || m.contains("file not found") || m.contains("unable to open")) {
            return job != null && job.started ? VANISHED : UNREADABLE;
        }
        if (t instanceof SecurityException || m.contains("permission denied") || m.contains("eacces")) {
            return UNREADABLE;
        }
        if (m.contains("drm") || m.contains("crypto") || type.contains("crypt")) {
            return DRM;
        }
        if (m.contains("encrypt")) {
            return ext.equals("pdf") ? DRM_PDF : DRM;
        }
        if (m.contains("no space") || m.contains("enospc") || m.contains("disk full")) {
            return DISK;
        }
        if (t instanceof ZipException || m.contains("invalid entry") || m.contains("zip")
                || m.contains("central directory") || m.contains("unexpected end")) {
            return ARCHIVE;
        }
        if (m.contains("codec") || m.contains("decoder") || m.contains("encoder")
                || m.contains("omx") || m.contains("c2.") || type.contains("codecexception")) {
            return m.contains("init") || m.contains("configure") ? CODEC : ENCODER;
        }
        if (m.contains("corrupt") || m.contains("not a pdf") || m.contains("header")
                || m.contains("malformed") || m.contains("no pdf objects")) {
            return String.format(Locale.US, HEADER, ext.isEmpty() ? "" : ("." + ext));
        }
        if (t instanceof IOException) {
            return (raw.isEmpty() ? "This file could not be read." : raw + ".")
                    + " Try adding it again, or copy it to internal storage first.";
        }
        if (raw.isEmpty()) {
            return "Squeeze hit an unexpected error (" + (t == null ? "unknown" : t.getClass().getSimpleName())
                    + ") on this file. Try Smallest mode, or add the file again.";
        }
        return raw + " — try Smallest mode, or add the file again.";
    }
}
