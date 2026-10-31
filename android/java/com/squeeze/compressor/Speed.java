package com.squeeze.compressor;

/**
 * Throughput and time-remaining math.
 *
 * Deliberately free of Android imports so the rules can be checked on a plain JVM:
 * a wrong ETA is a UI bug that no amount of device testing finds quickly, and the
 * arithmetic is the part that is easy to get subtly wrong (divide by zero, a
 * fraction that never moves, an estimate that jumps backwards).
 *
 * Two signals are used, in this order:
 *
 *  1. Bytes. When a job really consumes input at a steady rate (copy, ZIP, deflate)
 *     the rate is measured straight from bytes done over elapsed time. This is the
 *     honest one — it is what "bytes per second" means.
 *  2. Progress fraction. Encoders do not hand back "input bytes consumed" per frame,
 *     so for those the fraction of the job already done is the only clock available.
 *     Rate = fraction per second, remaining = (1 - fraction) / rate.
 *
 * Anything that cannot be estimated returns {@link #UNKNOWN} rather than a made-up
 * number, and the UI shows no ETA instead of a lie.
 */
final class Speed {

    /** No estimate available yet. */
    static final long UNKNOWN = -1;

    /** Below this much elapsed time any rate is noise, so do not guess yet. */
    static final long WARMUP_MS = 700;

    /** A single job is never reported as taking longer than this. */
    static final long CAP_SECS = 6 * 60 * 60;

    private Speed() { }

    /** Bytes per second over the window measured so far. 0 when it cannot be known. */
    static double bytesPerSec(long bytes, long elapsedMs) {
        if (bytes <= 0 || elapsedMs <= 0) return 0;
        return bytes * 1000.0 / elapsedMs;
    }

    /**
     * Time remaining from a byte rate.
     *
     * @param done      bytes already processed
     * @param total     bytes the whole job will process (input size); 0 when unknown
     * @param elapsedMs wall clock spent so far
     * @return seconds remaining, or {@link #UNKNOWN}
     */
    static long etaFromBytes(long done, long total, long elapsedMs) {
        if (total <= 0 || done <= 0) return UNKNOWN;
        if (done >= total) return 0;
        if (elapsedMs < WARMUP_MS) return UNKNOWN;
        double rate = bytesPerSec(done, elapsedMs);
        if (rate <= 0) return UNKNOWN;
        return clampSecs((long) Math.ceil((total - done) / rate));
    }

    /**
     * Time remaining from progress fraction alone — the fallback for encoders.
     *
     * @param fraction 0..1 of the job done
     * @param elapsedMs wall clock spent so far
     */
    static long etaFromFraction(float fraction, long elapsedMs) {
        if (elapsedMs < WARMUP_MS) return UNKNOWN;
        if (fraction <= 0.02f) return UNKNOWN;      // too early to extrapolate
        if (fraction >= 0.999f) return 0;
        double rate = fraction / (elapsedMs / 1000.0);
        if (rate <= 0) return UNKNOWN;
        return clampSecs((long) Math.ceil((1.0 - fraction) / rate));
    }

    /**
     * Pick the best available estimate. Bytes win when they are real, because they
     * track a job whose phases cost wildly different amounts of time; the fraction
     * estimate is used when there is no byte clock.
     *
     * @param byteMetered true when {@code done}/{@code total} are genuinely input bytes
     */
    static long eta(boolean byteMetered, long done, long total, float fraction, long elapsedMs) {
        if (byteMetered) {
            long b = etaFromBytes(done, total, elapsedMs);
            if (b != UNKNOWN) return b;
        }
        return etaFromFraction(fraction, elapsedMs);
    }

    /** Clamp a raw estimate into the range the UI is willing to print. */
    static long clampSecs(long secs) {
        if (secs < 0) return UNKNOWN;
        return Math.min(secs, CAP_SECS);
    }

    /** "45s" / "1m 30s" / "1h 2m" — short enough for a queue row. */
    static String human(long secs) {
        if (secs == UNKNOWN) return "";
        if (secs <= 0) return "almost done";
        if (secs < 60) return secs + "s";
        if (secs < 3600) {
            long m = secs / 60, s = secs % 60;
            return s == 0 ? m + "m" : m + "m " + s + "s";
        }
        long h = secs / 3600, m = (secs % 3600) / 60;
        return m == 0 ? h + "h" : h + "h " + m + "m";
    }
}
