package com.squeeze.compressor;

/**
 * Pure scheduling and stepping rules.
 *
 * Kept free of Android imports on purpose: the queue decides how many jobs run at
 * once and the image encoder decides how many passes to make, and both of those are
 * plain arithmetic that should be provable on a JVM rather than by watching a phone.
 *
 * Two scheduling classes exist because the engine has two kinds of work:
 *
 *  - <b>light</b> — photos, archives, documents, anything unknown. No MediaCodec, no
 *    shared hardware resource: several can run at once on a small thread pool.
 *  - <b>codec</b> — video and audio. MediaCodec instances are a scarce, device-wide
 *    resource, so those stay strictly single-threaded.
 */
final class Plan {

    /** How many light jobs may run at the same time. */
    static final int LIGHT_WORKERS = 2;

    private static final String IMG = "jpg jpeg png webp heic heif bmp tiff avif";
    private static final String GIF = "gif";
    private static final String VID = "mp4 mkv mov webm avi 3gp m4v ts m2ts mts flv f4v wmv";
    private static final String AUD = "mp3 m4a aac wav flac ogg opus amr aiff aif wma";

    private Plan() { }

    /** True when this file type can share the CPU with other jobs. */
    static boolean light(String ext) {
        return !codec(ext);
    }

    /** True when this file type needs MediaCodec and must not run concurrently. */
    static boolean codec(String ext) {
        return has(VID, ext) || has(AUD, ext);
    }

    /** True when progress bytes are real input bytes consumed, not a trial output size. */
    static boolean byteMetered(String ext) {
        return !codec(ext) && !image(ext);
    }

    static boolean image(String ext) {
        return has(IMG, ext) || has(GIF, ext);
    }

    private static boolean has(String list, String e) {
        return e != null && (" " + list + " ").contains(" " + e + " ");
    }

    /* ------------------------------------------------------------------ */
    /*  image encode ladder                                               */
    /* ------------------------------------------------------------------ */

    /**
     * Dimensions to encode at, biggest first.
     *
     * The source is decoded exactly once at the first rung and every later rung is a
     * downscale of that one bitmap, so this list is the whole cost model of the
     * photo path: three rungs means three bitmaps, not three decodes.
     */
    static int[] dims(int longest, int cap, boolean lossless) {
        int top = Math.max(240, Math.min(cap, Math.max(1, longest)));
        if (lossless) return new int[]{Math.max(1, longest)};
        int d0 = top;
        int d1 = Math.max(240, (int) (top * 0.85));
        int d2 = Math.max(240, (int) (top * 0.72));
        if (d1 >= d0) return new int[]{d0};
        if (d2 >= d1) return new int[]{d0, d1};
        return new int[]{d0, d1, d2};
    }

    /**
     * Qualities to try at each rung. Two passes per size: the starting quality, then
     * one firm step down. Cheaper than another resize and usually enough.
     */
    static int[] qualities(int start, boolean lossless) {
        if (lossless) return new int[]{100};
        int q0 = Math.max(30, Math.min(100, start));
        int q1 = Math.max(30, q0 - 14);
        if (q1 >= q0) return new int[]{q0};
        return new int[]{q0, q1};
    }

    /** Total encodes the ladder will make — used to turn the loop index into progress. */
    static int steps(int dimCount, int qualCount) {
        return Math.max(1, dimCount * qualCount);
    }

    /**
     * Can the heavy path be skipped entirely?
     *
     * A photo that already fits the target and is already inside the resolution cap
     * has nothing to gain, so it is copied untouched without a single pixel being
     * decoded. Lossless mode is exempt: a lossless re-encode still shrinks PNG/BMP.
     */
    static boolean imageAlreadyFits(long size, long target, int longest, int cap, boolean lossless) {
        if (lossless) return false;
        if (size <= 0 || target <= 0) return false;
        return size <= target && longest <= Math.max(320, cap);
    }
}
